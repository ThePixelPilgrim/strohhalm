package de.nereide.strohhalm

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.nereide.strohhalm.domain.AndroidSystemReader
import de.nereide.strohhalm.domain.EncryptedSshKeyStore
import kotlinx.coroutines.runBlocking
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.digest.BuiltinDigests
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.SshSessionFactory
import org.eclipse.jgit.transport.sshd.ServerKeyDatabase
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetSocketAddress
import java.security.PublicKey
import java.util.concurrent.atomic.AtomicReference

/**
 * THROWAWAY. Delete once it has answered its questions.
 *
 * The plan's Task 2 spike: confirm on real hardware that JGit works on Android,
 * that MINA SSHD negotiates Ed25519 against a real server, and what the exact
 * `ServerKeyDatabase` API shape is — all of which `JGitMirror` is written
 * against but nothing has yet verified.
 *
 * Unlike the version sketched in the plan, this uses the app's REAL key from
 * [EncryptedSshKeyStore] rather than generating a throwaway one, so it tests the
 * production key path end to end — the same key shown in Settings.
 *
 * The remote is passed in rather than hardcoded, so a private server URL never
 * enters git:
 *
 *   ./gradlew connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.remote=ssh://git@host:22/srv/git/repo.git \
 *     --tests '*SshSpikeTest*'
 */
@RunWith(AndroidJUnit4::class)
class SshSpikeTest {

    @Test
    fun mirrorClonesOverSsh() {
        val remote = InstrumentationRegistry.getArguments().getString("remote")
            ?: error(
                "No remote supplied. Re-run with:\n" +
                    "-Pandroid.testInstrumentationRunnerArguments.remote=ssh://git@host/path/repo.git"
            )

        AndroidSystemReader.install()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val work = File(context.cacheDir, "spike").apply { deleteRecursively(); mkdirs() }

        // The app's real identity — the key the Settings screen displays.
        val keyStore = EncryptedSshKeyStore(context.filesDir)
        val keyPair = runBlocking { keyStore.keyPair() }
        println("SPIKE pubkey: ${runBlocking { keyStore.publicKeyLine() }}")
        println("SPIKE remote: $remote")

        val observedHostKey = AtomicReference<String?>(null)

        val factory = SshdSessionFactoryBuilder()
            .setPreferredAuthentications("publickey")
            .setHomeDirectory(work)
            .setSshDirectory(File(work, "ssh").apply { mkdirs() })
            .setDefaultKeysProvider { listOf(keyPair) }
            .setServerKeyDatabase { _, _ ->
                object : ServerKeyDatabase {
                    override fun lookup(
                        connectAddress: String?,
                        remoteAddress: InetSocketAddress?,
                        config: ServerKeyDatabase.Configuration?,
                    ): List<PublicKey> = emptyList()

                    override fun accept(
                        connectAddress: String?,
                        remoteAddress: InetSocketAddress?,
                        serverKey: PublicKey?,
                        config: ServerKeyDatabase.Configuration?,
                        provider: CredentialsProvider?,
                    ): Boolean {
                        val fingerprint =
                            KeyUtils.getFingerPrint(BuiltinDigests.sha256, serverKey)
                        observedHostKey.set(fingerprint)
                        println("SPIKE hostkey: alg=${serverKey?.algorithm} fp=$fingerprint")
                        // Trust anything: this run is about discovering behaviour,
                        // not enforcing policy. JGitMirror pins for real.
                        return true
                    }
                }
            }
            .build(null)
        SshSessionFactory.setInstance(factory)

        val destination = File(work, "spike.git")
        Git.cloneRepository()
            .setURI(remote)
            .setDirectory(destination)
            .setBare(true)
            .setMirror(true)
            .call()
            .close()

        println("SPIKE hostkey captured: ${observedHostKey.get()}")
        println("SPIKE refs:")
        Git.open(destination).use { git ->
            git.repository.refDatabase.refs.forEach { println("SPIKE   ${it.name}") }
        }
        val size = destination.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        println("SPIKE size: $size bytes")

        assertTrue("no HEAD in the mirror", File(destination, "HEAD").isFile)
        assertTrue("no object database", File(destination, "objects").isDirectory)
        assertTrue("no host key was observed", observedHostKey.get() != null)
    }
}
