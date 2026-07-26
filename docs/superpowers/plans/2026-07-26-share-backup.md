# Share a backup as a zip — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give a mirrored repository a Share action that packs it into a zip, verifies it, and hands it to the system share sheet.

**Architecture:** A pure-JVM archiver zips the bare mirror directory reproducibly, writing a sidecar that records both the archive's own checksum and a fingerprint of the mirror's refs. The sidecar answers two independent questions — is this file intact, and is it current — so an abandoned share can be resumed from a finished archive instead of rebuilt. The archive is served to other apps through a `FileProvider` that reports a clean display name.

**Tech Stack:** Kotlin, `java.util.zip.ZipOutputStream`, `java.security.MessageDigest`, `java.time`, AndroidX `FileProvider`, Jetpack Compose, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-07-26-share-backup-design.md`

## Global Constraints

Every task's requirements implicitly include these. Violating one is a defect even if tests pass.

- Package `de.nereide.strohhalm`. `minSdk 26`, `compileSdk`/`targetSdk 35`, Java 17.
- **Every Gradle invocation must set `JAVA_HOME`:** `export JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk`. A bare `./gradlew` dies parsing the Java 25 version string before any task runs.
- **`UP-TO-DATE` is not evidence.** Use `--rerun` and read the per-class counts from the XML.
- **`isMinifyEnabled = false`** stays. MINA SSHD resolves implementations through `ServiceLoader` and reflection.
- **Android's `InputStream` gained `readNBytes`/`skipNBytes` only at API 33.** `minSdk` is 26, so neither may appear in `src/main`. They are fine in JVM tests.
- **No hardcoded user-facing strings** in Compose. Everything goes through `res/values/strings.xml`.
- **The private key never leaves internal storage**, and must never end up inside an archive. The mirror folder does not contain it; nothing in this feature may change that.
- **Never push, commit, or write to a remote.** This feature only reads the mirror folder.
- **Kotlin block comments nest.** Never write a literal `/*` inside a comment.
- **`versionName` lives only in `version.properties`.** Never hand-edit `versionCode`.
- **Dependencies go in `gradle/libs.versions.toml`.** Never inline a coordinate in `app/build.gradle.kts`. This feature needs no new dependency.
- **TDD strictly:** write the failing test, run it and see it fail, implement, run it and see it pass, commit.

## File Structure

New production code lives in `de.nereide.strohhalm.domain.archive`, except the Android provider.

| File | Responsibility |
| --- | --- |
| `domain/archive/RefFingerprint.kt` | Digest of the ref list; the `toHex` helper |
| `domain/archive/MirrorArchiver.kt` | `MirrorArchiver`, `ArchiveProgress`, `ArchiveResult`, `ZipMirrorArchiver` |
| `domain/archive/ArchiveNames.kt` | Filename construction and parsing, in one place |
| `domain/archive/ArchiveStore.kt` | Cache directory, sidecar, verify, atomic rename, prune |
| `domain/archive/ArchiveSpace.kt` | Free-space precheck against `StorageManager` |
| `ArchiveFileProvider.kt` | `FileProvider` subclass reporting a clean display name |
| `ArchiveMaintenance.kt` | Application-scoped pruning on sync completion and memory trim |

Tests mirror this under `app/src/test/java/de/nereide/strohhalm/domain/archive/`.

Modified: `AndroidManifest.xml`, `res/xml/file_paths.xml` (new), `res/values/strings.xml`, `AppContainer.kt`, `StrohhalmApp.kt`, `ui/detail/RepoDetailViewModel.kt`, `ui/detail/RepoDetailScreen.kt`.

### Existing API this plan consumes

Verified against the current sources. Do not guess these.

```kotlin
// domain/git/MirrorRepository.kt
class MirrorRepository(val gitDir: File) {
    fun exists(): Boolean
    fun localRefs(): Map<String, String>   // refName -> objectId
}

// domain/GitMirror.kt
fun interface MirrorProgress { fun update(task: String, completed: Int, total: Int) }

// domain/SyncError.kt
data class SyncError(val code: SyncErrorCode, val detail: String? = null, val diagnostic: String? = null)
enum class SyncErrorCode { NO_NETWORK, LOW_STORAGE, PERMISSION_LOST, AUTH_FAILED,
    HOST_KEY_MISMATCH, HOST_UNREACHABLE, REMOTE_ERROR, LOCAL_CORRUPT, INTERRUPTED, CANCELLED, UNKNOWN }

// domain/SyncRunner.kt
class SyncRunner {
    val running: StateFlow<Boolean>
    val progress: StateFlow<SyncProgress?>
    fun launchSyncOne(id: Long): Job
    fun cancel()
}

// data/Repo.kt
data class Repo(val id: Long, val displayName: String, val remoteUrl: String, val localPath: String,
    val lastSyncAt: Long?, val sizeBytes: Long, /* … */)

// data/RepoSlug.kt
object RepoSlug { fun fromRemoteUrl(url: String): String }
```

---

### Task 1: `RefFingerprint`

The cache key. A digest over the ref list, which is what makes "has the mirror changed?" answerable without reading a single object.

**Files:**
- Create: `app/src/main/java/de/nereide/strohhalm/domain/archive/RefFingerprint.kt`
- Test: `app/src/test/java/de/nereide/strohhalm/domain/archive/RefFingerprintTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `object RefFingerprint { fun of(refs: Map<String, String>): String }` returning 64 lowercase hex characters, and `internal fun ByteArray.toHex(): String` used by later tasks in this package.

- [ ] **Step 1: Write the failing test**

```kotlin
package de.nereide.strohhalm.domain.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RefFingerprintTest {

    private val sha1Refs = mapOf(
        "refs/heads/main" to "a".repeat(40),
        "refs/tags/v1" to "b".repeat(40),
    )

    private val sha256Refs = mapOf(
        "refs/heads/main" to "a".repeat(64),
        "refs/tags/v1" to "b".repeat(64),
    )

    @Test
    fun `the fingerprint is 64 hex characters whatever the object format`() {
        assertEquals(64, RefFingerprint.of(sha1Refs).length)
        assertEquals(64, RefFingerprint.of(sha256Refs).length)
        assertTrue(RefFingerprint.of(sha256Refs).all { it in "0123456789abcdef" })
    }

    @Test
    fun `map order does not change the fingerprint`() {
        val reversed = linkedMapOf(
            "refs/tags/v1" to "b".repeat(64),
            "refs/heads/main" to "a".repeat(64),
        )
        assertEquals(RefFingerprint.of(sha256Refs), RefFingerprint.of(reversed))
    }

    /** The case the whole cache key exists for: a tag arrives, HEAD does not move. */
    @Test
    fun `adding a tag changes the fingerprint`() {
        val withTag = sha256Refs + ("refs/tags/v2" to "c".repeat(64))
        assertNotEquals(RefFingerprint.of(sha256Refs), RefFingerprint.of(withTag))
    }

    @Test
    fun `moving a ref changes the fingerprint`() {
        val moved = sha256Refs + ("refs/heads/main" to "d".repeat(64))
        assertNotEquals(RefFingerprint.of(sha256Refs), RefFingerprint.of(moved))
    }

    /** A name and an id must not be interchangeable; without a separator they would be. */
    @Test
    fun `a ref name and an id cannot be confused for one another`() {
        assertNotEquals(
            RefFingerprint.of(mapOf("refs/heads/ab" to "c".repeat(64))),
            RefFingerprint.of(mapOf("refs/heads/a" to "bc".padEnd(64, 'c'))),
        )
    }

    @Test
    fun `an empty ref list has a stable fingerprint`() {
        assertEquals(RefFingerprint.of(emptyMap()), RefFingerprint.of(emptyMap()))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk
./gradlew :app:testDebugUnitTest --rerun --tests "de.nereide.strohhalm.domain.archive.RefFingerprintTest"
```

Expected: FAIL — `Unresolved reference 'RefFingerprint'` at compile time.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package de.nereide.strohhalm.domain.archive

import java.security.MessageDigest

/**
 * A digest of a mirror's entire ref list — the answer to "has this repository
 * changed since the archive was built?".
 *
 * It covers every ref rather than HEAD alone, because a sync that adds only a
 * tag or a side branch leaves HEAD where it was. Keyed on HEAD, such a sync
 * would leave a stale archive that still verifies clean, and sharing a backup
 * silently missing refs is the worst thing this feature could do.
 *
 * The digest is always SHA-256 regardless of the repository's own object
 * format: it identifies a state of the ref list, not a git object.
 */
object RefFingerprint {

    fun of(refs: Map<String, String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        // Sorted, so an unordered map cannot change the answer. The space and
        // newline are load-bearing: without a separator, "ab" + "c" and
        // "a" + "bc" would hash identically.
        refs.entries
            .map { (name, id) -> "$id $name" }
            .sorted()
            .forEach {
                digest.update(it.toByteArray(Charsets.UTF_8))
                digest.update('\n'.code.toByte())
            }
        return digest.digest().toHex()
    }
}

internal fun ByteArray.toHex(): String = buildString(size * 2) {
    this@toHex.forEach { append(HEX[(it.toInt() shr 4) and 0xf]).append(HEX[it.toInt() and 0xf]) }
}

private const val HEX = "0123456789abcdef"
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --rerun --tests "de.nereide.strohhalm.domain.archive.RefFingerprintTest"
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  app/build/test-results/testDebugUnitTest/TEST-de.nereide.strohhalm.domain.archive.RefFingerprintTest.xml
```

Expected: PASS, `tests="6" skipped="0" failures="0" errors="0"`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/de/nereide/strohhalm/domain/archive/RefFingerprint.kt \
        app/src/test/java/de/nereide/strohhalm/domain/archive/RefFingerprintTest.kt
git commit -m "feat(archive): fingerprint a mirror's ref list"
```

---

### Task 2: `MirrorArchiver`

Zips the bare repository. Reproducible, interruptible, and deliberately not re-compressing pack files.

**Files:**
- Create: `app/src/main/java/de/nereide/strohhalm/domain/archive/MirrorArchiver.kt`
- Test: `app/src/test/java/de/nereide/strohhalm/domain/archive/ZipMirrorArchiverTest.kt`

**Interfaces:**
- Consumes: `toHex` (Task 1).
- Produces:
  - `fun interface ArchiveProgress { fun update(task: String, completed: Int, total: Int) }`
  - `data class ArchiveResult(val sha256: String, val bytes: Long, val entries: Int)`
  - `interface MirrorArchiver { fun archive(gitDir: File, target: File, progress: ArchiveProgress?): ArchiveResult }`
  - `class ZipMirrorArchiver : MirrorArchiver`

- [ ] **Step 1: Write the failing test**

```kotlin
package de.nereide.strohhalm.domain.archive

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

class ZipMirrorArchiverTest {

    @get:Rule val temp = TemporaryFolder()

    private val archiver = ZipMirrorArchiver()

    /** A directory shaped like a bare mirror, including a fake pack. */
    private fun mirror(): File = temp.newFolder("yamiro.git").apply {
        File(this, "HEAD").writeText("ref: refs/heads/main\n")
        File(this, "config").writeText("[core]\n\tbare = true\n")
        File(this, "packed-refs").writeText("a".repeat(64) + " refs/heads/main\n")
        File(this, "objects/pack").mkdirs()
        File(this, "objects/pack/pack-abc.pack").writeBytes(ByteArray(4096) { (it % 251).toByte() })
        File(this, "objects/pack/pack-abc.idx").writeBytes(ByteArray(512) { (it % 127).toByte() })
    }

    @Test
    fun `every file is present under a single top-level directory`() {
        val target = File(temp.root, "out.zip")
        val result = archiver.archive(mirror(), target, null)

        ZipFile(target).use { zip ->
            val names = zip.entries().toList().map { it.name }.sorted()
            assertEquals(
                listOf(
                    "yamiro.git/HEAD",
                    "yamiro.git/config",
                    "yamiro.git/objects/pack/pack-abc.idx",
                    "yamiro.git/objects/pack/pack-abc.pack",
                    "yamiro.git/packed-refs",
                ),
                names,
            )
            assertEquals(5, result.entries)
        }
    }

    @Test
    fun `contents round-trip byte for byte`() {
        val source = mirror()
        val target = File(temp.root, "out.zip")
        archiver.archive(source, target, null)

        ZipFile(target).use { zip ->
            val pack = assertNotNull(zip.getEntry("yamiro.git/objects/pack/pack-abc.pack"))
            assertArrayEquals(
                File(source, "objects/pack/pack-abc.pack").readBytes(),
                zip.getInputStream(pack).readBytes(),
            )
            assertEquals(
                "ref: refs/heads/main\n",
                zip.getInputStream(zip.getEntry("yamiro.git/HEAD")).readBytes().decodeToString(),
            )
        }
    }

    /**
     * The checksum must identify the content, not the moment of building —
     * otherwise a cached archive could never be matched against its sidecar.
     */
    @Test
    fun `the same input produces the same bytes and the same checksum`() {
        val source = mirror()
        val first = File(temp.root, "a.zip")
        val second = File(temp.root, "b.zip")

        val one = archiver.archive(source, first, null)
        val two = archiver.archive(source, second, null)

        assertEquals(one.sha256, two.sha256)
        assertArrayEquals(first.readBytes(), second.readBytes())
    }

    @Test
    fun `the reported checksum is the checksum of the file on disk`() {
        val target = File(temp.root, "out.zip")
        val result = archiver.archive(mirror(), target, null)

        val actual = MessageDigest.getInstance("SHA-256").digest(target.readBytes()).toHex()
        assertEquals(actual, result.sha256)
        assertEquals(target.length(), result.bytes)
    }

    @Test
    fun `progress counts every entry`() {
        val seen = mutableListOf<Pair<Int, Int>>()
        archiver.archive(mirror(), File(temp.root, "out.zip")) { _, completed, total ->
            seen += completed to total
        }
        assertEquals(5, seen.size)
        assertEquals(5 to 5, seen.last())
    }

    /** Cancellation is a thread interrupt, exactly as in the mirror engine. */
    @Test
    fun `an interrupted archive throws and leaves no complete file behind`() {
        val target = File(temp.root, "out.zip")
        var thrown: Throwable? = null

        val worker = Thread {
            try {
                archiver.archive(mirror(), target) { _, _, _ ->
                    Thread.currentThread().interrupt()
                }
            } catch (t: Throwable) {
                thrown = t
            }
        }
        worker.start()
        worker.join()

        assertTrue("expected InterruptedException, got $thrown", thrown is InterruptedException)
        assertFalse("a cancelled build must not leave its output", target.exists())
    }

    /** Packs are already deflated; re-deflating them is pure cost. */
    @Test
    fun `pack files are stored rather than compressed`() {
        val target = File(temp.root, "out.zip")
        archiver.archive(mirror(), target, null)

        ZipFile(target).use { zip ->
            val pack = zip.getEntry("yamiro.git/objects/pack/pack-abc.pack")
            assertEquals(pack.size, pack.compressedSize)
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk
./gradlew :app:testDebugUnitTest --rerun --tests "de.nereide.strohhalm.domain.archive.ZipMirrorArchiverTest"
```

Expected: FAIL — `Unresolved reference 'ZipMirrorArchiver'`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package de.nereide.strohhalm.domain.archive

import java.io.File
import java.io.FileOutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Progress while packing. Mirrors `MirrorProgress` so one bar renders both. */
fun interface ArchiveProgress {
    fun update(task: String, completed: Int, total: Int)
}

data class ArchiveResult(
    val sha256: String,
    val bytes: Long,
    val entries: Int,
)

interface MirrorArchiver {
    /**
     * Packs [gitDir] into [target], returning the archive's own checksum.
     *
     * Throws [InterruptedException] if the calling thread is interrupted, and
     * deletes [target] before doing so — a half-written archive that survived
     * would be indistinguishable from a finished one.
     */
    fun archive(gitDir: File, target: File, progress: ArchiveProgress?): ArchiveResult
}

class ZipMirrorArchiver : MirrorArchiver {

    override fun archive(gitDir: File, target: File, progress: ArchiveProgress?): ArchiveResult {
        // Sorted, because directory iteration order is not guaranteed and the
        // archive has to be reproducible for its checksum to mean anything.
        val entries = gitDir.walkTopDown()
            .filter { it.isFile }
            .map { it to it.relativeTo(gitDir).path.replace(File.separatorChar, '/') }
            .sortedBy { (_, relative) -> relative }
            .toList()

        val root = gitDir.name
        val digest = MessageDigest.getInstance("SHA-256")

        try {
            DigestOutputStream(FileOutputStream(target).buffered(), digest).use { sink ->
                ZipOutputStream(sink).use { zip ->
                    entries.forEachIndexed { index, (file, relative) ->
                        // Polled per entry: the work is blocking file I/O that no
                        // coroutine can unwind on its own.
                        if (Thread.currentThread().isInterrupted) {
                            throw InterruptedException("archive cancelled")
                        }
                        // Pack files arrive already deflated. Compressing them
                        // again costs real seconds on a phone and saves nothing.
                        zip.setLevel(
                            if (relative.endsWith(".pack") || relative.endsWith(".idx")) {
                                Deflater.NO_COMPRESSION
                            } else {
                                Deflater.DEFAULT_COMPRESSION
                            }
                        )
                        zip.putNextEntry(ZipEntry("$root/$relative").apply { time = FIXED_TIME })
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                        progress?.update("Packing", index + 1, entries.size)
                    }
                }
            }
        } catch (t: Throwable) {
            target.delete()
            throw t
        }

        return ArchiveResult(
            sha256 = digest.digest().toHex(),
            bytes = target.length(),
            entries = entries.size,
        )
    }

    private companion object {
        /**
         * A fixed entry timestamp, so the same mirror always yields the same
         * bytes. Only stability on one device matters — the checksum is never
         * compared against one computed elsewhere.
         *
         * 2000-01-01T00:00:00Z, comfortably inside the DOS timestamp range a
         * zip entry can represent, whose epoch is 1980.
         */
        const val FIXED_TIME = 946_684_800_000L
    }
}
```

Note on `setLevel`: it applies to entries written after the call, which is why it precedes `putNextEntry`. `NO_COMPRESSION` still uses the DEFLATE *method*, so `compressedSize` equals `size` for those entries without the manual CRC bookkeeping that `ZipEntry.STORED` would demand.

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --rerun --tests "de.nereide.strohhalm.domain.archive.ZipMirrorArchiverTest"
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  app/build/test-results/testDebugUnitTest/TEST-de.nereide.strohhalm.domain.archive.ZipMirrorArchiverTest.xml
```

Expected: PASS, `tests="7" skipped="0" failures="0" errors="0"`.

If `pack files are stored rather than compressed` fails with `compressedSize` slightly exceeding `size`, the deflate wrapper is adding block headers — confirm `setLevel` is called *before* `putNextEntry`, not after.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/de/nereide/strohhalm/domain/archive/MirrorArchiver.kt \
        app/src/test/java/de/nereide/strohhalm/domain/archive/ZipMirrorArchiverTest.kt
git commit -m "feat(archive): pack a mirror reproducibly, storing packs uncompressed"
```

---

### Task 3: `ArchiveNames`

Filename construction and parsing, isolated so both the store and the content provider agree on one format.

**Files:**
- Create: `app/src/main/java/de/nereide/strohhalm/domain/archive/ArchiveNames.kt`
- Test: `app/src/test/java/de/nereide/strohhalm/domain/archive/ArchiveNamesTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `object ArchiveNames` with `fun archive(slug: String, lastSyncAt: Long, fingerprint: String, zone: ZoneId = ZoneId.systemDefault()): String`, `fun sidecar(archiveName: String): String`, `fun part(archiveName: String): String`, `fun matches(fileName: String, slug: String, fingerprint: String): Boolean`, `fun displayName(archiveName: String): String`, and `const val SHORT = 12`.

- [ ] **Step 1: Write the failing test**

```kotlin
package de.nereide.strohhalm.domain.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ArchiveNamesTest {

    private val utc = ZoneId.of("UTC")
    private val fingerprint = "4f2a91c07b3e59d0a8c1e77b0f3d2a6c9e5b18f4c07a3e2d1b6f8095c4a7d3e21"

    /** 2026-07-26T10:00:00Z */
    private val synced = 1_785_060_000_000L

    @Test
    fun `an archive is named slug, date and the fingerprint prefix`() {
        assertEquals(
            "yamiro-2026-07-26-4f2a91c07b3e.zip",
            ArchiveNames.archive("yamiro", synced, fingerprint, utc),
        )
    }

    @Test
    fun `the sidecar and part files hang off the archive name`() {
        val name = ArchiveNames.archive("yamiro", synced, fingerprint, utc)
        assertEquals("yamiro-2026-07-26-4f2a91c07b3e.zip.sha256", ArchiveNames.sidecar(name))
        assertEquals("yamiro-2026-07-26-4f2a91c07b3e.zip.part", ArchiveNames.part(name))
    }

    /**
     * Lookup matches on slug and fingerprint only. The date must be ignored:
     * lastSyncAt advances on every successful sync, including the common one
     * where nothing moved, so a date in the key would rebuild daily.
     */
    @Test
    fun `matching ignores the date`() {
        assertTrue(ArchiveNames.matches("yamiro-2026-07-26-4f2a91c07b3e.zip", "yamiro", fingerprint))
        assertTrue(ArchiveNames.matches("yamiro-2019-01-01-4f2a91c07b3e.zip", "yamiro", fingerprint))
    }

    @Test
    fun `matching rejects another repository or another fingerprint`() {
        assertFalse(ArchiveNames.matches("other-2026-07-26-4f2a91c07b3e.zip", "yamiro", fingerprint))
        assertFalse(ArchiveNames.matches("yamiro-2026-07-26-000000000000.zip", "yamiro", fingerprint))
        assertFalse(ArchiveNames.matches("yamiro-2026-07-26-4f2a91c07b3e.zip.part", "yamiro", fingerprint))
        assertFalse(ArchiveNames.matches("yamiro-2026-07-26-4f2a91c07b3e.zip.sha256", "yamiro", fingerprint))
    }

    /** A slug containing digits and dashes must not confuse the parser. */
    @Test
    fun `a dashed slug survives round-tripping`() {
        val name = ArchiveNames.archive("my-notes-2", synced, fingerprint, utc)
        assertEquals("my-notes-2-2026-07-26-4f2a91c07b3e.zip", name)
        assertTrue(ArchiveNames.matches(name, "my-notes-2", fingerprint))
        assertFalse(ArchiveNames.matches(name, "my-notes", fingerprint))
    }

    /** The recipient sees no hash: that is the point of the provider override. */
    @Test
    fun `the display name drops the fingerprint`() {
        assertEquals(
            "yamiro-2026-07-26.zip",
            ArchiveNames.displayName("yamiro-2026-07-26-4f2a91c07b3e.zip"),
        )
    }

    @Test
    fun `an unrecognised name is its own display name`() {
        assertEquals("something.zip", ArchiveNames.displayName("something.zip"))
    }

    @Test
    fun `a repository never synced is dated from the epoch rather than crashing`() {
        assertEquals(
            "yamiro-1970-01-01-4f2a91c07b3e.zip",
            ArchiveNames.archive("yamiro", 0L, fingerprint, utc),
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk
./gradlew :app:testDebugUnitTest --rerun --tests "de.nereide.strohhalm.domain.archive.ArchiveNamesTest"
```

Expected: FAIL — `Unresolved reference 'ArchiveNames'`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package de.nereide.strohhalm.domain.archive

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The one place that knows what an archive is called.
 *
 * A name carries three things: which repository, when the copy was taken, and
 * which ref state it holds. Only the third takes part in lookup — the date is
 * there for whoever receives the file.
 */
object ArchiveNames {

    /** How much of the fingerprint appears in the filename. */
    const val SHORT = 12

    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun archive(
        slug: String,
        lastSyncAt: Long,
        fingerprint: String,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val date = DATE.format(Instant.ofEpochMilli(lastSyncAt).atZone(zone).toLocalDate())
        return "$slug-$date-${fingerprint.take(SHORT)}.zip"
    }

    fun sidecar(archiveName: String): String = "$archiveName.sha256"

    fun part(archiveName: String): String = "$archiveName.part"

    /**
     * Whether [fileName] is an archive of [slug] holding [fingerprint]'s ref
     * state — a *hint*, narrowing a directory to one candidate. The sidecar
     * decides; a twelve-character prefix is an index, not a proof.
     */
    fun matches(fileName: String, slug: String, fingerprint: String): Boolean {
        if (!fileName.endsWith(".zip")) return false
        val stem = fileName.removeSuffix(".zip")
        return stem.startsWith("$slug-") &&
            stem.endsWith("-${fingerprint.take(SHORT)}") &&
            // Guards a slug that is a prefix of another: "my-notes" must not
            // match "my-notes-2-…". What is left between the two anchors has to
            // be exactly the date.
            stem.removePrefix("$slug-").removeSuffix("-${fingerprint.take(SHORT)}").length == DATE_LENGTH
    }

    /** The name the recipient sees: no hash, because it means nothing to them. */
    fun displayName(archiveName: String): String {
        if (!archiveName.endsWith(".zip")) return archiveName
        val stem = archiveName.removeSuffix(".zip")
        val short = stem.takeLast(SHORT)
        if (short.length < SHORT || !short.all { it in "0123456789abcdef" }) return archiveName
        if (stem.length < SHORT + 1 || stem[stem.length - SHORT - 1] != '-') return archiveName
        return stem.dropLast(SHORT + 1) + ".zip"
    }

    private const val DATE_LENGTH = 10
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --rerun --tests "de.nereide.strohhalm.domain.archive.ArchiveNamesTest"
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  app/build/test-results/testDebugUnitTest/TEST-de.nereide.strohhalm.domain.archive.ArchiveNamesTest.xml
```

Expected: PASS, `tests="8" skipped="0" failures="0" errors="0"`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/de/nereide/strohhalm/domain/archive/ArchiveNames.kt \
        app/src/test/java/de/nereide/strohhalm/domain/archive/ArchiveNamesTest.kt
git commit -m "feat(archive): name archives by repository, date and ref state"
```

---

### Task 4: `ArchiveStore`

The cache: look up, verify, build atomically, prune.

**Files:**
- Create: `app/src/main/java/de/nereide/strohhalm/domain/archive/ArchiveStore.kt`
- Test: `app/src/test/java/de/nereide/strohhalm/domain/archive/ArchiveStoreTest.kt`

**Interfaces:**
- Consumes: `RefFingerprint`, `toHex` (Task 1); `MirrorArchiver`, `ArchiveProgress`, `ZipMirrorArchiver` (Task 2); `ArchiveNames` (Task 3).
- Produces:
  - `data class Sidecar(val refFingerprint: String, val archiveSha256: String, val archiveName: String)` with `fun render(): String` and `companion object { fun parse(text: String): Sidecar? }`
  - `class ArchiveStore(private val root: File, private val archiver: MirrorArchiver = ZipMirrorArchiver())` with
    `fun existing(slug: String, fingerprint: String): File?`,
    `fun build(slug: String, gitDir: File, fingerprint: String, lastSyncAt: Long, progress: ArchiveProgress?): File`,
    `fun prune(slug: String, currentFingerprint: String?): Int`

- [ ] **Step 1: Write the failing test**

```kotlin
package de.nereide.strohhalm.domain.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArchiveStoreTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var store: ArchiveStore
    private lateinit var cache: File

    private val refs = mapOf("refs/heads/main" to "a".repeat(64))
    private val fingerprint get() = RefFingerprint.of(refs)
    private val synced = 1_785_060_000_000L

    private fun mirror(): File = temp.newFolder("yamiro.git").apply {
        File(this, "HEAD").writeText("ref: refs/heads/main\n")
        File(this, "packed-refs").writeText("${"a".repeat(64)} refs/heads/main\n")
    }

    private fun store(): ArchiveStore {
        cache = temp.newFolder("archives")
        return ArchiveStore(cache)
    }

    @Test
    fun `nothing is cached before the first build`() {
        store = store()
        assertNull(store.existing("yamiro", fingerprint))
    }

    @Test
    fun `a build produces an archive and a sidecar carrying both checksums`() {
        store = store()
        val archive = store.build("yamiro", mirror(), fingerprint, synced, null)

        assertTrue(archive.isFile)
        assertEquals("yamiro-2026-07-26-${fingerprint.take(12)}.zip", archive.name)

        val sidecar = File(cache, ArchiveNames.sidecar(archive.name))
        val parsed = assertNotNull(Sidecar.parse(sidecar.readText()))
        assertEquals(fingerprint, parsed!!.refFingerprint)
        assertEquals(archive.name, parsed.archiveName)

        val actual = java.security.MessageDigest.getInstance("SHA-256")
            .digest(archive.readBytes()).toHex()
        assertEquals(actual, parsed.archiveSha256)
    }

    /** The sidecar must stay readable by ordinary tools. */
    @Test
    fun `the sidecar is sha256sum's format with the fingerprint as a comment`() {
        store = store()
        val archive = store.build("yamiro", mirror(), fingerprint, synced, null)
        val lines = File(cache, ArchiveNames.sidecar(archive.name)).readLines()

        assertEquals("# refs $fingerprint", lines[0])
        assertTrue(lines[1].endsWith("  ${archive.name}"))
        assertEquals(64, lines[1].substringBefore(' ').length)
    }

    @Test
    fun `a finished archive is found again and not rebuilt`() {
        store = store()
        val first = store.build("yamiro", mirror(), fingerprint, synced, null)
        val stamp = first.lastModified()

        val found = store.existing("yamiro", fingerprint)
        assertEquals(first, found)
        assertEquals(stamp, found!!.lastModified())
    }

    /** The date must not participate: a no-op sync moves it and nothing else. */
    @Test
    fun `a later sync date still finds the same archive`() {
        store = store()
        val first = store.build("yamiro", mirror(), fingerprint, synced, null)
        assertEquals(first, store.existing("yamiro", fingerprint))
    }

    @Test
    fun `a tampered archive is not offered`() {
        store = store()
        val archive = store.build("yamiro", mirror(), fingerprint, synced, null)
        archive.appendBytes(byteArrayOf(0))

        assertNull(store.existing("yamiro", fingerprint))
    }

    @Test
    fun `an archive with no sidecar is not trusted`() {
        store = store()
        val archive = store.build("yamiro", mirror(), fingerprint, synced, null)
        File(cache, ArchiveNames.sidecar(archive.name)).delete()

        assertNull(store.existing("yamiro", fingerprint))
    }

    /** The filename is an index; the sidecar decides. */
    @Test
    fun `a sidecar naming a different ref state is not trusted`() {
        store = store()
        val archive = store.build("yamiro", mirror(), fingerprint, synced, null)
        val sidecar = File(cache, ArchiveNames.sidecar(archive.name))
        sidecar.writeText(sidecar.readText().replace("# refs $fingerprint", "# refs ${"f".repeat(64)}"))

        assertNull(store.existing("yamiro", fingerprint))
    }

    @Test
    fun `moved refs mean a different archive, and the old one is pruned`() {
        store = store()
        val old = store.build("yamiro", mirror(), fingerprint, synced, null)

        val moved = RefFingerprint.of(mapOf("refs/heads/main" to "b".repeat(64)))
        assertNull(store.existing("yamiro", moved))

        assertEquals(1, store.prune("yamiro", moved))
        assertFalse(old.exists())
        assertFalse(File(cache, ArchiveNames.sidecar(old.name)).exists())
    }

    @Test
    fun `pruning with an unchanged fingerprint removes nothing`() {
        store = store()
        val archive = store.build("yamiro", mirror(), fingerprint, synced, null)

        assertEquals(0, store.prune("yamiro", fingerprint))
        assertTrue(archive.exists())
    }

    /** What a severe memory trim does: everything goes, current included. */
    @Test
    fun `pruning with no current fingerprint removes everything for the slug`() {
        store = store()
        val archive = store.build("yamiro", mirror(), fingerprint, synced, null)

        assertEquals(1, store.prune("yamiro", null))
        assertFalse(archive.exists())
    }

    @Test
    fun `pruning leaves other repositories alone`() {
        store = store()
        val mine = store.build("yamiro", mirror(), fingerprint, synced, null)

        assertEquals(0, store.prune("other", null))
        assertTrue(mine.exists())
    }

    /** A cancelled build must leave nothing that could later be mistaken for done. */
    @Test
    fun `a failed build leaves no part file behind`() {
        cache = temp.newFolder("archives")
        val exploding = object : MirrorArchiver {
            override fun archive(gitDir: File, target: File, progress: ArchiveProgress?): ArchiveResult {
                target.writeBytes(ByteArray(16))
                throw InterruptedException("cancelled")
            }
        }
        store = ArchiveStore(cache, exploding)

        try {
            store.build("yamiro", mirror(), fingerprint, synced, null)
        } catch (expected: InterruptedException) {
            // the point of the test
        }

        assertEquals(emptyList<String>(), cache.list()!!.toList())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk
./gradlew :app:testDebugUnitTest --rerun --tests "de.nereide.strohhalm.domain.archive.ArchiveStoreTest"
```

Expected: FAIL — `Unresolved reference 'ArchiveStore'`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package de.nereide.strohhalm.domain.archive

import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * What is recorded beside an archive.
 *
 * Two checksums, because two independent questions get asked of a cached
 * archive: [archiveSha256] answers *is this file intact?*, and
 * [refFingerprint] answers *is this file current?*. Neither implies the other —
 * a stale archive verifies perfectly, and a current one can be truncated.
 *
 * The rendered form is valid input to `sha256sum -c`, which ignores `#` lines,
 * so a recipient can check the archive with ordinary tools.
 */
data class Sidecar(
    val refFingerprint: String,
    val archiveSha256: String,
    val archiveName: String,
) {
    fun render(): String = "# refs $refFingerprint\n$archiveSha256  $archiveName\n"

    companion object {
        fun parse(text: String): Sidecar? {
            val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
            val refs = lines.firstOrNull { it.startsWith("# refs ") }
                ?.removePrefix("# refs ")?.trim() ?: return null
            val checksum = lines.firstOrNull { !it.startsWith("#") } ?: return null
            val hash = checksum.substringBefore(' ').trim()
            val name = checksum.substringAfter(' ').trim()
            if (hash.isEmpty() || name.isEmpty()) return null
            return Sidecar(refFingerprint = refs, archiveSha256 = hash, archiveName = name)
        }
    }
}

/**
 * The archive cache.
 *
 * Everything here rests on one rule: an archive is current exactly when its
 * sidecar's ref fingerprint matches the mirror's. There is no timer, no grace
 * period and no protection for a recently shared file — deleting a file another
 * process holds open is harmless on Android, since the reader's descriptor
 * keeps the data alive until it closes.
 */
class ArchiveStore(
    private val root: File,
    private val archiver: MirrorArchiver = ZipMirrorArchiver(),
) {

    /** A finished, intact, current archive for [slug], or null. */
    fun existing(slug: String, fingerprint: String): File? {
        val candidate = root.listFiles()
            ?.firstOrNull { ArchiveNames.matches(it.name, slug, fingerprint) }
            ?: return null

        val sidecar = File(root, ArchiveNames.sidecar(candidate.name))
        if (!sidecar.isFile) return null
        val recorded = Sidecar.parse(sidecar.readText()) ?: return null

        // The full fingerprint, not the twelve characters in the filename. That
        // prefix narrows the search; it does not decide.
        if (recorded.refFingerprint != fingerprint) return null
        if (recorded.archiveName != candidate.name) return null
        if (checksumOf(candidate) != recorded.archiveSha256) return null
        return candidate
    }

    /**
     * Builds an archive for [slug], replacing any superseded one.
     *
     * The build goes to a `.part` file and is renamed only once complete, so an
     * interrupted build can never be found by [existing]. The rename is the
     * mechanism; the checksum is the second line of defence.
     */
    fun build(
        slug: String,
        gitDir: File,
        fingerprint: String,
        lastSyncAt: Long,
        progress: ArchiveProgress?,
    ): File {
        root.mkdirs()
        val name = ArchiveNames.archive(slug, lastSyncAt, fingerprint)
        val part = File(root, ArchiveNames.part(name))
        val archive = File(root, name)

        val result = try {
            archiver.archive(gitDir, part, progress)
        } catch (t: Throwable) {
            part.delete()
            throw t
        }

        if (!part.renameTo(archive)) {
            part.delete()
            throw java.io.IOException("could not finalise $archive")
        }
        File(root, ArchiveNames.sidecar(name)).writeText(
            Sidecar(fingerprint, result.sha256, name).render()
        )

        // A rebuild happens because the refs moved, so clearing the superseded
        // archive is the ordinary prune on the ordinary condition.
        prune(slug, fingerprint)
        return archive
    }

    /**
     * Deletes archives of [slug] that no longer describe the mirror.
     *
     * A null [currentFingerprint] means *all of them*, which is what a severe
     * memory trim asks for: an archive is fully regenerable, so it is the first
     * thing worth giving back.
     *
     * @return how many archives were removed.
     */
    fun prune(slug: String, currentFingerprint: String?): Int {
        val files = root.listFiles() ?: return 0
        var removed = 0
        files.filter { it.name.startsWith("$slug-") && it.name.endsWith(".zip") }
            .forEach { archive ->
                val keep = currentFingerprint != null &&
                    ArchiveNames.matches(archive.name, slug, currentFingerprint)
                if (!keep) {
                    File(root, ArchiveNames.sidecar(archive.name)).delete()
                    if (archive.delete()) removed++
                }
            }
        // Orphaned part files are never valid; a build that left one is over.
        files.filter { it.name.startsWith("$slug-") && it.name.endsWith(".part") }
            .forEach { it.delete() }
        return removed
    }

    private fun checksumOf(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(file.inputStream().buffered(), digest).use { input ->
            val buffer = ByteArray(64 * 1024)
            // Not readNBytes: Android only gained it at API 33, and minSdk is 26.
            while (input.read(buffer) >= 0) Unit
        }
        return digest.digest().toHex()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --rerun --tests "de.nereide.strohhalm.domain.archive.ArchiveStoreTest"
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  app/build/test-results/testDebugUnitTest/TEST-de.nereide.strohhalm.domain.archive.ArchiveStoreTest.xml
```

Expected: PASS, `tests="13" skipped="0" failures="0" errors="0"`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/de/nereide/strohhalm/domain/archive/ArchiveStore.kt \
        app/src/test/java/de/nereide/strohhalm/domain/archive/ArchiveStoreTest.kt
git commit -m "feat(archive): cache archives, verified by a two-checksum sidecar"
```

---

### Task 5: `ArchiveSpace`

The free-space precheck. Storage problems must be reported as storage problems, up front.

**Files:**
- Create: `app/src/main/java/de/nereide/strohhalm/domain/archive/ArchiveSpace.kt`
- Test: `app/src/test/java/de/nereide/strohhalm/domain/archive/ArchiveSpaceTest.kt`

**Interfaces:**
- Consumes: `SyncError`, `SyncErrorCode` (existing, `de.nereide.strohhalm.domain`).
- Produces: `object ArchiveSpace { const val FLOOR_BYTES = 10L * 1024 * 1024; fun required(mirrorBytes: Long): Long; fun check(mirrorBytes: Long, allocatableBytes: Long): SyncError? }`

- [ ] **Step 1: Write the failing test**

```kotlin
package de.nereide.strohhalm.domain.archive

import de.nereide.strohhalm.domain.SyncErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveSpaceTest {

    private val tenMiB = 10L * 1024 * 1024

    @Test
    fun `the requirement is the mirror plus five percent plus a floor`() {
        assertEquals(100L + 5 + tenMiB, ArchiveSpace.required(100L))
        assertEquals(2000L + 100 + tenMiB, ArchiveSpace.required(2000L))
    }

    /** A tiny repository must not be judged against a percentage of nearly nothing. */
    @Test
    fun `an empty mirror still demands the floor`() {
        assertEquals(tenMiB, ArchiveSpace.required(0L))
    }

    @Test
    fun `enough space is no error`() {
        assertNull(ArchiveSpace.check(mirrorBytes = 1_000, allocatableBytes = 1_000_000_000))
    }

    @Test
    fun `too little space is reported as low storage, not as a failure to write`() {
        val error = assertNotNull(ArchiveSpace.check(mirrorBytes = 50L * 1024 * 1024, allocatableBytes = 1_000))
        assertEquals(SyncErrorCode.LOW_STORAGE, error!!.code)
    }

    @Test
    fun `the message names both figures so the gap is visible`() {
        val error = ArchiveSpace.check(mirrorBytes = 50L * 1024 * 1024, allocatableBytes = 1_024)!!
        assertTrue(error.detail!!.contains("1024"))
        assertTrue(error.detail!!.contains(ArchiveSpace.required(50L * 1024 * 1024).toString()))
    }

    @Test
    fun `exactly enough is enough`() {
        val need = ArchiveSpace.required(1_000)
        assertNull(ArchiveSpace.check(mirrorBytes = 1_000, allocatableBytes = need))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk
./gradlew :app:testDebugUnitTest --rerun --tests "de.nereide.strohhalm.domain.archive.ArchiveSpaceTest"
```

Expected: FAIL — `Unresolved reference 'ArchiveSpace'`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package de.nereide.strohhalm.domain.archive

import de.nereide.strohhalm.domain.SyncError
import de.nereide.strohhalm.domain.SyncErrorCode

/**
 * How much internal storage an archive needs, and whether it is available.
 *
 * Checked before building rather than discovered during it. The unmounted-card
 * failure taught this the hard way: a storage problem reported as whatever
 * happened to throw first, two minutes in, sends the reader looking in entirely
 * the wrong place.
 */
object ArchiveSpace {

    /**
     * A flat cushion, so a small repository is not judged against a percentage
     * of almost nothing.
     */
    const val FLOOR_BYTES = 10L * 1024 * 1024

    /**
     * The archive is close to the mirror's own size — pack files, nearly all of
     * the bytes, are stored rather than re-deflated. The five percent covers
     * zip per-entry overhead, which scales with file count.
     */
    fun required(mirrorBytes: Long): Long = mirrorBytes + mirrorBytes / 20 + FLOOR_BYTES

    /**
     * @param allocatableBytes what the system could make available, from
     *   `StorageManager.getAllocatableBytes` — not what is merely free now.
     *   The difference is other apps' reclaimable caches, and using the smaller
     *   figure would refuse builds the device could comfortably do.
     */
    fun check(mirrorBytes: Long, allocatableBytes: Long): SyncError? {
        val need = required(mirrorBytes)
        if (allocatableBytes >= need) return null
        return SyncError(
            SyncErrorCode.LOW_STORAGE,
            "packing this backup needs $need bytes of internal storage; " +
                "only $allocatableBytes can be made available",
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --rerun --tests "de.nereide.strohhalm.domain.archive.ArchiveSpaceTest"
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  app/build/test-results/testDebugUnitTest/TEST-de.nereide.strohhalm.domain.archive.ArchiveSpaceTest.xml
```

Expected: PASS, `tests="6" skipped="0" failures="0" errors="0"`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/de/nereide/strohhalm/domain/archive/ArchiveSpace.kt \
        app/src/test/java/de/nereide/strohhalm/domain/archive/ArchiveSpaceTest.kt
git commit -m "feat(archive): refuse a build that will not fit, before starting it"
```

---

### Task 6: `ArchiveFileProvider` and the manifest

Serving the archive to other apps, under a name the recipient can read.

**Files:**
- Create: `app/src/main/java/de/nereide/strohhalm/ArchiveFileProvider.kt`
- Create: `app/src/main/res/xml/file_paths.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/de/nereide/strohhalm/domain/archive/ArchiveDisplayNameTest.kt`

**Interfaces:**
- Consumes: `ArchiveNames` (Task 3).
- Produces: `class ArchiveFileProvider : FileProvider()`, authority `de.nereide.strohhalm.fileprovider`.

The provider's `query` override cannot run in a JVM unit test — it needs a real `ContentResolver`. The logic worth testing is `ArchiveNames.displayName`, already covered in Task 3; this task adds one test proving the column rewrite maps the right value, and leaves the wiring to the device check in Task 10.

- [ ] **Step 1: Write the failing test**

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk
./gradlew :app:testDebugUnitTest --rerun --tests "de.nereide.strohhalm.domain.archive.ArchiveDisplayNameTest"
```

Expected: FAIL — `Unresolved reference 'ArchiveNames'` if Task 3 is not yet in. If Task 3 is in, these two assertions pass immediately, which is correct: this task's new code is the provider and the manifest, neither of which a JVM test can reach. Go straight to Step 3.

- [ ] **Step 3: Write the implementation**

`app/src/main/res/xml/file_paths.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <!--
      Scoped to archives/ alone. The cache holds nothing else worth exposing,
      and a provider that serves the whole cache directory is a provider that
      will one day serve something it should not.
    -->
    <cache-path name="archives" path="archives/" />
</paths>
```

`app/src/main/java/de/nereide/strohhalm/ArchiveFileProvider.kt`:

```kotlin
package de.nereide.strohhalm

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import de.nereide.strohhalm.domain.archive.ArchiveNames

/**
 * Serves archives to other apps, under a name a person would want to receive.
 *
 * `FileProvider` reports the on-disk filename as the display name, which would
 * send every backup out carrying a twelve-character hash. That suffix exists so
 * two ref states never collide on one path — it is meaningless to a recipient,
 * so it is stripped here rather than given up on disk.
 */
class ArchiveFileProvider : FileProvider() {

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val delegate = super.query(uri, projection, selection, selectionArgs, sortOrder)
        val column = delegate.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (column < 0) return delegate

        return delegate.use { source ->
            val names = Array(source.columnCount) { source.getColumnName(it) }
            val rewritten = MatrixCursor(names, source.count)
            while (source.moveToNext()) {
                val row = arrayOfNulls<Any>(source.columnCount)
                for (i in 0 until source.columnCount) {
                    row[i] = when {
                        i == column -> ArchiveNames.displayName(source.getString(i).orEmpty())
                        source.getType(i) == Cursor.FIELD_TYPE_INTEGER -> source.getLong(i)
                        else -> source.getString(i)
                    }
                }
                rewritten.addRow(row)
            }
            rewritten
        }
    }
}
```

In `app/src/main/AndroidManifest.xml`, inside `<application>`:

```xml
        <provider
            android:name=".ArchiveFileProvider"
            android:authorities="de.nereide.strohhalm.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>
```

- [ ] **Step 4: Run test to verify it passes, and confirm the app still builds**

```bash
./gradlew :app:testDebugUnitTest --rerun --tests "de.nereide.strohhalm.domain.archive.ArchiveDisplayNameTest"
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  app/build/test-results/testDebugUnitTest/TEST-de.nereide.strohhalm.domain.archive.ArchiveDisplayNameTest.xml
./gradlew assembleDebug
```

Expected: PASS, `tests="2" ... failures="0" errors="0"`, and `BUILD SUCCESSFUL`.

`assembleDebug` is the real gate for this task: a malformed `<provider>` element or a missing `file_paths.xml` fails the manifest merger, and nothing in the unit suite would catch it.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/de/nereide/strohhalm/ArchiveFileProvider.kt \
        app/src/main/res/xml/file_paths.xml app/src/main/AndroidManifest.xml \
        app/src/test/java/de/nereide/strohhalm/domain/archive/ArchiveDisplayNameTest.kt
git commit -m "feat(archive): serve archives under a name worth receiving"
```

---

### Task 7: `ArchiveMaintenance`

Pruning, on the two triggers, without the UI ever noticing.

**Files:**
- Create: `app/src/main/java/de/nereide/strohhalm/ArchiveMaintenance.kt`
- Test: `app/src/test/java/de/nereide/strohhalm/domain/archive/ArchiveMaintenanceTest.kt`

**Interfaces:**
- Consumes: `ArchiveStore`, `RefFingerprint` (Tasks 1, 4); `MirrorRepository` (`de.nereide.strohhalm.domain.git`); `RepoRepository`, `RepoSlug`.
- Produces: `class ArchiveMaintenance(store, repos, scope, io)` with `fun observe(running: StateFlow<Boolean>)`, `suspend fun pruneStale()`, `suspend fun dropEverything()`.

- [ ] **Step 1: Write the failing test**

```kotlin
package de.nereide.strohhalm.domain.archive

import de.nereide.strohhalm.ArchiveMaintenance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArchiveMaintenanceTest {

    @get:Rule val temp = TemporaryFolder()

    private val refs = mapOf("refs/heads/main" to "a".repeat(64))

    private fun mirrorAt(parent: File): File = File(parent, "yamiro.git").apply {
        mkdirs()
        File(this, "HEAD").writeText("ref: refs/heads/main\n")
        File(this, "packed-refs").writeText("${"a".repeat(64)} refs/heads/main\n")
    }

    @Test
    fun `an archive matching the mirror survives a prune`() = runTest {
        val cache = temp.newFolder("archives")
        val gitDir = mirrorAt(temp.newFolder("mirrors"))
        val store = ArchiveStore(cache)
        val archive = store.build("yamiro", gitDir, RefFingerprint.of(refs), 1_785_060_000_000L, null)

        val maintenance = ArchiveMaintenance(
            store = store,
            mirrors = { listOf("yamiro" to gitDir) },
            scope = this,
            io = StandardTestDispatcher(testScheduler),
        )
        maintenance.pruneStale()

        assertTrue(archive.exists())
    }

    @Test
    fun `an archive whose refs have moved is pruned`() = runTest {
        val cache = temp.newFolder("archives")
        val gitDir = mirrorAt(temp.newFolder("mirrors"))
        val store = ArchiveStore(cache)
        val archive = store.build("yamiro", gitDir, RefFingerprint.of(refs), 1_785_060_000_000L, null)

        // The mirror moves on.
        File(gitDir, "packed-refs").writeText("${"b".repeat(64)} refs/heads/main\n")

        val maintenance = ArchiveMaintenance(
            store = store,
            mirrors = { listOf("yamiro" to gitDir) },
            scope = this,
            io = StandardTestDispatcher(testScheduler),
        )
        maintenance.pruneStale()

        assertFalse(archive.exists())
    }

    /** A severe memory trim gives back even a current archive. */
    @Test
    fun `dropping everything removes a current archive too`() = runTest {
        val cache = temp.newFolder("archives")
        val gitDir = mirrorAt(temp.newFolder("mirrors"))
        val store = ArchiveStore(cache)
        val archive = store.build("yamiro", gitDir, RefFingerprint.of(refs), 1_785_060_000_000L, null)

        val maintenance = ArchiveMaintenance(
            store = store,
            mirrors = { listOf("yamiro" to gitDir) },
            scope = this,
            io = StandardTestDispatcher(testScheduler),
        )
        maintenance.dropEverything()

        assertFalse(archive.exists())
    }

    @Test
    fun `an unreadable mirror does not stop the others being pruned`() = runTest {
        val cache = temp.newFolder("archives")
        val mirrors = temp.newFolder("mirrors")
        val good = mirrorAt(mirrors)
        val store = ArchiveStore(cache)
        val archive = store.build("yamiro", good, RefFingerprint.of(refs), 1_785_060_000_000L, null)
        File(good, "packed-refs").writeText("${"b".repeat(64)} refs/heads/main\n")

        val maintenance = ArchiveMaintenance(
            store = store,
            mirrors = { listOf("gone" to File(mirrors, "absent.git"), "yamiro" to good) },
            scope = this,
            io = StandardTestDispatcher(testScheduler),
        )
        maintenance.pruneStale()

        assertFalse(archive.exists())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk
./gradlew :app:testDebugUnitTest --rerun --tests "de.nereide.strohhalm.domain.archive.ArchiveMaintenanceTest"
```

Expected: FAIL — `Unresolved reference 'ArchiveMaintenance'`.

If `runTest` or `StandardTestDispatcher` is unresolved, `kotlinx-coroutines-test` is missing. Add to `gradle/libs.versions.toml` under `[libraries]`:

```toml
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
```

and `testImplementation(libs.kotlinx.coroutines.test)` to `app/build.gradle.kts`. If no `coroutines` version entry exists, add one matching the coroutines version already resolved for the app.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package de.nereide.strohhalm

import de.nereide.strohhalm.domain.archive.ArchiveStore
import de.nereide.strohhalm.domain.archive.RefFingerprint
import de.nereide.strohhalm.domain.git.MirrorRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Keeps the archive cache honest, invisibly.
 *
 * Two triggers, and no others: a sync that moved the refs, and Android asking
 * for memory back. Everything here is deliberately unobservable from the UI —
 * application-scoped so navigating away cannot cancel it, off the sync's
 * critical path so a slow delete cannot delay the sync's completion write, and
 * emitting no state at all. A failure to delete is swallowed: a leftover file
 * is worth no user-facing noise, and the next prune takes it.
 *
 * @param mirrors the current repositories as slug-to-directory pairs. A lambda
 *   rather than a repository, so this class stays testable without Room.
 */
class ArchiveMaintenance(
    private val store: ArchiveStore,
    private val mirrors: suspend () -> List<Pair<String, File>>,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Prunes each time a sync finishes.
     *
     * Launched, never awaited. The sync's own final database write runs under
     * `NonCancellable` because it must outlive cancellation; putting file
     * deletion in that path would make completion wait on unrelated work.
     */
    fun observe(running: StateFlow<Boolean>) {
        scope.launch {
            var wasRunning = running.value
            running.collect { isRunning ->
                if (wasRunning && !isRunning) pruneStale()
                wasRunning = isRunning
            }
        }
    }

    /** Deletes archives whose recorded ref fingerprint no longer matches. */
    suspend fun pruneStale() = withContext(io) {
        mirrors().forEach { (slug, gitDir) ->
            runCatching {
                val repository = MirrorRepository(gitDir)
                if (!repository.exists()) return@runCatching
                store.prune(slug, RefFingerprint.of(repository.localRefs()))
            }
        }
    }

    /** Gives back every archive, current ones included. */
    suspend fun dropEverything() = withContext(io) {
        mirrors().forEach { (slug, _) -> runCatching { store.prune(slug, null) } }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --rerun --tests "de.nereide.strohhalm.domain.archive.ArchiveMaintenanceTest"
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  app/build/test-results/testDebugUnitTest/TEST-de.nereide.strohhalm.domain.archive.ArchiveMaintenanceTest.xml
```

Expected: PASS, `tests="4" skipped="0" failures="0" errors="0"`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/de/nereide/strohhalm/ArchiveMaintenance.kt \
        app/src/test/java/de/nereide/strohhalm/domain/archive/ArchiveMaintenanceTest.kt \
        gradle/libs.versions.toml app/build.gradle.kts
git commit -m "feat(archive): prune stale archives after a sync and on memory pressure"
```

---

### Task 8: The share state machine

`RepoDetailViewModel` gains the whole flow: waiting, archiving, the failure branch.

**Files:**
- Create: `app/src/main/java/de/nereide/strohhalm/ui/detail/ShareState.kt`
- Modify: `app/src/main/java/de/nereide/strohhalm/ui/detail/RepoDetailViewModel.kt`
- Modify: `app/src/main/java/de/nereide/strohhalm/AppContainer.kt`
- Test: `app/src/test/java/de/nereide/strohhalm/ui/detail/ShareStateMachineTest.kt`

`AppContainer` is edited here rather than in Task 9 because the ViewModel cannot compile without it.

**Interfaces:**
- Consumes: `ArchiveStore`, `ArchiveSpace`, `RefFingerprint` (Tasks 1, 4, 5); `SyncRunner`, `Repo`, `RepoSlug`, `SyncError`.
- Produces: `sealed interface ShareState { data object Idle; data class Waiting(val neverSynced: Boolean); data class Packing(val completed: Int, val total: Int); data class Blocked(val error: SyncError, val cancelled: Boolean, val canShareAnyway: Boolean); data class Ready(val archive: File) }` and on the ViewModel: `val shareState: StateFlow<ShareState>`, `fun share()`, `fun shareAnyway()`, `fun retrySync()`, `fun cancelShare()`, `fun shareConsumed()`.

Because the machine is what the user sees, it is tested directly against a fake sync signal — no Android, no Room.

- [ ] **Step 1: Write the failing test**

```kotlin
package de.nereide.strohhalm.ui.detail

import de.nereide.strohhalm.domain.SyncError
import de.nereide.strohhalm.domain.SyncErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The state machine only, as a pure function of what has happened. The
 * ViewModel wires it to flows; the rules live here so they can be pinned
 * without a device.
 */
class ShareStateMachineTest {

    private val failure = SyncError(SyncErrorCode.HOST_UNREACHABLE, "no route to host")

    @Test
    fun `sharing while a sync runs waits for it`() {
        val state = ShareRules.onShareRequested(syncing = true, everSynced = true)
        assertTrue(state is ShareState.Waiting)
        assertFalse((state as ShareState.Waiting).neverSynced)
    }

    @Test
    fun `sharing a never-synced repository waits, and says so`() {
        val state = ShareRules.onShareRequested(syncing = false, everSynced = false)
        assertEquals(ShareState.Waiting(neverSynced = true), state)
    }

    @Test
    fun `sharing an idle, previously synced repository packs immediately`() {
        assertEquals(
            ShareState.Packing(0, 0),
            ShareRules.onShareRequested(syncing = false, everSynced = true),
        )
    }

    @Test
    fun `a sync that fails while waiting offers both ways forward`() {
        val state = ShareRules.onSyncFinished(failed = failure, cancelled = false, everSynced = true)
        val blocked = state as ShareState.Blocked
        assertEquals(failure, blocked.error)
        assertFalse(blocked.cancelled)
        assertTrue(blocked.canShareAnyway)
    }

    /** Nothing to share means no offer to share it. */
    @Test
    fun `a never-synced repository is never offered share anyway`() {
        val blocked = ShareRules.onSyncFinished(failed = failure, cancelled = false, everSynced = false)
            as ShareState.Blocked
        assertFalse(blocked.canShareAnyway)
    }

    @Test
    fun `a cancelled sync is not presented as a failure`() {
        val blocked = ShareRules.onSyncFinished(
            failed = SyncError(SyncErrorCode.CANCELLED), cancelled = true, everSynced = true,
        ) as ShareState.Blocked
        assertTrue(blocked.cancelled)
        assertTrue(blocked.canShareAnyway)
    }

    @Test
    fun `a sync that succeeds while waiting goes on to pack`() {
        assertEquals(
            ShareState.Packing(0, 0),
            ShareRules.onSyncFinished(failed = null, cancelled = false, everSynced = true),
        )
    }

    /** The share survives a retry; that is the whole reason the button is here. */
    @Test
    fun `retrying returns to waiting rather than abandoning the share`() {
        assertEquals(ShareState.Waiting(neverSynced = false), ShareRules.onRetry(everSynced = true))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk
./gradlew :app:testDebugUnitTest --rerun --tests "de.nereide.strohhalm.ui.detail.ShareStateMachineTest"
```

Expected: FAIL — `Unresolved reference 'ShareRules'`.

- [ ] **Step 3: Write minimal implementation**

`app/src/main/java/de/nereide/strohhalm/ui/detail/ShareState.kt`:

```kotlin
package de.nereide.strohhalm.ui.detail

import de.nereide.strohhalm.domain.SyncError
import java.io.File

/** Where a pending share has got to. */
sealed interface ShareState {

    /** No share pending. */
    data object Idle : ShareState

    /**
     * A sync stands between the request and an archive.
     *
     * [neverSynced] distinguishes "wait for the running sync" from "there is
     * nothing backed up yet", which need different words and different buttons.
     */
    data class Waiting(val neverSynced: Boolean) : ShareState

    data class Packing(val completed: Int, val total: Int) : ShareState

    /**
     * The sync ended without producing anything new.
     *
     * [cancelled] is not a failure and must not be styled as one — a stopped
     * sync is recorded as stopped. [canShareAnyway] is false before the first
     * successful sync, because an archive of a never-cloned repository would
     * restore nothing.
     */
    data class Blocked(
        val error: SyncError,
        val cancelled: Boolean,
        val canShareAnyway: Boolean,
    ) : ShareState

    /** An archive is ready to hand to the share sheet. */
    data class Ready(val archive: File) : ShareState
}

/**
 * The transitions, as plain functions.
 *
 * Kept apart from the ViewModel so every branch can be pinned by a test that
 * needs no Android, no Room and no clock.
 */
object ShareRules {

    fun onShareRequested(syncing: Boolean, everSynced: Boolean): ShareState = when {
        syncing -> ShareState.Waiting(neverSynced = !everSynced)
        !everSynced -> ShareState.Waiting(neverSynced = true)
        else -> ShareState.Packing(0, 0)
    }

    fun onSyncFinished(failed: SyncError?, cancelled: Boolean, everSynced: Boolean): ShareState =
        if (failed == null) {
            ShareState.Packing(0, 0)
        } else {
            ShareState.Blocked(
                error = failed,
                cancelled = cancelled,
                canShareAnyway = everSynced,
            )
        }

    /** A retry keeps the share pending; losing it would make the button pointless. */
    fun onRetry(everSynced: Boolean): ShareState = ShareState.Waiting(neverSynced = !everSynced)
}
```

Now wire it into `RepoDetailViewModel`. Add these constructor parameters after `syncRunner`:

```kotlin
    private val archives: ArchiveStore,
    private val allocatableBytes: suspend () -> Long,
```

and this body, after the existing `refs` declaration:

```kotlin
    private val _shareState = MutableStateFlow<ShareState>(ShareState.Idle)
    val shareState: StateFlow<ShareState> = _shareState.asStateFlow()

    private var packJob: Job? = null

    fun share() {
        val current = repo.value ?: return
        val next = ShareRules.onShareRequested(
            syncing = syncRunner.running.value,
            everSynced = current.lastSyncAt != null,
        )
        _shareState.value = next
        if (next is ShareState.Packing) pack(current)
    }

    /** Archive the mirror as it stands, after a sync failed. */
    fun shareAnyway() {
        val current = repo.value ?: return
        _shareState.value = ShareState.Packing(0, 0)
        pack(current)
    }

    fun retrySync() {
        val current = repo.value ?: return
        _shareState.value = ShareRules.onRetry(everSynced = current.lastSyncAt != null)
        syncRunner.launchSyncOne(id)
    }

    /** Back, or Stop while packing. Stop means stop, not pause. */
    fun cancelShare() {
        packJob?.cancel()
        packJob = null
        _shareState.value = ShareState.Idle
    }

    /** Called once the share sheet has been launched. */
    fun shareConsumed() {
        _shareState.value = ShareState.Idle
    }

    private fun pack(current: Repo) {
        packJob?.cancel()
        packJob = viewModelScope.launch {
            val gitDir = File(current.localPath)
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val repository = MirrorRepository(gitDir)
                    val fingerprint = RefFingerprint.of(repository.localRefs())
                    val slug = RepoSlug.fromRemoteUrl(current.remoteUrl)

                    archives.existing(slug, fingerprint) ?: run {
                        ArchiveSpace.check(current.sizeBytes, allocatableBytes())
                            ?.let { throw ArchiveRefused(it) }
                        runInterruptible {
                            archives.build(
                                slug = slug,
                                gitDir = gitDir,
                                fingerprint = fingerprint,
                                lastSyncAt = current.lastSyncAt ?: System.currentTimeMillis(),
                            ) { _, completed, total ->
                                _shareState.value = ShareState.Packing(completed, total)
                            }
                        }
                    }
                }
            }
            outcome
                .onSuccess { _shareState.value = ShareState.Ready(it) }
                .onFailure { t ->
                    if (t is CancellationException) throw t
                    _shareState.value = ShareState.Blocked(
                        error = (t as? ArchiveRefused)?.error ?: SyncErrors.fromException(t),
                        cancelled = false,
                        canShareAnyway = false,
                    )
                }
        }
    }

    private class ArchiveRefused(val error: SyncError) : Exception(error.detail)
```

Add the imports `ShareState`/`ShareRules` are in the same package; the rest:

```kotlin
import de.nereide.strohhalm.data.RepoSlug
import de.nereide.strohhalm.domain.SyncError
import de.nereide.strohhalm.domain.SyncErrors
import de.nereide.strohhalm.domain.archive.ArchiveSpace
import de.nereide.strohhalm.domain.archive.ArchiveStore
import de.nereide.strohhalm.domain.archive.RefFingerprint
import de.nereide.strohhalm.domain.git.MirrorRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runInterruptible
```

Extend the existing `init` block's sync observation so a finishing sync advances a pending share:

```kotlin
        viewModelScope.launch {
            var wasRunning = syncRunner.running.value
            syncRunner.running.collect { isRunning ->
                if (wasRunning && !isRunning) {
                    loadRefs()
                    val pending = _shareState.value
                    if (pending is ShareState.Waiting) {
                        val current = repository.observe(id).first()
                        val error = current?.lastErrorCode?.let {
                            SyncError(SyncErrorCode.valueOf(it), current.lastErrorDetail)
                        }
                        val next = ShareRules.onSyncFinished(
                            failed = if (current?.lastStatus == SyncStatus.OK) null else error,
                            cancelled = error?.code == SyncErrorCode.CANCELLED,
                            everSynced = current?.lastSyncAt != null,
                        )
                        _shareState.value = next
                        if (next is ShareState.Packing && current != null) pack(current)
                    }
                }
                wasRunning = isRunning
            }
        }
```

Replace the previous `syncRunner.running.collect { running -> if (!running) loadRefs() }` block with the above. Add `import de.nereide.strohhalm.data.SyncStatus` and `import de.nereide.strohhalm.domain.SyncErrorCode`.

Add to `AppContainer.kt` the two properties the ViewModel needs. `applicationScope` already exists there (`CoroutineScope(SupervisorJob() + Dispatchers.Default)`), so only these are new:

```kotlin
    val archiveStore: ArchiveStore by lazy {
        ArchiveStore(File(context.cacheDir, "archives"))
    }

    val archiveMaintenance: ArchiveMaintenance by lazy {
        ArchiveMaintenance(
            store = archiveStore,
            mirrors = {
                repoRepository.all().map {
                    RepoSlug.fromRemoteUrl(it.remoteUrl) to File(it.localPath)
                }
            },
            scope = applicationScope,
        )
    }

    /**
     * What the system could free for us, not merely what is unused right now.
     * The difference is other apps' reclaimable caches; asking for the smaller
     * figure would refuse builds the device could comfortably do.
     *
     * Both APIs are API 26, which is minSdk. The fallback covers a device that
     * refuses the query rather than letting a precheck become a crash.
     */
    suspend fun allocatableCacheBytes(): Long = withContext(Dispatchers.IO) {
        runCatching {
            val storage = context.getSystemService(StorageManager::class.java)
            storage.getAllocatableBytes(storage.getUuidForPath(context.cacheDir))
        }.getOrElse { context.cacheDir.usableSpace }
    }
```

`RepoRepository.all(): List<Repo>` exists already — verified against the interface.

Finally, extend the companion `viewModelFactory` initializer to pass the two new parameters:

```kotlin
                archives = container.archiveStore,
                allocatableBytes = container::allocatableCacheBytes,
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --rerun --tests "de.nereide.strohhalm.ui.detail.ShareStateMachineTest"
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  app/build/test-results/testDebugUnitTest/TEST-de.nereide.strohhalm.ui.detail.ShareStateMachineTest.xml
```

Expected: PASS, `tests="8" skipped="0" failures="0" errors="0"`.

Then confirm the wiring compiles, since the ViewModel edits are not covered by any unit test:

```bash
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/de/nereide/strohhalm/ui/detail/ShareState.kt \
        app/src/main/java/de/nereide/strohhalm/ui/detail/RepoDetailViewModel.kt \
        app/src/main/java/de/nereide/strohhalm/AppContainer.kt \
        app/src/test/java/de/nereide/strohhalm/ui/detail/ShareStateMachineTest.kt
git commit -m "feat(ui): drive sharing through an explicit state machine"
```

---

### Task 9: Wiring and the screen

`AppContainer` provides the store; the detail screen grows a Share action.

**Files:**
- Modify: `app/src/main/java/de/nereide/strohhalm/AppContainer.kt`
- Modify: `app/src/main/java/de/nereide/strohhalm/StrohhalmApp.kt`
- Modify: `app/src/main/java/de/nereide/strohhalm/ui/detail/RepoDetailScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: everything above.
- Produces: `AppContainer.archiveStore: ArchiveStore`, `AppContainer.archiveMaintenance: ArchiveMaintenance`, `suspend fun AppContainer.allocatableCacheBytes(): Long`.

- [ ] **Step 1: Add the strings**

In `app/src/main/res/values/strings.xml`:

```xml
    <string name="share_backup">Share backup</string>
    <string name="share_waiting_sync">Waiting for the sync to finish…</string>
    <string name="share_never_synced">Nothing has been backed up yet.</string>
    <string name="share_packing">Packing… %1$d of %2$d files</string>
    <string name="share_sync_stopped">The sync was stopped.</string>
    <string name="share_retry_sync">Retry sync</string>
    <string name="share_sync_now">Sync now</string>
    <string name="share_anyway">Share anyway</string>
    <string name="share_anyway_dated">Share the copy from %1$s</string>
    <string name="share_unencrypted_warning">The archive is not encrypted. Anyone you send it to can read the whole repository.</string>
    <string name="share_chooser_title">Share backup</string>
```

- [ ] **Step 2: Start the maintenance and hook memory pressure**

`archiveStore`, `archiveMaintenance` and `allocatableCacheBytes` were added to `AppContainer` in Task 8. What remains is starting the observer and responding to trim.

In `StrohhalmApp.kt`, after `AppContainer` construction:

```kotlin
        container.archiveMaintenance.observe(container.syncRunner.running)
```

and add the trim hook to the same class:

```kotlin
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Only the severe level. The lesser ones fire whenever the app
        // backgrounds, and dropping the cache on every backgrounding would
        // defeat reuse entirely.
        if (level >= TRIM_MEMORY_COMPLETE) {
            container.applicationScope.launch { container.archiveMaintenance.dropEverything() }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        container.applicationScope.launch { container.archiveMaintenance.dropEverything() }
    }
```

- [ ] **Step 3: Add the Share action to the screen**

In `RepoDetailScreen.kt`, add a share icon to the top bar beside the existing ones:

```kotlin
                    IconButton(onClick = { viewModel.share() }) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = stringResource(R.string.share_backup),
                        )
                    }
```

and render the state. Place this inside the screen's main column:

```kotlin
    val shareState by viewModel.shareState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    when (val state = shareState) {
        is ShareState.Idle -> Unit

        is ShareState.Waiting -> ShareNotice(
            text = if (state.neverSynced) {
                stringResource(R.string.share_never_synced)
            } else {
                stringResource(R.string.share_waiting_sync)
            },
            primary = if (state.neverSynced) {
                stringResource(R.string.share_sync_now) to viewModel::retrySync
            } else {
                null
            },
            onDismiss = viewModel::cancelShare,
        )

        is ShareState.Packing -> ShareNotice(
            text = stringResource(R.string.share_packing, state.completed, state.total),
            primary = null,
            onDismiss = viewModel::cancelShare,
        )

        is ShareState.Blocked -> ShareBlocked(
            state = state,
            lastSyncAt = repo?.lastSyncAt,
            onRetry = viewModel::retrySync,
            onShareAnyway = viewModel::shareAnyway,
            onDismiss = viewModel::cancelShare,
        )

        is ShareState.Ready -> LaunchedEffect(state.archive) {
            val uri = FileProvider.getUriForFile(
                context,
                "de.nereide.strohhalm.fileprovider",
                state.archive,
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(send, context.getString(R.string.share_chooser_title))
            )
            viewModel.shareConsumed()
        }
    }
```

with these two composables at the bottom of the file:

```kotlin
@Composable
private fun ShareNotice(
    text: String,
    primary: Pair<String, () -> Unit>?,
    onDismiss: () -> Unit,
) {
    // Back dismisses the share without touching the sync, which keeps running.
    BackHandler(onBack = onDismiss)
    Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text)
            Text(
                text = stringResource(R.string.share_unencrypted_warning),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(modifier = Modifier.padding(top = 8.dp)) {
                primary?.let { (label, action) ->
                    Button(onClick = action) { Text(label) }
                }
            }
        }
    }
}

@Composable
private fun ShareBlocked(
    state: ShareState.Blocked,
    lastSyncAt: Long?,
    onRetry: () -> Unit,
    onShareAnyway: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (state.cancelled) {
                    stringResource(R.string.share_sync_stopped)
                } else {
                    // syncErrorText takes the *code name*, not a SyncError.
                    syncErrorText(state.error.code.name)
                        ?: state.error.detail.orEmpty()
                },
            )
            Row(modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = onRetry) { Text(stringResource(R.string.share_retry_sync)) }
                if (state.canShareAnyway) {
                    TextButton(
                        onClick = onShareAnyway,
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        Text(
                            lastSyncAt?.let {
                                stringResource(R.string.share_anyway_dated, relative(it))
                            } ?: stringResource(R.string.share_anyway)
                        )
                    }
                }
            }
        }
    }
}
```

Both helpers already exist and are reused rather than rewritten, with the exact signatures verified against the sources:

- `syncErrorText(code: String?): String?` in `ui/common/SyncErrorText.kt` — it takes the **code's name**, not a `SyncError`, and returns null for an unmapped code, hence the `?: state.error.detail.orEmpty()` fallback above.
- `relative(millis)` in `RepoDetailScreen.kt` — already used for `lastSyncAt` on line 143, so the date reads the same in both places.

- [ ] **Step 4: Build and run the whole suite**

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk
./gradlew assembleDebug
./gradlew :app:testDebugUnitTest --rerun
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  app/build/test-results/testDebugUnitTest/TEST-*.xml
```

Expected: `BUILD SUCCESSFUL`, every class reporting `failures="0" errors="0"`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/de/nereide/strohhalm/StrohhalmApp.kt \
        app/src/main/java/de/nereide/strohhalm/ui/detail/RepoDetailScreen.kt \
        app/src/main/res/values/strings.xml
git commit -m "feat(ui): add a Share backup action to the repository screen"
```

---

### Task 10: End-to-end, validated by real tools

The archive is only worth anything if real `git` can restore it and real `sha256sum` can check it.

**Files:**
- Test: `app/src/test/java/de/nereide/strohhalm/domain/archive/ArchiveEndToEndTest.kt`

**Interfaces:**
- Consumes: `ArchiveStore`, `RefFingerprint`, `ZipMirrorArchiver`.
- Produces: nothing.

- [ ] **Step 1: Write the failing test**

```kotlin
package de.nereide.strohhalm.domain.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Real `git` and real `sha256sum` validating our output. Everything else in
 * this package tests our reader against our writer; only this proves that what
 * a recipient gets is usable with ordinary tools.
 */
class ArchiveEndToEndTest {

    @get:Rule val temp = TemporaryFolder()

    private fun run(vararg command: String, cwd: File): Pair<Int, String> {
        val process = ProcessBuilder(*command)
            .directory(cwd)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() to output
    }

    private fun toolsPresent(): Boolean = runCatching {
        run("git", "--version", cwd = temp.root).first == 0 &&
            run("sha256sum", "--version", cwd = temp.root).first == 0
    }.getOrDefault(false)

    /** Builds a real bare mirror with the system git. */
    private fun bareMirror(objectFormat: String): File {
        val work = temp.newFolder("work-$objectFormat")
        assertEquals(0, run("git", "init", "--object-format=$objectFormat", "-q", ".", cwd = work).first)
        File(work, "a.txt").writeText("hello\n")
        run("git", "add", "a.txt", cwd = work)
        run("git", "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "first", cwd = work)
        run("git", "tag", "v1", cwd = work)

        val bare = File(temp.root, "mirror-$objectFormat.git")
        assertEquals(0, run("git", "clone", "-q", "--mirror", work.absolutePath, bare.absolutePath, cwd = temp.root).first)
        return bare
    }

    @Test
    fun `a sha256 mirror survives packing, unpacking, fsck and clone`() {
        assumeTrue("needs git and sha256sum", toolsPresent())
        assumeTrue(
            "needs a git with sha256 support",
            run("git", "init", "--object-format=sha256", "-q", "probe", cwd = temp.root).first == 0,
        )

        val bare = bareMirror("sha256")
        val cache = temp.newFolder("archives")
        val store = ArchiveStore(cache)
        val fingerprint = RefFingerprint.of(
            de.nereide.strohhalm.domain.git.MirrorRepository(bare).localRefs()
        )

        val archive = store.build("mirror-sha256", bare, fingerprint, 1_785_060_000_000L, null)

        // The sidecar must be checkable by the tool it imitates.
        val (checkCode, checkOut) = run("sha256sum", "-c", ArchiveNames.sidecar(archive.name), cwd = cache)
        assertEquals("sha256sum -c said:\n$checkOut", 0, checkCode)

        val out = temp.newFolder("unpacked")
        assertEquals(0, run("unzip", "-q", archive.absolutePath, cwd = out).first)
        val restored = File(out, bare.name)
        assertTrue("expected a single top-level ${bare.name}", restored.isDirectory)

        val (fsckCode, fsckOut) = run("git", "fsck", "--strict", "--full", cwd = restored)
        assertEquals("git fsck said:\n$fsckOut", 0, fsckCode)

        val clone = File(temp.root, "clone")
        assertEquals(0, run("git", "clone", "-q", restored.absolutePath, clone.absolutePath, cwd = temp.root).first)
        assertEquals("hello\n", File(clone, "a.txt").readText())
    }

    /** The Share anyway claim: a mirror left by a failed fetch is still valid. */
    @Test
    fun `a sha1 mirror packs and restores just as well`() {
        assumeTrue("needs git and sha256sum", toolsPresent())

        val bare = bareMirror("sha1")
        val store = ArchiveStore(temp.newFolder("archives-sha1"))
        val fingerprint = RefFingerprint.of(
            de.nereide.strohhalm.domain.git.MirrorRepository(bare).localRefs()
        )
        val archive = store.build("mirror-sha1", bare, fingerprint, 1_785_060_000_000L, null)

        val out = temp.newFolder("unpacked-sha1")
        assertEquals(0, run("unzip", "-q", archive.absolutePath, cwd = out).first)
        val restored = File(out, bare.name)
        assertEquals(0, run("git", "fsck", "--strict", "--full", cwd = restored).first)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk
./gradlew :app:testDebugUnitTest --rerun --tests "de.nereide.strohhalm.domain.archive.ArchiveEndToEndTest"
```

Expected: this test should PASS once Tasks 1–5 are in. If it fails, the failure is real and points at the archiver or the store — do not weaken the assertions. Confirm `unzip` is installed; if it is not, install it rather than dropping the unpack step, since unpacking with a third-party tool is the point.

- [ ] **Step 3: Confirm the skips are not hiding anything**

```bash
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  app/build/test-results/testDebugUnitTest/TEST-de.nereide.strohhalm.domain.archive.ArchiveEndToEndTest.xml
```

Expected: `tests="2" skipped="0" failures="0" errors="0"`.

`skipped="0"` is the assertion that matters. A skipped test and a passing one are indistinguishable in the exit code, and this test's whole value is that real tools ran.

- [ ] **Step 4: Commit**

```bash
git add app/src/test/java/de/nereide/strohhalm/domain/archive/ArchiveEndToEndTest.kt
git commit -m "test(archive): validate archives with real git, unzip and sha256sum"
```

---

### Task 11: Device check

Cannot be automated. Do not mark done without running it.

**Files:** none.

- [ ] **Step 1: Install**

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk
./gradlew installDebug
```

- [ ] **Step 2: Confirm each of these on the device**

1. **Share a synced repository.** The share sheet opens, and the filename offered carries no hash — `yamiro-2026-07-26.zip`, not `yamiro-2026-07-26-4f2a91c07b3e.zip`.
2. **Send it somewhere and restore it.** Unzip on a computer, `git clone` the result, confirm the working tree.
3. **Share the same repository again.** It should reach the share sheet immediately, without a packing bar — proving the cache was reused rather than rebuilt.
4. **Sync so the refs move, then share again.** It rebuilds, and the old archive is gone from the cache.
5. **Share while a sync is running.** The waiting state appears with live sync progress, and archiving starts by itself when the sync completes.
6. **Back out of the waiting state.** The share is abandoned and the sync keeps running.
7. **Make a sync fail** (aeroplane mode), share, and check the failure branch shows the error with both Retry sync and Share anyway.
8. **Share a never-synced repository.** It offers Sync now, and does *not* offer Share anyway.
9. **The large mirror.** Share the 44 MiB repository: watch that packing shows progress, stays responsive, and does not exhaust memory.
10. **Stop during packing.** The archive is abandoned and no `.part` file survives in the cache.

- [ ] **Step 3: Record the outcome**

Add what passed to `CLAUDE.md` under "Verified on real hardware", and anything that did not to "NOT verified on hardware". Do not record a step as verified on the strength of the unit tests.

---

## Verification not covered by this plan

- **A receiving app that streams rather than copies.** The narrow window where a sync could delete an archive between the grant being issued and the receiver opening it is documented in the spec as an accepted risk. Reproducing it deliberately is not worth an automated test.
- **`onTrimMemory` at `TRIM_MEMORY_COMPLETE`.** Reachable on a device under real memory pressure; not reproducible in a JVM test.
