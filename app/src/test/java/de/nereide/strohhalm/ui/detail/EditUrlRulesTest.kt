package de.nereide.strohhalm.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The gate in front of `RepoRepository.updateRemoteUrl`. It matters that the
 * no-op cases really are no-ops: every write clears the pinned host key, so a
 * confirm on an untouched field would cost the user a fresh TOFU prompt.
 */
class EditUrlRulesTest {

    private val current = "git@old.example.com:me/repo.git"

    @Test
    fun `a different remote is persisted`() {
        assertEquals(
            "git@new.example.com:me/repo.git",
            EditUrlRules.urlToPersist(current, "git@new.example.com:me/repo.git"),
        )
    }

    @Test
    fun `blank input does nothing`() {
        assertNull(EditUrlRules.urlToPersist(current, ""))
        assertNull(EditUrlRules.urlToPersist(current, "   \n"))
    }

    @Test
    fun `the unchanged remote does nothing`() {
        assertNull(EditUrlRules.urlToPersist(current, current))
    }

    @Test
    fun `surrounding whitespace is neither a change nor kept`() {
        assertNull(EditUrlRules.urlToPersist(current, "  $current\n"))
        assertEquals(
            "git@new.example.com:me/repo.git",
            EditUrlRules.urlToPersist(current, "  git@new.example.com:me/repo.git\n"),
        )
    }

    @Test
    fun `an unknown current remote still accepts input`() {
        assertEquals(
            "git@new.example.com:me/repo.git",
            EditUrlRules.urlToPersist(null, "git@new.example.com:me/repo.git"),
        )
        assertNull(EditUrlRules.urlToPersist(null, " "))
    }
}
