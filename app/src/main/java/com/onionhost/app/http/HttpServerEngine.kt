package com.onionhost.app.http

import fi.iki.elonen.NanoHTTPD
import com.onionhost.app.security.MimeValidator
import com.onionhost.app.security.PathTraversalSanitizer
import com.onionhost.app.security.RateLimiter
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.Locale
import java.net.URLEncoder
import org.json.JSONObject

private const val MAX_CHAT_BODY_BYTES = AnonymousChatStore.MAX_ATTACHMENT_CHARS + 64 * 1024

class HttpServerEngine(
    private val port: Int,
    private val webRootDir: File,
    private val rateLimiter: RateLimiter = RateLimiter(),
    private val requiresAuth: Boolean = false,
    private val authUsername: String = "",
    private val authPasswordHash: String = "",
    private val onRequestServed: ((path: String, statusCode: Int, bytesSent: Long, isDownload: Boolean) -> Unit)? = null
) : NanoHTTPD("127.0.0.1", port) {
    // Browser chat polls every two seconds; keep its abuse budget separate from
    // static-file requests while still placing a bound on anonymous endpoints.
    private val chatRateLimiter = RateLimiter(maxRequestsPerMinute = 1_800)

    override fun serve(session: IHTTPSession): Response {
        val requestPath = session.uri.trimEnd('/').ifBlank { "/" }
        val isChatRequest = requestPath == "/chat" || requestPath.startsWith("/chat/") ||
            requestPath == "/invite" || requestPath.startsWith("/invite/") ||
            requestPath == "/api/chat/messages" || requestPath == "/api/chat/join"
        val allowed = if (isChatRequest) {
            chatRateLimiter.isAllowed(session.remoteIpAddress ?: "127.0.0.1")
        } else {
            rateLimiter.isAllowed(session.remoteIpAddress ?: "127.0.0.1")
        }
        if (!allowed) {
            return newFixedLengthResponse(Response.Status.TOO_MANY_REQUESTS, MIME_PLAINTEXT, "429 Too Many Requests")
        }

        // Protect chat pages and APIs with the same optional site authentication.
        // Previously chatResponse ran before both the limiter and this check.
        if (requiresAuth) {
            if (authUsername.isBlank() || authPasswordHash.isBlank()) {
                return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    MIME_PLAINTEXT,
                    "Protected site credentials are not configured"
                )
            }
            val authHeader = session.headers["authorization"]
            if (authHeader == null || !checkBasicAuth(authHeader)) {
                return newFixedLengthResponse(
                    Response.Status.UNAUTHORIZED,
                    MIME_PLAINTEXT,
                    "401 Unauthorized"
                ).apply { addHeader("WWW-Authenticate", "Basic realm=\"OnionHost Private Area\"") }
            }
        }

        // Invite links are served by the host itself, rather than relying on a
        // third-party JavaScript service (which Tor Browser may block).
        chatResponse(session)?.let { return it }

        var uri = session.uri
        if (uri.endsWith("/") || uri.isEmpty()) {
            uri += "index.html"
        }

        // Path Traversal Security check
        val safeFile = PathTraversalSanitizer.getSafeFile(webRootDir, uri)
            ?: return newFixedLengthResponse(
                Response.Status.FORBIDDEN,
                MIME_PLAINTEXT,
                "403 Forbidden: Directory Escape Detected"
            )

        if (!safeFile.exists()) {
            // Check if user requested a directory without trailing slash
            val dirFile = PathTraversalSanitizer.getSafeFile(webRootDir, session.uri)
            if (dirFile != null && dirFile.isDirectory) {
                val indexInDir = File(dirFile, "index.html")
                if (indexInDir.exists()) {
                    return serveFile(indexInDir, session.headers)
                }
                return generateDirectoryListing(dirFile, session.uri)
            }
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                MIME_PLAINTEXT,
                "404 Not Found"
            )
        }

        if (safeFile.isDirectory) {
            val indexFile = File(safeFile, "index.html")
            if (indexFile.exists()) {
                return serveFile(indexFile, session.headers)
            }
            return generateDirectoryListing(safeFile, session.uri)
        }

        return serveFile(safeFile, session.headers)
    }

    private fun chatResponse(session: IHTTPSession): Response? {
        val path = session.uri.trimEnd('/').ifBlank { "/" }
        val isChatPage = path == "/chat" || path == "/invite" ||
            path.startsWith("/chat/") || path.startsWith("/invite/")
        if (isChatPage) {
            val room = roomFrom(session)
            return htmlResponse(chatPage(room))
        }

        val room = roomFrom(session)
        if (path == "/api/chat/join") {
            val clientId = session.parameters["clientId"]?.firstOrNull().orEmpty().take(80)
            return when (session.method) {
                Method.POST -> {
                    val state = AnonymousChatStore.requestJoin(room, clientId)
                    val status = if (state == "invalid") Response.Status.BAD_REQUEST else Response.Status.OK
                    jsonResponse(status, "{\"status\":${JSONObject.quote(state)}}")
                }
                Method.GET -> {
                    if (clientId.length !in 8..80) jsonResponse(Response.Status.BAD_REQUEST, "{\"error\":\"Invalid client ID\"}")
                    else jsonResponse(Response.Status.OK, "{\"status\":${JSONObject.quote(AnonymousChatStore.joinStatus(room, clientId))}}")
                }
                else -> jsonResponse(Response.Status.METHOD_NOT_ALLOWED, "{\"error\":\"Method not allowed\"}")
            }
        }
        if (path != "/api/chat/messages") return null
        return when (session.method) {
            Method.GET -> {
                val after = session.parameters["after"]?.firstOrNull()?.toLongOrNull() ?: 0L
                val clientId = session.parameters["clientId"]?.firstOrNull()
                if (clientId.isNullOrBlank() || !AnonymousChatStore.isApproved(room, clientId)) {
                    return jsonResponse(Response.Status.FORBIDDEN, "{\"error\":\"Host approval is required\"}")
                }
                clientId?.takeIf { it.length >= 8 }?.let { AnonymousChatStore.touchClient(room, it) }
                val messages = AnonymousChatStore.since(room, after)
                val body = messages.joinToString(prefix = "{\"messages\":[", postfix = "],\"activeUsers\":${AnonymousChatStore.activeClientCount(room)},\"historyRevision\":${AnonymousChatStore.historyRevision(room)}}") { message ->
                    chatMessageJson(message, clientId)
                }
                jsonResponse(Response.Status.OK, body)
            }
            Method.POST -> {
                val bodyLength = session.headers["content-length"]?.toLongOrNull()
                if (bodyLength == null || bodyLength !in 0..MAX_CHAT_BODY_BYTES.toLong()) {
                    return jsonResponse(Response.Status.BAD_REQUEST, "{\"error\":\"Request body size is invalid or exceeds the limit\"}")
                }
                val files = HashMap<String, String>()
                return try {
                    session.parseBody(files)
                    val rawBody = files["postData"].orEmpty()
                    val request = JSONObject(rawBody)
                    val text = request.optString("text", "").trim()
                    val clientId = request.optString("clientId", "").take(80)
                    if (!AnonymousChatStore.isApproved(room, clientId)) {
                        return jsonResponse(Response.Status.FORBIDDEN, "{\"error\":\"Host approval is required before sending messages\"}")
                    }
                    val attachmentData = request.optString("attachmentData", "")
                    val attachmentType = request.optString("attachmentType", "")
                    val attachmentName = request.optString("attachmentName", "attachment").take(120)
                    val safeMediaTypes = setOf("image/png", "image/jpeg", "image/gif", "image/webp", "video/mp4", "video/webm", "audio/mpeg", "audio/ogg", "audio/wav", "audio/webm")
                    val hasValidAttachment = attachmentType in safeMediaTypes &&
                        attachmentData.startsWith("data:$attachmentType;base64,") &&
                        attachmentData.length <= AnonymousChatStore.MAX_ATTACHMENT_CHARS &&
                        attachmentData.substringAfter("base64,").all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
                    if ((text.isBlank() && !hasValidAttachment) || clientId.length < 8) {
                        jsonResponse(Response.Status.BAD_REQUEST, "{\"error\":\"Message, attachment, or valid client ID is required\"}")
                    } else {
                        val attachment = if (hasValidAttachment) AnonymousChatStore.Attachment(attachmentData, attachmentName, attachmentType) else null
                        val message = AnonymousChatStore.add(room, text, clientId, attachment)
                        jsonResponse(Response.Status.OK, "{\"id\":${message.id},\"sentAt\":${message.sentAt}}")
                    }
                } catch (_: Exception) {
                    jsonResponse(Response.Status.BAD_REQUEST, "{\"error\":\"Invalid message\"}")
                }
            }
            Method.DELETE -> {
                val id = session.parameters["id"]?.firstOrNull()?.toLongOrNull()
                val clientId = session.parameters["clientId"]?.firstOrNull().orEmpty()
                if (AnonymousChatStore.isApproved(room, clientId) && id != null && AnonymousChatStore.deleteByOwner(room, id, clientId)) jsonResponse(Response.Status.OK, "{\"deleted\":true}")
                else jsonResponse(Response.Status.FORBIDDEN, "{\"error\":\"You can only delete your own messages\"}")
            }
            else -> jsonResponse(Response.Status.METHOD_NOT_ALLOWED, "{\"error\":\"Method not allowed\"}")
        }
    }

    private fun chatMessageJson(message: AnonymousChatStore.Message, clientId: String?): String {
        val json = JSONObject().put("id", message.id).put("text", message.text).put("sender", message.sender).put("sentAt", message.sentAt)
            .put("canDelete", !clientId.isNullOrBlank() && message.ownerId == clientId)
        message.attachment?.let { json.put("attachment", JSONObject().put("data", it.dataUrl).put("name", it.name).put("type", it.mimeType)) }
        return json.toString()
    }

    private fun roomFrom(session: IHTTPSession): String {
        val pathRoom = session.uri.trimEnd('/').substringAfterLast('/', "")
            .takeIf { session.uri.startsWith("/chat/") || session.uri.startsWith("/invite/") }
        val candidate = session.parameters["room"]?.firstOrNull() ?: pathRoom ?: "lobby"
        // Room names are never used as filesystem paths; limiting the charset
        // also makes a copied invite URL stable across clients.
        return candidate.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .take(80).ifBlank { "lobby" }
    }

    private fun htmlResponse(body: String): Response =
        newFixedLengthResponse(Response.Status.OK, MIME_HTML, body).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("X-Content-Type-Options", "nosniff")
            addHeader("Content-Security-Policy", "default-src 'self'; connect-src 'self'; img-src data:; media-src data:; style-src 'unsafe-inline'; script-src 'unsafe-inline'; object-src 'none'; base-uri 'none'")
        }

    private fun jsonResponse(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", body).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("X-Content-Type-Options", "nosniff")
        }

    private fun chatPage(room: String): String = """
        <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Anonymous chat</title><style>
        *{box-sizing:border-box}body{margin:0;min-height:100vh;background:radial-gradient(circle at top,#322161,#10111a 55%);color:#f8f7ff;font:15px Inter,system-ui,-apple-system,sans-serif}.wrap{max-width:760px;height:100dvh;min-height:380px;margin:auto;padding:10px 14px max(10px,env(safe-area-inset-bottom));display:flex;flex-direction:column}.top{display:flex;align-items:center;gap:11px;padding:12px 4px 16px}.mark{width:42px;height:42px;border-radius:14px;display:grid;place-items:center;background:linear-gradient(135deg,#9f67ff,#5f2eea);font-size:21px;box-shadow:0 8px 24px #7c3aed66}.title{font-weight:800;font-size:18px;letter-spacing:-.3px}.sub{font-size:12px;color:#c7c2d8;margin-top:2px}.presence{font-size:11px;color:#bff6d0;margin-top:3px}.presence i{display:inline-block;width:8px;height:8px;background:#32d583;border-radius:50%;margin-right:5px}.room{margin-left:auto;padding:6px 9px;border:1px solid #ffffff26;border-radius:999px;color:#d9d2ff;font-size:11px;max-width:130px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}#messages{flex:1;min-height:0;overflow:auto;padding:8px 3px 18px;scroll-behavior:smooth}.empty{margin:60px auto;text-align:center;color:#bbb5ca}.msg{max-width:82%;margin:9px 0;padding:10px 12px;border-radius:17px 17px 17px 5px;background:#262433;border:1px solid #ffffff0d;box-shadow:0 6px 18px #0000001c;white-space:pre-wrap;overflow-wrap:anywhere}.msg.mine{margin-left:auto;border-radius:17px 17px 5px 17px;background:linear-gradient(135deg,#8a4fff,#6534dd)}.sender{font-size:11px;font-weight:800;color:#cdbaff;margin-bottom:4px}.mine .sender{color:#eee9ff}.time{font-size:10px;opacity:.65;margin-top:6px}.msg img,.msg video{display:block;margin-top:8px;max-width:100%;max-height:280px;border-radius:10px}.msg audio{display:block;margin-top:8px;max-width:100%}.delete{margin:8px 0 0;padding:0;background:transparent;border:0;color:#eee9ff;font-size:11px;text-decoration:underline}.composer{padding:10px;border:1px solid #ffffff1c;border-radius:20px;background:#1d1c28e8;box-shadow:0 10px 32px #0005}.file-row{display:flex;align-items:center;gap:8px;padding:0 3px 7px;color:#bdb7cc;font-size:11px}.file{max-width:210px;color:#d9d2eb}.input-row{display:flex;align-items:flex-end;gap:8px}textarea{flex:1;resize:none;min-height:44px;max-height:100px;padding:12px 14px;border:1px solid #ffffff18;border-radius:14px;background:#292735;color:#fff;font:inherit;outline:none}textarea:focus{border-color:#a474ff}button.send{width:44px;height:44px;padding:0;border:0;border-radius:14px;background:linear-gradient(135deg,#a36bff,#6938e5);color:#fff;font-size:18px;box-shadow:0 5px 15px #7c3aed77}button.send:disabled{opacity:.6}.preview{display:none;align-items:center;gap:10px;margin:0 0 8px;padding:8px;border:1px solid #ffffff1c;border-radius:14px;background:#292735}.preview.show{display:flex}.preview img{width:56px;height:56px;object-fit:cover;border-radius:9px}.preview span{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:12px}.preview button{border:0;background:transparent;color:#d9d2eb;font-size:12px}#status{min-height:20px;padding:6px 4px 0;color:#bcb5cd;font-size:11px}.loading{position:fixed;inset:0;display:none;place-items:center;background:#10111ad9;z-index:5}.loading.show{display:grid}.spinner{width:34px;height:34px;border:4px solid #ffffff33;border-top-color:#a36bff;border-radius:50%;animation:spin .8s linear infinite}@keyframes spin{to{transform:rotate(360deg)}}
        </style></head><body><div id="loading" class="loading show"><div><div class="spinner"></div><p>Loading chat…</p></div></div><main class="wrap"><header class="top"><div class="mark">◉</div><div><div class="title">OnionHost Chat</div><div class="sub">Private Onion room · no phone number</div><div class="presence"><i></i><span id="presence">Connecting…</span></div></div><div class="room">${escapeHtml(room)}</div></header><section id="messages" aria-live="polite"><div class="empty">Start the conversation.<br><small>Your anonymous name is generated automatically.</small></div></section><form id="form" class="composer"><div class="file-row">📎 <input class="file" id="file" type="file" accept="image/png,image/jpeg,image/gif,image/webp,video/mp4,video/webm,audio/mpeg,audio/ogg,audio/wav,audio/webm"></div><div id="preview" class="preview"><img id="previewImage" alt="Selected image preview"><span id="previewName"></span><button id="removeFile" type="button">Remove</button></div><div class="input-row"><textarea id="text" maxlength="1000" placeholder="Message…" aria-label="Message"></textarea><button class="send" type="submit" aria-label="Send">➤</button></div></form><div id="status" role="status" aria-live="polite"></div></main>
        <script>const room=${JSONObject.quote(room)},box=document.querySelector('#messages'),text=document.querySelector('#text'),file=document.querySelector('#file'),status=document.querySelector('#status'),presence=document.querySelector('#presence'),loader=document.querySelector('#loading'),preview=document.querySelector('#preview'),previewImage=document.querySelector('#previewImage'),previewName=document.querySelector('#previewName');const clientId=localStorage.chatClientId||(localStorage.chatClientId=(crypto.randomUUID?crypto.randomUUID():Math.random().toString(36).slice(2)+Date.now()));let latest=0,loading=false,previewUrl='',historyRevision=null;
        const endpoint=()=>'/api/chat/messages?room='+encodeURIComponent(room)+'&after='+latest+'&clientId='+encodeURIComponent(clientId);
        function add(m){box.querySelector('.empty')?.remove();const el=document.createElement('article');el.className='msg'+(m.canDelete?' mine':'');const who=document.createElement('div');who.className='sender';who.textContent=m.sender||'Anonymous';el.appendChild(who);if(m.text){const p=document.createElement('div');p.textContent=m.text;el.appendChild(p)}if(m.attachment){let media=document.createElement(m.attachment.type.startsWith('image/')?'img':m.attachment.type.startsWith('video/')?'video':'audio');media.src=m.attachment.data;media.controls=true;el.appendChild(media)}if(m.canDelete){const d=document.createElement('button');d.className='delete';d.textContent='Delete message';d.onclick=()=>fetch('/api/chat/messages?room='+encodeURIComponent(room)+'&id='+m.id+'&clientId='+encodeURIComponent(clientId),{method:'DELETE'}).then(()=>location.reload());el.appendChild(d)}const t=document.createElement('div');t.className='time';t.textContent=new Date(m.sentAt).toLocaleTimeString();el.appendChild(t);box.appendChild(el);box.scrollTop=box.scrollHeight;latest=Math.max(latest,m.id)}
        async function poll(){if(loading)return;loading=true;try{const accessUrl='/api/chat/join?room='+encodeURIComponent(room)+'&clientId='+encodeURIComponent(clientId);let accessResponse=await fetch(accessUrl,{cache:'no-store'});let access=await accessResponse.json();if(access.status==='not_requested'){accessResponse=await fetch(accessUrl,{method:'POST',cache:'no-store'});access=await accessResponse.json()}if(access.status!=='approved'){const waiting=access.status==='pending',busy=access.status==='busy',cancelled=access.status==='cancelled';presence.textContent=waiting?'Waiting for host approval':busy?'Room is busy':'Access not approved';status.textContent=waiting?'Your request was sent. The host must approve before you can chat.':busy?'This room has too many pending requests. Try again later.':cancelled?'The host cancelled this request. Contact the host if you still need access.':'Could not request access. Refresh to retry.';text.disabled=true;file.disabled=true;document.querySelector('button.send').disabled=true;return}text.disabled=false;file.disabled=false;document.querySelector('button.send').disabled=false;const r=await fetch(endpoint(),{cache:'no-store'});if(!r.ok)throw Error();const payload=await r.json();if(historyRevision!==null&&historyRevision!==payload.historyRevision){box.replaceChildren();latest=0;const notice=document.createElement('div');notice.className='empty';notice.textContent='The host cleared this chat history.';box.appendChild(notice)}historyRevision=payload.historyRevision;payload.messages.forEach(add);presence.textContent=payload.activeUsers>1?payload.activeUsers+' people active':'Approved · you are active';status.textContent='Connected'}catch(e){presence.textContent='Reconnecting…';status.textContent='Connection error. Retrying…'}finally{loader.classList.remove('show');loading=false}}
        file.addEventListener('change',()=>{if(previewUrl)URL.revokeObjectURL(previewUrl);previewUrl='';const f=file.files[0];if(!f){preview.classList.remove('show');return}if(f.size>5*1024*1024){status.textContent='Attachment limit is 5 MB';file.value='';preview.classList.remove('show');return}previewName.textContent=f.name;preview.classList.add('show');if(f.type.startsWith('image/')){previewUrl=URL.createObjectURL(f);previewImage.src=previewUrl;previewImage.style.display='block'}else{previewImage.removeAttribute('src');previewImage.style.display='none'}});document.querySelector('#removeFile').addEventListener('click',()=>{file.value='';if(previewUrl)URL.revokeObjectURL(previewUrl);previewUrl='';preview.classList.remove('show')});
        document.querySelector('#form').addEventListener('submit',async e=>{e.preventDefault();const f=file.files[0],button=e.currentTarget.querySelector('button.send');if(!text.value.trim()&&!f)return;if(f&&f.size>5*1024*1024){status.textContent='Attachment limit is 5 MB';return}button.disabled=true;status.textContent='Sending…';try{const data=f?await new Promise((ok,bad)=>{const r=new FileReader();r.onload=()=>ok(r.result);r.onerror=bad; r.readAsDataURL(f)}):'';const r=await fetch('/api/chat/messages?room='+encodeURIComponent(room),{method:'POST',headers:{'Content-Type':'application/json','Accept':'application/json'},cache:'no-store',body:JSON.stringify({text:text.value.trim(),clientId:clientId,attachmentData:data,attachmentType:f?f.type:'',attachmentName:f?f.name:''})});if(!r.ok){let detail='';try{detail=(await r.json()).error||''}catch(_){}throw Error(detail||('send failed ('+r.status+')'))}text.value='';file.value='';if(previewUrl)URL.revokeObjectURL(previewUrl);previewUrl='';preview.classList.remove('show');await poll();status.textContent='Message sent';setTimeout(()=>{if(status.textContent==='Message sent')status.textContent='Connected'},3500)}catch(e){status.textContent='Could not send: '+(e.message||'check connection and try again')}finally{button.disabled=false}});poll();setInterval(poll,2000);</script></body></html>
    """.trimIndent()

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun serveFile(file: File, headers: Map<String, String>): Response {
        val mimeType = MimeValidator.getMimeType(file)
        val fileLength = file.length()
        val isDownload = MimeValidator.isDownloadableType(file)

        // Support for Range requests (HTTP 206 Partial Content)
        var rangeHeader = headers["range"]
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            rangeHeader = rangeHeader.substring("bytes=".length)
            val minusIndex = rangeHeader.indexOf('-')
            var start: Long = 0
            var end: Long = fileLength - 1

            if (minusIndex >= 0) {
                try {
                    val startStr = rangeHeader.substring(0, minusIndex)
                    if (startStr.isNotEmpty()) start = startStr.toLong()

                    val endStr = rangeHeader.substring(minusIndex + 1)
                    if (endStr.isNotEmpty()) end = endStr.toLong()
                } catch (ignored: NumberFormatException) {
                }
            }

            if (start > end || start >= fileLength) {
                val res = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "")
                res.addHeader("Content-Range", "bytes */$fileLength")
                return res
            }

            if (end >= fileLength) end = fileLength - 1
            val contentLength = end - start + 1

            val fileStream = FileInputStream(file)
            fileStream.skip(start)

            val response = newFixedLengthResponse(
                Response.Status.PARTIAL_CONTENT,
                mimeType,
                fileStream,
                contentLength
            )
            response.addHeader("Content-Range", "bytes $start-$end/$fileLength")
            response.addHeader("Accept-Ranges", "bytes")
            response.addHeader("Cache-Control", "public, max-age=3600")

            onRequestServed?.invoke(file.name, 206, contentLength, isDownload)
            return response
        }

        val response = newFixedLengthResponse(
            Response.Status.OK,
            mimeType,
            FileInputStream(file),
            fileLength
        )

        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Cache-Control", "public, max-age=3600")
        response.addHeader("X-Content-Type-Options", "nosniff")
        response.addHeader("X-Frame-Options", "DENY")
        response.addHeader("X-XSS-Protection", "1; mode=block")

        if (isDownload) {
            response.addHeader("Content-Disposition", "attachment; filename=\"${file.name}\"")
        }

        onRequestServed?.invoke(file.name, 200, fileLength, isDownload)
        return response
    }

    private fun generateDirectoryListing(dir: File, requestUri: String): Response {
        val files = dir.listFiles() ?: arrayOf()
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html><head><title>Index of ").append(requestUri).append("</title>")
        sb.append("<style>body{font-family:monospace;background:#18181b;color:#e4e4e7;padding:2rem;}a{color:#38bdf8;text-decoration:none;}a:hover{text-decoration:underline;}</style></head><body>")
        sb.append("<h2>Directory Index: ").append(escapeHtml(requestUri)).append("</h2><hr/><ul>")
        if (requestUri != "/") {
            sb.append("<li><a href=\"..\">.. (Parent Directory)</a></li>")
        }
        for (f in files) {
            val name = f.name + if (f.isDirectory) "/" else ""
            val encodedName = URLEncoder.encode(f.name, Charsets.UTF_8.name()).replace("+", "%20") +
                if (f.isDirectory) "/" else ""
            sb.append("<li><a href=\"").append(escapeHtml(encodedName)).append("\">")
                .append(escapeHtml(name)).append("</a></li>")
        }
        sb.append("</ul><hr/><p><em>Powered by OnionHost Embedded Engine</em></p></body></html>")

        val res = newFixedLengthResponse(Response.Status.OK, MIME_HTML, sb.toString())
        onRequestServed?.invoke(requestUri, 200, sb.length.toLong(), false)
        return res
    }

    private fun checkBasicAuth(authHeader: String): Boolean {
        return try {
            val base64Credentials = authHeader.substring("Basic ".length).trim()
            val credentials = String(android.util.Base64.decode(base64Credentials, android.util.Base64.DEFAULT), Charsets.UTF_8)
            val parts = credentials.split(":", limit = 2)
            if (parts.size == 2) {
                parts[0] == authUsername && parts[1] == authPasswordHash
            } else false
        } catch (e: Exception) {
            false
        }
    }
}
