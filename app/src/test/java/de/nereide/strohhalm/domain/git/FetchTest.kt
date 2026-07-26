package de.nereide.strohhalm.domain.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class FetchTest {

    private val caps = ServerCapabilities(
        raw = mapOf("version" to "2", "fetch" to "", "object-format" to "sha256"),
        objectHash = ObjectHash.SHA256,
    )
    private val want = "a".repeat(64)
    private val have = "b".repeat(64)

    /** A server response: the packfile section header, then sideband data. */
    private fun serverResponse(packBytes: String): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        PktLine.writeString(out, "packfile\n")
        PktLine.writeBytes(out, byteArrayOf(1) + packBytes.toByteArray())
        PktLine.writeFlush(out)
        return ByteArrayInputStream(out.toByteArray())
    }

    @Test
    fun `the pack stream contains exactly the band 1 payload`() {
        val protocol = UploadPackV2(serverResponse("PACKDATA"), ByteArrayOutputStream())
        val stream = protocol.fetch(caps, listOf(want), emptyList()) {}
        assertEquals("PACKDATA", String(stream.readBytes()))
    }

    @Test
    fun `wants haves and done are sent, and thin-pack is not requested`() {
        val sent = ByteArrayOutputStream()
        UploadPackV2(serverResponse("X"), sent)
            .fetch(caps, listOf(want), listOf(have)) {}

        val request = sent.toString(Charsets.UTF_8.name())
        assertTrue(request.contains("command=fetch"))
        assertTrue(request.contains("object-format=sha256"))
        assertTrue(request.contains("want $want"))
        assertTrue(request.contains("have $have"))
        assertTrue(request.contains("done"))
        assertTrue("offset deltas cut transfer size", request.contains("ofs-delta"))
        // Declining thin-pack obliges the server to send a self-contained pack,
        // which is what lets the indexer never read local objects.
        assertFalse("thin-pack must not be requested", request.contains("thin-pack"))
    }

    @Test
    fun `server progress reaches the callback`() {
        val out = ByteArrayOutputStream()
        PktLine.writeString(out, "packfile\n")
        PktLine.writeBytes(out, byteArrayOf(2) + "Counting objects: 12".toByteArray())
        PktLine.writeBytes(out, byteArrayOf(1) + "P".toByteArray())
        PktLine.writeFlush(out)

        val seen = mutableListOf<String>()
        val stream = UploadPackV2(ByteArrayInputStream(out.toByteArray()), ByteArrayOutputStream())
            .fetch(caps, listOf(want), emptyList()) { seen += it }
        stream.readBytes()

        assertEquals(listOf("Counting objects: 12"), seen)
    }
}
