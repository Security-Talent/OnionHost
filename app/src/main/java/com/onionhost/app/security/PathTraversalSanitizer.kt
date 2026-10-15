package com.onionhost.app.security

import java.io.File
import java.net.URLDecoder

object PathTraversalSanitizer {

    /**
     * Resolves a URI path against the root directory while enforcing path traversal
     * and directory escape prevention.
     * Returns null if the requested path attempts to escape rootDir.
     */
    fun getSafeFile(rootDir: File, uriPath: String): File? {
        val decodedPath = try {
            // URLDecoder treats '+' as a form-encoded space, but '+' is a valid
            // literal character in a URL path. Preserve it while decoding %xx.
            URLDecoder.decode(uriPath.replace("+", "%2B"), "UTF-8")
        } catch (e: Exception) {
            return null
        }

        // Refuse ambiguous separators and null-byte injection before canonicalizing.
        if (decodedPath.contains("\u0000") || decodedPath.contains('\\')) return null

        val canonicalRootDir = rootDir.canonicalFile
        // HTTP paths are absolute by syntax ("/index.html"), while File(parent,
        // absoluteChild) can discard the parent. Convert to a root-relative path.
        val relativePath = decodedPath.trimStart('/')
        val targetFile = File(canonicalRootDir, relativePath).canonicalFile

        // Path component boundary matters: "/root-escape" must not pass for
        // a root named "/root". Canonicalization also resolves symlinks.
        val rootPath = canonicalRootDir.path
        if (targetFile.path != rootPath && !targetFile.path.startsWith(rootPath + File.separator)) {
            return null
        }

        return targetFile
    }

    /**
     * Validates whether a file is safe for reading (exists, readable, not a device special file).
     */
    fun isSafeToServe(file: File): Boolean {
        return file.exists() && file.canRead() && file.isFile
    }
}
