package de.nereide.strohhalm.domain.git

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ObjectHashTest {

    @Test
    fun `lengths follow the hash`() {
        assertEquals(20, ObjectHash.SHA1.rawLength)
        assertEquals(40, ObjectHash.SHA1.hexLength)
        assertEquals(32, ObjectHash.SHA256.rawLength)
        assertEquals(64, ObjectHash.SHA256.hexLength)
    }

    @Test
    fun `config names match git's extensions objectFormat values`() {
        assertEquals(ObjectHash.SHA1, ObjectHash.fromConfigName("sha1"))
        assertEquals(ObjectHash.SHA256, ObjectHash.fromConfigName("sha256"))
    }

    @Test
    fun `an unknown format is refused rather than guessed`() {
        assertThrows(IllegalArgumentException::class.java) {
            ObjectHash.fromConfigName("sha512")
        }
    }

    /** `printf '' | git hash-object --stdin` — the empty blob, both formats. */
    @Test
    fun `object id of the empty blob matches git`() {
        assertEquals(
            "e69de29bb2d1d6434b8b29ae775ad8c2e48c5391",
            ObjectHash.SHA1.toHex(ObjectHash.SHA1.objectId("blob", ByteArray(0))),
        )
        assertEquals(
            "473a0f4c3be8a93681a267e3b1e9a7dcda1185436fe141f7749120a303721813",
            ObjectHash.SHA256.toHex(ObjectHash.SHA256.objectId("blob", ByteArray(0))),
        )
    }

    @Test
    fun `hex round-trips`() {
        val raw = ObjectHash.SHA256.objectId("blob", "hello".toByteArray())
        assertArrayEquals(raw, ObjectHash.SHA256.fromHex(ObjectHash.SHA256.toHex(raw)))
    }
}
