# Non-Blocking Add Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Adding a repository saves immediately and never blocks on the network; host-key verification happens in the background from the detail view; an SSH auth failure shows a persistent card with the forge's key-settings link; the remote URL is copyable.

**Architecture:** The add screen loses all network I/O and saves a row with `hostKeyFingerprint = null`. The detail view, seeing a null fingerprint, drives the existing probe machinery in the background and pins on user confirmation — including when authentication failed, since the host key is observed during key exchange, before auth. `SyncRunner` quietly skips unverified repositories. A pure `VerifyRules` object maps probe results to UI outcomes (the `ShareRules` pattern), and a pure `KeySetupLinks` object maps hosts to their SSH-key settings pages.

**Tech Stack:** Kotlin, Jetpack Compose + Material 3, Room, MINA SSHD (domain/git only), JUnit4 JVM tests with the existing local-`SshServer` harness.

**Spec:** `docs/superpowers/specs/2026-07-28-nonblocking-add-design.md`

## Global Constraints

- Every Gradle call needs `export JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk` first — the default JDK 25 kills Gradle before any task runs.
- Run tests with `./gradlew :app:testDebugUnitTest --tests "<Class>"`; `UP-TO-DATE` is not evidence — verify via the XML counts when in doubt:
  `grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' app/build/test-results/testDebugUnitTest/TEST-<fqcn>.xml`
- TDD strictly: write the failing test, run it, see it fail, implement, see it pass, commit. Never claim a pass without reading output.
- No hardcoded user-facing strings in Compose — everything through `res/values/strings.xml`.
- Only `domain/git` may import MINA SSHD; library exceptions are mapped to `SyncError` inside the domain layer. Tests may import SSHD/JGit for fixtures.
- Never push, commit, or write to a git remote from app code.
- Conventional commits: `feat(domain):`, `test(ui):`, `fix(ui):`, etc. One commit per task.
- Tasks are **sequential** — this repo cannot run two Gradle builds at once (shared build directory, whole-source-set Kotlin compilation).

---

### Task 1: `probeHostKey` carries the fingerprint out of every failure where a key was seen

The host key is observed during SSH key exchange, before authentication. Today `ProtocolMirror.probeHostKey` wraps a failure in `ProbeRejectedException` only when the server wrote to stderr; an auth failure (blank stderr) discards the fingerprint. After this task, any failure past key exchange carries the fingerprint.

**Files:**
- Modify: `app/src/main/java/de/nereide/strohhalm/domain/git/ProtocolMirror.kt` (the `probeHostKey` override, near line 175)
- Modify: `app/src/main/java/de/nereide/strohhalm/domain/SyncError.kt` (`ProbeRejectedException` message, `SyncErrors.classify`, `classifyByMessage`)
- Test: `app/src/test/java/de/nereide/strohhalm/domain/git/ProbeAuthFailureTest.kt` (create)
- Test: `app/src/test/java/de/nereide/strohhalm/domain/SyncErrorsTest.kt` (extend)

**Interfaces:**
- Consumes: `UploadPackChannel.observedHostKey: String?`, `channel.stderrText(): String` (existing).
- Produces: `ProbeRejectedException(fingerprint: String, serverMessage: String, cause: Throwable)` now thrown for **every** probe failure where a fingerprint was observed; `serverMessage` may be `""`. `SyncErrors.fromException` classifies a blank-message `ProbeRejectedException` by its *cause* (e.g. `AUTH_FAILED`), not as `REMOTE_ERROR`. Task 4 relies on exactly this.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/de/nereide/strohhalm/domain/git/ProbeAuthFailureTest.kt`:

```kotlin
package de.nereide.strohhalm.domain.git

import de.nereide.strohhalm.domain.ProbeRejectedException
import de.nereide.strohhalm.domain.SyncErrorCode
import de.nereide.strohhalm.domain.SyncErrors
import kotlinx.coroutines.runBlocking
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.pubkey.RejectAllPublickeyAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator

/**
 * The missing-public-key scenario: the server completes the key exchange —
 * presenting its host key — and then refuses authentication. The probe must
 * hand that fingerprint out anyway, because pinning it is exactly what lets
 * the user add the repository now and install the key later.
 */
class ProbeAuthFailureTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var server: SshServer
    private lateinit var clientKey: KeyPair

    @Before
    fun startServer() {
        // RSA, not Ed25519: sshd 2.14 supports Ed25519 only through the
        // net.i2p EdDSA provider, not the JDK's own EdEC keys.
        clientKey = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }.generateKeyPair()
        server = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(
                File(temp.newFolder("hostkey"), "host.ser").toPath()
            )
            publickeyAuthenticator = RejectAllPublickeyAuthenticator.INSTANCE
            start()
        }
    }

    @After
    fun stopServer() {
        if (::server.isInitialized) server.stop(true)
    }

    @Test
    fun `a refused authentication still yields the observed host key`() = runBlocking {
        val url = "ssh://test@127.0.0.1:${server.port}/srv/repo.git"

        val result = ProtocolMirror(keyPairProvider = { clientKey }).probeHostKey(url)

        val failure = result.exceptionOrNull()
        assertTrue("expected ProbeRejectedException, got $failure",
            failure is ProbeRejectedException)
        failure as ProbeRejectedException
        assertTrue("fingerprint captured: ${failure.fingerprint}",
            failure.fingerprint.startsWith("SHA256:"))
        assertEquals(
            SyncErrorCode.AUTH_FAILED,
            SyncErrors.fromException(failure).code,
        )
    }
}
```

Add to `app/src/test/java/de/nereide/strohhalm/domain/SyncErrorsTest.kt` (append inside the class; match its existing imports — it already imports the error types):

```kotlin
    /**
     * A ProbeRejectedException with a blank server message is a transport
     * carrying a fingerprint, not a server verdict. Classification must fall
     * through to its cause — here an auth failure — instead of reporting a
     * REMOTE_ERROR with an empty message.
     */
    @Test
    fun `a blank probe message defers classification to the cause`() {
        val error = SyncErrors.fromException(
            ProbeRejectedException(
                fingerprint = "SHA256:abc",
                serverMessage = "",
                cause = Exception("Permission denied (publickey)"),
            )
        )
        assertEquals(SyncErrorCode.AUTH_FAILED, error.code)
    }

    @Test
    fun `a probe message from the server still wins over the cause`() {
        val error = SyncErrors.fromException(
            ProbeRejectedException(
                fingerprint = "SHA256:abc",
                serverMessage = "repository not found",
                cause = Exception("stream ended"),
            )
        )
        assertEquals(SyncErrorCode.REMOTE_ERROR, error.code)
    }
```

- [ ] **Step 2: Run the tests, watch them fail**

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk
./gradlew :app:testDebugUnitTest --tests "de.nereide.strohhalm.domain.git.ProbeAuthFailureTest" --tests "de.nereide.strohhalm.domain.SyncErrorsTest"
```

Expected: `ProbeAuthFailureTest` fails — the probe currently rethrows the raw
transport failure (blank stderr), so `failure is ProbeRejectedException` is
false. The new `SyncErrorsTest` case `a blank probe message defers…` fails
with `REMOTE_ERROR`. If the auth assertion fails with `UNKNOWN` instead, note
the actual exception message printed — Step 3 adds the mapping.

- [ ] **Step 3: Implement**

In `ProtocolMirror.kt`, replace the failure branch of `probeHostKey` (currently
`val message = channel.stderrText(); if (message.isBlank()) throw failure; throw ProbeRejectedException(...)`):

```kotlin
                }.onFailure { failure ->
                    // Past key exchange the server's identity is known even
                    // when authentication or the git command failed. Carrying
                    // it out lets the UI offer to pin now and sync later —
                    // the missing-public-key case.
                    val fingerprint = channel.observedHostKey ?: throw failure
                    throw ProbeRejectedException(fingerprint, channel.stderrText(), failure)
                }
```

In `SyncError.kt`, update `ProbeRejectedException`'s message so a blank server
message does not render as `the server said: `:

```kotlin
class ProbeRejectedException(
    val fingerprint: String,
    val serverMessage: String,
    cause: Throwable,
) : Exception(
    if (serverMessage.isBlank()) cause.message ?: "the connection failed"
    else "the server said: $serverMessage",
    cause,
)
```

In `SyncErrors.classify`, make the probe branch conditional on a non-blank
message (blank falls through to the cause chain):

```kotlin
        t is ProbeRejectedException && t.serverMessage.isNotBlank() ->
            SyncError(SyncErrorCode.REMOTE_ERROR, "the server said: ${t.serverMessage}")
```

In `classifyByMessage`, extend the auth patterns — MINA reports a rejected
public key as "No more authentication methods available", which none of the
current patterns match:

```kotlin
            "auth fail" in lower ||
                "permission denied" in lower ||
                "no more authentication" in lower ||
                "publickey" in lower ->
                SyncError(SyncErrorCode.AUTH_FAILED, message)
```

- [ ] **Step 4: Run the tests, watch them pass**

Same command as Step 2. Expected: both classes green. If `ProbeAuthFailureTest`
still reports a non-`AUTH_FAILED` code, read the printed diagnostic chain and
add the *actual* MINA message fragment to `classifyByMessage` — do not guess.

- [ ] **Step 5: Run the full suite** (`probeHostKey` has other callers)

```bash
./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL. `MirrorEndToEndTest` and `ProtocolMirrorErrorTest`
exercise the probe's success and host-key-refusal paths and must stay green.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat(domain): carry the host key out of auth-failed probes

The key is observed during key exchange, before authentication, so a
server that refuses auth has still identified itself. The probe now wraps
every post-KEX failure in ProbeRejectedException; a blank server message
defers classification to the cause instead of masking it as REMOTE_ERROR."
```

---

### Task 2: `KeySetupLinks` — forge host → SSH-key settings page

**Files:**
- Create: `app/src/main/java/de/nereide/strohhalm/domain/KeySetupLinks.kt`
- Test: `app/src/test/java/de/nereide/strohhalm/domain/KeySetupLinksTest.kt` (create)

**Interfaces:**
- Produces: `KeySetupLinks.forHost(host: String): String?` — null for unknown hosts. Task 6 calls it with `GitRemote.parse(url).host`.

- [ ] **Step 1: Write the failing test**

```kotlin
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
```

- [ ] **Step 2: Run, watch it fail** — `--tests "de.nereide.strohhalm.domain.KeySetupLinksTest"`. Expected: compile error, `KeySetupLinks` unresolved.

- [ ] **Step 3: Implement**

```kotlin
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
```

- [ ] **Step 4: Run, watch it pass** — same command. Expected: 3 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(domain): map known forges to their ssh-key settings page"
```

---

### Task 3: Unverified repositories — nullable fingerprint on add, quietly skipped by syncs

**Files:**
- Modify: `app/src/main/java/de/nereide/strohhalm/domain/RepoRepository.kt` (`add` signature)
- Modify: `app/src/main/java/de/nereide/strohhalm/domain/DefaultRepoRepository.kt` (`add` signature)
- Modify: `app/src/main/java/de/nereide/strohhalm/domain/SyncRunner.kt` (`launchSyncOne`, `launchSyncAll`)
- Test: `app/src/test/java/de/nereide/strohhalm/domain/SyncRunnerSkipTest.kt` (create)
- Test: `app/src/test/java/de/nereide/strohhalm/domain/DefaultRepoRepositoryTest.kt` (extend)

**Interfaces:**
- Produces: `RepoRepository.add(displayName: String, remoteUrl: String, hostKeyFingerprint: String?): Long` — fingerprint now nullable. `SyncRunner.launchSyncOne`/`launchSyncAll` never call the mirror for a repo whose `hostKeyFingerprint == null` and record nothing on its row. Tasks 5 and 6 rely on both.
- Consumes: `Repo.hostKeyFingerprint: String?` (already nullable — no Room migration).

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/de/nereide/strohhalm/domain/SyncRunnerSkipTest.kt`
(the fakes follow `SyncRunnerCancelTest` exactly):

```kotlin
package de.nereide.strohhalm.domain

import de.nereide.strohhalm.data.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A repository whose host key was never confirmed must not be contacted: the
 * engine would refuse the unpinned key and the row would collect a failure
 * every 15 minutes for a state the UI already explains. Skipping is silent —
 * no SYNCING, no error, no attempt timestamp.
 */
class SyncRunnerSkipTest {

    private val dao = FakeRepoDao()
    private val repos = DefaultRepoRepository(
        dao = dao,
        storageRoot = { File("/storage/emulated/0/Strohhalm") },
        clock = { 1_000L },
    )

    private val syncCalls = AtomicInteger(0)

    private val countingMirror = object : GitMirror {
        override suspend fun sync(
            remoteUrl: String,
            destination: File,
            pinnedFingerprint: String?,
            progress: MirrorProgress?,
        ): MirrorOutcome {
            syncCalls.incrementAndGet()
            return MirrorOutcome.Success(sizeBytes = 0, refCount = 0)
        }

        override suspend fun probeHostKey(remoteUrl: String) = Result.failure<String>(
            UnsupportedOperationException()
        )

        override fun refNames(destination: File): List<String> = emptyList()

        override fun sizeBytes(destination: File): Long = 0
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val runner = SyncRunner(repos, countingMirror, scope)

    private suspend fun awaitIdle() =
        withTimeout(TimeUnit.SECONDS.toMillis(10)) { runner.running.first { !it } }

    @Test
    fun `sync-all only touches verified repositories`() = runBlocking {
        val unverified = repos.add("Pending", "ssh://git@host/srv/pending.git", null)
        repos.add("Ready", "ssh://git@host/srv/ready.git", "SHA256:aaa")

        runner.launchSyncAll()
        awaitIdle()

        assertEquals("only the verified repo syncs", 1, syncCalls.get())
        val row = dao.byId(unverified)!!
        assertEquals(SyncStatus.NEVER, row.lastStatus)
        assertNull("no error for a skipped repo", row.lastErrorCode)
        assertNull("no attempt recorded", row.lastAttemptAt)
    }

    @Test
    fun `sync-one refuses an unverified repository`() = runBlocking {
        val id = repos.add("Pending", "ssh://git@host/srv/pending.git", null)

        runner.launchSyncOne(id)
        awaitIdle()

        assertEquals(0, syncCalls.get())
        assertEquals(SyncStatus.NEVER, dao.byId(id)!!.lastStatus)
    }
}
```

Add to `DefaultRepoRepositoryTest.kt` (append inside the class, following its
existing `repos.add(...)` style — read the file first for the fixture names):

```kotlin
    @Test
    fun `a repository can be added without a host key`() = runBlocking {
        val id = repos.add("Pending", "ssh://git@host/srv/pending.git", hostKeyFingerprint = null)
        assertNull(dao.byId(id)!!.hostKeyFingerprint)
    }
```

- [ ] **Step 2: Run, watch them fail**

```bash
./gradlew :app:testDebugUnitTest --tests "de.nereide.strohhalm.domain.SyncRunnerSkipTest" --tests "de.nereide.strohhalm.domain.DefaultRepoRepositoryTest"
```

Expected: compile error — `add`'s third parameter is non-null `String`.

- [ ] **Step 3: Implement**

`RepoRepository.kt`:

```kotlin
    /**
     * Creates a repository with a directory name derived from [remoteUrl] and
     * made unique against those already taken. Returns the new row id.
     *
     * A null [hostKeyFingerprint] is a repository added before its server was
     * verified; syncs skip it until a key is pinned.
     */
    suspend fun add(displayName: String, remoteUrl: String, hostKeyFingerprint: String?): Long
```

`DefaultRepoRepository.kt`: change the parameter type to `String?` (the `Repo`
field is already nullable; nothing else changes).

`SyncRunner.kt` — replace the two launch functions' bodies:

```kotlin
    fun launchSyncOne(id: Long): Boolean = launch {
        repos.all().firstOrNull { it.id == id }
            ?.takeIf { it.hostKeyFingerprint != null }
            ?.let { sync(it) }
    }

    /** @return whether a sync actually started; see [launchSyncOne]. */
    fun launchSyncAll(): Boolean = launch {
        // Unverified repositories are skipped silently: contacting them would
        // only manufacture the refusal the UI already explains, once per cycle.
        repos.all().filter { it.hostKeyFingerprint != null }.forEach { sync(it) }
    }
```

- [ ] **Step 4: Run, watch them pass** — same command as Step 2.

- [ ] **Step 5: Full suite** — `./gradlew :app:testDebugUnitTest`. `SyncRunnerCancelTest` passes a non-null fingerprint and must stay green.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat(domain): allow adding a repository before its server is verified

The fingerprint parameter of add() is nullable, and SyncRunner silently
skips rows without a pinned key — contacting them would only manufacture
the refusal the UI already explains, once per sync cycle."
```

---

### Task 4: `VerifyRules` — probe result → verification outcome (pure)

**Files:**
- Create: `app/src/main/java/de/nereide/strohhalm/ui/detail/VerifyRules.kt`
- Test: `app/src/test/java/de/nereide/strohhalm/ui/detail/VerifyRulesTest.kt` (create)

**Interfaces:**
- Consumes: `ProbeRejectedException` (Task 1 semantics), `SyncErrors.fromException`.
- Produces:
  ```kotlin
  sealed interface VerifyOutcome {
      data class Pending(val fingerprint: String, val authFailed: Boolean) : VerifyOutcome
      data class Failed(val error: SyncError) : VerifyOutcome
  }
  object VerifyRules { fun fromProbe(result: Result<String>): VerifyOutcome }
  ```
  Task 6's ViewModel calls `fromProbe` with `mirror.probeHostKey(url)`'s result.

- [ ] **Step 1: Write the failing test**

```kotlin
package de.nereide.strohhalm.ui.detail

import de.nereide.strohhalm.domain.ProbeRejectedException
import de.nereide.strohhalm.domain.SyncErrorCode
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.UnknownHostException

/**
 * What each probe result means for the verification flow. Pure, because the
 * decision is subtle enough to deserve tests without a ViewModel around it:
 * an auth failure carrying a fingerprint is an *offer to pin*, not an error.
 */
class VerifyRulesTest {

    @Test
    fun `a clean probe offers the fingerprint`() {
        assertEquals(
            VerifyOutcome.Pending("SHA256:abc", authFailed = false),
            VerifyRules.fromProbe(Result.success("SHA256:abc")),
        )
    }

    @Test
    fun `auth refused with a key seen still offers the fingerprint, flagged`() {
        val failure = ProbeRejectedException(
            fingerprint = "SHA256:abc",
            serverMessage = "",
            cause = Exception("Permission denied (publickey)"),
        )
        assertEquals(
            VerifyOutcome.Pending("SHA256:abc", authFailed = true),
            VerifyRules.fromProbe(Result.failure(failure)),
        )
    }

    /**
     * The server spoke: auth worked and the repository itself was refused.
     * Pinning would be premature — the URL is wrong, and the fix is to correct
     * it, not to trust a server the user may have mistyped.
     */
    @Test
    fun `a server-refused repository is a failure, not an offer`() {
        val failure = ProbeRejectedException(
            fingerprint = "SHA256:abc",
            serverMessage = "repository not found",
            cause = Exception("stream ended"),
        )
        val outcome = VerifyRules.fromProbe(Result.failure(failure))
        assertEquals(
            SyncErrorCode.REMOTE_ERROR,
            (outcome as VerifyOutcome.Failed).error.code,
        )
    }

    @Test
    fun `an unreachable host is a plain failure`() {
        val outcome = VerifyRules.fromProbe(Result.failure(UnknownHostException("no.such.host")))
        assertEquals(
            SyncErrorCode.HOST_UNREACHABLE,
            (outcome as VerifyOutcome.Failed).error.code,
        )
    }
}
```

- [ ] **Step 2: Run, watch it fail** — `--tests "de.nereide.strohhalm.ui.detail.VerifyRulesTest"`. Expected: compile error, `VerifyRules` unresolved.

- [ ] **Step 3: Implement**

```kotlin
package de.nereide.strohhalm.ui.detail

import de.nereide.strohhalm.domain.ProbeRejectedException
import de.nereide.strohhalm.domain.SyncError
import de.nereide.strohhalm.domain.SyncErrorCode
import de.nereide.strohhalm.domain.SyncErrors

/** What a finished probe means for the verification flow. */
sealed interface VerifyOutcome {
    /** A fingerprint to show the user, [authFailed] when the server refused auth. */
    data class Pending(val fingerprint: String, val authFailed: Boolean) : VerifyOutcome

    data class Failed(val error: SyncError) : VerifyOutcome
}

/**
 * Maps a probe result to its verification outcome.
 *
 * The one non-obvious rule: an auth failure that carries a fingerprint is an
 * *offer to pin* — the missing-public-key case this whole flow exists for —
 * while any other failure, fingerprint or not, is just a failure. A server
 * that refused the *repository* spoke after auth succeeded; pinning then
 * would trust a server the user may simply have mistyped.
 */
object VerifyRules {

    fun fromProbe(result: Result<String>): VerifyOutcome = result.fold(
        onSuccess = { VerifyOutcome.Pending(it, authFailed = false) },
        onFailure = { failure ->
            val error = SyncErrors.fromException(failure)
            val probe = failure as? ProbeRejectedException
            if (probe != null && error.code == SyncErrorCode.AUTH_FAILED) {
                VerifyOutcome.Pending(probe.fingerprint, authFailed = true)
            } else {
                VerifyOutcome.Failed(error)
            }
        },
    )
}
```

- [ ] **Step 4: Run, watch it pass** — same command. Expected: 4 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(ui): decide what a probe result means for verification"
```

---

### Task 5: The add screen saves immediately

**Files:**
- Modify: `app/src/main/java/de/nereide/strohhalm/ui/add/AddRepoViewModel.kt` (rewrite)
- Modify: `app/src/main/java/de/nereide/strohhalm/ui/add/AddRepoScreen.kt` (drop probe UI and dialog; keep `DiagnosticCard` — the detail screen imports it)
- Modify: `app/src/main/java/de/nereide/strohhalm/ui/nav/AppNavHost.kt` (route to the new detail)
- Modify: `app/src/main/res/values/strings.xml`
- Test: compile + full suite (the ViewModel is a thin shell over `GitRemote.parse` and `repository.add`, both already under test; this codebase deliberately keeps ViewModels untested and extracts logic — there is none left here)

**Interfaces:**
- Consumes: `RepoRepository.add(name, url, hostKeyFingerprint = null): Long` (Task 3), `GitRemote.parse` (throws `IllegalArgumentException` on junk).
- Produces: `AddRepoScreen(onDone: () -> Unit, onAdded: (Long) -> Unit)` — `onAdded` fires with the new row id after saving.

- [ ] **Step 1: Rewrite the ViewModel**

Replace the body of `AddRepoViewModel.kt` with:

```kotlin
package de.nereide.strohhalm.ui.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.nereide.strohhalm.domain.RepoRepository
import de.nereide.strohhalm.domain.git.GitRemote
import de.nereide.strohhalm.ui.common.appContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AddRepoUiState(
    val url: String = "",
    val name: String = "",
    val invalidUrl: Boolean = false,
    /** Set once the row exists; navigation to its detail view follows. */
    val savedId: Long? = null,
)

/**
 * Adding saves immediately and touches no network. Only the URL's *shape* is
 * checked here; the server itself is verified afterwards, in the background,
 * from the detail view — so a repository can be added before its server is
 * reachable or its key installed, and the user is never parked behind a
 * "Contacting the server…" spinner.
 */
class AddRepoViewModel(
    private val repository: RepoRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddRepoUiState())
    val uiState: StateFlow<AddRepoUiState> = _uiState.asStateFlow()

    fun setUrl(value: String) {
        _uiState.value = _uiState.value.copy(url = value, invalidUrl = false)
    }

    fun setName(value: String) {
        _uiState.value = _uiState.value.copy(name = value)
    }

    fun add() {
        val state = _uiState.value
        val url = state.url.trim()
        if (url.isEmpty()) return
        if (runCatching { GitRemote.parse(url) }.isFailure) {
            _uiState.value = state.copy(invalidUrl = true)
            return
        }
        viewModelScope.launch {
            val id = repository.add(
                displayName = state.name.trim(),
                remoteUrl = url,
                hostKeyFingerprint = null,
            )
            _uiState.value = _uiState.value.copy(savedId = id)
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                AddRepoViewModel(repository = this.appContainer().repoRepository)
            }
        }
    }
}
```

- [ ] **Step 2: Rewrite the screen's action area**

In `AddRepoScreen.kt`:
- Change the signature to `fun AddRepoScreen(onDone: () -> Unit, onAdded: (Long) -> Unit, viewModel: ...)`.
- Replace `LaunchedEffect(uiState.saved) { if (uiState.saved) onDone() }` with
  `LaunchedEffect(uiState.savedId) { uiState.savedId?.let(onAdded) }`.
- Replace everything between the name field's `Spacer` and the closing of the
  `Column` (the probing spinner, probe button, and `syncErrorText` diagnostic
  block) with:

```kotlin
            Button(
                onClick = viewModel::add,
                enabled = uiState.url.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.add_save))
            }

            if (uiState.invalidUrl) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.add_url_invalid),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
```

- Delete the trailing `uiState.fingerprint?.let { ... AlertDialog ... }` block.
- Remove now-unused imports (`CircularProgressIndicator`, `AlertDialog`,
  `TextButton`, `ClipData`, `ClipboardManager`, `syncErrorText`, `LocalContext`
  if unused) — but **keep the `DiagnosticCard` composable definition** in this
  file; the detail screen imports it.

- [ ] **Step 3: Update navigation**

In `AppNavHost.kt`:

```kotlin
        composable(Routes.ADD) {
            AddRepoScreen(
                onDone = { navController.popBackStack() },
                onAdded = { id ->
                    // The new repo's detail view takes over verification; Back
                    // from there should land on the list, not the add form.
                    navController.navigate(Routes.detail(id)) {
                        popUpTo(Routes.LIST)
                    }
                },
            )
        }
```

- [ ] **Step 4: Update strings**

In `strings.xml`, in the "Add repository" block: remove `add_probe` and
`add_checking`; keep `add_fingerprint_title`, `add_fingerprint_body` and
`add_fingerprint_accept` (Task 6 reuses them for the first-trust dialog); add:

```xml
    <string name="add_save">Add repository</string>
    <string name="add_url_invalid">This is not an SSH remote. Use ssh://user@host/path or user@host:path.</string>
```

- [ ] **Step 5: Build and run the full suite**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL — this proves the Compose code compiles and nothing
else regressed. There is no JVM test for the screen itself.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat(ui): save a new repository immediately, without touching the network

The add screen checks only the URL's shape and creates the row with no
pinned key, then hands off to the detail view. Nothing blocks: the server
is verified in the background from there, so a repository can be added
before its server is set up — or reachable at all."
```

---

### Task 6: The detail view verifies in the background

**Files:**
- Modify: `app/src/main/java/de/nereide/strohhalm/ui/detail/RepoDetailViewModel.kt`
- Modify: `app/src/main/java/de/nereide/strohhalm/ui/detail/RepoDetailScreen.kt`
- Modify: `app/src/main/java/de/nereide/strohhalm/ui/list/RepoListScreen.kt` (unverified status line)
- Modify: `app/src/main/res/values/strings.xml`
- Test: compile + full suite (all decision logic already lives in `VerifyRules`, tested in Task 4; the ViewModel only wires flows)

**Interfaces:**
- Consumes: `VerifyRules.fromProbe` (Task 4), `SyncRunner.launchSyncOne` skipping semantics (Task 3), `CalmIndeterminateBar` (exists in `ui/common`).
- Produces on the ViewModel: `data class PendingHostKey(val fingerprint: String, val authFailed: Boolean, val firstTrust: Boolean)`; `pendingHostKey: StateFlow<PendingHostKey?>`; `verifying: StateFlow<Boolean>`; `probeError: StateFlow<SyncError?>`; `fun verify()`; `fun confirmHostKey()`; `fun dismissHostKey()`. Task 7 reads `probeError` for the auth card.

- [ ] **Step 1: Rework the ViewModel's host-key section**

In `RepoDetailViewModel.kt`, replace the `_pendingHostKey` declaration and the
`recheckHostKey`/`confirmNewHostKey`/`dismissNewHostKey` functions with:

```kotlin
    /** A probed fingerprint awaiting the user's confirmation. */
    data class PendingHostKey(
        val fingerprint: String,
        /** The server refused auth — likely the public key is not installed yet. */
        val authFailed: Boolean,
        /** No key was pinned before: accepting also starts the first sync. */
        val firstTrust: Boolean,
    )

    private val _pendingHostKey = MutableStateFlow<PendingHostKey?>(null)
    val pendingHostKey: StateFlow<PendingHostKey?> = _pendingHostKey.asStateFlow()

    private val _verifying = MutableStateFlow(false)
    val verifying: StateFlow<Boolean> = _verifying.asStateFlow()

    private val _probeError = MutableStateFlow<SyncError?>(null)
    val probeError: StateFlow<SyncError?> = _probeError.asStateFlow()

    /**
     * Asks the server for its host key in the background. Serves both the
     * first verification of a just-added repository and the deliberate
     * re-check after a mismatch; either way the user sees the fingerprint
     * before anything is pinned.
     */
    fun verify() {
        if (_verifying.value) return
        _verifying.value = true
        _probeError.value = null
        viewModelScope.launch {
            try {
                val current = repository.observe(id).first() ?: return@launch
                val firstTrust = current.hostKeyFingerprint == null
                when (val outcome = VerifyRules.fromProbe(mirror.probeHostKey(current.remoteUrl))) {
                    is VerifyOutcome.Pending -> _pendingHostKey.value = PendingHostKey(
                        fingerprint = outcome.fingerprint,
                        authFailed = outcome.authFailed,
                        firstTrust = firstTrust,
                    )
                    is VerifyOutcome.Failed -> _probeError.value = outcome.error
                }
            } finally {
                _verifying.value = false
            }
        }
    }

    fun confirmHostKey() {
        val pending = _pendingHostKey.value ?: return
        viewModelScope.launch {
            repository.updateHostKey(id, pending.fingerprint)
            _pendingHostKey.value = null
            // The whole point of confirming was to mirror; do not make the
            // user find the sync button next. Refused harmlessly if the key
            // is not on the server yet — the row then shows the auth failure
            // and the key-setup card.
            if (pending.firstTrust) syncRunner.launchSyncOne(id)
        }
    }

    fun dismissHostKey() {
        _pendingHostKey.value = null
    }
```

Then extend the existing `init` block — add as its first statement:

```kotlin
        // A just-added repository has no pinned key; verification is this
        // screen's opening move, not something the user must discover.
        viewModelScope.launch {
            val current = repository.observe(id).first()
            if (current != null && current.hostKeyFingerprint == null) verify()
        }
```

- [ ] **Step 2: Rework the screen**

In `RepoDetailScreen.kt`:

1. Collect the new state next to the existing collections:

```kotlin
    val verifying by viewModel.verifying.collectAsStateWithLifecycle()
    val probeError by viewModel.probeError.collectAsStateWithLifecycle()
```

2. Compute the unverified flag at the top, right after the state collections —
   not further down after `val current = repo ?: return@Scaffold`, because the
   top-bar `actions` block renders before that point and needs it:

```kotlin
    val unverified = repo != null && repo?.hostKeyFingerprint == null
```

3. Disable Sync and Share while unverified (top-bar `actions` block): the sync
   `IconButton` gets `enabled = shareState !is ShareState.Packing && !unverified`,
   the share `IconButton` gets `enabled = !unverified`.

4. Insert the verification card directly after `SyncProgressBar(...)` inside
   the `Column`:

```kotlin
            if (unverified) {
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        if (verifying) {
                            Text(
                                stringResource(R.string.detail_verifying),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(8.dp))
                            CalmIndeterminateBar(modifier = Modifier.fillMaxWidth())
                        } else {
                            Text(
                                stringResource(R.string.detail_unverified_body),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = viewModel::verify) {
                                Text(stringResource(R.string.detail_verify_now))
                            }
                        }
                    }
                }
            }

            probeError?.let { error ->
                syncErrorText(error.code.name)?.let { message ->
                    DiagnosticCard(
                        message = message,
                        detail = error.detail,
                        diagnostic = error.diagnostic,
                        onCopy = {
                            val text = buildString {
                                appendLine("Strohhalm verification failure")
                                appendLine("remote=${current.remoteUrl}")
                                appendLine("code=${error.code.name}")
                                appendLine("detail=${error.detail}")
                                appendLine("chain=${error.diagnostic}")
                            }
                            context.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(ClipData.newPlainText("Strohhalm diagnostics", text))
                        }
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }
```

Add the needed import: `de.nereide.strohhalm.ui.common.CalmIndeterminateBar`.

5. Update the mismatch re-check button (the
   `if (current.lastErrorCode == "HOST_KEY_MISMATCH")` block) to call
   `viewModel.verify()` instead of `viewModel.recheckHostKey()`.

6. Replace the `pendingHostKey?.let { fingerprint -> AlertDialog(...) }` block:

```kotlin
    pendingHostKey?.let { pending ->
        val body = buildString {
            append(
                if (pending.firstTrust) {
                    stringResource(R.string.add_fingerprint_body, pending.fingerprint)
                } else {
                    stringResource(R.string.detail_new_host_key_body, pending.fingerprint)
                }
            )
            if (pending.authFailed) {
                append("\n\n")
                append(stringResource(R.string.detail_verify_auth_note))
            }
        }
        AlertDialog(
            onDismissRequest = viewModel::dismissHostKey,
            title = {
                Text(
                    stringResource(
                        if (pending.firstTrust) R.string.add_fingerprint_title
                        else R.string.detail_new_host_key_title
                    )
                )
            },
            text = { Text(body) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmHostKey) {
                    Text(
                        stringResource(
                            if (pending.firstTrust) R.string.add_fingerprint_accept
                            else R.string.detail_new_host_key_accept
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissHostKey) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
```

- [ ] **Step 3: Mark unverified rows in the list**

In `RepoListScreen.kt`, `statusLine` starts with a new guard before the
`when (repo.lastStatus)`:

```kotlin
private fun statusLine(repo: Repo): String {
    if (repo.hostKeyFingerprint == null) {
        return stringResource(R.string.status_unverified)
    }
    return when (repo.lastStatus) {
        // ... existing branches unchanged ...
    }
}
```

(Adjust to the file's actual shape — `statusLine` is currently an expression
body `= when (...)`; convert to a block body as shown. It is `@Composable`
via `stringResource`; keep whatever annotation it already carries.)

- [ ] **Step 4: Add strings**

```xml
    <string name="detail_verifying">Verifying the server\'s identity…</string>
    <string name="detail_unverified_body">This server has not been verified yet. Strohhalm will not sync this repository until you have seen and accepted the server\'s key.</string>
    <string name="detail_verify_now">Verify the server</string>
    <string name="detail_verify_auth_note">The server refused authentication — your public key is probably not installed there yet. You can trust the server\'s identity now; syncing will work once the key is in place.</string>
    <string name="status_unverified">Not verified yet — open to verify the server</string>
```

- [ ] **Step 5: Build and run the full suite**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, no failures.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat(ui): verify a new repository's server from the detail view, in the background

The screen opens verification by itself for a repository with no pinned
key, shows the fingerprint when the probe answers — including when the
server refused auth, since the key is observed before authentication —
and starts the first sync the moment the user accepts. Sync and Share
stay disabled until then, and the list names the state."
```

---

### Task 7: The key-setup card — copy the key, open the forge's settings page

**Files:**
- Create: `app/src/main/java/de/nereide/strohhalm/ui/detail/KeySetupCard.kt`
- Modify: `app/src/main/java/de/nereide/strohhalm/ui/detail/RepoDetailViewModel.kt` (expose the public key; new `keys` dependency)
- Modify: `app/src/main/java/de/nereide/strohhalm/ui/detail/RepoDetailScreen.kt` (render the card in both auth-failure places)
- Modify: `app/src/main/res/values/strings.xml`
- Test: compile + full suite (`KeySetupLinks` is tested; the card is presentation)

**Interfaces:**
- Consumes: `KeySetupLinks.forHost` (Task 2), `SshKeyStore.publicKeyLine(): String` (existing, on `AppContainer.sshKeyStore`), `GitRemote.parse(url).host`, `probeError` (Task 6), `Repo.lastErrorCode`.
- Produces: `KeySetupCard(host: String, publicKey: String?, onCopyKey: () -> Unit)` composable.

- [ ] **Step 1: ViewModel — expose the public key**

In `RepoDetailViewModel.kt`:
- Add a constructor parameter `private val keys: de.nereide.strohhalm.domain.SshKeyStore,`
  (import `SshKeyStore`) and pass `keys = this.appContainer().sshKeyStore` in the
  `factory` initializer.
- Add next to the other state flows:

```kotlin
    /** The public key line, for the auth-failure card's copy action. */
    private val _publicKey = MutableStateFlow<String?>(null)
    val publicKey: StateFlow<String?> = _publicKey.asStateFlow()
```

- Add as the last statement of `init`:

```kotlin
        viewModelScope.launch {
            _publicKey.value = runCatching { keys.publicKeyLine() }.getOrNull()
        }
```

- [ ] **Step 2: The card**

Create `KeySetupCard.kt`:

```kotlin
package de.nereide.strohhalm.ui.detail

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import de.nereide.strohhalm.R
import de.nereide.strohhalm.domain.KeySetupLinks

/**
 * Shown while authentication is failing: the fix is always "put the public
 * key on the server", so the card carries both halves of it — the key, and
 * for a known forge, the exact page it belongs on. State-driven, not a
 * one-shot hint: it stays as long as the failure does.
 */
@Composable
fun KeySetupCard(
    host: String,
    publicKey: String?,
    onCopyKey: () -> Unit,
) {
    val context = LocalContext.current
    val link = KeySetupLinks.forHost(host)
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.keysetup_body, host),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Row {
                if (publicKey != null) {
                    OutlinedButton(onClick = onCopyKey) {
                        Text(stringResource(R.string.keysetup_copy_key))
                    }
                }
                if (link != null) {
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, link.toUri()))
                    }) {
                        Text(stringResource(R.string.keysetup_open, host))
                    }
                }
            }
        }
    }
}
```

If `androidx.core.net.toUri` does not resolve, use
`android.net.Uri.parse(link)` instead — do not add a dependency for this.

- [ ] **Step 3: Render it in both auth-failure places**

In `RepoDetailScreen.kt`:

1. Collect the key next to the other state: `val publicKey by viewModel.publicKey.collectAsStateWithLifecycle()`.
2. Add one local helper above the `Column`'s content (inside the composable,
   after `val current = repo ?: return@Scaffold`):

```kotlin
            val remoteHost = remember(current.remoteUrl) {
                runCatching {
                    de.nereide.strohhalm.domain.git.GitRemote.parse(current.remoteUrl).host
                }.getOrNull()
            }
            val copyPublicKey = {
                publicKey?.let { line ->
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("Strohhalm public key", line))
                }
                Unit
            }
```

3. Directly after the `probeError?.let { ... }` block from Task 6, add:

```kotlin
            if (probeError?.code == SyncErrorCode.AUTH_FAILED && remoteHost != null) {
                KeySetupCard(host = remoteHost, publicKey = publicKey, onCopyKey = copyPublicKey)
            }
```

(import `de.nereide.strohhalm.domain.SyncErrorCode`)

4. Inside the existing persisted-failure block
   (`syncErrorText(current.lastErrorCode)?.let { ... }`), after the
   `HOST_KEY_MISMATCH` button and before the `Spacer`, add:

```kotlin
                if (current.lastErrorCode == SyncErrorCode.AUTH_FAILED.name && remoteHost != null) {
                    KeySetupCard(host = remoteHost, publicKey = publicKey, onCopyKey = copyPublicKey)
                }
```

This is what makes the card *persistent*: `lastErrorCode` lives on the row and
survives navigation and process death, and only a successful sync clears it.

- [ ] **Step 4: Add strings**

```xml
    <string name="keysetup_body">%1$s refused the key this device signs with. Add Strohhalm\'s public key to your account on the server, then sync again.</string>
    <string name="keysetup_copy_key">Copy public key</string>
    <string name="keysetup_open">Open %1$s</string>
```

- [ ] **Step 5: Build and run the full suite**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, no failures.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat(ui): offer the fix when authentication fails

An auth failure always means the same thing — the public key is not on
the server — so the failure card now carries the fix: a copy button for
the key and, for GitHub and Codeberg, a link to the exact settings page
it belongs on. Driven by the persisted error state, so it stays for as
long as the failure does."
```

---

### Task 8: Copyable, selectable remote — and the final full check

**Files:**
- Modify: `app/src/main/java/de/nereide/strohhalm/ui/detail/RepoDetailScreen.kt` (the `Field` composable and the Remote call site)
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: nothing new. Produces: `Field(label: String, value: String, onCopy: (() -> Unit)? = null)`.

- [ ] **Step 1: Make field values selectable and the remote copyable**

Replace the `Field` composable at the bottom of `RepoDetailScreen.kt`:

```kotlin
/**
 * [SelectionContainer] because these are exactly the values a user needs to
 * get *out* of the app — an URL, a path, a fingerprint — and Compose text is
 * otherwise a picture of a value, not a value. The remote additionally gets
 * a one-tap copy: long-press selection on a phone is a chore.
 */
@Composable
private fun Field(label: String, value: String, onCopy: (() -> Unit)? = null) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            if (onCopy != null) {
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onCopy) {
                    Text(stringResource(R.string.detail_copy))
                }
            }
        }
        SelectionContainer {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
```

Imports to add: `androidx.compose.foundation.text.selection.SelectionContainer`,
`androidx.compose.foundation.layout.width`.

Change the Remote call site:

```kotlin
            Field(
                stringResource(R.string.detail_remote),
                current.remoteUrl,
                onCopy = {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(
                            ClipData.newPlainText("Strohhalm remote", current.remoteUrl)
                        )
                },
            )
```

Add the string:

```xml
    <string name="detail_copy">Copy</string>
```

- [ ] **Step 2: Full verification — suite, counts, build**

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk
./gradlew :app:testDebugUnitTest --rerun assembleDebug
grep -oh 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  app/build/test-results/testDebugUnitTest/TEST-*.xml \
  | awk -F'"' '{t+=$2; s+=$4; f+=$6; e+=$8} END {print t" tests, "s" skipped, "f" failures, "e" errors"}'
```

Expected: BUILD SUCCESSFUL; failures 0, errors 0; total noticeably above the
pre-plan 240 (new: ProbeAuthFailureTest 1, SyncErrorsTest +2, KeySetupLinksTest 3,
SyncRunnerSkipTest 2, DefaultRepoRepositoryTest +1, VerifyRulesTest 4 → ≥ 253).
The 2 skips are the pre-existing `LiveCodebergCloneTest`.

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "feat(ui): make the remote copyable and every detail value selectable

An URL, a path and a fingerprint are exactly the values a user needs to
get out of the app, and Compose text is otherwise a picture of a value."
```

---

## Self-Review Notes

- **Spec coverage:** add-saves-immediately (Task 5), background verification with all three probe outcomes (Tasks 1, 4, 6), pin-despite-auth-failure (Tasks 1, 4, 6), sync skipping + list state + disabled actions (Tasks 3, 6), key-setup link persistent on `AUTH_FAILED` (Tasks 2, 7), copy public key (Task 7), copyable/selectable remote (Task 8), URL-shape rejection (Task 5). Delete-unverified needs nothing: the delete flow already tolerates a missing mirror directory.
- **Deliberately not planned:** ViewModel-level JVM tests — this codebase's convention is extracting decision logic into pure, tested objects (`ShareRules`, now `VerifyRules`) and keeping ViewModels as untested wiring. Follow it.
- **Device-only checks, to hand to the user at the end:** the browser intent on a real phone, clipboard behaviour, and the full add→verify→install-key→sync loop against a real forge.
