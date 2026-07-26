package de.nereide.strohhalm.domain.archive

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The provider's `query` override needs a real `ContentResolver` and so belongs
 * to the device check in Task 11. What is testable here is the name mapping it
 * applies, which is where a mistake would actually reach a recipient.
 */
class ArchiveDisplayNameTest {

    @Test
    fun `a real archive name becomes a name worth receiving`() {
        assertEquals(
            "yamiro-2026-07-26.zip",
            ArchiveNames.displayName("yamiro-2026-07-26-4f2a91c07b3e.zip"),
        )
    }

    @Test
    fun `a part file is never presentable and is left untouched`() {
        assertEquals(
            "yamiro-2026-07-26-4f2a91c07b3e.zip.part",
            ArchiveNames.displayName("yamiro-2026-07-26-4f2a91c07b3e.zip.part"),
        )
    }
}
