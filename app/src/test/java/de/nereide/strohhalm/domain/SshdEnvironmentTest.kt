package de.nereide.strohhalm.domain

import org.apache.sshd.common.util.io.PathUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SshdEnvironmentTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `install creates a home directory and points SSHD at it`() {
        val filesDir = tmp.newFolder("files")

        SshdEnvironment.install(filesDir)

        val expected = File(filesDir, "sshd-home")
        assertTrue("home directory was not created", expected.isDirectory)
        assertTrue(".ssh was not created", File(expected, ".ssh").isDirectory)
        assertEquals(expected.toPath(), PathUtils.getUserHomeFolder())
    }

    @Test
    fun `install is idempotent - a second call does not move the home`() {
        val first = tmp.newFolder("first")
        SshdEnvironment.install(first)
        val settled = SshdEnvironment.homeDir()

        SshdEnvironment.install(tmp.newFolder("second"))

        assertEquals(settled, SshdEnvironment.homeDir())
    }
}
