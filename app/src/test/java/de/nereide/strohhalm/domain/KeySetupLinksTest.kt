package de.nereide.strohhalm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeySetupLinksTest {

    @Test
    fun `known forges map to their ssh key settings page`() {
        assertEquals("https://github.com/settings/keys", KeySetupLinks.forHost("github.com"))
        assertEquals("https://codeberg.org/user/settings/keys", KeySetupLinks.forHost("codeberg.org"))
    }

    @Test
    fun `case does not matter — hosts are case-insensitive`() {
        assertEquals("https://github.com/settings/keys", KeySetupLinks.forHost("GitHub.com"))
    }

    @Test
    fun `unknown hosts map to nothing`() {
        assertNull(KeySetupLinks.forHost("git.example.org"))
        assertNull(KeySetupLinks.forHost("gitlab.com"))
        // A lookalike must not match: the link sends the user to a login page.
        assertNull(KeySetupLinks.forHost("github.com.evil.example"))
    }
}
