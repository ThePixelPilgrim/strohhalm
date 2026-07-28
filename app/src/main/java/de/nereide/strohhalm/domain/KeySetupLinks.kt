package de.nereide.strohhalm.domain

/**
 * Where a forge keeps its "add SSH key" page.
 *
 * Shown when authentication fails: the fix is always "put the public key on
 * the server", and for a known forge the app can hand over the exact page
 * instead of a description of it. Exact host match only — a suffix match
 * would follow lookalike hosts to a login page.
 */
object KeySetupLinks {

    fun forHost(host: String): String? = when (host.lowercase()) {
        "github.com" -> "https://github.com/settings/keys"
        "codeberg.org" -> "https://codeberg.org/user/settings/keys"
        else -> null
    }
}
