# SHA-256 Mirror Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace JGit with a hash-agnostic mirror engine so Strohhalm can back up SHA-256 repositories.

**Architecture:** A Kotlin implementation of git's protocol v2 fetch, running over the Apache MINA SSHD transport the app already uses. The engine speaks pkt-line to `git-upload-pack`, streams the returned packfile through a pack indexer that writes `.pack` and `.idx`, and maintains a bare repository layout. Every component takes the negotiated hash as a parameter; there is no default and no compile-time length constant.

**Tech Stack:** Kotlin, Apache MINA SSHD 2.14.0 (`sshd-osgi`), `java.util.zip.Inflater`, `java.security.MessageDigest`, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-07-26-sha256-mirror-engine-design.md`

## Global Constraints

Every task's requirements implicitly include these. Violating one is a defect even if tests pass.

- Package `de.nereide.strohhalm`. `minSdk 26`, `compileSdk`/`targetSdk 35`, Java 17.
- **Every Gradle invocation must set `JAVA_HOME`:** `export JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk`. A bare `./gradlew` dies parsing the Java 25 version string before any task runs.
- **`UP-TO-DATE` is not evidence.** Use `--rerun` and read the per-class counts.
- **`isMinifyEnabled = false`** stays. MINA SSHD resolves implementations through `ServiceLoader` and reflection.
- **Never push, commit, or write to a remote.** Only `git-upload-pack` is ever invoked.
- **The new `domain/git/` package is the only place in `src/main` that may import MINA SSHD.** Library exceptions are mapped to `SyncError` before leaving it. Tests may import JGit to build fixtures.
- **The private key never leaves internal storage** and is never written to the mirror folder.
- **No hardcoded user-facing strings** in Compose; everything through `res/values/strings.xml`.
- **`versionName` lives only in `version.properties`.** Never hand-edit `versionCode`.
- **Dependencies go in `gradle/libs.versions.toml`.** Never inline a coordinate in `app/build.gradle.kts`.
- **Kotlin block comments nest.** Write refspecs in prose (`+refs/…:refs/…`) inside comments; the literal glob is only safe inside string literals.
- **Live tests are gitignored** (`app/src/test/java/**/Live*Test.kt`). Never commit one, and never commit a real remote URL.
- **TDD strictly:** write the failing test, run it and see it fail, implement, run it and see it pass, commit.

## File Structure

All new production code lives in a new package, `de.nereide.strohhalm.domain.git`:

| File | Responsibility |
| --- | --- |
| `ObjectHash.kt` | Hash kind: raw/hex length, `MessageDigest`, object-id computation |
| `PktLine.kt` | pkt-line framing, sideband demultiplexing |
| `UploadPackChannel.kt` | One SSH session + exec channel; host key pinning; stdin/stdout/stderr |
| `UploadPackV2.kt` | Capability advertisement, `ls-refs`, `fetch` |
| `PackIndexer.kt` | `PackIndexer` interface + `PackResult` — the seam a Rust indexer would replace |
| `PackIndexWriter.kt` | `.idx` version 2 writer |
| `PackDelta.kt` | Delta instruction decoding |
| `KotlinPackIndexer.kt` | Pack parse, delta resolution, `.pack` + `.idx` output |
| `MirrorRepository.kt` | Bare layout, config, refs, pruning, ref reading |
| `ProtocolMirror.kt` | The `GitMirror` implementation |

Tests mirror this under `app/src/test/java/de/nereide/strohhalm/domain/git/`.

Modified: `gradle/libs.versions.toml`, `app/build.gradle.kts`, `AppContainer.kt`, `SyncError.kt`.
Deleted at the end: `JGitMirror.kt`, `AndroidSystemReader.kt`, and their tests.

---

### Task 1: `ObjectHash`

The hash kind, passed to everything else. This exists so that no other file ever writes `20` or `40`.

**Files:**
- Create: `app/src/main/java/de/nereide/strohhalm/domain/git/ObjectHash.kt`
- Test: `app/src/test/java/de/nereide/strohhalm/domain/git/ObjectHashTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `enum class ObjectHash { SHA1, SHA256 }` with `configName: String`, `rawLength: Int`, `hexLength: Int`, `newDigest(): MessageDigest`, `objectId(type: String, content: ByteArray): ByteArray`, `toHex(raw: ByteArray): String`, `fromHex(hex: String): ByteArray`, and `companion object { fun fromConfigName(name: String): ObjectHash }`.

- [ ] **Step 1: Write the failing test**

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk
./gradlew :app:testDebugUnitTest --rerun --tests "de.nereide.strohhalm.domain.git.ObjectHashTest"
```

Expected: FAIL — `Unresolved reference: ObjectHash` at compile time.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package de.nereide.strohhalm.domain.git

import java.security.MessageDigest

/**
 * The object hash a repository uses.
 *
 * Every component that touches an object id takes one of these. Nothing else in
 * the engine may name a length: JGit's inability to read SHA-256 comes down to
 * `OBJECT_ID_LENGTH` being a compile-time constant in 153 places, and this type
 * exists so that mistake cannot be repeated here.
 */
enum class ObjectHash(
    /** The value git writes as `extensions.objectFormat`. */
    val configName: String,
    val rawLength: Int,
    private val digestName: String,
) {
    SHA1("sha1", 20, "SHA-1"),
    SHA256("sha256", 32, "SHA-256");

    val hexLength: Int get() = rawLength * 2

    fun newDigest(): MessageDigest = MessageDigest.getInstance(digestName)

    /**
     * The id of a loose object: the hash of `"<type> <length>\0"` followed by
     * the body. Identical in both formats apart from the digest.
     */
    fun objectId(type: String, content: ByteArray): ByteArray = newDigest().run {
        update("$type ${content.size}".toByteArray(Charsets.US_ASCII))
        update(0)
        digest(content)
    }

    fun toHex(raw: ByteArray): String = buildString(raw.size * 2) {
        raw.forEach { append(HEX[(it.toInt() shr 4) and 0xf]).append(HEX[it.toInt() and 0xf]) }
    }

    fun fromHex(hex: String): ByteArray {
        require(hex.length == hexLength) { "expected $hexLength hex chars, got ${hex.length}" }
        return ByteArray(rawLength) { i ->
            ((digit(hex[i * 2]) shl 4) or digit(hex[i * 2 + 1])).toByte()
        }
    }

    private fun digit(c: Char): Int {
        val v = Character.digit(c, 16)
        require(v >= 0) { "not a hex digit: $c" }
        return v
    }

    companion object {
        private const val HEX = "0123456789abcdef"

        fun fromConfigName(name: String): ObjectHash =
            entries.firstOrNull { it.configName == name }
                ?: throw IllegalArgumentException("unsupported object format: $name")
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --rerun --tests "de.nereide.strohhalm.domain.git.ObjectHashTest"
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  app/build/test-results/testDebugUnitTest/TEST-de.nereide.strohhalm.domain.git.ObjectHashTest.xml
```

Expected: PASS, `tests="5" skipped="0" failures="0" errors="0"`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/de/nereide/strohhalm/domain/git/ObjectHash.kt \
        app/src/test/java/de/nereide/strohhalm/domain/git/ObjectHashTest.kt
git commit -m "feat(git): add ObjectHash, the engine's only source of hash length"
```

---

### Task 2: `PktLine`

Git's framing. A 4-hex length prefix that **includes itself**, plus three special packets.

**Files:**
- Create: `app/src/main/java/de/nereide/strohhalm/domain/git/PktLine.kt`
- Test: `app/src/test/java/de/nereide/strohhalm/domain/git/PktLineTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `sealed interface Pkt` with `data class Data(val bytes: ByteArray) : Pkt`, `object Flush : Pkt`, `object Delim : Pkt`, `object ResponseEnd : Pkt`
  - `object PktLine { fun read(input: InputStream): Pkt; fun writeString(out: OutputStream, text: String); fun writeFlush(out: OutputStream); fun writeDelim(out: OutputStream) }`
  - `Pkt.Data.text(): String`

- [ ] **Step 1: Write the failing test**

```kotlin
package de.nereide.strohhalm.domain.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException

class PktLineTest {

    private fun read(wire: String): Pkt =
        PktLine.read(ByteArrayInputStream(wire.toByteArray(Charsets.UTF_8)))

    @Test
    fun `the length prefix counts itself`() {
        // "0006" = 6 bytes total, so 2 bytes of payload.
        assertEquals("hi", (read("0006hi") as Pkt.Data).text())
    }

    @Test
    fun `special packets are distinguished`() {
        assertTrue(read("0000") is Pkt.Flush)
        assertTrue(read("0001") is Pkt.Delim)
        assertTrue(read("0002") is Pkt.ResponseEnd)
    }

    @Test
    fun `an empty payload is data, not a flush`() {
        assertEquals("", (read("0004") as Pkt.Data).text())
    }

    @Test
    fun `a truncated packet is an error, not a silent short read`() {
        assertThrows(EOFException::class.java) { read("0010short") }
    }

    @Test
    fun `writing prefixes the length including the prefix`() {
        val out = ByteArrayOutputStream()
        PktLine.writeString(out, "want abc\n")
        assertEquals("000dwant abc\n", out.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun `flush and delim are written verbatim`() {
        val out = ByteArrayOutputStream()
        PktLine.writeFlush(out)
        PktLine.writeDelim(out)
        assertEquals("00000001", out.toString(Charsets.UTF_8.name()))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk
./gradlew :app:testDebugUnitTest --rerun --tests "de.nereide.strohhalm.domain.git.PktLineTest"
```

Expected: FAIL — `Unresolved reference: PktLine`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package de.nereide.strohhalm.domain.git

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** One packet from a pkt-line stream. */
sealed interface Pkt {
    /** A payload packet. Empty payloads are legal and are *not* a flush. */
    class Data(val bytes: ByteArray) : Pkt {
        fun text(): String = String(bytes, Charsets.UTF_8)
    }

    /** `0000` — end of a section or of the whole response. */
    data object Flush : Pkt

    /** `0001` — separates command capabilities from arguments in protocol v2. */
    data object Delim : Pkt

    /** `0002` — end of response. */
    data object ResponseEnd : Pkt
}

/**
 * Git's wire framing: four hex digits giving the total packet length, *including
 * the four digits themselves*, followed by that many bytes minus four.
 *
 * The off-by-four is the classic mistake here; `0004` is a legal empty packet
 * and `0000` is a flush, so the two cannot be conflated.
 */
object PktLine {

    /** Git's maximum, including the length prefix. */
    const val MAX_PACKET = 65520

    fun read(input: InputStream): Pkt {
        val length = Integer.parseInt(String(readFully(input, 4), Charsets.US_ASCII), 16)
        return when (length) {
            0 -> Pkt.Flush
            1 -> Pkt.Delim
            2 -> Pkt.ResponseEnd
            3 -> throw IOException("invalid pkt-line length 3")
            else -> {
                if (length > MAX_PACKET) throw IOException("pkt-line too long: $length")
                Pkt.Data(readFully(input, length - 4))
            }
        }
    }

    fun writeString(out: OutputStream, text: String) =
        writeBytes(out, text.toByteArray(Charsets.UTF_8))

    fun writeBytes(out: OutputStream, payload: ByteArray) {
        val length = payload.size + 4
        require(length <= MAX_PACKET) { "pkt-line too long: $length" }
        out.write(String.format("%04x", length).toByteArray(Charsets.US_ASCII))
        out.write(payload)
    }

    fun writeFlush(out: OutputStream) = out.write("0000".toByteArray(Charsets.US_ASCII))

    fun writeDelim(out: OutputStream) = out.write("0001".toByteArray(Charsets.US_ASCII))

    private fun readFully(input: InputStream, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = input.read(buffer, read, count - read)
            if (n < 0) throw EOFException("stream ended after $read of $count bytes")
            read += n
        }
        return buffer
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --rerun --tests "de.nereide.strohhalm.domain.git.PktLineTest"
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  app/build/test-results/testDebugUnitTest/TEST-de.nereide.strohhalm.domain.git.PktLineTest.xml
```

Expected: PASS, `tests="6" ... failures="0" errors="0"`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/de/nereide/strohhalm/domain/git/PktLine.kt \
        app/src/test/java/de/nereide/strohhalm/domain/git/PktLineTest.kt
git commit -m "feat(git): add pkt-line framing"
```

---

### Task 3: Sideband demultiplexing

The packfile section is sideband-encoded: the first payload byte is a band number. Band 1 is pack data, band 2 progress text, band 3 a fatal error.

**Files:**
- Modify: `app/src/main/java/de/nereide/strohhalm/domain/git/PktLine.kt`
- Test: `app/src/test/java/de/nereide/strohhalm/domain/git/SidebandTest.kt`

**Interfaces:**
- Consumes: `Pkt`, `PktLine` (Task 2).
- Produces: `class SidebandException(val serverMessage: String) : IOException`, and
  `class SidebandInputStream(input: InputStream, onProgress: (String) -> Unit) : InputStream` — an `InputStream` over band 1 that ends at the section's flush packet.

- [ ] **Step 1: Write the failing test**

```kotlin
package de.nereide.strohhalm.domain.git

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class SidebandTest {

    /** Builds a sideband stream: each entry is band number to payload. */
    private fun wire(vararg parts: Pair<Int, String>): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        parts.forEach { (band, payload) ->
            PktLine.writeBytes(out, byteArrayOf(band.toByte()) + payload.toByteArray())
        }
        PktLine.writeFlush(out)
        return ByteArrayInputStream(out.toByteArray())
    }

    @Test
    fun `band 1 is the pack data and flush ends it`() {
        val stream = SidebandInputStream(wire(1 to "PACK", 1 to "DATA")) {}
        assertArrayEquals("PACKDATA".toByteArray(), stream.readBytes())
    }

    @Test
    fun `band 2 is progress and never reaches the pack data`() {
        val seen = mutableListOf<String>()
        val stream = SidebandInputStream(wire(2 to "Counting objects: 5", 1 to "PACK")) {
            seen += it
        }
        assertArrayEquals("PACK".toByteArray(), stream.readBytes())
        assertEquals(listOf("Counting objects: 5"), seen)
    }

    @Test
    fun `band 3 raises the server's own message`() {
        val stream = SidebandInputStream(wire(3 to "repository not found")) {}
        val thrown = assertThrows(SidebandException::class.java) { stream.readBytes() }
        assertEquals("repository not found", thrown.serverMessage)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk
./gradlew :app:testDebugUnitTest --rerun --tests "de.nereide.strohhalm.domain.git.SidebandTest"
```

Expected: FAIL — `Unresolved reference: SidebandInputStream`.

- [ ] **Step 3: Write minimal implementation**

Append to `PktLine.kt`:

```kotlin
/**
 * The server refused, and said why on band 3. Carries the message verbatim so
 * the UI can show a git host's own words rather than a generic failure.
 */
class SidebandException(val serverMessage: String) :
    IOException("the server said: $serverMessage")

/**
 * Presents band 1 of a sideband-encoded section as a plain [InputStream].
 *
 * Band 2 is progress text and is handed to [onProgress]; band 3 is fatal and
 * becomes a [SidebandException]. The stream ends at the section's flush packet,
 * which is why the pack data can be handed straight to the indexer without the
 * indexer knowing anything about pkt-line.
 */
class SidebandInputStream(
    private val input: InputStream,
    private val onProgress: (String) -> Unit,
) : InputStream() {

    private var buffer: ByteArray = ByteArray(0)
    private var position = 0
    private var finished = false

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(destination: ByteArray, offset: Int, length: Int): Int {
        if (!fill()) return -1
        val n = minOf(length, buffer.size - position)
        System.arraycopy(buffer, position, destination, offset, n)
        position += n
        return n
    }

    /** True when [buffer] holds unread band-1 bytes. */
    private fun fill(): Boolean {
        while (position >= buffer.size) {
            if (finished) return false
            when (val pkt = PktLine.read(input)) {
                is Pkt.Flush, is Pkt.ResponseEnd -> {
                    finished = true
                    return false
                }

                is Pkt.Delim -> Unit // section separator; nothing to emit

                is Pkt.Data -> {
                    if (pkt.bytes.isEmpty()) continue
                    val payload = pkt.bytes.copyOfRange(1, pkt.bytes.size)
                    when (pkt.bytes[0].toInt()) {
                        BAND_DATA -> {
                            buffer = payload
                            position = 0
                        }

                        BAND_PROGRESS ->
                            String(payload, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
                                ?.let(onProgress)

                        BAND_ERROR ->
                            throw SidebandException(String(payload, Charsets.UTF_8).trim())

                        else -> throw IOException("unknown sideband ${pkt.bytes[0].toInt()}")
                    }
                }
            }
        }
        return true
    }

    private companion object {
        const val BAND_DATA = 1
        const val BAND_PROGRESS = 2
        const val BAND_ERROR = 3
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --rerun --tests "de.nereide.strohhalm.domain.git.SidebandTest"
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  app/build/test-results/testDebugUnitTest/TEST-de.nereide.strohhalm.domain.git.SidebandTest.xml
```

Expected: PASS, `tests="3" ... failures="0" errors="0"`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/de/nereide/strohhalm/domain/git/PktLine.kt \
        app/src/test/java/de/nereide/strohhalm/domain/git/SidebandTest.kt
git commit -m "feat(git): demultiplex the sideband, surfacing band 3 as the server's message"
```

---

### Task 4: `UploadPackChannel`

One SSH session, one exec channel running `git-upload-pack '<path>'`, with host key pinning and all three streams exposed.

This replaces JGit's `SshdSessionFactory`. `HostKeyVerifier` (existing, pure domain logic) is reused unchanged.

**Files:**
- Create: `app/src/main/java/de/nereide/strohhalm/domain/git/UploadPackChannel.kt`
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`
- Test: `app/src/test/java/de/nereide/strohhalm/domain/git/UploadPackChannelTest.kt`

**Interfaces:**
- Consumes: `HostKeyVerifier`, `HostKeyDecision`, `SyncError`, `SyncErrorCode` (existing, in `de.nereide.strohhalm.domain`).
- Produces:
  - `data class GitRemote(val user: String, val host: String, val port: Int, val path: String)` with `companion object { fun parse(url: String): GitRemote }`
  - `class UploadPackChannel(remote, keyPair, pinnedFingerprint, capture, timeout) : Closeable` exposing
    `val output: OutputStream` (remote stdin), `val input: InputStream` (remote stdout),
    `fun open()`, `fun stderrText(): String`, `val observedHostKey: String?`, `val rejection: SyncError?`

- [ ] **Step 1: Write the failing test**

The URL parser is the part worth unit-testing without a network; the channel itself is exercised end to end in Task 12.

```kotlin
package de.nereide.strohhalm.domain.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UploadPackChannelTest {

    @Test
    fun `an ssh url splits into user host port and path`() {
        val remote = GitRemote.parse("ssh://git@example.org/owner/repo.git")
        assertEquals("git", remote.user)
        assertEquals("example.org", remote.host)
        assertEquals(22, remote.port)
        assertEquals("/owner/repo.git", remote.path)
    }

    @Test
    fun `an explicit port is honoured`() {
        assertEquals(2222, GitRemote.parse("ssh://git@example.org:2222/o/r.git").port)
    }

    @Test
    fun `scp-style remotes are accepted and their path is relative`() {
        val remote = GitRemote.parse("git@example.org:owner/repo.git")
        assertEquals("git", remote.user)
        assertEquals("example.org", remote.host)
        assertEquals(22, remote.port)
        assertEquals("owner/repo.git", remote.path)
    }

    @Test
    fun `a missing user defaults to git`() {
        assertEquals("git", GitRemote.parse("ssh://example.org/o/r.git").user)
    }

    @Test
    fun `a non-ssh url is refused rather than half-handled`() {
        assertThrows(IllegalArgumentException::class.java) {
            GitRemote.parse("https://example.org/o/r.git")
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk
./gradlew :app:testDebugUnitTest --rerun --tests "de.nereide.strohhalm.domain.git.UploadPackChannelTest"
```

Expected: FAIL — `Unresolved reference: GitRemote`.

- [ ] **Step 3a: Add the SSHD dependency**

MINA SSHD currently arrives transitively through `jgit-ssh-apache`. Declare it directly so removing JGit later does not remove the transport. Use `sshd-osgi`, the same shaded artifact JGit pulls today — it is the exact code path already verified on device.

In `gradle/libs.versions.toml`, add to `[versions]`:

```toml
sshd = "2.14.0"
```

and to `[libraries]`:

```toml
sshd-osgi = { group = "org.apache.sshd", name = "sshd-osgi", version.ref = "sshd" }
```

In `app/build.gradle.kts`, beside the existing `implementation(libs.jgit.ssh.apache)` line:

```kotlin
implementation(libs.sshd.osgi)
```

- [ ] **Step 3b: Write the implementation**

```kotlin
package de.nereide.strohhalm.domain.git

import de.nereide.strohhalm.domain.HostKeyDecision
import de.nereide.strohhalm.domain.HostKeyVerifier
import de.nereide.strohhalm.domain.SyncError
import de.nereide.strohhalm.domain.SyncErrorCode
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.channel.ChannelExec
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.digest.BuiltinDigests
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyPair
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/** An `ssh://` or scp-style remote, split into the parts SSHD needs. */
data class GitRemote(
    val user: String,
    val host: String,
    val port: Int,
    val path: String,
) {
    companion object {
        private const val DEFAULT_PORT = 22
        private const val DEFAULT_USER = "git"

        fun parse(url: String): GitRemote {
            if (url.startsWith("ssh://")) {
                val rest = url.removePrefix("ssh://")
                val slash = rest.indexOf('/')
                require(slash > 0) { "no path in $url" }
                val authority = rest.substring(0, slash)
                val path = rest.substring(slash)
                val user = authority.substringBefore('@', DEFAULT_USER)
                val hostPort = authority.substringAfter('@', authority)
                return GitRemote(
                    user = user,
                    host = hostPort.substringBefore(':'),
                    port = hostPort.substringAfter(':', "").toIntOrNull() ?: DEFAULT_PORT,
                    path = path,
                )
            }
            // scp-style: user@host:path. Rejects everything else rather than
            // guessing — a half-understood URL is how a backup silently targets
            // the wrong place.
            require("://" !in url) { "not an ssh remote: $url" }
            val colon = url.indexOf(':')
            require(colon > 0) { "not an ssh remote: $url" }
            val authority = url.substring(0, colon)
            return GitRemote(
                user = authority.substringBefore('@', DEFAULT_USER),
                host = authority.substringAfter('@', authority),
                port = DEFAULT_PORT,
                path = url.substring(colon + 1),
            )
        }
    }
}

/**
 * One SSH session running `git-upload-pack` on the remote.
 *
 * Unlike JGit's transport this exposes **stderr** alongside stdout. That single
 * difference removes the diagnostic probe the JGit engine needed: a git host
 * explains a refusal on stderr, and JGit discarded it, so the reason had to be
 * harvested over a second connection while a watchdog raced a read that would
 * otherwise never return. Here it is just another stream on the same channel.
 */
class UploadPackChannel(
    private val remote: GitRemote,
    private val keyPair: KeyPair,
    private val pinnedFingerprint: String?,
    private val capture: Boolean,
    private val timeout: Duration,
) : Closeable {

    private var client: SshClient? = null
    private var session: ClientSession? = null
    private var channel: ChannelExec? = null

    private val observed = AtomicReference<String?>(null)
    private val refusal = AtomicReference<SyncError?>(null)

    /** The host key the server presented, once the handshake has run. */
    val observedHostKey: String? get() = observed.get()

    /**
     * Why the host key check refused, when it did.
     *
     * SSHD closes the session on a `false` return and the cause never propagates,
     * so a rejection surfaces to the caller as an unexplained end of stream. The
     * reason is recorded here instead and preferred over whatever the transport
     * ends up reporting — the same mechanism the JGit engine used.
     */
    val rejection: SyncError? get() = refusal.get()

    lateinit var input: InputStream
        private set

    lateinit var output: OutputStream
        private set

    fun open() {
        val ssh = SshClient.setUpDefaultClient().apply {
            serverKeyVerifier = org.apache.sshd.client.keyverifier.ServerKeyVerifier { _, _, key ->
                val algorithm = key?.algorithm ?: "unknown"
                val presented = KeyUtils.getFingerPrint(BuiltinDigests.sha256, key)
                if (presented == null) {
                    refusal.set(
                        SyncError(
                            SyncErrorCode.UNKNOWN,
                            "the server presented no readable host key (algorithm $algorithm)",
                        )
                    )
                    return@ServerKeyVerifier false
                }
                observed.set(presented)
                when (val decision = HostKeyVerifier.verify(pinnedFingerprint, presented)) {
                    is HostKeyDecision.Trusted -> true

                    is HostKeyDecision.FirstUse -> capture.also {
                        if (!it) refusal.set(
                            SyncError(
                                SyncErrorCode.HOST_KEY_MISMATCH,
                                "no host key is pinned for this repository; " +
                                    "the server offered $presented ($algorithm)",
                            )
                        )
                    }

                    is HostKeyDecision.Mismatch -> {
                        refusal.set(
                            SyncError(
                                SyncErrorCode.HOST_KEY_MISMATCH,
                                "expected ${decision.stored}, got ${decision.presented} " +
                                    "(algorithm $algorithm)",
                            )
                        )
                        false
                    }
                }
            }
            start()
        }
        client = ssh

        val opened = ssh.connect(remote.user, remote.host, remote.port)
            .verify(timeout)
            .clientSession
        session = opened
        opened.addPublicKeyIdentity(keyPair)
        opened.auth().verify(timeout)

        // Single-quoted, as git itself does: the remote runs this through a shell.
        val exec = opened.createExecChannel("git-upload-pack '${remote.path}'")
        channel = exec
        // Leaving out/err unset is what makes the inverted streams available.
        exec.open().verify(timeout)

        input = exec.invertedOut
        output = exec.invertedIn
    }

    /** Whatever the server wrote to stderr, without blocking on more. */
    fun stderrText(): String = runCatching {
        val err = channel?.invertedErr ?: return ""
        val available = err.available()
        if (available <= 0) return ""
        String(err.readNBytes(minOf(available, MAX_SERVER_MESSAGE)), Charsets.UTF_8).trim()
    }.getOrDefault("")

    override fun close() {
        runCatching { channel?.close(false) }
        runCatching { session?.close(false) }
        runCatching { client?.stop() }
    }

    private companion object {
        const val MAX_SERVER_MESSAGE = 2_000
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --rerun --tests "de.nereide.strohhalm.domain.git.UploadPackChannelTest"
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  app/build/test-results/testDebugUnitTest/TEST-de.nereide.strohhalm.domain.git.UploadPackChannelTest.xml
```

Expected: PASS, `tests="5" ... failures="0" errors="0"`.

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts \
        app/src/main/java/de/nereide/strohhalm/domain/git/UploadPackChannel.kt \
        app/src/test/java/de/nereide/strohhalm/domain/git/UploadPackChannelTest.kt
git commit -m "feat(git): run git-upload-pack over MINA SSHD with host key pinning"
```

---

### Task 5: Capability advertisement and `object-format`

The first thing `git-upload-pack` sends. This is where the hash for the whole session is decided.

**Files:**
- Create: `app/src/main/java/de/nereide/strohhalm/domain/git/UploadPackV2.kt`
- Test: `app/src/test/java/de/nereide/strohhalm/domain/git/UploadPackV2AdvertisementTest.kt`

**Interfaces:**
- Consumes: `Pkt`, `PktLine`, `ObjectHash`.
- Produces:
  - `data class ServerCapabilities(val raw: Map<String, String>, val objectHash: ObjectHash)` with `fun supports(command: String): Boolean`
  - `class UploadPackV2(private val input: InputStream, private val output: OutputStream)` with `fun readAdvertisement(): ServerCapabilities`

- [ ] **Step 1: Write the failing test**

```kotlin
package de.nereide.strohhalm.domain.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class UploadPackV2AdvertisementTest {

    private fun advertisement(vararg lines: String): UploadPackV2 {
        val out = ByteArrayOutputStream()
        lines.forEach { PktLine.writeString(out, "$it\n") }
        PktLine.writeFlush(out)
        return UploadPackV2(ByteArrayInputStream(out.toByteArray()), ByteArrayOutputStream())
    }

    @Test
    fun `a sha256 server selects the sha256 hash`() {
        val caps = advertisement(
            "version 2",
            "agent=git/2.55.0",
            "ls-refs=unborn",
            "fetch=shallow wait-for-done",
            "object-format=sha256",
        ).readAdvertisement()

        assertEquals(ObjectHash.SHA256, caps.objectHash)
        assertTrue(caps.supports("fetch"))
        assertTrue(caps.supports("ls-refs"))
    }

    @Test
    fun `no object-format means sha1, which is what older servers omit`() {
        val caps = advertisement("version 2", "agent=git/2.30.0", "fetch", "ls-refs")
            .readAdvertisement()
        assertEquals(ObjectHash.SHA1, caps.objectHash)
    }

    @Test
    fun `protocol v0 is refused with a message naming the version`() {
        val thrown = assertThrows(IOException::class.java) {
            advertisement("version 1", "fetch").readAdvertisement()
        }
        assertTrue(thrown.message!!.contains("version 1"))
    }

    @Test
    fun `a server without fetch is refused`() {
        assertThrows(IOException::class.java) {
            advertisement("version 2", "ls-refs").readAdvertisement()
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk
./gradlew :app:testDebugUnitTest --rerun --tests "de.nereide.strohhalm.domain.git.UploadPackV2AdvertisementTest"
```

Expected: FAIL — `Unresolved reference: UploadPackV2`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package de.nereide.strohhalm.domain.git

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * What the server said it can do, and the hash it uses.
 *
 * [objectHash] is the single most important value in the engine: every id parsed
 * or written afterwards takes its length from here, which is what makes the same
 * code work for both formats.
 */
data class ServerCapabilities(
    val raw: Map<String, String>,
    val objectHash: ObjectHash,
) {
    fun supports(command: String): Boolean = raw.containsKey(command)
}

/**
 * Client half of git's protocol v2 over an already-open `git-upload-pack`
 * channel.
 *
 * Only v2 is implemented. Servers older than git 2.18 (2018) are refused with a
 * clear message rather than silently falling back — a backup tool quietly using
 * a weaker protocol is worse than one that says it cannot.
 */
class UploadPackV2(
    private val input: InputStream,
    private val output: OutputStream,
) {

    fun readAdvertisement(): ServerCapabilities {
        val entries = mutableMapOf<String, String>()
        while (true) {
            when (val pkt = PktLine.read(input)) {
                is Pkt.Flush, is Pkt.ResponseEnd -> break
                is Pkt.Delim -> Unit
                is Pkt.Data -> {
                    val line = pkt.text().trim()
                    if (line.isEmpty()) continue
                    entries[line.substringBefore('=')] = line.substringAfter('=', "")
                }
            }
        }

        val version = entries["version"]
        if (version != "2") {
            throw IOException(
                "the server offered protocol version ${version ?: "0"}; " +
                    "Strohhalm requires version 2 (git 2.18 or newer)"
            )
        }
        if (!entries.containsKey("fetch")) {
            throw IOException("the server does not advertise the fetch command")
        }

        val format = entries["object-format"]?.takeIf { it.isNotEmpty() } ?: "sha1"
        return ServerCapabilities(entries, ObjectHash.fromConfigName(format))
    }
}
```

Note: `version 2` arrives as the key `version` with value `2` only if the server writes `version=2`. Real servers write `version 2` with a space. Handle both by normalising the first token:

```kotlin
                is Pkt.Data -> {
                    val line = pkt.text().trim()
                    if (line.isEmpty()) continue
                    // The version line is "version 2" (space); capability lines are
                    // "key=value" or a bare "key".
                    val normalised = if (line.startsWith("version ")) {
                        "version=" + line.removePrefix("version ")
                    } else {
                        line
                    }
                    entries[normalised.substringBefore('=')] =
                        normalised.substringAfter('=', "")
                }
```

Use the normalising version; the test's `"version 2"` line covers it.

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --rerun --tests "de.nereide.strohhalm.domain.git.UploadPackV2AdvertisementTest"
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  app/build/test-results/testDebugUnitTest/TEST-de.nereide.strohhalm.domain.git.UploadPackV2AdvertisementTest.xml
```

Expected: PASS, `tests="4" ... failures="0" errors="0"`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/de/nereide/strohhalm/domain/git/UploadPackV2.kt \
        app/src/test/java/de/nereide/strohhalm/domain/git/UploadPackV2AdvertisementTest.kt
git commit -m "feat(git): read the v2 advertisement and select the negotiated object format"
```

---

### Task 6: `ls-refs`

**Files:**
- Modify: `app/src/main/java/de/nereide/strohhalm/domain/git/UploadPackV2.kt`
- Test: `app/src/test/java/de/nereide/strohhalm/domain/git/LsRefsTest.kt`

**Interfaces:**
- Consumes: `ServerCapabilities`, `ObjectHash`, `PktLine`.
- Produces: `data class RemoteRef(val name: String, val objectId: String, val symrefTarget: String?, val peeled: String?)` and `UploadPackV2.lsRefs(caps: ServerCapabilities): List<RemoteRef>`.

- [ ] **Step 1: Write the failing test**

```kotlin
package de.nereide.strohhalm.domain.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class LsRefsTest {

    private val sha256Caps = ServerCapabilities(
        raw = mapOf("version" to "2", "fetch" to "", "ls-refs" to "", "object-format" to "sha256"),
        objectHash = ObjectHash.SHA256,
    )

    private val id = "a".repeat(64)
    private val peeledId = "b".repeat(64)

    private fun response(vararg lines: String): Pair<UploadPackV2, ByteArrayOutputStream> {
        val server = ByteArrayOutputStream()
        lines.forEach { PktLine.writeString(server, "$it\n") }
        PktLine.writeFlush(server)
        val sent = ByteArrayOutputStream()
        return UploadPackV2(ByteArrayInputStream(server.toByteArray()), sent) to sent
    }

    @Test
    fun `refs are parsed with their symref target and peeled id`() {
        val (protocol, _) = response(
            "$id HEAD symref-target:refs/heads/main",
            "$id refs/heads/main",
            "$peeledId refs/tags/v1 peeled:$id",
        )

        val refs = protocol.lsRefs(sha256Caps)

        assertEquals(3, refs.size)
        assertEquals("refs/heads/main", refs[0].symrefTarget)
        assertEquals(id, refs[1].objectId)
        assertEquals("refs/tags/v1", refs[2].name)
        assertEquals(id, refs[2].peeled)
    }

    @Test
    fun `the request announces the negotiated object format`() {
        val (protocol, sent) = response("$id refs/heads/main")
        protocol.lsRefs(sha256Caps)

        val request = sent.toString(Charsets.UTF_8.name())
        assertTrue(request.contains("command=ls-refs"))
        assertTrue(request.contains("object-format=sha256"))
        assertTrue(request.contains("peel"))
        assertTrue(request.contains("symrefs"))
        // Everything under refs/, so branches, tags and notes all arrive.
        assertTrue(request.contains("ref-prefix refs/"))
        assertTrue(request.endsWith("0000"))
    }

    @Test
    fun `an empty repository yields no refs rather than failing`() {
        val (protocol, _) = response()
        assertTrue(protocol.lsRefs(sha256Caps).isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk
./gradlew :app:testDebugUnitTest --rerun --tests "de.nereide.strohhalm.domain.git.LsRefsTest"
```

Expected: FAIL — `Unresolved reference: lsRefs`.

- [ ] **Step 3: Write minimal implementation**

Add to `UploadPackV2.kt`:

```kotlin
/** One ref as the server described it. Ids are hex, at the negotiated length. */
data class RemoteRef(
    val name: String,
    val objectId: String,
    val symrefTarget: String? = null,
    val peeled: String? = null,
)
```

and to the `UploadPackV2` class:

```kotlin
    /**
     * Every ref under `refs/`, plus `HEAD`.
     *
     * The prefix is deliberately broad: a mirror that tracked only head refs is
     * how backups end up quietly incomplete, so branches, tags and notes are all
     * requested in one call.
     */
    fun lsRefs(caps: ServerCapabilities): List<RemoteRef> {
        writeCommand("ls-refs", caps, listOf("peel", "symrefs", "unborn", "ref-prefix refs/"))

        val refs = mutableListOf<RemoteRef>()
        while (true) {
            when (val pkt = PktLine.read(input)) {
                is Pkt.Flush, is Pkt.ResponseEnd -> break
                is Pkt.Delim -> Unit
                is Pkt.Data -> {
                    val line = pkt.text().trim()
                    if (line.isEmpty()) continue
                    val fields = line.split(' ')
                    if (fields.size < 2) continue
                    refs += RemoteRef(
                        objectId = fields[0],
                        name = fields[1],
                        symrefTarget = fields.firstOrNull { it.startsWith("symref-target:") }
                            ?.removePrefix("symref-target:"),
                        peeled = fields.firstOrNull { it.startsWith("peeled:") }
                            ?.removePrefix("peeled:"),
                    )
                }
            }
        }
        return refs
    }

    /**
     * A v2 command: the command line and capabilities, a delimiter, then the
     * arguments, then a flush.
     *
     * `object-format` must be echoed back or a SHA-256 server will refuse the
     * request — the negotiation is two-sided, not an announcement.
     */
    private fun writeCommand(
        command: String,
        caps: ServerCapabilities,
        arguments: List<String>,
    ) {
        PktLine.writeString(output, "command=$command\n")
        PktLine.writeString(output, "object-format=${caps.objectHash.configName}\n")
        PktLine.writeDelim(output)
        arguments.forEach { PktLine.writeString(output, "$it\n") }
        PktLine.writeFlush(output)
        output.flush()
    }
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --rerun --tests "de.nereide.strohhalm.domain.git.LsRefsTest"
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  app/build/test-results/testDebugUnitTest/TEST-de.nereide.strohhalm.domain.git.LsRefsTest.xml
```

Expected: PASS, `tests="3" ... failures="0" errors="0"`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/de/nereide/strohhalm/domain/git/UploadPackV2.kt \
        app/src/test/java/de/nereide/strohhalm/domain/git/LsRefsTest.kt
git commit -m "feat(git): list remote refs over protocol v2"
```

---

### Task 7: `PackIndexWriter`

The `.idx` version 2 format. Written before the pack parser so it can be tested against known ids without needing a real pack.

**Files:**
- Create: `app/src/main/java/de/nereide/strohhalm/domain/git/PackIndexWriter.kt`
- Test: `app/src/test/java/de/nereide/strohhalm/domain/git/PackIndexWriterTest.kt`

**Interfaces:**
- Consumes: `ObjectHash`.
- Produces:
  - `data class IndexedObject(val id: ByteArray, val offset: Long, val crc32: Int)`
  - `object PackIndexWriter { fun write(target: File, objects: List<IndexedObject>, packChecksum: ByteArray, hash: ObjectHash) }`

- [ ] **Step 1: Write the failing test**

```kotlin
package de.nereide.strohhalm.domain.git

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataInputStream
import java.io.File

class PackIndexWriterTest {

    @get:Rule val temp = TemporaryFolder()

    private val hash = ObjectHash.SHA256

    private fun id(firstByte: Int): ByteArray =
        ByteArray(hash.rawLength).also { it[0] = firstByte.toByte() }

    @Test
    fun `the header, fanout and trailers match the idx v2 layout`() {
        val objects = listOf(
            IndexedObject(id(0x00), 12L, 0x11111111),
            IndexedObject(id(0x40), 400L, 0x22222222),
            IndexedObject(id(0xff), 900L, 0x33333333),
        )
        val packChecksum = ByteArray(hash.rawLength) { 0x7 }
        val target = File(temp.root, "pack-test.idx")

        PackIndexWriter.write(target, objects, packChecksum, hash)

        DataInputStream(target.inputStream().buffered()).use { input ->
            assertArrayEquals(
                byteArrayOf(0xff.toByte(), 't'.code.toByte(), 'O'.code.toByte(), 'c'.code.toByte()),
                ByteArray(4).also(input::readFully),
            )
            assertEquals(2, input.readInt())

            // Fanout is cumulative: every bucket holds the count of objects whose
            // first byte is <= that bucket's index.
            val fanout = IntArray(256) { input.readInt() }
            assertEquals(1, fanout[0x00])
            assertEquals(1, fanout[0x3f])
            assertEquals(2, fanout[0x40])
            assertEquals(3, fanout[0xff])

            objects.forEach { assertArrayEquals(it.id, ByteArray(hash.rawLength).also(input::readFully)) }
            objects.forEach { assertEquals(it.crc32, input.readInt()) }
            objects.forEach { assertEquals(it.offset.toInt(), input.readInt()) }

            assertArrayEquals(packChecksum, ByteArray(hash.rawLength).also(input::readFully))
        }
    }

    @Test
    fun `objects are sorted by id regardless of input order`() {
        val objects = listOf(
            IndexedObject(id(0xff), 900L, 3),
            IndexedObject(id(0x00), 12L, 1),
        )
        val target = File(temp.root, "pack-sorted.idx")

        PackIndexWriter.write(target, objects, ByteArray(hash.rawLength), hash)

        DataInputStream(target.inputStream().buffered()).use { input ->
            input.skipNBytes((8 + 256 * 4).toLong())
            assertEquals(0x00.toByte(), ByteArray(hash.rawLength).also(input::readFully)[0])
            assertEquals(0xff.toByte(), ByteArray(hash.rawLength).also(input::readFully)[0])
        }
    }

    @Test
    fun `an offset beyond 2GB moves to the large offset table`() {
        val big = 3L shl 30 // 3 GiB, past the 31-bit limit
        val target = File(temp.root, "pack-large.idx")

        PackIndexWriter.write(
            target,
            listOf(IndexedObject(id(1), big, 0)),
            ByteArray(hash.rawLength),
            hash,
        )

        DataInputStream(target.inputStream().buffered()).use { input ->
            input.skipNBytes((8 + 256 * 4 + hash.rawLength + 4).toLong())
            val encoded = input.readInt()
            assertEquals("MSB marks an indirection", -0x80000000, encoded and -0x80000000)
            assertEquals("index 0 into the large table", 0, encoded and 0x7fffffff)
            assertEquals(big, input.readLong())
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk
./gradlew :app:testDebugUnitTest --rerun --tests "de.nereide.strohhalm.domain.git.PackIndexWriterTest"
```

Expected: FAIL — `Unresolved reference: PackIndexWriter`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package de.nereide.strohhalm.domain.git

import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.security.DigestOutputStream

/** One entry in a pack index: where an object is, and how to check it. */
class IndexedObject(
    val id: ByteArray,
    val offset: Long,
    val crc32: Int,
)

/**
 * Writes a pack index, version 2.
 *
 * Layout, in order: the magic `\377tOc`, version 2, a 256-entry cumulative
 * fanout over the first byte of each id, the sorted ids, one CRC32 per object,
 * one 32-bit offset per object, a table of 64-bit offsets for anything past
 * 2 GiB, the pack checksum, and finally a checksum of the index itself.
 *
 * Every width that depends on the hash comes from [ObjectHash.rawLength]. This
 * file is the reason SHA-256 works: the format was always hash-parameterised,
 * and only the implementations hardcoded 20.
 *
 * The index is derivable — `git index-pack -f` regenerates it from the pack — so
 * a defect here costs one repair command rather than data.
 */
object PackIndexWriter {

    private val MAGIC = byteArrayOf(0xff.toByte(), 't'.code.toByte(), 'O'.code.toByte(), 'c'.code.toByte())
    private const val VERSION = 2
    private const val LARGE_OFFSET_FLAG = -0x80000000 // 0x80000000 as a signed Int
    private const val MAX_SMALL_OFFSET = 0x7fffffffL

    fun write(
        target: File,
        objects: List<IndexedObject>,
        packChecksum: ByteArray,
        hash: ObjectHash,
    ) {
        val sorted = objects.sortedWith { a, b -> compareIds(a.id, b.id) }
        val digest = hash.newDigest()

        target.outputStream().buffered().use { raw ->
            val out = DataOutputStream(DigestOutputStream(raw, digest))

            out.write(MAGIC)
            out.writeInt(VERSION)

            // Fanout: bucket i holds the number of objects whose first byte is <= i.
            var seen = 0
            var bucket = 0
            val counts = IntArray(256)
            sorted.forEach { counts[it.id[0].toInt() and 0xff]++ }
            while (bucket < 256) {
                seen += counts[bucket]
                out.writeInt(seen)
                bucket++
            }

            sorted.forEach { out.write(it.id) }
            sorted.forEach { out.writeInt(it.crc32) }

            val largeOffsets = mutableListOf<Long>()
            sorted.forEach { entry ->
                if (entry.offset <= MAX_SMALL_OFFSET) {
                    out.writeInt(entry.offset.toInt())
                } else {
                    out.writeInt(LARGE_OFFSET_FLAG or largeOffsets.size)
                    largeOffsets += entry.offset
                }
            }
            largeOffsets.forEach { out.writeLong(it) }

            out.write(packChecksum)
            out.flush()

            // The trailing checksum covers everything above it, so it is written
            // outside the digest stream.
            raw.write(digest.digest())
            raw.flush()
        }
    }

    /** Unsigned byte order, which is how git sorts object ids. */
    private fun compareIds(a: ByteArray, b: ByteArray): Int {
        for (i in a.indices) {
            val difference = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (difference != 0) return difference
        }
        return 0
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --rerun --tests "de.nereide.strohhalm.domain.git.PackIndexWriterTest"
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  app/build/test-results/testDebugUnitTest/TEST-de.nereide.strohhalm.domain.git.PackIndexWriterTest.xml
```

Expected: PASS, `tests="3" ... failures="0" errors="0"`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/de/nereide/strohhalm/domain/git/PackIndexWriter.kt \
        app/src/test/java/de/nereide/strohhalm/domain/git/PackIndexWriterTest.kt
git commit -m "feat(git): write pack index v2 at the negotiated hash width"
```

---

### Task 8: Delta decoding

Git's delta instruction format, isolated so it can be tested exhaustively without a pack.

**Files:**
- Create: `app/src/main/java/de/nereide/strohhalm/domain/git/PackDelta.kt`
- Test: `app/src/test/java/de/nereide/strohhalm/domain/git/PackDeltaTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `object PackDelta { fun apply(base: ByteArray, delta: ByteArray): ByteArray }`

- [ ] **Step 1: Write the failing test**

```kotlin
package de.nereide.strohhalm.domain.git

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

class PackDeltaTest {

    /** Sizes are little-endian 7-bit varints, high bit meaning "more follows". */
    private fun varint(value: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var remaining = value
        while (true) {
            val b = remaining and 0x7f
            remaining = remaining ushr 7
            out.write(if (remaining == 0) b else b or 0x80)
            if (remaining == 0) break
        }
        return out.toByteArray()
    }

    @Test
    fun `an insert instruction copies literal bytes into the result`() {
        val base = "unused".toByteArray()
        val delta = ByteArrayOutputStream().apply {
            write(varint(base.size))
            write(varint(3))
            write(3)               // insert 3 literal bytes
            write("abc".toByteArray())
        }.toByteArray()

        assertArrayEquals("abc".toByteArray(), PackDelta.apply(base, delta))
    }

    @Test
    fun `a copy instruction takes a run from the base`() {
        val base = "HELLO WORLD".toByteArray()
        val delta = ByteArrayOutputStream().apply {
            write(varint(base.size))
            write(varint(5))
            write(0x90)            // copy, offset absent (0), size byte 1 present
            write(5)               // 5 bytes
        }.toByteArray()

        assertArrayEquals("HELLO".toByteArray(), PackDelta.apply(base, delta))
    }

    @Test
    fun `copy and insert combine`() {
        val base = "HELLO WORLD".toByteArray()
        val delta = ByteArrayOutputStream().apply {
            write(varint(base.size))
            write(varint(8))
            write(0x91); write(6); write(5)   // copy 5 bytes from offset 6 -> "WORLD"
            write(3); write("!!!".toByteArray())
        }.toByteArray()

        assertArrayEquals("WORLD!!!".toByteArray(), PackDelta.apply(base, delta))
    }

    @Test
    fun `a size mismatch is refused rather than returned`() {
        val base = "abc".toByteArray()
        val delta = ByteArrayOutputStream().apply {
            write(varint(99))      // wrong base size
            write(varint(1))
            write(1); write('x'.code)
        }.toByteArray()

        assertThrows(IOException::class.java) { PackDelta.apply(base, delta) }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk
./gradlew :app:testDebugUnitTest --rerun --tests "de.nereide.strohhalm.domain.git.PackDeltaTest"
```

Expected: FAIL — `Unresolved reference: PackDelta`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package de.nereide.strohhalm.domain.git

import java.io.IOException

/**
 * Git's delta encoding: a base size, a result size, then a stream of copy and
 * insert instructions.
 *
 * A copy instruction has its high bit set; the remaining bits say which of four
 * offset bytes and three size bytes follow, so an unchanged field costs nothing.
 * A copy size of zero means 0x10000 — a quirk of the format, not a bug here.
 *
 * This is the one hot loop in the engine that is not already native, so it
 * copies into a pre-sized array and never grows a buffer.
 */
object PackDelta {

    fun apply(base: ByteArray, delta: ByteArray): ByteArray {
        var position = 0

        fun varint(): Int {
            var value = 0
            var shift = 0
            while (true) {
                if (position >= delta.size) throw IOException("truncated delta header")
                val b = delta[position++].toInt() and 0xff
                value = value or ((b and 0x7f) shl shift)
                if (b and 0x80 == 0) return value
                shift += 7
            }
        }

        val baseSize = varint()
        if (baseSize != base.size) {
            throw IOException("delta expects a base of $baseSize bytes, got ${base.size}")
        }
        val resultSize = varint()
        val result = ByteArray(resultSize)
        var written = 0

        while (position < delta.size) {
            val command = delta[position++].toInt() and 0xff
            if (command and 0x80 != 0) {
                var offset = 0
                var size = 0
                if (command and 0x01 != 0) offset = offset or (delta[position++].toInt() and 0xff)
                if (command and 0x02 != 0) offset = offset or ((delta[position++].toInt() and 0xff) shl 8)
                if (command and 0x04 != 0) offset = offset or ((delta[position++].toInt() and 0xff) shl 16)
                if (command and 0x08 != 0) offset = offset or ((delta[position++].toInt() and 0xff) shl 24)
                if (command and 0x10 != 0) size = size or (delta[position++].toInt() and 0xff)
                if (command and 0x20 != 0) size = size or ((delta[position++].toInt() and 0xff) shl 8)
                if (command and 0x40 != 0) size = size or ((delta[position++].toInt() and 0xff) shl 16)
                if (size == 0) size = 0x10000

                if (offset + size > base.size || written + size > resultSize) {
                    throw IOException("delta copy runs past the end of its base")
                }
                System.arraycopy(base, offset, result, written, size)
                written += size
            } else {
                if (command == 0) throw IOException("reserved delta instruction 0")
                if (written + command > resultSize) {
                    throw IOException("delta insert runs past the result size")
                }
                System.arraycopy(delta, position, result, written, command)
                position += command
                written += command
            }
        }

        if (written != resultSize) {
            throw IOException("delta produced $written bytes, expected $resultSize")
        }
        return result
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --rerun --tests "de.nereide.strohhalm.domain.git.PackDeltaTest"
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  app/build/test-results/testDebugUnitTest/TEST-de.nereide.strohhalm.domain.git.PackDeltaTest.xml
```

Expected: PASS, `tests="4" ... failures="0" errors="0"`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/de/nereide/strohhalm/domain/git/PackDelta.kt \
        app/src/test/java/de/nereide/strohhalm/domain/git/PackDeltaTest.kt
git commit -m "feat(git): decode git's delta instruction format"
```

---

### Task 9: `PackIndexer` and `KotlinPackIndexer`

The pack datastream becomes a `.pack` and a `.idx`. **The `PackIndexer` interface is the seam** a Rust `gix-pack` implementation would drop into; nothing above it changes.

**Files:**
- Create: `app/src/main/java/de/nereide/strohhalm/domain/git/PackIndexer.kt`
- Create: `app/src/main/java/de/nereide/strohhalm/domain/git/KotlinPackIndexer.kt`
- Test: `app/src/test/java/de/nereide/strohhalm/domain/git/KotlinPackIndexerTest.kt`

**Interfaces:**
- Consumes: `ObjectHash`, `PackDelta`, `PackIndexWriter`, `IndexedObject`, `MirrorProgress` (existing, `de.nereide.strohhalm.domain`).
- Produces:
  - `data class PackResult(val packName: String, val objectCount: Int, val bytes: Long)`
  - `interface PackIndexer { fun consume(pack: InputStream, hash: ObjectHash, objectsDir: File, progress: MirrorProgress?): PackResult }`
  - `class KotlinPackIndexer : PackIndexer`

- [ ] **Step 1: Write the failing test**

The fixture is a real pack built by JGit, which the project already permits in tests. It exercises SHA-1; SHA-256 packs are covered end to end in Task 12 with real `git`.

```kotlin
package de.nereide.strohhalm.domain.git

import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

class KotlinPackIndexerTest {

    @get:Rule val temp = TemporaryFolder()

    /**
     * Builds a real pack with JGit and returns its bytes.
     *
     * JGit is allowed in tests as a fixture builder; it is only unusable as the
     * engine because it cannot read SHA-256.
     */
    private fun sha1PackBytes(fileCount: Int): ByteArray {
        val work = temp.newFolder("work")
        Git.init().setDirectory(work).call().use { git ->
            repeat(fileCount) { i ->
                File(work, "file$i.txt").writeText("content $i\n".repeat(i + 1))
                git.add().addFilepattern("file$i.txt").call()
                git.commit().setMessage("commit $i").setSign(false).call()
            }
            val packDir = temp.newFolder("packout")
            git.repository.newObjectReader().use { reader ->
                org.eclipse.jgit.internal.storage.pack.PackWriter(git.repository).use { writer ->
                    val refs = git.repository.refDatabase.refs.map { it.objectId }
                    writer.preparePack(org.eclipse.jgit.lib.NullProgressMonitor.INSTANCE, refs, emptySet())
                    val out = File(packDir, "out.pack")
                    out.outputStream().use { writer.writePack(
                        org.eclipse.jgit.lib.NullProgressMonitor.INSTANCE,
                        org.eclipse.jgit.lib.NullProgressMonitor.INSTANCE,
                        it,
                    ) }
                    return out.readBytes()
                }
            }
        }
    }

    @Test
    fun `a pack produces a pack file and an index named after its checksum`() {
        val objects = temp.newFolder("objects")
        val bytes = sha1PackBytes(fileCount = 3)

        val result = KotlinPackIndexer()
            .consume(ByteArrayInputStream(bytes), ObjectHash.SHA1, objects, null)

        val packFile = File(objects, "pack/${result.packName}.pack")
        val idxFile = File(objects, "pack/${result.packName}.idx")
        assertTrue("pack written", packFile.isFile)
        assertTrue("index written", idxFile.isFile)
        assertEquals(bytes.size.toLong(), packFile.length())
        assertTrue("some objects indexed", result.objectCount > 0)
    }

    @Test
    fun `deltas are resolved, so every object in the pack is indexed`() {
        val objects = temp.newFolder("objects")
        // Repeated similar content is what makes JGit emit deltas at all.
        val bytes = sha1PackBytes(fileCount = 8)

        val result = KotlinPackIndexer()
            .consume(ByteArrayInputStream(bytes), ObjectHash.SHA1, objects, null)

        // Header object count and indexed count must agree, which is only true
        // if every delta resolved.
        val declared = ((bytes[8].toInt() and 0xff) shl 24) or
            ((bytes[9].toInt() and 0xff) shl 16) or
            ((bytes[10].toInt() and 0xff) shl 8) or
            (bytes[11].toInt() and 0xff)
        assertEquals(declared, result.objectCount)
    }

    @Test
    fun `a corrupted trailer is refused`() {
        val objects = temp.newFolder("objects")
        val bytes = sha1PackBytes(fileCount = 2)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte()

        assertThrows(IOException::class.java) {
            KotlinPackIndexer().consume(ByteArrayInputStream(bytes), ObjectHash.SHA1, objects, null)
        }
    }

    @Test
    fun `a stream that is not a pack is refused`() {
        val objects = temp.newFolder("objects")
        assertThrows(IOException::class.java) {
            KotlinPackIndexer().consume(
                ByteArrayInputStream("NOTAPACK".toByteArray()),
                ObjectHash.SHA1,
                objects,
                null,
            )
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk
./gradlew :app:testDebugUnitTest --rerun --tests "de.nereide.strohhalm.domain.git.KotlinPackIndexerTest"
```

Expected: FAIL — `Unresolved reference: KotlinPackIndexer`.

- [ ] **Step 3a: Write the interface**

`PackIndexer.kt`:

```kotlin
package de.nereide.strohhalm.domain.git

import de.nereide.strohhalm.domain.MirrorProgress
import java.io.File
import java.io.InputStream

/** What a pack turned into on disk. */
data class PackResult(
    /** `pack-<checksum>`, without an extension. */
    val packName: String,
    val objectCount: Int,
    val bytes: Long,
)

/**
 * Turns a packfile datastream into a `.pack` plus its `.idx`.
 *
 * **This interface is the seam.** [KotlinPackIndexer] is the pure-JVM
 * implementation; a Rust implementation calling gitoxide's
 * `gix_pack::bundle::write::write_to_directory` would drop in here without
 * anything above this interface changing. The engine chose pure JVM so the app
 * ships no native code — see the design's distribution reasoning — and this
 * boundary is what keeps that a reversible decision.
 */
interface PackIndexer {
    fun consume(
        pack: InputStream,
        hash: ObjectHash,
        objectsDir: File,
        progress: MirrorProgress?,
    ): PackResult
}
```

- [ ] **Step 3b: Write the implementation**

`KotlinPackIndexer.kt`:

```kotlin
package de.nereide.strohhalm.domain.git

import de.nereide.strohhalm.domain.MirrorProgress
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Pack ingestion in two passes.
 *
 * Pass one streams the pack to disk while hashing it, so the server's trailer
 * checksum is verified without holding anything in memory — a 72k-object pack
 * must never be buffered on a phone. Pass two reads back through the file,
 * inflating each object and resolving deltas, to compute the ids the index
 * needs.
 *
 * The engine never interprets an object body; the only reason it inflates at all
 * is that an id is the hash of the *inflated* content.
 */
class KotlinPackIndexer : PackIndexer {

    private data class Entry(
        val offset: Long,
        val type: Int,
        val dataOffset: Long,
        val baseOffset: Long,
        val baseId: ByteArray?,
        val crc32: Int,
    )

    override fun consume(
        pack: InputStream,
        hash: ObjectHash,
        objectsDir: File,
        progress: MirrorProgress?,
    ): PackResult {
        val packDir = File(objectsDir, "pack").apply { mkdirs() }
        val temporary = File.createTempFile("incoming-", ".pack", packDir)

        try {
            val checksum = writeToDisk(pack, temporary, hash)
            val entries = RandomAccessFile(temporary, "r").use { file ->
                scan(file, hash, progress)
            }
            val indexed = RandomAccessFile(temporary, "r").use { file ->
                resolve(file, entries, hash, progress)
            }

            val name = "pack-${hash.toHex(checksum)}"
            val finalPack = File(packDir, "$name.pack")
            if (!temporary.renameTo(finalPack)) {
                throw IOException("could not move the pack into place: $finalPack")
            }
            PackIndexWriter.write(File(packDir, "$name.idx"), indexed, checksum, hash)

            return PackResult(name, indexed.size, finalPack.length())
        } finally {
            temporary.delete()
        }
    }

    /** Streams to disk, verifying the trailer the server computed. */
    private fun writeToDisk(pack: InputStream, target: File, hash: ObjectHash): ByteArray {
        val digest = hash.newDigest()
        val trailer = ByteArray(hash.rawLength)
        var trailerFilled = 0

        target.outputStream().buffered().use { out ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = pack.read(buffer)
                if (read < 0) break
                // The last rawLength bytes are the checksum, not pack content —
                // so bytes are held back until enough have arrived to know which
                // are which.
                var consumed = 0
                while (consumed < read) {
                    val room = hash.rawLength - trailerFilled
                    val take = minOf(room, read - consumed)
                    if (trailerFilled == hash.rawLength) {
                        digest.update(trailer, 0, 1)
                        out.write(trailer, 0, 1)
                        System.arraycopy(trailer, 1, trailer, 0, hash.rawLength - 1)
                        trailerFilled--
                        continue
                    }
                    System.arraycopy(buffer, consumed, trailer, trailerFilled, take)
                    trailerFilled += take
                    consumed += take
                }
            }
        }

        if (trailerFilled != hash.rawLength) throw IOException("pack ended before its checksum")

        val computed = digest.digest()
        if (!computed.contentEquals(trailer)) {
            throw IOException("pack checksum mismatch: the transfer was corrupted")
        }
        return computed
    }

    /** Records where every object starts, without inflating any of them. */
    private fun scan(
        file: RandomAccessFile,
        hash: ObjectHash,
        progress: MirrorProgress?,
    ): List<Entry> {
        val magic = ByteArray(4).also(file::readFully)
        if (!magic.contentEquals("PACK".toByteArray(Charsets.US_ASCII))) {
            throw IOException("not a packfile")
        }
        val version = file.readInt()
        if (version != 2 && version != 3) throw IOException("unsupported pack version $version")
        val count = file.readInt()

        val entries = ArrayList<Entry>(count)
        repeat(count) { index ->
            val offset = file.filePointer
            val crc = CRC32()

            var b = file.readUnsignedByte().also { crc.update(it) }
            val type = (b shr 4) and 7
            var size = (b and 0x0f).toLong()
            var shift = 4
            while (b and 0x80 != 0) {
                b = file.readUnsignedByte().also { crc.update(it) }
                size = size or ((b and 0x7f).toLong() shl shift)
                shift += 7
            }

            var baseOffset = -1L
            var baseId: ByteArray? = null
            when (type) {
                OBJ_OFS_DELTA -> {
                    b = file.readUnsignedByte().also { crc.update(it) }
                    var delta = (b and 0x7f).toLong()
                    while (b and 0x80 != 0) {
                        b = file.readUnsignedByte().also { crc.update(it) }
                        delta = ((delta + 1) shl 7) or (b and 0x7f).toLong()
                    }
                    baseOffset = offset - delta
                }

                OBJ_REF_DELTA -> {
                    baseId = ByteArray(hash.rawLength).also(file::readFully)
                    crc.update(baseId)
                }
            }

            val dataOffset = file.filePointer
            val compressed = skipDeflated(file, size)
            crc.update(compressed)

            entries += Entry(offset, type, dataOffset, baseOffset, baseId, crc.value.toInt())
            if (index % PROGRESS_EVERY == 0) progress?.update("Indexing objects", index, count)
        }
        progress?.update("Indexing objects", count, count)
        return entries
    }

    /**
     * Advances past one deflate stream and returns its raw bytes.
     *
     * The pack does not record the compressed length, so the only way to find the
     * next object is to inflate until this one ends and ask the [Inflater] how
     * many bytes it actually consumed.
     */
    private fun skipDeflated(file: RandomAccessFile, expectedSize: Long): ByteArray {
        val start = file.filePointer
        val inflater = Inflater()
        val input = ByteArray(BUFFER)
        val scratch = ByteArray(BUFFER)
        var consumed = 0L
        var produced = 0L
        try {
            while (!inflater.finished()) {
                if (inflater.needsInput()) {
                    val read = file.read(input)
                    if (read < 0) throw IOException("pack ended inside a compressed object")
                    inflater.setInput(input, 0, read)
                    consumed += read
                }
                produced += inflater.inflate(scratch)
            }
            if (produced != expectedSize) {
                throw IOException("object declared $expectedSize bytes, inflated to $produced")
            }
            val used = consumed - inflater.remaining
            file.seek(start + used)
            val raw = ByteArray(used.toInt())
            val here = file.filePointer
            file.seek(start)
            file.readFully(raw)
            file.seek(here)
            return raw
        } finally {
            inflater.end()
        }
    }

    /** Inflates and resolves, producing the ids the index is built from. */
    private fun resolve(
        file: RandomAccessFile,
        entries: List<Entry>,
        hash: ObjectHash,
        progress: MirrorProgress?,
    ): List<IndexedObject> {
        val byOffset = entries.associateBy { it.offset }
        val contentCache = HashMap<Long, Pair<Int, ByteArray>>()
        val idToOffset = HashMap<String, Long>()
        val indexed = ArrayList<IndexedObject>(entries.size)

        fun contentOf(entry: Entry): Pair<Int, ByteArray> {
            contentCache[entry.offset]?.let { return it }

            val body = inflate(file, entry.dataOffset)
            val resolved = when (entry.type) {
                OBJ_OFS_DELTA, OBJ_REF_DELTA -> {
                    val base = when (entry.type) {
                        OBJ_OFS_DELTA -> byOffset[entry.baseOffset]
                            ?: throw IOException("delta base at ${entry.baseOffset} is missing")

                        else -> {
                            val key = hash.toHex(entry.baseId!!)
                            val offset = idToOffset[key]
                                ?: throw IOException("delta base $key is not in this pack")
                            byOffset[offset]!!
                        }
                    }
                    val (baseType, baseBytes) = contentOf(base)
                    baseType to PackDelta.apply(baseBytes, body)
                }

                else -> entry.type to body
            }
            contentCache[entry.offset] = resolved
            if (contentCache.size > CACHE_LIMIT) {
                contentCache.keys.take(contentCache.size - CACHE_LIMIT).forEach(contentCache::remove)
            }
            return resolved
        }

        entries.forEachIndexed { index, entry ->
            val (type, body) = contentOf(entry)
            val id = hash.objectId(typeName(type), body)
            idToOffset[hash.toHex(id)] = entry.offset
            indexed += IndexedObject(id, entry.offset, entry.crc32)
            if (index % PROGRESS_EVERY == 0) {
                progress?.update("Resolving deltas", index, entries.size)
            }
        }
        progress?.update("Resolving deltas", entries.size, entries.size)
        return indexed
    }

    private fun inflate(file: RandomAccessFile, dataOffset: Long): ByteArray {
        file.seek(dataOffset)
        val stream = InflaterInputStream(
            object : InputStream() {
                override fun read(): Int = file.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int = file.read(b, off, len)
            }
        )
        return stream.readBytes()
    }

    private fun typeName(type: Int): String = when (type) {
        OBJ_COMMIT -> "commit"
        OBJ_TREE -> "tree"
        OBJ_BLOB -> "blob"
        OBJ_TAG -> "tag"
        else -> throw IOException("unresolved object type $type")
    }

    private companion object {
        const val OBJ_COMMIT = 1
        const val OBJ_TREE = 2
        const val OBJ_BLOB = 3
        const val OBJ_TAG = 4
        const val OBJ_OFS_DELTA = 6
        const val OBJ_REF_DELTA = 7

        const val BUFFER = 64 * 1024
        const val CACHE_LIMIT = 256
        const val PROGRESS_EVERY = 256
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --rerun --tests "de.nereide.strohhalm.domain.git.KotlinPackIndexerTest"
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  app/build/test-results/testDebugUnitTest/TEST-de.nereide.strohhalm.domain.git.KotlinPackIndexerTest.xml
```

Expected: PASS, `tests="4" ... failures="0" errors="0"`.

If `ref-delta` bases resolve out of order, note that a non-thin pack always places a base before its deltas — so the failure indicates the pack was thin, which the fetch request must never request.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/de/nereide/strohhalm/domain/git/PackIndexer.kt \
        app/src/main/java/de/nereide/strohhalm/domain/git/KotlinPackIndexer.kt \
        app/src/test/java/de/nereide/strohhalm/domain/git/KotlinPackIndexerTest.kt
git commit -m "feat(git): index packfiles in pure Kotlin, hash-agnostically"
```

---

### Task 10: `fetch`

**Files:**
- Modify: `app/src/main/java/de/nereide/strohhalm/domain/git/UploadPackV2.kt`
- Test: `app/src/test/java/de/nereide/strohhalm/domain/git/FetchTest.kt`

**Interfaces:**
- Consumes: `ServerCapabilities`, `RemoteRef`, `SidebandInputStream`.
- Produces: `UploadPackV2.fetch(caps: ServerCapabilities, wants: List<String>, haves: List<String>, onProgress: (String) -> Unit): InputStream` — the band-1 pack stream, positioned at the start of the packfile section.

- [ ] **Step 1: Write the failing test**

```kotlin
package de.nereide.strohhalm.domain.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class FetchTest {

    private val caps = ServerCapabilities(
        raw = mapOf("version" to "2", "fetch" to "", "object-format" to "sha256"),
        objectHash = ObjectHash.SHA256,
    )
    private val want = "a".repeat(64)
    private val have = "b".repeat(64)

    /** A server response: the packfile section header, then sideband data. */
    private fun serverResponse(packBytes: String): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        PktLine.writeString(out, "packfile\n")
        PktLine.writeBytes(out, byteArrayOf(1) + packBytes.toByteArray())
        PktLine.writeFlush(out)
        return ByteArrayInputStream(out.toByteArray())
    }

    @Test
    fun `the pack stream contains exactly the band 1 payload`() {
        val protocol = UploadPackV2(serverResponse("PACKDATA"), ByteArrayOutputStream())
        val stream = protocol.fetch(caps, listOf(want), emptyList()) {}
        assertEquals("PACKDATA", String(stream.readBytes()))
    }

    @Test
    fun `wants haves and done are sent, and thin-pack is not requested`() {
        val sent = ByteArrayOutputStream()
        UploadPackV2(serverResponse("X"), sent)
            .fetch(caps, listOf(want), listOf(have)) {}

        val request = sent.toString(Charsets.UTF_8.name())
        assertTrue(request.contains("command=fetch"))
        assertTrue(request.contains("object-format=sha256"))
        assertTrue(request.contains("want $want"))
        assertTrue(request.contains("have $have"))
        assertTrue(request.contains("done"))
        assertTrue("offset deltas cut transfer size", request.contains("ofs-delta"))
        // Declining thin-pack obliges the server to send a self-contained pack,
        // which is what lets the indexer never read local objects.
        assertFalse("thin-pack must not be requested", request.contains("thin-pack"))
    }

    @Test
    fun `server progress reaches the callback`() {
        val out = ByteArrayOutputStream()
        PktLine.writeString(out, "packfile\n")
        PktLine.writeBytes(out, byteArrayOf(2) + "Counting objects: 12".toByteArray())
        PktLine.writeBytes(out, byteArrayOf(1) + "P".toByteArray())
        PktLine.writeFlush(out)

        val seen = mutableListOf<String>()
        val stream = UploadPackV2(ByteArrayInputStream(out.toByteArray()), ByteArrayOutputStream())
            .fetch(caps, listOf(want), emptyList()) { seen += it }
        stream.readBytes()

        assertEquals(listOf("Counting objects: 12"), seen)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk
./gradlew :app:testDebugUnitTest --rerun --tests "de.nereide.strohhalm.domain.git.FetchTest"
```

Expected: FAIL — `Unresolved reference: fetch`.

- [ ] **Step 3: Write minimal implementation**

Add to `UploadPackV2`:

```kotlin
    /**
     * Asks for [wants], offering [haves], and returns the packfile as a plain
     * stream of bytes.
     *
     * Two negotiation choices, both deliberate:
     *
     * `thin-pack` is **not** advertised. It is an optional client capability, and
     * declining it obliges the server to send a self-contained pack — which
     * removes fix-thin, the step that would otherwise force this engine to read
     * local objects it is designed never to read.
     *
     * [haves] are ref tips only. Walking history to offer better haves would mean
     * parsing commits, which the engine otherwise never does; the cost of a
     * weaker negotiation is a larger download, never a wrong result.
     */
    fun fetch(
        caps: ServerCapabilities,
        wants: List<String>,
        haves: List<String>,
        onProgress: (String) -> Unit,
    ): InputStream {
        val arguments = buildList {
            add("ofs-delta")
            wants.forEach { add("want $it") }
            haves.forEach { add("have $it") }
            add("done")
        }
        writeCommand("fetch", caps, arguments)

        // With "done" sent the server skips acknowledgments and goes straight to
        // the packfile section, so the only thing to skip is its header line.
        while (true) {
            when (val pkt = PktLine.read(input)) {
                is Pkt.Data -> if (pkt.text().trim() == "packfile") break
                is Pkt.Delim -> Unit
                is Pkt.Flush, is Pkt.ResponseEnd ->
                    throw IOException("the server sent no packfile section")
            }
        }
        return SidebandInputStream(input, onProgress)
    }
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --rerun --tests "de.nereide.strohhalm.domain.git.FetchTest"
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  app/build/test-results/testDebugUnitTest/TEST-de.nereide.strohhalm.domain.git.FetchTest.xml
```

Expected: PASS, `tests="3" ... failures="0" errors="0"`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/de/nereide/strohhalm/domain/git/UploadPackV2.kt \
        app/src/test/java/de/nereide/strohhalm/domain/git/FetchTest.kt
git commit -m "feat(git): fetch a self-contained pack over protocol v2"
```

---

### Task 11: `MirrorRepository`

The bare layout on disk, and the ref bookkeeping that makes a mirror a mirror.

**Files:**
- Create: `app/src/main/java/de/nereide/strohhalm/domain/git/MirrorRepository.kt`
- Test: `app/src/test/java/de/nereide/strohhalm/domain/git/MirrorRepositoryTest.kt`

**Interfaces:**
- Consumes: `ObjectHash`, `RemoteRef`.
- Produces: `class MirrorRepository(val gitDir: File)` with
  `fun exists(): Boolean`, `fun initialise(hash: ObjectHash)`, `fun objectHash(): ObjectHash`,
  `fun localRefs(): Map<String, String>`, `fun writeRefs(refs: List<RemoteRef>)`,
  `fun setHead(target: String)`, `fun refNames(): List<String>`, `fun objectsDir(): File`

- [ ] **Step 1: Write the failing test**

```kotlin
package de.nereide.strohhalm.domain.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MirrorRepositoryTest {

    @get:Rule val temp = TemporaryFolder()

    private fun repo(): MirrorRepository = MirrorRepository(File(temp.root, "repo.git"))

    private fun ref(name: String, id: String) = RemoteRef(name = name, objectId = id)

    private val a = "a".repeat(64)
    private val b = "b".repeat(64)

    @Test
    fun `initialising a sha256 mirror records the object format`() {
        val mirror = repo()
        mirror.initialise(ObjectHash.SHA256)

        val config = File(mirror.gitDir, "config").readText()
        assertTrue(config.contains("repositoryformatversion = 1"))
        assertTrue(config.contains("objectFormat = sha256"))
        assertTrue(config.contains("bare = true"))
        assertTrue(File(mirror.gitDir, "HEAD").isFile)
        assertTrue(File(mirror.gitDir, "objects/pack").isDirectory)
        assertEquals(ObjectHash.SHA256, mirror.objectHash())
    }

    @Test
    fun `a sha1 mirror stays at format version 0, as git writes it`() {
        val mirror = repo()
        mirror.initialise(ObjectHash.SHA1)

        val config = File(mirror.gitDir, "config").readText()
        assertTrue(config.contains("repositoryformatversion = 0"))
        assertFalse(config.contains("objectFormat"))
        assertEquals(ObjectHash.SHA1, mirror.objectHash())
    }

    @Test
    fun `refs round-trip through packed-refs`() {
        val mirror = repo()
        mirror.initialise(ObjectHash.SHA256)
        mirror.writeRefs(listOf(ref("refs/heads/main", a), ref("refs/tags/v1", b)))

        assertEquals(mapOf("refs/heads/main" to a, "refs/tags/v1" to b), mirror.localRefs())
        assertEquals(listOf("refs/heads/main", "refs/tags/v1"), mirror.refNames())
    }

    @Test
    fun `a ref deleted upstream is removed locally`() {
        val mirror = repo()
        mirror.initialise(ObjectHash.SHA256)
        mirror.writeRefs(listOf(ref("refs/heads/main", a), ref("refs/heads/old", b)))
        mirror.writeRefs(listOf(ref("refs/heads/main", a)))

        assertEquals(listOf("refs/heads/main"), mirror.refNames())
    }

    @Test
    fun `HEAD is not written as a ref but as a symbolic pointer`() {
        val mirror = repo()
        mirror.initialise(ObjectHash.SHA256)
        mirror.writeRefs(
            listOf(
                RemoteRef("HEAD", a, symrefTarget = "refs/heads/trunk"),
                ref("refs/heads/trunk", a),
            )
        )

        assertEquals("ref: refs/heads/trunk", File(mirror.gitDir, "HEAD").readText().trim())
        assertFalse("HEAD is not a ref entry", mirror.refNames().contains("HEAD"))
    }

    @Test
    fun `loose refs written by another engine are read too`() {
        val mirror = repo()
        mirror.initialise(ObjectHash.SHA1)
        // JGit-created mirrors may hold refs loose rather than packed.
        File(mirror.gitDir, "refs/heads").mkdirs()
        File(mirror.gitDir, "refs/heads/legacy").writeText("${"c".repeat(40)}\n")

        assertTrue(mirror.localRefs().containsKey("refs/heads/legacy"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk
./gradlew :app:testDebugUnitTest --rerun --tests "de.nereide.strohhalm.domain.git.MirrorRepositoryTest"
```

Expected: FAIL — `Unresolved reference: MirrorRepository`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package de.nereide.strohhalm.domain.git

import java.io.File
import java.io.IOException

/**
 * A bare mirror on disk.
 *
 * The layout is deliberately the plainest git accepts — loose `packed-refs`, a
 * `HEAD` file, packs under `objects/pack` — because the whole recovery promise is
 * that a user's own `git` can read the folder with no Strohhalm-specific tooling.
 *
 * Fetches append packs and never repack. For an append-only backup that is the
 * right behaviour: nothing already written is ever rewritten.
 */
class MirrorRepository(val gitDir: File) {

    fun exists(): Boolean = File(gitDir, "HEAD").isFile

    fun objectsDir(): File = File(gitDir, "objects")

    fun initialise(hash: ObjectHash) {
        File(gitDir, "objects/pack").mkdirs()
        File(gitDir, "refs/heads").mkdirs()
        File(gitDir, "refs/tags").mkdirs()
        File(gitDir, "HEAD").writeText("ref: refs/heads/main\n")

        // Format version 1 plus an extension is how git marks a non-SHA-1
        // repository; a SHA-1 one stays at version 0 so older git can read it.
        val config = buildString {
            appendLine("[core]")
            appendLine("\trepositoryformatversion = ${if (hash == ObjectHash.SHA1) 0 else 1}")
            appendLine("\tfilemode = false")
            appendLine("\tbare = true")
            if (hash != ObjectHash.SHA1) {
                appendLine("[extensions]")
                appendLine("\tobjectFormat = ${hash.configName}")
            }
        }
        File(gitDir, "config").writeText(config)
    }

    fun objectHash(): ObjectHash {
        val config = File(gitDir, "config")
        if (!config.isFile) throw IOException("not a repository: $gitDir")
        val declared = config.readLines()
            .firstOrNull { it.trim().startsWith("objectFormat") }
            ?.substringAfter('=')
            ?.trim()
        return declared?.let(ObjectHash::fromConfigName) ?: ObjectHash.SHA1
    }

    /** Every ref this mirror already holds, packed or loose. */
    fun localRefs(): Map<String, String> {
        val refs = linkedMapOf<String, String>()

        File(gitDir, "packed-refs").takeIf { it.isFile }?.forEachLine { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("^")) return@forEachLine
            val id = trimmed.substringBefore(' ')
            val name = trimmed.substringAfter(' ', "")
            if (name.isNotEmpty()) refs[name] = id
        }

        // A mirror created by the JGit engine may hold refs loose. Both must be
        // read, or an incremental fetch would offer no haves and re-download.
        val refsRoot = File(gitDir, "refs")
        if (refsRoot.isDirectory) {
            refsRoot.walkTopDown().filter { it.isFile }.forEach { file ->
                val name = file.relativeTo(gitDir).path.replace(File.separatorChar, '/')
                refs.putIfAbsent(name, file.readText().trim())
            }
        }
        return refs
    }

    fun refNames(): List<String> = localRefs().keys.sorted()

    /**
     * Replaces the ref set wholesale, which is what makes this a mirror: a ref
     * deleted upstream disappears here too. A merging write would let a deleted
     * branch linger forever and quietly diverge from the remote.
     */
    fun writeRefs(refs: List<RemoteRef>) {
        val head = refs.firstOrNull { it.name == "HEAD" }
        val entries = refs.filter { it.name != "HEAD" }.sortedBy { it.name }

        val packed = buildString {
            appendLine("# pack-refs with: peeled fully-peeled sorted ")
            entries.forEach { ref ->
                appendLine("${ref.objectId} ${ref.name}")
                ref.peeled?.let { appendLine("^$it") }
            }
        }
        File(gitDir, "packed-refs").writeText(packed)

        // Loose refs would shadow packed-refs, so a previous engine's leftovers
        // are cleared rather than left to win.
        File(gitDir, "refs").walkBottomUp().forEach { file ->
            if (file.isFile) file.delete()
        }
        File(gitDir, "refs/heads").mkdirs()
        File(gitDir, "refs/tags").mkdirs()

        head?.symrefTarget?.let(::setHead)
    }

    fun setHead(target: String) {
        File(gitDir, "HEAD").writeText("ref: $target\n")
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --rerun --tests "de.nereide.strohhalm.domain.git.MirrorRepositoryTest"
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  app/build/test-results/testDebugUnitTest/TEST-de.nereide.strohhalm.domain.git.MirrorRepositoryTest.xml
```

Expected: PASS, `tests="6" ... failures="0" errors="0"`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/de/nereide/strohhalm/domain/git/MirrorRepository.kt \
        app/src/test/java/de/nereide/strohhalm/domain/git/MirrorRepositoryTest.kt
git commit -m "feat(git): maintain the bare mirror layout, pruning refs deleted upstream"
```

---

### Task 12: `ProtocolMirror`

The `GitMirror` implementation. This is the only class the rest of the app sees.

**Files:**
- Create: `app/src/main/java/de/nereide/strohhalm/domain/git/ProtocolMirror.kt`
- Modify: `app/src/main/java/de/nereide/strohhalm/domain/SyncError.kt`
- Test: `app/src/test/java/de/nereide/strohhalm/domain/git/ProtocolMirrorErrorTest.kt`

**Interfaces:**
- Consumes: everything above, plus `GitMirror`, `MirrorOutcome`, `MirrorProgress`, `SyncErrors`.
- Produces: `class ProtocolMirror(keyPairProvider: suspend () -> KeyPair, indexer: PackIndexer = KotlinPackIndexer(), io: CoroutineDispatcher = Dispatchers.IO) : GitMirror`

- [ ] **Step 1: Write the failing test**

```kotlin
package de.nereide.strohhalm.domain.git

import de.nereide.strohhalm.domain.SyncErrorCode
import de.nereide.strohhalm.domain.SyncErrors
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class ProtocolMirrorErrorTest {

    @Test
    fun `a band 3 message becomes a remote error carrying the server's words`() {
        val error = SyncErrors.fromException(SidebandException("repository not found"))
        assertEquals(SyncErrorCode.REMOTE_ERROR, error.code)
        assertEquals("the server said: repository not found", error.detail)
    }

    @Test
    fun `a pack checksum mismatch is local corruption, not an unknown fault`() {
        val error = SyncErrors.fromException(
            IOException("pack checksum mismatch: the transfer was corrupted")
        )
        assertEquals(SyncErrorCode.LOCAL_CORRUPT, error.code)
    }

    @Test
    fun `an old server is a remote error naming the protocol version`() {
        val error = SyncErrors.fromException(
            IOException("the server offered protocol version 0; Strohhalm requires version 2")
        )
        assertEquals(SyncErrorCode.REMOTE_ERROR, error.code)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk
./gradlew :app:testDebugUnitTest --rerun --tests "de.nereide.strohhalm.domain.git.ProtocolMirrorErrorTest"
```

Expected: FAIL — `Unresolved reference: SidebandException` is resolvable, but the first assertion fails because `SyncErrors` does not classify it yet.

- [ ] **Step 3a: Teach `SyncErrors` the engine's exceptions**

In `SyncError.kt`, add to the `classify` function, above the `NoRemoteRepositoryException` branch:

```kotlin
        t is de.nereide.strohhalm.domain.git.SidebandException ->
            SyncError(SyncErrorCode.REMOTE_ERROR, "the server said: ${t.serverMessage}")
```

and extend `classifyByMessage`'s `when` with two branches, before `else -> null`:

```kotlin
            "checksum mismatch" in lower ->
                SyncError(SyncErrorCode.LOCAL_CORRUPT, message)

            "protocol version" in lower ->
                SyncError(SyncErrorCode.REMOTE_ERROR, message)
```

- [ ] **Step 3b: Write `ProtocolMirror`**

```kotlin
package de.nereide.strohhalm.domain.git

import de.nereide.strohhalm.domain.GitMirror
import de.nereide.strohhalm.domain.MirrorOutcome
import de.nereide.strohhalm.domain.MirrorProgress
import de.nereide.strohhalm.domain.ProbeRejectedException
import de.nereide.strohhalm.domain.SyncErrorCode
import de.nereide.strohhalm.domain.SyncErrors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyPair
import java.time.Duration

/**
 * The mirror engine: protocol v2 over SSH, hash-agnostic.
 *
 * Replaces the JGit implementation. The `GitMirror` contract is unchanged, so
 * the scheduler, the foreground service, the notification policy and the UI are
 * untouched by the swap.
 *
 * This package is the only place in `src/main` allowed to import MINA SSHD, and
 * library exceptions are mapped to `SyncError` before they leave it.
 */
class ProtocolMirror(
    private val keyPairProvider: suspend () -> KeyPair,
    private val indexer: PackIndexer = KotlinPackIndexer(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : GitMirror {

    override suspend fun sync(
        remoteUrl: String,
        destination: File,
        pinnedFingerprint: String?,
        progress: MirrorProgress?,
    ): MirrorOutcome = withContext(io) {
        val keyPair = keyPairProvider()
        runCatching {
            // runInterruptible, not a plain call: the transfer blocks in socket
            // reads that no coroutine can unwind. Without the thread interrupt,
            // cancelling would clear the UI while bytes kept flowing.
            runInterruptible { mirror(remoteUrl, destination, pinnedFingerprint, keyPair, progress) }
        }.getOrElse { t ->
            // runCatching catches everything, including the CancellationException
            // that stopping the sync depends on.
            if (t is CancellationException) throw t
            MirrorOutcome.Failure(SyncErrors.fromException(t))
        }
    }

    private fun mirror(
        remoteUrl: String,
        destination: File,
        pinnedFingerprint: String?,
        keyPair: KeyPair,
        progress: MirrorProgress?,
    ): MirrorOutcome {
        val mirror = MirrorRepository(destination)

        // A clone that failed part-way leaves a directory with no HEAD. Removing
        // it is safe — without HEAD it was never a usable repository — and
        // without this a single failed attempt wedges the mirror permanently.
        if (destination.exists() && !mirror.exists()) destination.deleteRecursively()
        destination.parentFile?.mkdirs()

        UploadPackChannel(
            remote = GitRemote.parse(remoteUrl),
            keyPair = keyPair,
            pinnedFingerprint = pinnedFingerprint,
            capture = false,
            timeout = TIMEOUT,
        ).use { channel ->
            channel.open()
            channel.rejection?.let { return MirrorOutcome.Failure(it) }

            val protocol = UploadPackV2(channel.input, channel.output)
            val caps = protocol.readAdvertisement()
            val refs = protocol.lsRefs(caps)
            if (refs.isEmpty()) return MirrorOutcome.Success(0L, 0)

            if (!mirror.exists()) mirror.initialise(caps.objectHash)
            val haves = mirror.localRefs().values.distinct()
            val wants = refs.map { it.objectId }.distinct()

            val pack = protocol.fetch(caps, wants, haves) { line ->
                progress?.update(line, 0, 0)
            }
            indexer.consume(pack, caps.objectHash, mirror.objectsDir(), progress)

            mirror.writeRefs(refs)
            return MirrorOutcome.Success(sizeBytes(destination), mirror.refNames().size)
        }
    }

    /**
     * Completes a handshake and returns the host key fingerprint, running no git
     * operation beyond reading the advertisement.
     *
     * The key is read before authentication, so a fingerprint can be captured
     * even when the repository is unreachable. Reporting success on that basis
     * alone is what once let a broken remote be added as if it worked, so a
     * failure is still surfaced — enriched with whatever the server wrote to
     * stderr, which this engine can read on the same connection.
     */
    override suspend fun probeHostKey(remoteUrl: String): Result<String> = withContext(io) {
        val keyPair = keyPairProvider()
        runCatching {
            UploadPackChannel(
                remote = GitRemote.parse(remoteUrl),
                keyPair = keyPair,
                pinnedFingerprint = null,
                capture = true,
                timeout = TIMEOUT,
            ).use { channel ->
                runCatching {
                    channel.open()
                    UploadPackV2(channel.input, channel.output).readAdvertisement()
                }.onFailure { failure ->
                    val fingerprint = channel.observedHostKey ?: throw failure
                    val message = channel.stderrText()
                    if (message.isBlank()) throw failure
                    throw ProbeRejectedException(fingerprint, message, failure)
                }
                channel.observedHostKey ?: error("the server presented no host key")
            }
        }
    }

    override fun refNames(destination: File): List<String> =
        runCatching { MirrorRepository(destination).refNames() }.getOrDefault(emptyList())

    override fun sizeBytes(destination: File): Long {
        if (!destination.isDirectory) return 0L
        return destination.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private companion object {
        /**
         * Bounds connect, auth and channel open. The read path re-arms per read,
         * so a slow transfer is fine as long as bytes keep flowing — the failure
         * this guards is a silently dropped connection, which would otherwise
         * block a read forever.
         */
        val TIMEOUT: Duration = Duration.ofSeconds(300)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --rerun --tests "de.nereide.strohhalm.domain.git.ProtocolMirrorErrorTest"
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  app/build/test-results/testDebugUnitTest/TEST-de.nereide.strohhalm.domain.git.ProtocolMirrorErrorTest.xml
```

Expected: PASS, `tests="3" ... failures="0" errors="0"`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/de/nereide/strohhalm/domain/git/ProtocolMirror.kt \
        app/src/main/java/de/nereide/strohhalm/domain/SyncError.kt \
        app/src/test/java/de/nereide/strohhalm/domain/git/ProtocolMirrorErrorTest.kt
git commit -m "feat(git): add ProtocolMirror, the hash-agnostic GitMirror implementation"
```

---

### Task 13: End-to-end against a local SSH server, validated by real `git`

The strongest evidence available without a device: mirror a real SHA-256 repository over a real SSH connection, then let `git` itself judge the result.

**Files:**
- Create: `app/src/test/java/de/nereide/strohhalm/domain/git/MirrorEndToEndTest.kt`

**Interfaces:**
- Consumes: `ProtocolMirror`, and the local `SshServer` pattern from the existing `JGitMirrorDiagnosticHangTest`.
- Produces: nothing.

- [ ] **Step 1: Write the failing test**

```kotlin
package de.nereide.strohhalm.domain.git

import de.nereide.strohhalm.domain.MirrorOutcome
import kotlinx.coroutines.runBlocking
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator

/**
 * A full mirror over a real SSH connection to a local `git-upload-pack`.
 *
 * The remote repository is created with the system `git` in SHA-256 mode, and
 * the produced mirror is validated by `git fsck` and `git verify-pack` — real
 * git judging the one artifact this engine authors. Skipped where git is absent
 * or too old to support `--object-format=sha256`.
 */
class MirrorEndToEndTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var server: SshServer
    private lateinit var clientKey: KeyPair

    private fun git(vararg args: String, cwd: File): String {
        val process = ProcessBuilder("git", *args)
            .directory(cwd)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals("git ${args.joinToString(" ")} failed:\n$output", 0, process.waitFor())
        return output
    }

    private fun gitSupportsSha256(): Boolean = runCatching {
        val probe = temp.newFolder("probe")
        git("init", "--object-format=sha256", "--bare", "probe.git", cwd = probe)
        true
    }.getOrDefault(false)

    @Before
    fun startServer() {
        assumeTrue("needs git with SHA-256 support", gitSupportsSha256())

        clientKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

        server = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(
                File(temp.newFolder("hostkey"), "host.ser").toPath()
            )
            publickeyAuthenticator = org.apache.sshd.server.auth.pubkey.AcceptAllPublickeyAuthenticator.INSTANCE
            // git-upload-pack is executed for real; nothing about the transfer is faked.
            setCommandFactory(UploadPackCommandFactory())
            start()
        }
    }

    /**
     * Runs the real `git upload-pack` for whatever path the client asked for.
     *
     * Written out rather than using a stock shell factory because the command
     * arrives as `git-upload-pack '<path>'` — single-quoted, exactly as git
     * sends it — and a factory that splits on spaces would hand `'<path>'` to
     * git with the quotes still attached.
     */
    private class UploadPackCommandFactory : CommandFactory {
        override fun createCommand(channel: ChannelSession, command: String): Command =
            object : Command {
                private lateinit var out: OutputStream
                private lateinit var err: OutputStream
                private lateinit var input: InputStream
                private var callback: ExitCallback? = null
                private var process: Process? = null

                override fun setInputStream(value: InputStream) { input = value }
                override fun setOutputStream(value: OutputStream) { out = value }
                override fun setErrorStream(value: OutputStream) { err = value }
                override fun setExitCallback(value: ExitCallback) { callback = value }

                override fun start(session: ChannelSession, env: Environment) {
                    val path = command.substringAfter(' ').trim().trim('\'')
                    val started = ProcessBuilder("git", "upload-pack", path).start()
                    process = started
                    pump(input, started.outputStream, closeTarget = true)
                    pump(started.inputStream, out, closeTarget = false)
                    pump(started.errorStream, err, closeTarget = false)
                    Thread {
                        val code = started.waitFor()
                        runCatching { out.flush() }
                        callback?.onExit(code)
                    }.apply { isDaemon = true }.start()
                }

                override fun destroy(session: ChannelSession) {
                    process?.destroy()
                }

                private fun pump(from: InputStream, to: OutputStream, closeTarget: Boolean) {
                    Thread {
                        runCatching {
                            from.copyTo(to)
                            to.flush()
                            if (closeTarget) to.close()
                        }
                    }.apply { isDaemon = true }.start()
                }
            }
    }

    @After
    fun stopServer() {
        if (::server.isInitialized) server.stop(true)
    }

    /** Creates a SHA-256 remote with a few commits and returns its bare path. */
    private fun remoteRepository(): File {
        val work = temp.newFolder("remote-work")
        git("init", "--object-format=sha256", cwd = work)
        git("config", "user.email", "test@example.invalid", cwd = work)
        git("config", "user.name", "Test", cwd = work)
        repeat(5) { i ->
            File(work, "file$i.txt").writeText("line $i\n".repeat(i + 1))
            git("add", ".", cwd = work)
            git("commit", "-m", "commit $i", cwd = work)
        }
        git("tag", "v1", cwd = work)

        val bare = temp.newFolder("remote.git")
        git("clone", "--bare", "--object-format=sha256", work.absolutePath, bare.absolutePath, cwd = temp.root)
        return bare
    }

    @Test
    fun `a sha256 repository mirrors and passes git fsck`() = runBlocking {
        val remote = remoteRepository()
        val destination = File(temp.root, "mirror.git")
        val url = "ssh://test@127.0.0.1:${server.port}${remote.absolutePath}"

        val outcome = ProtocolMirror(keyPairProvider = { clientKey })
            .sync(url, destination, pinnedFingerprint = null)

        // No pin, so the first attempt must be refused rather than trusted.
        assertTrue("unpinned host must be refused", outcome is MirrorOutcome.Failure)

        // Pin what the server actually presented, then mirror for real.
        val fingerprint = ProtocolMirror(keyPairProvider = { clientKey })
            .probeHostKey(url)
            .getOrThrow()
        val second = ProtocolMirror(keyPairProvider = { clientKey })
            .sync(url, destination, pinnedFingerprint = fingerprint)

        assertTrue("mirror succeeded: $second", second is MirrorOutcome.Success)

        val fsck = git("fsck", "--strict", cwd = destination)
        assertTrue("git fsck reported problems:\n$fsck", fsck.isBlank())

        val refs = git("show-ref", cwd = destination)
        assertTrue("branch mirrored", refs.contains("refs/heads/"))
        assertTrue("tag mirrored", refs.contains("refs/tags/v1"))

        val idx = File(destination, "objects/pack").listFiles { f -> f.name.endsWith(".idx") }!!.single()
        git("verify-pack", "-v", idx.absolutePath, cwd = destination)
    }

    @Test
    fun `a second sync is incremental and still valid`() = runBlocking {
        val remote = remoteRepository()
        val destination = File(temp.root, "mirror2.git")
        val url = "ssh://test@127.0.0.1:${server.port}${remote.absolutePath}"
        val fingerprint = ProtocolMirror(keyPairProvider = { clientKey }).probeHostKey(url).getOrThrow()
        val mirror = ProtocolMirror(keyPairProvider = { clientKey })

        assertTrue(mirror.sync(url, destination, fingerprint) is MirrorOutcome.Success)
        assertTrue(mirror.sync(url, destination, fingerprint) is MirrorOutcome.Success)

        assertTrue(git("fsck", "--strict", cwd = destination).isBlank())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk
./gradlew :app:testDebugUnitTest --rerun --tests "de.nereide.strohhalm.domain.git.MirrorEndToEndTest"
```

Expected: FAIL. This is the integration point, so the first failure is informative rather than predictable — likely in `ls-refs` parsing, the fetch section header, or delta resolution. Read the failure, fix the component it points at, and re-run. Do **not** weaken an assertion to make it pass.

- [ ] **Step 3: Fix whatever the test exposes**

Work through failures one at a time, in the component that owns the behaviour, adding a unit test there for anything that was not covered. The end-to-end test is the gate; the unit tests are where each fix is pinned.

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --rerun --tests "de.nereide.strohhalm.domain.git.MirrorEndToEndTest"
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  app/build/test-results/testDebugUnitTest/TEST-de.nereide.strohhalm.domain.git.MirrorEndToEndTest.xml
```

Expected: PASS, `tests="2" ... failures="0" errors="0"`. If `skipped="2"`, git is missing or too old — install a git with `--object-format=sha256` and re-run. A skipped run is **not** evidence.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/de/nereide/strohhalm/domain/git/MirrorEndToEndTest.kt \
        app/src/main/java/de/nereide/strohhalm/domain/git/
git commit -m "test(git): mirror a real sha256 repo over ssh and validate with git fsck"
```

---

### Task 14: Fetch into a mirror the JGit engine created

Existing users have SHA-1 mirrors on disk. They must keep refreshing with no migration.

**Files:**
- Create: `app/src/test/java/de/nereide/strohhalm/domain/git/LegacyMirrorFetchTest.kt`

**Interfaces:**
- Consumes: `ProtocolMirror`, `MirrorRepository`, JGit (as a fixture builder).
- Produces: nothing.

- [ ] **Step 1: Write the failing test**

```kotlin
package de.nereide.strohhalm.domain.git

import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A mirror created by the old JGit engine must be readable by the new one.
 *
 * The risk is not the objects — those are ordinary — but the refs: JGit may
 * leave them loose rather than packed, and an engine that read only `packed-refs`
 * would offer no haves and silently re-download everything on every sync.
 */
class LegacyMirrorFetchTest {

    @get:Rule val temp = TemporaryFolder()

    private fun jgitCreatedMirror(): File {
        val source = temp.newFolder("source")
        Git.init().setDirectory(source).call().use { git ->
            File(source, "a.txt").writeText("hello\n")
            git.add().addFilepattern("a.txt").call()
            git.commit().setMessage("first").setSign(false).call()
        }
        val mirror = File(temp.root, "legacy.git")
        Git.cloneRepository()
            .setURI(source.toURI().toString())
            .setDirectory(mirror)
            .setBare(true)
            .setMirror(true)
            .call()
            .close()
        return mirror
    }

    @Test
    fun `refs written by JGit are visible to MirrorRepository`() {
        val mirror = MirrorRepository(jgitCreatedMirror())

        assertTrue("recognised as an existing mirror", mirror.exists())
        assertEquals(ObjectHash.SHA1, mirror.objectHash())
        assertTrue(
            "a JGit-created mirror's refs must be readable: ${mirror.refNames()}",
            mirror.refNames().any { it.startsWith("refs/heads/") },
        )
        assertTrue("every ref has an id", mirror.localRefs().values.all { it.length == 40 })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk
./gradlew :app:testDebugUnitTest --rerun --tests "de.nereide.strohhalm.domain.git.LegacyMirrorFetchTest"
```

Expected: FAIL if `localRefs()` misses loose refs — which is the specific defect this task exists to catch.

- [ ] **Step 3: Fix `MirrorRepository.localRefs` if needed**

The implementation in Task 11 already walks `refs/` for loose entries. If this test fails, the walk is wrong — most likely the relative path is being computed from the wrong root, producing a name like `heads/main` instead of `refs/heads/main`. Fix it there, not here.

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --rerun --tests "de.nereide.strohhalm.domain.git.LegacyMirrorFetchTest"
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  app/build/test-results/testDebugUnitTest/TEST-de.nereide.strohhalm.domain.git.LegacyMirrorFetchTest.xml
```

Expected: PASS, `tests="1" ... failures="0" errors="0"`.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/de/nereide/strohhalm/domain/git/LegacyMirrorFetchTest.kt \
        app/src/main/java/de/nereide/strohhalm/domain/git/MirrorRepository.kt
git commit -m "test(git): keep JGit-created mirrors readable by the new engine"
```

---

### Task 15: Wire the engine in, then remove JGit

Two halves, one commit each: switch `AppContainer` over, verify on hardware, then delete.

**Files:**
- Modify: `app/src/main/java/de/nereide/strohhalm/AppContainer.kt`
- Delete: `app/src/main/java/de/nereide/strohhalm/domain/JGitMirror.kt`
- Delete: `app/src/main/java/de/nereide/strohhalm/domain/AndroidSystemReader.kt`
- Delete: `app/src/test/java/de/nereide/strohhalm/domain/JGitMirrorDiagnosticHangTest.kt`
- Delete: `app/src/test/java/de/nereide/strohhalm/domain/JGitMirrorProgressTest.kt`
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`, `CLAUDE.md`

**Interfaces:**
- Consumes: `ProtocolMirror`.
- Produces: nothing.

- [ ] **Step 1: Switch `AppContainer` to the new engine**

In `app/src/main/java/de/nereide/strohhalm/AppContainer.kt`, replace the import on line 12:

```kotlin
import de.nereide.strohhalm.domain.JGitMirror
```

with:

```kotlin
import de.nereide.strohhalm.domain.git.ProtocolMirror
```

and change the property at lines 55–57 from:

```kotlin
    override val gitMirror: GitMirror by lazy {
        JGitMirror(keyPairProvider = { sshKeyStore.keyPair() })
    }
```

to:

```kotlin
    override val gitMirror: GitMirror by lazy {
        ProtocolMirror(keyPairProvider = { sshKeyStore.keyPair() })
    }
```

The `GitMirror` interface type on line 26 and everything else in the file stay as they are — that is the point of the seam.

- [ ] **Step 2: Build and run the whole suite**

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk
./gradlew assembleDebug
./gradlew :app:testDebugUnitTest --rerun
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  app/build/test-results/testDebugUnitTest/TEST-*.xml
```

Expected: `BUILD SUCCESSFUL`, every class reporting `failures="0" errors="0"`.

- [ ] **Step 3: Verify on hardware — this cannot be automated or skipped**

```bash
./gradlew installDebug
```

On the device, confirm all four:

1. A SHA-256 remote mirrors end to end.
2. `git clone` from the mirror folder, on a computer, restores a working tree.
3. The 44 MiB / 72k-object repository completes — the benchmark that has never passed. Watch memory; this is where a non-streaming indexer would fail.
4. Cancelling a running sync stops it, including via the notification's Stop action.

Record the outcome in `CLAUDE.md` under "Verified on real hardware". Do not mark this step done on the strength of unit tests.

- [ ] **Step 4: Commit the switch**

```bash
git add app/src/main/java/de/nereide/strohhalm/AppContainer.kt CLAUDE.md
git commit -m "feat(sync): mirror through the hash-agnostic engine"
```

- [ ] **Step 5: Remove JGit**

Only after step 3 passes on hardware.

```bash
git rm app/src/main/java/de/nereide/strohhalm/domain/JGitMirror.kt \
       app/src/main/java/de/nereide/strohhalm/domain/AndroidSystemReader.kt \
       app/src/test/java/de/nereide/strohhalm/domain/JGitMirrorDiagnosticHangTest.kt \
       app/src/test/java/de/nereide/strohhalm/domain/JGitMirrorProgressTest.kt
```

In `app/build.gradle.kts`, remove `implementation(libs.jgit)` and `implementation(libs.jgit.ssh.apache)`. **Keep `testImplementation(libs.jgit)`** — Tasks 9 and 14 use JGit to build fixtures, which the project explicitly permits.

In `gradle/libs.versions.toml`, leave the `jgit` entries in place; they are still needed for the test-only dependency.

Update `CLAUDE.md`:
- The rule "`JGitMirror.kt` is the only file in `src/main` that may import JGit or MINA SSHD" becomes "the `domain/git` package is the only place in `src/main` that may import MINA SSHD; JGit is a test-only dependency."
- Keep the `isMinifyEnabled = false` entry, narrowing its reason to MINA SSHD's `ServiceLoader` use.
- Keep the `SshdEnvironment.install` entry unchanged — it is still required, for the same reason.
- Remove the "Never read a remote stream to EOF without a deadline" entry and replace it with a note that the engine reads stderr on the live channel, which is what made the diagnostic probe unnecessary.

Then:

```bash
./gradlew assembleDebug
./gradlew :app:testDebugUnitTest --rerun
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  app/build/test-results/testDebugUnitTest/TEST-*.xml
```

Expected: `BUILD SUCCESSFUL`, all zero failures.

- [ ] **Step 6: Commit the removal**

```bash
git add -A
git commit -m "refactor(git): drop JGit from the app, keeping it as a test fixture builder"
```

---

## Verification not covered by this plan

Two things must be checked by hand and cannot be faked:

- **The live SHA-256 remote.** Write a `Live*Test.kt` in the working tree — it is gitignored, and must never be committed, since it names a real remote and an operator's own key. Run with `LIVE_CLONE=1`.
- **The device checks in Task 15, step 3.** In particular the 44 MiB repository, which has never completed a mirror on any engine.
