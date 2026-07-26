package de.nereide.strohhalm.domain.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class LsRefsTest {

    private val sha256Caps = ServerCapabilities(
        raw = mapOf("version" to "2", "fetch" to "", "ls-refs" to "", "object-format" to "sha256"),
        objectHash = ObjectHash.SHA256,
    )

    private val id = "a".repeat(64)
    private val peeledId = "b".repeat(64)

    private fun response(vararg lines: String): Pair<UploadPackV2, ByteArrayOutputStream> {
        val server = ByteArrayOutputStream()
        lines.forEach { PktLine.writeString(server, "$it\n") }
        PktLine.writeFlush(server)
        val sent = ByteArrayOutputStream()
        return UploadPackV2(ByteArrayInputStream(server.toByteArray()), sent) to sent
    }

    @Test
    fun `refs are parsed with their symref target and peeled id`() {
        val (protocol, _) = response(
            "$id HEAD symref-target:refs/heads/main",
            "$id refs/heads/main",
            "$peeledId refs/tags/v1 peeled:$id",
        )

        val refs = protocol.lsRefs(sha256Caps)

        assertEquals(3, refs.size)
        assertEquals("refs/heads/main", refs[0].symrefTarget)
        assertEquals(id, refs[1].objectId)
        assertEquals("refs/tags/v1", refs[2].name)
        assertEquals(id, refs[2].peeled)
    }

    @Test
    fun `the request announces the negotiated object format`() {
        val (protocol, sent) = response("$id refs/heads/main")
        protocol.lsRefs(sha256Caps)

        val request = sent.toString(Charsets.UTF_8.name())
        assertTrue(request.contains("command=ls-refs"))
        assertTrue(request.contains("object-format=sha256"))
        assertTrue(request.contains("peel"))
        assertTrue(request.contains("symrefs"))
        // ls-refs filters strictly by prefix, so HEAD needs its own entry —
        // "refs/" alone would never return the symref local HEAD is set from.
        assertTrue(request.contains("ref-prefix HEAD"))
        // Everything under refs/, so branches, tags and notes all arrive.
        assertTrue(request.contains("ref-prefix refs/"))
        // Servers before git 2.30 die on the unknown "unborn" keyword, and the
        // mirror has no use for an unborn HEAD anyway.
        assertFalse(request.contains("unborn"))
        assertTrue(request.endsWith("0000"))
    }

    @Test
    fun `an empty repository yields no refs rather than failing`() {
        val (protocol, _) = response()
        assertTrue(protocol.lsRefs(sha256Caps).isEmpty())
    }
}
