package de.nereide.strohhalm.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RepoSlugTest {

    @Test
    fun `takes the last path segment of an ssh url`() {
        assertEquals("stromschnelle", RepoSlug.fromRemoteUrl("ssh://git@host:22/srv/git/stromschnelle.git"))
    }

    @Test
    fun `handles scp style remotes`() {
        assertEquals("notes", RepoSlug.fromRemoteUrl("git@host:notes.git"))
    }

    @Test
    fun `handles scp style remotes with a path`() {
        assertEquals("notes", RepoSlug.fromRemoteUrl("git@host:srv/git/notes.git"))
    }

    @Test
    fun `tolerates a trailing slash and a missing dot-git`() {
        assertEquals("erhebimus", RepoSlug.fromRemoteUrl("ssh://host/srv/erhebimus/"))
    }

    @Test
    fun `lowercases and collapses runs of punctuation`() {
        assertEquals("my-repo", RepoSlug.fromRemoteUrl("ssh://host/My__Repo!!.git"))
    }

    @Test
    fun `strips leading and trailing separators`() {
        assertEquals("repo", RepoSlug.fromRemoteUrl("ssh://host/--repo--.git"))
    }

    @Test
    fun `falls back when nothing usable remains`() {
        assertEquals("repo", RepoSlug.fromRemoteUrl("ssh://host/!!!.git"))
    }

    @Test
    fun `unique returns the base when it is free`() {
        assertEquals("notes", RepoSlug.unique("notes", emptySet()))
    }

    @Test
    fun `unique suffixes on collision`() {
        assertEquals("notes-2", RepoSlug.unique("notes", setOf("notes")))
    }

    @Test
    fun `unique keeps counting past several collisions`() {
        assertEquals("notes-4", RepoSlug.unique("notes", setOf("notes", "notes-2", "notes-3")))
    }
}
