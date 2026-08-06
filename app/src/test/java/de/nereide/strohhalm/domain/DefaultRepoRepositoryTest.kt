package de.nereide.strohhalm.domain

import de.nereide.strohhalm.data.SyncStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DefaultRepoRepositoryTest {

    private val dao = FakeRepoDao()
    private var now = 1_000L
    private val root = File("/storage/emulated/0/Strohhalm")

    private val repository = DefaultRepoRepository(
        dao = dao,
        storageRoot = { root },
        clock = { now }
    )

    @Test
    fun `add derives a local path from the remote url`() = runTest {
        val id = repository.add("Notes", "ssh://git@host/srv/notes.git", "SHA256:aaa")

        val repo = dao.byId(id)!!
        assertEquals(File(root, "notes.git").path, repo.localPath)
        assertEquals(SyncStatus.NEVER, repo.lastStatus)
        assertEquals(1_000L, repo.createdAt)
        assertEquals("SHA256:aaa", repo.hostKeyFingerprint)
    }

    @Test
    fun `a repository can be added without a host key`() = runTest {
        val id = repository.add(
            "Pending",
            "ssh://git@host/srv/pending.git",
            hostKeyFingerprint = null,
        )

        assertNull(dao.byId(id)!!.hostKeyFingerprint)
    }

    @Test
    fun `add makes colliding paths unique`() = runTest {
        repository.add("First", "ssh://a.host/srv/notes.git", "SHA256:aaa")
        val second = repository.add("Second", "ssh://b.host/other/notes.git", "SHA256:bbb")

        assertEquals(File(root, "notes-2.git").path, dao.byId(second)!!.localPath)
    }

    @Test
    fun `a blank display name falls back to the slug`() = runTest {
        val id = repository.add("  ", "ssh://host/srv/my-notes.git", "SHA256:aaa")

        assertEquals("my-notes", dao.byId(id)!!.displayName)
    }

    @Test
    fun `a successful sync records the time, size and ref count`() = runTest {
        val id = repository.add("Notes", "ssh://host/notes.git", "SHA256:aaa")
        now = 2_000L

        repository.markSuccess(id, sizeBytes = 4_096, refCount = 7)

        val repo = dao.byId(id)!!
        assertEquals(SyncStatus.OK, repo.lastStatus)
        assertEquals(2_000L, repo.lastSyncAt)
        assertEquals(2_000L, repo.lastAttemptAt)
        assertEquals(4_096L, repo.sizeBytes)
        assertEquals(7, repo.refCount)
        assertNull(repo.lastErrorCode)
    }

    @Test
    fun `a failed sync preserves the last successful time`() = runTest {
        val id = repository.add("Notes", "ssh://host/notes.git", "SHA256:aaa")
        now = 2_000L
        repository.markSuccess(id, sizeBytes = 4_096, refCount = 3)

        now = 3_000L
        repository.markFailure(
            id,
            SyncError(SyncErrorCode.AUTH_FAILED, "Auth fail", "TransportException")
        )

        val repo = dao.byId(id)!!
        assertEquals(SyncStatus.FAILED, repo.lastStatus)
        assertEquals("the success time must not move on failure", 2_000L, repo.lastSyncAt)
        assertEquals(3_000L, repo.lastAttemptAt)
        assertEquals("AUTH_FAILED", repo.lastErrorCode)
        assertEquals("Auth fail", repo.lastErrorDetail)
        assertEquals("TransportException", repo.lastErrorDiagnostic)
        assertEquals("the size must survive a failure", 4_096L, repo.sizeBytes)
    }

    @Test
    fun `a later success clears the recorded error`() = runTest {
        val id = repository.add("Notes", "ssh://host/notes.git", "SHA256:aaa")
        repository.markFailure(id, SyncError(SyncErrorCode.NO_NETWORK))
        repository.markSuccess(id, sizeBytes = 10, refCount = 1)

        val repo = dao.byId(id)!!
        assertNull(repo.lastErrorCode)
        assertNull(repo.lastErrorDetail)
        assertNull(repo.lastErrorDiagnostic)
    }

    @Test
    fun `markSyncing does not touch the timestamps`() = runTest {
        val id = repository.add("Notes", "ssh://host/notes.git", "SHA256:aaa")
        repository.markSyncing(id)

        val repo = dao.byId(id)!!
        assertEquals(SyncStatus.SYNCING, repo.lastStatus)
        assertNull(repo.lastSyncAt)
    }

    @Test
    fun `all returns the least recently synced first`() = runTest {
        val a = repository.add("A", "ssh://host/a.git", "SHA256:aaa")
        val b = repository.add("B", "ssh://host/b.git", "SHA256:bbb")
        now = 5_000L
        repository.markSuccess(a, 1, 1)

        assertEquals(listOf(b, a), repository.all().map { it.id })
    }

    @Test
    fun `delete removes the row`() = runTest {
        val id = repository.add("Notes", "ssh://host/notes.git", "SHA256:aaa")
        repository.delete(id)

        assertTrue(dao.all().isEmpty())
    }

    @Test
    fun `updateRemoteUrl repoints the row and unpins the host key`() = runTest {
        val id = repository.add("Notes", "ssh://old.host/srv/notes.git", "SHA256:aaa")

        repository.updateRemoteUrl(id, "ssh://new.host/srv/notes.git")

        val repo = dao.byId(id)!!
        assertEquals("ssh://new.host/srv/notes.git", repo.remoteUrl)
        assertNull("a new server must be trusted afresh", repo.hostKeyFingerprint)
    }

    @Test
    fun `updateRemoteUrl trims the input`() = runTest {
        val id = repository.add("Notes", "ssh://old.host/notes.git", "SHA256:aaa")

        repository.updateRemoteUrl(id, "  ssh://new.host/notes.git\n")

        assertEquals("ssh://new.host/notes.git", dao.byId(id)!!.remoteUrl)
    }

    @Test
    fun `updateRemoteUrl keeps the mirror and its sync status`() = runTest {
        val id = repository.add("Notes", "ssh://old.host/notes.git", "SHA256:aaa")
        now = 2_000L
        repository.markSuccess(id, sizeBytes = 4_096, refCount = 7)
        val before = dao.byId(id)!!

        repository.updateRemoteUrl(id, "ssh://new.host/notes.git")

        val repo = dao.byId(id)!!
        assertEquals("the mirror is the same content", before.localPath, repo.localPath)
        assertEquals(SyncStatus.OK, repo.lastStatus)
        assertEquals(2_000L, repo.lastSyncAt)
        assertEquals(2_000L, repo.lastAttemptAt)
        assertEquals(4_096L, repo.sizeBytes)
        assertEquals(7, repo.refCount)
        assertEquals(before.displayName, repo.displayName)
    }

    @Test
    fun `updateRemoteUrl ignores a blank url`() = runTest {
        val id = repository.add("Notes", "ssh://old.host/notes.git", "SHA256:aaa")

        repository.updateRemoteUrl(id, "   ")

        val repo = dao.byId(id)!!
        assertEquals("ssh://old.host/notes.git", repo.remoteUrl)
        assertEquals("SHA256:aaa", repo.hostKeyFingerprint)
    }

    @Test
    fun `resetStaleSyncing rewrites rows left mid-sync and leaves others alone`() = runTest {
        val stuck = repository.add("Stuck", "ssh://host/a.git", "SHA256:aaa")
        val fine = repository.add("Fine", "ssh://host/b.git", "SHA256:bbb")
        repository.markSuccess(fine, 10, 2)
        repository.markSyncing(stuck)

        repository.resetStaleSyncing(SyncError(SyncErrorCode.INTERRUPTED))

        assertEquals(SyncStatus.FAILED, dao.byId(stuck)!!.lastStatus)
        assertEquals("INTERRUPTED", dao.byId(stuck)!!.lastErrorCode)
        assertEquals("a healthy row must be untouched", SyncStatus.OK, dao.byId(fine)!!.lastStatus)
    }
}
