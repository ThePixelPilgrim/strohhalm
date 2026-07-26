package de.nereide.strohhalm.domain.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UploadPackChannelTest {

    @Test
    fun `an ssh url splits into user host port and path`() {
        val remote = GitRemote.parse("ssh://git@example.org/owner/repo.git")
        assertEquals("git", remote.user)
        assertEquals("example.org", remote.host)
        assertEquals(22, remote.port)
        assertEquals("/owner/repo.git", remote.path)
    }

    @Test
    fun `an explicit port is honoured`() {
        assertEquals(2222, GitRemote.parse("ssh://git@example.org:2222/o/r.git").port)
    }

    @Test
    fun `scp-style remotes are accepted and their path is relative`() {
        val remote = GitRemote.parse("git@example.org:owner/repo.git")
        assertEquals("git", remote.user)
        assertEquals("example.org", remote.host)
        assertEquals(22, remote.port)
        assertEquals("owner/repo.git", remote.path)
    }

    @Test
    fun `a missing user defaults to git`() {
        assertEquals("git", GitRemote.parse("ssh://example.org/o/r.git").user)
    }

    @Test
    fun `a non-ssh url is refused rather than half-handled`() {
        assertThrows(IllegalArgumentException::class.java) {
            GitRemote.parse("https://example.org/o/r.git")
        }
    }
}
