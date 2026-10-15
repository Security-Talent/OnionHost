package com.onionhost.security

import com.onionhost.app.security.PathTraversalSanitizer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PathTraversalSanitizerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testValidRelativeFileAccess() {
        val rootDir = tempFolder.newFolder("webroot")
        val sampleFile = File(rootDir, "index.html").apply { writeText("<h1>Test</h1>") }

        val resolved = PathTraversalSanitizer.getSafeFile(rootDir, "index.html")

        assertNotNull(resolved)
        assertEquals(sampleFile.canonicalPath, resolved?.canonicalPath)
    }

    @Test
    fun testLeadingHttpSlashResolvesInsideRoot() {
        val rootDir = tempFolder.newFolder("slash-root")
        val sample = File(rootDir, "index.html").apply { writeText("ok") }

        val resolved = PathTraversalSanitizer.getSafeFile(rootDir, "/index.html")

        assertEquals(sample.canonicalPath, resolved?.canonicalPath)
    }

    @Test
    fun testSiblingWithRootNamePrefixIsNotTreatedAsContained() {
        val rootDir = tempFolder.newFolder("webroot")
        val sibling = File(tempFolder.root, "webroot-secret").apply { mkdirs() }
        File(sibling, "secret.txt").writeText("secret")

        val resolved = PathTraversalSanitizer.getSafeFile(rootDir, "../webroot-secret/secret.txt")

        assertNull("A sibling path sharing the root prefix must be rejected", resolved)
    }

    @Test
    fun testPlusIsPreservedInUrlPath() {
        val rootDir = tempFolder.newFolder("plus-root")
        val sample = File(rootDir, "a+b.txt").apply { writeText("ok") }

        val resolved = PathTraversalSanitizer.getSafeFile(rootDir, "/a+b.txt")

        assertEquals(sample.canonicalPath, resolved?.canonicalPath)
    }

    @Test
    fun testDirectoryEscapeAttemptReturnsNull() {
        val rootDir = tempFolder.newFolder("webroot")
        val secretsFile = tempFolder.newFile("passwords.txt").apply { writeText("secret") }

        val resolved = PathTraversalSanitizer.getSafeFile(rootDir, "../passwords.txt")

        assertNull("Path traversal attack must be blocked", resolved)
    }

    @Test
    fun testEncodedPathTraversalAttemptReturnsNull() {
        val rootDir = tempFolder.newFolder("webroot")

        val resolved = PathTraversalSanitizer.getSafeFile(rootDir, "%2e%2e%2fpasswords.txt")

        assertNull("Encoded path traversal attack must be blocked", resolved)
    }
}
