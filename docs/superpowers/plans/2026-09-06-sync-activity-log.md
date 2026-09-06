# Sync Activity Log Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Record one entry per repository per sync attempt, with pack bytes received, refs changed, duration and trigger, and show them on an Activity screen filtered by default to entries that received data.

**Architecture:** A new Room table `sync_events` written by `SyncRunner` through a `SyncLog` interface. `ProtocolMirror` reports two extra numbers on `MirrorOutcome.Success`. An `ActivityScreen` reads the table through a DAO flow. Retention is a 30-day prune on each insert. Spec: `docs/superpowers/specs/2026-09-06-sync-activity-log-design.md`.

**Tech Stack:** Kotlin, Room 2.6.1 with KSP auto-migration, Jetpack Compose + Material 3, kotlinx-coroutines-test, JUnit 4.

## Global Constraints

- Package `de.nereide.strohhalm`; every Gradle call needs `export JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk`.
- Only `ProtocolMirror`/`JGitMirror` may know git. Nothing above `GitMirror` learns what a pack is.
- No hardcoded user-facing strings in Compose; everything via `res/values/strings.xml`.
- Library exceptions never escape the domain layer. Writing a log entry must never fail a sync.
- Unknown enum names read from storage fall back (`FAILED`, `MANUAL`), never throw.
- `UP-TO-DATE` is not evidence: verify with `--rerun` and read the per-class counts.
- Commit per task, conventional-commit style.

---

### Task 1: Entity, converters, DAO, migration

**Files:**
- Create: `app/src/main/java/de/nereide/strohhalm/data/SyncEvent.kt`
- Create: `app/src/main/java/de/nereide/strohhalm/data/SyncEventDao.kt`
- Modify: `app/src/main/java/de/nereide/strohhalm/data/StrohhalmDatabase.kt`
- Test: `app/src/test/java/de/nereide/strohhalm/data/SyncEventConvertersTest.kt`

**Interfaces:**
- Produces: `SyncEvent` entity, enums `SyncEventOutcome { RECEIVED, UP_TO_DATE, FAILED, CANCELLED }` and `SyncTrigger { MANUAL, SCHEDULED }` (both in `data`), `SyncEventDao` with `observeRecent(since: Long, receivedOnly: Boolean): Flow<List<SyncEvent>>`, `insert(event)`, `deleteOlderThan(cutoff)`, `recordAndPrune(event, cutoff)`. `StrohhalmDatabase.syncEventDao()`.

- [ ] **Step 1: Write the failing converter test**

```kotlin
package de.nereide.strohhalm.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A downgrade that removes an enum constant must not crash the Activity
 * screen; the converters fall back the way SyncStatusConverter does.
 */
class SyncEventConvertersTest {

    private val converters = SyncEventConverters()

    @Test
    fun `outcome round-trips`() {
        assertEquals(
            SyncEventOutcome.RECEIVED,
            converters.toOutcome(converters.fromOutcome(SyncEventOutcome.RECEIVED)),
        )
    }

    @Test
    fun `an unknown outcome falls back to FAILED`() {
        assertEquals(SyncEventOutcome.FAILED, converters.toOutcome("EXPLODED"))
    }

    @Test
    fun `trigger round-trips`() {
        assertEquals(
            SyncTrigger.SCHEDULED,
            converters.toTrigger(converters.fromTrigger(SyncTrigger.SCHEDULED)),
        )
    }

    @Test
    fun `an unknown trigger falls back to MANUAL`() {
        assertEquals(SyncTrigger.MANUAL, converters.toTrigger("CRON"))
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*SyncEventConvertersTest*' -q 2>&1 | grep -E "^e:|BUILD" | head`
Expected: `Unresolved reference 'SyncEventConverters'`.

- [ ] **Step 3: Create the entity and enums**

`app/src/main/java/de/nereide/strohhalm/data/SyncEvent.kt`:

```kotlin
package de.nereide.strohhalm.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

/** How one sync attempt of one repository ended. */
enum class SyncEventOutcome { RECEIVED, UP_TO_DATE, FAILED, CANCELLED }

/** What started the attempt. */
enum class SyncTrigger { MANUAL, SCHEDULED }

/**
 * One sync attempt of one repository. The repository's name is copied in so
 * a rename or deletion leaves the entry legible; [repoId] is not a foreign
 * key for the same reason.
 *
 * [bytesReceived] is the size of the pack the server sent — the transfer,
 * not the growth of the mirror folder — and is zero unless [outcome] is
 * [SyncEventOutcome.RECEIVED].
 */
@Entity(
    tableName = "sync_events",
    indices = [Index(value = ["startedAt"])]
)
data class SyncEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val repoId: Long,
    val repoName: String,
    val startedAt: Long,
    val finishedAt: Long,
    val outcome: SyncEventOutcome,
    val bytesReceived: Long = 0,
    val refsChanged: Int = 0,
    val errorCode: String? = null,
    val trigger: SyncTrigger,
) {
    val durationMillis: Long get() = (finishedAt - startedAt).coerceAtLeast(0)
}

class SyncEventConverters {
    @TypeConverter
    fun toOutcome(name: String): SyncEventOutcome =
        SyncEventOutcome.entries.firstOrNull { it.name == name } ?: SyncEventOutcome.FAILED

    @TypeConverter
    fun fromOutcome(outcome: SyncEventOutcome): String = outcome.name

    @TypeConverter
    fun toTrigger(name: String): SyncTrigger =
        SyncTrigger.entries.firstOrNull { it.name == name } ?: SyncTrigger.MANUAL

    @TypeConverter
    fun fromTrigger(trigger: SyncTrigger): String = trigger.name
}
```

- [ ] **Step 4: Run the converter test and confirm it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*SyncEventConvertersTest*' -q 2>&1 | grep -E "^e:|BUILD"; grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' app/build/test-results/testDebugUnitTest/TEST-*SyncEventConverters*.xml`
Expected: `tests="4" skipped="0" failures="0" errors="0"`.

- [ ] **Step 5: Create the DAO**

`app/src/main/java/de/nereide/strohhalm/data/SyncEventDao.kt`:

```kotlin
package de.nereide.strohhalm.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncEventDao {

    /**
     * Newest first. [receivedOnly] narrows to attempts that fetched a pack —
     * the Activity screen's default view.
     */
    @Query(
        """
        SELECT * FROM sync_events
        WHERE startedAt >= :since
          AND (:receivedOnly = 0 OR outcome = 'RECEIVED')
        ORDER BY startedAt DESC
        """
    )
    fun observeRecent(since: Long, receivedOnly: Boolean): Flow<List<SyncEvent>>

    @Insert
    suspend fun insert(event: SyncEvent)

    @Query("DELETE FROM sync_events WHERE finishedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    /** Insert and prune atomically, so the log never needs the database itself. */
    @Transaction
    suspend fun recordAndPrune(event: SyncEvent, cutoff: Long) {
        insert(event)
        deleteOlderThan(cutoff)
    }
}
```

- [ ] **Step 6: Register the entity, converters, DAO and migration**

In `app/src/main/java/de/nereide/strohhalm/data/StrohhalmDatabase.kt` replace the `@Database` block and add the DAO accessor:

```kotlin
import androidx.room.AutoMigration

@Database(
    entities = [Repo::class, SyncEvent::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
@TypeConverters(SyncStatusConverter::class, SyncEventConverters::class)
abstract class StrohhalmDatabase : RoomDatabase() {

    abstract fun repoDao(): RepoDao

    abstract fun syncEventDao(): SyncEventDao
```

Keep the companion object unchanged.

- [ ] **Step 7: Build and confirm the schema exported**

Run: `./gradlew :app:assembleDebug -q 2>&1 | grep -E "^e:|error|BUILD"; ls app/schemas/de.nereide.strohhalm.data.StrohhalmDatabase/`
Expected: no errors; both `1.json` and `2.json` listed. Confirm `2.json` contains `"tableName": "sync_events"`.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/de/nereide/strohhalm/data/SyncEvent.kt app/src/main/java/de/nereide/strohhalm/data/SyncEventDao.kt app/src/main/java/de/nereide/strohhalm/data/StrohhalmDatabase.kt app/src/test/java/de/nereide/strohhalm/data/SyncEventConvertersTest.kt app/schemas/
git commit -m "feat(data): sync_events table with a 1-to-2 auto-migration"
```

---

### Task 2: The engine reports bytes received and refs changed

**Files:**
- Modify: `app/src/main/java/de/nereide/strohhalm/domain/GitMirror.kt` (the `Success` class)
- Modify: `app/src/main/java/de/nereide/strohhalm/domain/git/ProtocolMirror.kt:170-194`
- Test: `app/src/test/java/de/nereide/strohhalm/domain/git/MirrorEndToEndTest.kt`

**Interfaces:**
- Produces: `MirrorOutcome.Success(sizeBytes, refCount, bytesReceived: Long = 0, refsChanged: Int = 0)`.

- [ ] **Step 1: Write the failing end-to-end assertions**

Replace the test `a second sync is incremental and still valid` in `MirrorEndToEndTest.kt`:

```kotlin
    @Test
    fun `a second sync is incremental and still valid`() = runBlocking {
        val remote = remoteRepository()
        val destination = File(temp.root, "mirror2.git")
        val url = "ssh://test@127.0.0.1:${server.port}${remote.absolutePath}"
        val fingerprint = ProtocolMirror(keyPairProvider = { clientKey }).probeHostKey(url).getOrThrow()
        val mirror = ProtocolMirror(keyPairProvider = { clientKey })

        val first = mirror.sync(url, destination, fingerprint)
        assertTrue("first sync: $first", first is MirrorOutcome.Success)
        first as MirrorOutcome.Success
        assertTrue("the first sync received a pack", first.bytesReceived > 0)
        assertEquals("every ref is new on a first sync", first.refCount, first.refsChanged)

        val second = mirror.sync(url, destination, fingerprint)
        assertTrue("second sync: $second", second is MirrorOutcome.Success)
        second as MirrorOutcome.Success
        assertEquals("nothing moved, nothing received", 0L, second.bytesReceived)
        assertEquals("nothing moved, no ref changed", 0, second.refsChanged)

        assertTrue(git("fsck", "--strict", cwd = destination).isBlank())
    }
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*MirrorEndToEndTest*' -q 2>&1 | grep -E "^e:|BUILD" | head`
Expected: `Unresolved reference 'bytesReceived'`.

- [ ] **Step 3: Extend the outcome**

In `app/src/main/java/de/nereide/strohhalm/domain/GitMirror.kt` replace the `Success` line:

```kotlin
    /**
     * [bytesReceived] is the pack the server sent, and [refsChanged] the refs
     * whose target differs from before. Both are zero when nothing moved
     * upstream and the fetch was skipped.
     */
    data class Success(
        val sizeBytes: Long,
        val refCount: Int,
        val bytesReceived: Long = 0,
        val refsChanged: Int = 0,
    ) : MirrorOutcome
```

- [ ] **Step 4: Fill the fields in `ProtocolMirror`**

Replace the tail of the sync function (from `val haves = ...` to the final `return`):

```kotlin
        val before = mirror.localRefs()
        val haves = before.values.distinct()
        val wants = refs.map { it.objectId }.distinct()

        // Steady state: nothing moved upstream since the last sync. Skip
        // the fetch entirely — with `done` negotiation a server always
        // sends a pack section, and at the 15-minute floor an unconditional
        // fetch would accumulate tens of thousands of empty packs a year,
        // each one another file for git to open.
        val known = haves.toHashSet()
        if (wants.all { it in known }) {
            mirror.writeRefs(refs)
            return MirrorOutcome.Success(sizeBytes(destination), mirror.refNames().size)
        }

        // Shown until the server's own progress replaces it. For a large
        // repository the server legitimately spends minutes enumerating and
        // compressing before its first sideband line arrives, and that
        // silence must carry a name of its own.
        progress?.update("Waiting for the server to gather objects", 0, 0)
        val pack = protocol.fetch(caps, wants, haves) { line ->
            progress?.update(line, 0, 0)
        }
        val result = indexer.consume(pack, caps.objectHash, mirror.objectsDir(), progress)

        mirror.writeRefs(refs)
        val changed = refs.count { it.name != "HEAD" && before[it.name] != it.objectId }
        return MirrorOutcome.Success(
            sizeBytes = sizeBytes(destination),
            refCount = mirror.refNames().size,
            bytesReceived = result.bytes,
            refsChanged = changed,
        )
```

Note `HEAD` is excluded: `writeRefs` does not store it as a ref, so counting it would make `refsChanged` exceed `refCount` on a first sync.

- [ ] **Step 5: Run the end-to-end tests and confirm they pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*MirrorEndToEndTest*' -q 2>&1 | grep -E "^e:|BUILD"; grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' app/build/test-results/testDebugUnitTest/TEST-*MirrorEndToEnd*.xml`
Expected: `tests="3" skipped="0" failures="0" errors="0"`. If the class reports skipped, the system `git` lacks SHA-256 support; that is an environment fault, not a pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/de/nereide/strohhalm/domain/GitMirror.kt app/src/main/java/de/nereide/strohhalm/domain/git/ProtocolMirror.kt app/src/test/java/de/nereide/strohhalm/domain/git/MirrorEndToEndTest.kt
git commit -m "feat(domain): report pack bytes received and refs changed per sync"
```

---

### Task 3: `SyncLog`, and the runner records every attempt

**Files:**
- Create: `app/src/main/java/de/nereide/strohhalm/domain/SyncLog.kt`
- Modify: `app/src/main/java/de/nereide/strohhalm/domain/SyncRunner.kt`
- Test: `app/src/test/java/de/nereide/strohhalm/domain/SyncRunnerLogTest.kt`
- Test helper: `app/src/test/java/de/nereide/strohhalm/domain/RecordingSyncLog.kt`

**Interfaces:**
- Consumes: `SyncEvent`, `SyncEventOutcome`, `SyncTrigger` from Task 1; `MirrorOutcome.Success.bytesReceived/refsChanged` from Task 2.
- Produces: `interface SyncLog { suspend fun record(event: SyncEvent) }`, `object NoSyncLog`, `SyncRunner(repos, mirror, scope, foreground, access, log: SyncLog = NoSyncLog, clock: () -> Long = System::currentTimeMillis)`, `launchSyncAll(trigger: SyncTrigger = SyncTrigger.MANUAL)`, `launchSyncOne(id, trigger = MANUAL)`.

- [ ] **Step 1: Write the recording fake and the failing tests**

`app/src/test/java/de/nereide/strohhalm/domain/RecordingSyncLog.kt`:

```kotlin
package de.nereide.strohhalm.domain

import de.nereide.strohhalm.data.SyncEvent
import java.util.concurrent.CopyOnWriteArrayList

/** Collects events in memory; [failing] makes every record throw. */
class RecordingSyncLog(private val failing: Boolean = false) : SyncLog {
    val events = CopyOnWriteArrayList<SyncEvent>()

    override suspend fun record(event: SyncEvent) {
        if (failing) throw IllegalStateException("disk full")
        events += event
    }
}
```

`app/src/test/java/de/nereide/strohhalm/domain/SyncRunnerLogTest.kt`:

```kotlin
package de.nereide.strohhalm.domain

import de.nereide.strohhalm.data.SyncEventOutcome
import de.nereide.strohhalm.data.SyncStatus
import de.nereide.strohhalm.data.SyncTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One entry per attempt, stamped with what the mirror reported. The log is
 * bookkeeping: it must reflect the row exactly, and it must never be the
 * reason a sync is marked failed.
 */
class SyncRunnerLogTest {

    private val dao = FakeRepoDao()
    private val repos = DefaultRepoRepository(
        dao = dao,
        storageRoot = { File("/storage/emulated/0/Strohhalm") },
        clock = { 1_000L },
    )
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun mirrorReturning(outcome: MirrorOutcome) = object : GitMirror {
        override suspend fun sync(
            remoteUrl: String,
            destination: File,
            pinnedFingerprint: String?,
            progress: MirrorProgress?,
        ): MirrorOutcome = outcome

        override suspend fun probeHostKey(remoteUrl: String) = Result.failure<String>(
            UnsupportedOperationException()
        )

        override fun refNames(destination: File): List<String> = emptyList()

        override fun sizeBytes(destination: File): Long = 0
    }

    private suspend fun SyncRunner.awaitIdle() =
        withTimeout(TimeUnit.SECONDS.toMillis(10)) { running.first { !it } }

    @Test
    fun `a sync that received data is logged with its numbers`() = runBlocking {
        val log = RecordingSyncLog()
        var now = 5_000L
        val runner = SyncRunner(
            repos, mirrorReturning(MirrorOutcome.Success(sizeBytes = 99, refCount = 3, bytesReceived = 4_096, refsChanged = 2)),
            scope, log = log, clock = { now.also { now += 250 } },
        )
        val id = repos.add("Alpha", "ssh://git@host/srv/alpha.git", "SHA256:aaa")

        runner.launchSyncAll()
        runner.awaitIdle()

        val event = log.events.single()
        assertEquals(id, event.repoId)
        assertEquals("Alpha", event.repoName)
        assertEquals(SyncEventOutcome.RECEIVED, event.outcome)
        assertEquals(4_096L, event.bytesReceived)
        assertEquals(2, event.refsChanged)
        assertEquals(SyncTrigger.MANUAL, event.trigger)
        assertEquals(5_000L, event.startedAt)
        assertEquals(5_250L, event.finishedAt)
        assertNull(event.errorCode)
    }

    @Test
    fun `a sync with nothing to fetch is logged as up to date`() = runBlocking {
        val log = RecordingSyncLog()
        val runner = SyncRunner(repos, mirrorReturning(MirrorOutcome.Success(99, 3)), scope, log = log)
        repos.add("Alpha", "ssh://git@host/srv/alpha.git", "SHA256:aaa")

        runner.launchSyncAll()
        runner.awaitIdle()

        assertEquals(SyncEventOutcome.UP_TO_DATE, log.events.single().outcome)
        assertEquals(0L, log.events.single().bytesReceived)
    }

    @Test
    fun `a failure is logged with its error code`() = runBlocking {
        val log = RecordingSyncLog()
        val runner = SyncRunner(
            repos, mirrorReturning(MirrorOutcome.Failure(SyncError(SyncErrorCode.AUTH_FAILED))),
            scope, log = log,
        )
        repos.add("Alpha", "ssh://git@host/srv/alpha.git", "SHA256:aaa")

        runner.launchSyncAll()
        runner.awaitIdle()

        assertEquals(SyncEventOutcome.FAILED, log.events.single().outcome)
        assertEquals("AUTH_FAILED", log.events.single().errorCode)
    }

    @Test
    fun `the trigger is whatever the caller says`() = runBlocking {
        val log = RecordingSyncLog()
        val runner = SyncRunner(repos, mirrorReturning(MirrorOutcome.Success(99, 3)), scope, log = log)
        val id = repos.add("Alpha", "ssh://git@host/srv/alpha.git", "SHA256:aaa")

        runner.launchSyncOne(id, SyncTrigger.SCHEDULED)
        runner.awaitIdle()

        assertEquals(SyncTrigger.SCHEDULED, log.events.single().trigger)
    }

    @Test
    fun `a cancelled sync is logged as cancelled`() = runBlocking {
        val log = RecordingSyncLog()
        val started = CountDownLatch(1)
        val blocking = object : GitMirror by mirrorReturning(MirrorOutcome.Success(0, 0)) {
            override suspend fun sync(
                remoteUrl: String,
                destination: File,
                pinnedFingerprint: String?,
                progress: MirrorProgress?,
            ): MirrorOutcome = runInterruptible(Dispatchers.IO) {
                started.countDown()
                Thread.sleep(TimeUnit.MINUTES.toMillis(5))
                MirrorOutcome.Success(0, 0)
            }
        }
        val runner = SyncRunner(repos, blocking, scope, log = log)
        repos.add("Alpha", "ssh://git@host/srv/alpha.git", "SHA256:aaa")

        runner.launchSyncAll()
        started.await(10, TimeUnit.SECONDS)
        runner.cancel()
        runner.awaitIdle()

        assertEquals(SyncEventOutcome.CANCELLED, log.events.single().outcome)
    }

    @Test
    fun `a log that throws does not fail the sync`() = runBlocking {
        val runner = SyncRunner(
            repos, mirrorReturning(MirrorOutcome.Success(99, 3)), scope,
            log = RecordingSyncLog(failing = true),
        )
        val id = repos.add("Alpha", "ssh://git@host/srv/alpha.git", "SHA256:aaa")

        runner.launchSyncAll()
        runner.awaitIdle()

        assertEquals(SyncStatus.OK, dao.byId(id)!!.lastStatus)
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*SyncRunnerLogTest*' -q 2>&1 | grep -E "^e:|BUILD" | head`
Expected: `Unresolved reference 'SyncLog'`.

- [ ] **Step 3: Create the interface**

`app/src/main/java/de/nereide/strohhalm/domain/SyncLog.kt`:

```kotlin
package de.nereide.strohhalm.domain

import de.nereide.strohhalm.data.SyncEvent

/** Where the runner writes one entry per sync attempt. */
interface SyncLog {
    suspend fun record(event: SyncEvent)
}

/** Tests, and anywhere no history is wanted. */
object NoSyncLog : SyncLog {
    override suspend fun record(event: SyncEvent) = Unit
}
```

- [ ] **Step 4: Record in the runner**

In `app/src/main/java/de/nereide/strohhalm/domain/SyncRunner.kt`:

Add imports:

```kotlin
import de.nereide.strohhalm.data.SyncEvent
import de.nereide.strohhalm.data.SyncEventOutcome
import de.nereide.strohhalm.data.SyncTrigger
```

Change the constructor:

```kotlin
class SyncRunner(
    private val repos: RepoRepository,
    private val mirror: GitMirror,
    private val scope: CoroutineScope,
    private val foreground: ForegroundHold = NoForegroundHold,
    private val access: MirrorAccess = MirrorAccess(),
    private val log: SyncLog = NoSyncLog,
    private val clock: () -> Long = System::currentTimeMillis,
) {
```

Change the two launch methods:

```kotlin
    suspend fun launchSyncOne(id: Long, trigger: SyncTrigger = SyncTrigger.MANUAL): Boolean {
        val repo = repos.all().firstOrNull { it.id == id }
            ?.takeIf { it.hostKeyFingerprint != null }
            ?: return false
        return launch { sync(repo, trigger) }
    }

    /** @return whether a sync actually started; see [launchSyncOne]. */
    suspend fun launchSyncAll(trigger: SyncTrigger = SyncTrigger.MANUAL): Boolean {
        // Unverified repositories are skipped silently: contacting them would
        // only manufacture the refusal the UI already explains, once per cycle.
        val verified = repos.all().filter { it.hostKeyFingerprint != null }
        if (verified.isEmpty()) return false
        return launch { verified.forEach { sync(it, trigger) } }
    }
```

Change `sync` to take the trigger, use the injected clock, and record after each row write:

```kotlin
    private suspend fun sync(repo: Repo, trigger: SyncTrigger) {
        repos.markSyncing(repo.id)
        val startedAt = clock()
```

Replace the `catch (cancelled: CancellationException)` block:

```kotlin
        } catch (cancelled: CancellationException) {
            // The row must not be left claiming to sync, and the write has to
            // outlive the cancellation that triggered it — hence NonCancellable.
            // Rethrown so the scope still unwinds as cancelled.
            withContext(NonCancellable) {
                repos.markFailure(repo.id, SyncError(SyncErrorCode.CANCELLED))
                record(repo, trigger, startedAt, SyncEventOutcome.CANCELLED)
            }
            throw cancelled
        }
```

Replace the final `when (outcome)`:

```kotlin
        when (outcome) {
            is MirrorOutcome.Success -> {
                repos.markSuccess(repo.id, outcome.sizeBytes, outcome.refCount)
                record(
                    repo, trigger, startedAt,
                    if (outcome.bytesReceived > 0) SyncEventOutcome.RECEIVED else SyncEventOutcome.UP_TO_DATE,
                    bytesReceived = outcome.bytesReceived,
                    refsChanged = outcome.refsChanged,
                )
            }
            is MirrorOutcome.Failure -> {
                repos.markFailure(repo.id, outcome.error)
                record(repo, trigger, startedAt, SyncEventOutcome.FAILED, errorCode = outcome.error.code.name)
            }
        }
    }

    /**
     * Bookkeeping, never a reason to fail: the row already says what
     * happened, and a log entry lost to a full disk is the lesser fault.
     */
    private suspend fun record(
        repo: Repo,
        trigger: SyncTrigger,
        startedAt: Long,
        outcome: SyncEventOutcome,
        bytesReceived: Long = 0,
        refsChanged: Int = 0,
        errorCode: String? = null,
    ) {
        runCatching {
            log.record(
                SyncEvent(
                    repoId = repo.id,
                    repoName = repo.displayName,
                    startedAt = startedAt,
                    finishedAt = clock(),
                    outcome = outcome,
                    bytesReceived = bytesReceived,
                    refsChanged = refsChanged,
                    errorCode = errorCode,
                    trigger = trigger,
                )
            )
        }
    }
```

`startedAt` was already computed via `System.currentTimeMillis()`; it now uses `clock()`, and the `SyncProgress` construction below it is unchanged.

- [ ] **Step 5: Run the runner tests and confirm they pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*SyncRunner*' -q 2>&1 | grep -E "^e:|BUILD"; grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' app/build/test-results/testDebugUnitTest/TEST-*SyncRunner*.xml`
Expected: the log test reports `tests="6" ... failures="0"`, and the cancel and skip tests still pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/de/nereide/strohhalm/domain/SyncLog.kt app/src/main/java/de/nereide/strohhalm/domain/SyncRunner.kt app/src/test/java/de/nereide/strohhalm/domain/SyncRunnerLogTest.kt app/src/test/java/de/nereide/strohhalm/domain/RecordingSyncLog.kt
git commit -m "feat(domain): record one sync event per attempt through SyncLog"
```

---

### Task 4: The scheduled cycle records `SCHEDULED`

**Files:**
- Modify: `app/src/main/java/de/nereide/strohhalm/work/ScheduledSync.kt`
- Test: `app/src/test/java/de/nereide/strohhalm/work/ScheduledSyncTest.kt`

**Interfaces:**
- Consumes: `SyncRunner.launchSyncAll(trigger)` from Task 3, `RecordingSyncLog` from Task 3.

- [ ] **Step 1: Add the failing test**

Append inside `ScheduledSyncTest`:

```kotlin
    @Test
    fun `a scheduled cycle is logged as scheduled`() = runBlocking {
        val log = de.nereide.strohhalm.domain.RecordingSyncLog()
        val logging = SyncRunner(repos, mirror, scope, log = log)
        repos.add("A", "ssh://git@host/srv/a.git", "SHA256:aaa")

        ScheduledSync(runner = logging, repos = repos, preconditions = { null }, clock = { 1_000L }).run()

        assertEquals(
            de.nereide.strohhalm.data.SyncTrigger.SCHEDULED,
            log.events.single().trigger,
        )
    }
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*ScheduledSyncTest*' -q 2>&1 | grep -E "^e:|BUILD"; grep -A3 "<failure" app/build/test-results/testDebugUnitTest/TEST-*ScheduledSync*.xml | head -5`
Expected: one failure, `expected:<SCHEDULED> but was:<MANUAL>`.

- [ ] **Step 3: Pass the trigger**

In `ScheduledSync.kt` add `import de.nereide.strohhalm.data.SyncTrigger` and change the launch line:

```kotlin
        if (!runner.launchSyncAll(SyncTrigger.SCHEDULED)) return Outcome.NothingToSync
```

- [ ] **Step 4: Run and confirm it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*ScheduledSyncTest*' -q 2>&1 | grep -E "^e:|BUILD"; grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' app/build/test-results/testDebugUnitTest/TEST-*ScheduledSync*.xml`
Expected: `tests="7" skipped="0" failures="0" errors="0"`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/de/nereide/strohhalm/work/ScheduledSync.kt app/src/test/java/de/nereide/strohhalm/work/ScheduledSyncTest.kt
git commit -m "feat(work): stamp scheduled syncs as SCHEDULED in the log"
```

---

### Task 5: `DefaultSyncLog` with the 30-day prune, wired into the container

**Files:**
- Create: `app/src/main/java/de/nereide/strohhalm/domain/DefaultSyncLog.kt`
- Modify: `app/src/main/java/de/nereide/strohhalm/AppContainer.kt`
- Test: `app/src/test/java/de/nereide/strohhalm/domain/DefaultSyncLogTest.kt`
- Test helper: `app/src/test/java/de/nereide/strohhalm/domain/FakeSyncEventDao.kt`

**Interfaces:**
- Consumes: `SyncEventDao` from Task 1, `SyncLog` from Task 3.
- Produces: `DefaultSyncLog(dao: SyncEventDao) : SyncLog` with `RETENTION_MILLIS = 30 days`. `AppContainer.syncLog: SyncLog`. `FakeSyncEventDao` for Task 6.

- [ ] **Step 1: Write the fake DAO and the failing test**

`app/src/test/java/de/nereide/strohhalm/domain/FakeSyncEventDao.kt`:

```kotlin
package de.nereide.strohhalm.domain

import de.nereide.strohhalm.data.SyncEvent
import de.nereide.strohhalm.data.SyncEventDao
import de.nereide.strohhalm.data.SyncEventOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory [SyncEventDao] mirroring the SQL in the real one. */
class FakeSyncEventDao : SyncEventDao {

    val rows = MutableStateFlow<List<SyncEvent>>(emptyList())
    private var nextId = 1L

    override fun observeRecent(since: Long, receivedOnly: Boolean): Flow<List<SyncEvent>> =
        rows.map { list ->
            list.filter { it.startedAt >= since }
                .filter { !receivedOnly || it.outcome == SyncEventOutcome.RECEIVED }
                .sortedByDescending { it.startedAt }
        }

    override suspend fun insert(event: SyncEvent) {
        rows.value = rows.value + event.copy(id = nextId++)
    }

    override suspend fun deleteOlderThan(cutoff: Long) {
        rows.value = rows.value.filter { it.finishedAt >= cutoff }
    }
}
```

`recordAndPrune` is a default method on the interface, so the fake inherits it.

`app/src/test/java/de/nereide/strohhalm/domain/DefaultSyncLogTest.kt`:

```kotlin
package de.nereide.strohhalm.domain

import de.nereide.strohhalm.data.SyncEvent
import de.nereide.strohhalm.data.SyncEventOutcome
import de.nereide.strohhalm.data.SyncTrigger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class DefaultSyncLogTest {

    private val dao = FakeSyncEventDao()
    private val log = DefaultSyncLog(dao)

    private fun event(finishedAt: Long) = SyncEvent(
        repoId = 1, repoName = "Alpha",
        startedAt = finishedAt - 100, finishedAt = finishedAt,
        outcome = SyncEventOutcome.UP_TO_DATE, trigger = SyncTrigger.MANUAL,
    )

    @Test
    fun `recording keeps the entry`() = runTest {
        log.record(event(finishedAt = 1_000_000))
        assertEquals(1, dao.rows.value.size)
    }

    @Test
    fun `recording prunes entries older than thirty days before the new one`() = runTest {
        val now = TimeUnit.DAYS.toMillis(100)
        val day = TimeUnit.DAYS.toMillis(1)
        log.record(event(finishedAt = now - 31 * day))
        log.record(event(finishedAt = now - 29 * day))

        log.record(event(finishedAt = now))

        assertEquals(
            listOf(now - 29 * day, now),
            dao.rows.value.map { it.finishedAt },
        )
    }

    @Test
    fun `the retention is thirty days`() {
        assertEquals(TimeUnit.DAYS.toMillis(30), DefaultSyncLog.RETENTION_MILLIS)
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*DefaultSyncLogTest*' -q 2>&1 | grep -E "^e:|BUILD" | head`
Expected: `Unresolved reference 'DefaultSyncLog'`.

- [ ] **Step 3: Implement**

`app/src/main/java/de/nereide/strohhalm/domain/DefaultSyncLog.kt`:

```kotlin
package de.nereide.strohhalm.domain

import de.nereide.strohhalm.data.SyncEvent
import de.nereide.strohhalm.data.SyncEventDao
import java.util.concurrent.TimeUnit

/**
 * Writes events to Room and prunes on every write. One indexed DELETE per
 * sync is cheap, and doing it here means there is no separate maintenance
 * path to forget. The cutoff is relative to the event being written, not to
 * the wall clock, so a device with a wrong clock prunes consistently with
 * what it records.
 */
class DefaultSyncLog(private val dao: SyncEventDao) : SyncLog {

    override suspend fun record(event: SyncEvent) {
        dao.recordAndPrune(event, cutoff = event.finishedAt - RETENTION_MILLIS)
    }

    companion object {
        val RETENTION_MILLIS: Long = TimeUnit.DAYS.toMillis(30)
    }
}
```

- [ ] **Step 4: Run and confirm it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*DefaultSyncLogTest*' -q 2>&1 | grep -E "^e:|BUILD"; grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' app/build/test-results/testDebugUnitTest/TEST-*DefaultSyncLog*.xml`
Expected: `tests="3" skipped="0" failures="0" errors="0"`.

- [ ] **Step 5: Wire the container**

In `app/src/main/java/de/nereide/strohhalm/AppContainer.kt`:

Add imports:

```kotlin
import de.nereide.strohhalm.data.SyncEventDao
import de.nereide.strohhalm.domain.DefaultSyncLog
import de.nereide.strohhalm.domain.SyncLog
```

Add to the interface after `syncRunner`:

```kotlin
    /** History of sync attempts; the Activity screen reads it. */
    val syncLog: SyncLog
    val syncEventDao: SyncEventDao
```

Add to `DefaultAppContainer` before `syncRunner`, and pass the log:

```kotlin
    override val syncEventDao: SyncEventDao by lazy {
        StrohhalmDatabase.getInstance(appContext).syncEventDao()
    }

    override val syncLog: SyncLog by lazy { DefaultSyncLog(syncEventDao) }

    override val syncRunner: SyncRunner by lazy {
        SyncRunner(
            repos = repoRepository,
            mirror = gitMirror,
            scope = applicationScope,
            foreground = SyncForegroundService.hold(appContext),
            access = mirrorAccess,
            log = syncLog,
        )
    }
```

- [ ] **Step 6: Build and commit**

Run: `./gradlew :app:assembleDebug -q 2>&1 | grep -E "^e:|error|BUILD"`
Expected: no output (clean build).

```bash
git add app/src/main/java/de/nereide/strohhalm/domain/DefaultSyncLog.kt app/src/main/java/de/nereide/strohhalm/AppContainer.kt app/src/test/java/de/nereide/strohhalm/domain/DefaultSyncLogTest.kt app/src/test/java/de/nereide/strohhalm/domain/FakeSyncEventDao.kt
git commit -m "feat(domain): persist sync events with a thirty-day prune"
```

---

### Task 6: The Activity screen

**Files:**
- Create: `app/src/main/java/de/nereide/strohhalm/ui/activity/ActivityViewModel.kt`
- Create: `app/src/main/java/de/nereide/strohhalm/ui/activity/ActivityScreen.kt`
- Create: `app/src/main/java/de/nereide/strohhalm/ui/common/RelativeTime.kt`
- Modify: `app/src/main/java/de/nereide/strohhalm/ui/detail/RepoDetailScreen.kt:576-581` (remove the private `relative`, import the shared one)
- Modify: `app/src/main/java/de/nereide/strohhalm/ui/nav/AppNavHost.kt`
- Modify: `app/src/main/java/de/nereide/strohhalm/ui/list/RepoListScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/de/nereide/strohhalm/ui/activity/ActivityViewModelTest.kt`

**Interfaces:**
- Consumes: `SyncEventDao.observeRecent`, `FakeSyncEventDao`, `AppContainer.syncEventDao`, `DefaultSyncLog.RETENTION_MILLIS`, `SyncErrorCode.messageRes()` from `ui/common/SyncErrorText.kt`.
- Produces: `ActivityViewModel(dao: SyncEventDao, clock: () -> Long = System::currentTimeMillis)` with `receivedOnly: StateFlow<Boolean>`, `events: StateFlow<List<SyncEvent>>`, `setReceivedOnly(Boolean)`. `Routes.ACTIVITY`.

- [ ] **Step 1: Write the failing ViewModel test**

```kotlin
package de.nereide.strohhalm.ui.activity

import de.nereide.strohhalm.data.SyncEvent
import de.nereide.strohhalm.data.SyncEventOutcome
import de.nereide.strohhalm.data.SyncTrigger
import de.nereide.strohhalm.domain.FakeSyncEventDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val dao = FakeSyncEventDao()
    private val now = TimeUnit.DAYS.toMillis(100)

    private fun event(name: String, outcome: SyncEventOutcome, startedAt: Long) = SyncEvent(
        repoId = 1, repoName = name, startedAt = startedAt, finishedAt = startedAt + 10,
        outcome = outcome, trigger = SyncTrigger.SCHEDULED,
    )

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `opens showing only entries that received data, newest first`() = runTest(dispatcher) {
        dao.insert(event("older", SyncEventOutcome.RECEIVED, now - 2_000))
        dao.insert(event("clean", SyncEventOutcome.UP_TO_DATE, now - 1_500))
        dao.insert(event("newer", SyncEventOutcome.RECEIVED, now - 1_000))
        val viewModel = ActivityViewModel(dao, clock = { now })

        val shown = viewModel.events.first { it.isNotEmpty() }

        assertEquals(listOf("newer", "older"), shown.map { it.repoName })
    }

    @Test
    fun `turning the filter off shows everything`() = runTest(dispatcher) {
        dao.insert(event("clean", SyncEventOutcome.UP_TO_DATE, now - 1_500))
        dao.insert(event("failed", SyncEventOutcome.FAILED, now - 1_000))
        val viewModel = ActivityViewModel(dao, clock = { now })

        viewModel.setReceivedOnly(false)
        val shown = viewModel.events.first { it.isNotEmpty() }

        assertEquals(listOf("failed", "clean"), shown.map { it.repoName })
    }

    @Test
    fun `entries older than thirty days are not shown even if not yet pruned`() = runTest(dispatcher) {
        dao.insert(event("ancient", SyncEventOutcome.RECEIVED, now - TimeUnit.DAYS.toMillis(31)))
        dao.insert(event("recent", SyncEventOutcome.RECEIVED, now - TimeUnit.DAYS.toMillis(1)))
        val viewModel = ActivityViewModel(dao, clock = { now })

        val shown = viewModel.events.first { it.isNotEmpty() }

        assertEquals(listOf("recent"), shown.map { it.repoName })
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*ActivityViewModelTest*' -q 2>&1 | grep -E "^e:|BUILD" | head`
Expected: `Unresolved reference 'ActivityViewModel'`.

- [ ] **Step 3: Implement the ViewModel**

`app/src/main/java/de/nereide/strohhalm/ui/activity/ActivityViewModel.kt`:

```kotlin
package de.nereide.strohhalm.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.nereide.strohhalm.data.SyncEvent
import de.nereide.strohhalm.data.SyncEventDao
import de.nereide.strohhalm.domain.DefaultSyncLog
import de.nereide.strohhalm.ui.common.appContainer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * The filter defaults to entries that received data because that is the
 * question the screen exists to answer. The 30-day bound on the query is a
 * second line behind the prune in [DefaultSyncLog], so an entry the prune
 * has not reached yet still stays off the screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityViewModel(
    dao: SyncEventDao,
    clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _receivedOnly = MutableStateFlow(true)
    val receivedOnly: StateFlow<Boolean> = _receivedOnly.asStateFlow()

    val events: StateFlow<List<SyncEvent>> = _receivedOnly
        .flatMapLatest { only ->
            dao.observeRecent(since = clock() - DefaultSyncLog.RETENTION_MILLIS, receivedOnly = only)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setReceivedOnly(only: Boolean) {
        _receivedOnly.value = only
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { ActivityViewModel(dao = this.appContainer().syncEventDao) }
        }
    }
}
```

- [ ] **Step 4: Run and confirm it passes**

Run: `./gradlew :app:testDebugUnitTest --tests '*ActivityViewModelTest*' -q 2>&1 | grep -E "^e:|BUILD"; grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' app/build/test-results/testDebugUnitTest/TEST-*ActivityViewModel*.xml`
Expected: `tests="3" skipped="0" failures="0" errors="0"`.

- [ ] **Step 5: Share the relative-time helper**

`app/src/main/java/de/nereide/strohhalm/ui/common/RelativeTime.kt`:

```kotlin
package de.nereide.strohhalm.ui.common

import android.text.format.DateUtils

/** "5 minutes ago", minute resolution. */
fun relative(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(
        millis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS
    ).toString()
```

In `RepoDetailScreen.kt` delete the private `relative` function at the end of the file, delete the now-unused `import android.text.format.DateUtils` if nothing else uses it, and add `import de.nereide.strohhalm.ui.common.relative`.

- [ ] **Step 6: Strings**

Add to `app/src/main/res/values/strings.xml` after the `list_` block:

```xml
    <string name="list_activity">Activity</string>
    <string name="activity_title">Activity</string>
    <string name="activity_filter_received">Received data only</string>
    <string name="activity_empty_received">No sync has received data in the last 30 days.</string>
    <string name="activity_empty_all">No syncs in the last 30 days.</string>
    <string name="activity_received">%1$s received, %2$d refs changed</string>
    <string name="activity_up_to_date">Up to date</string>
    <string name="activity_cancelled">Stopped</string>
    <string name="activity_trigger_scheduled">scheduled</string>
    <string name="activity_trigger_manual">manual</string>
    <string name="activity_duration_seconds">%1$d s</string>
    <string name="activity_duration_minutes">%1$d min %2$d s</string>
```

- [ ] **Step 7: The screen**

`app/src/main/java/de/nereide/strohhalm/ui/activity/ActivityScreen.kt`:

```kotlin
package de.nereide.strohhalm.ui.activity

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.nereide.strohhalm.R
import de.nereide.strohhalm.data.SyncEvent
import de.nereide.strohhalm.data.SyncEventOutcome
import de.nereide.strohhalm.data.SyncTrigger
import de.nereide.strohhalm.ui.common.relative
import de.nereide.strohhalm.ui.common.syncErrorText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    onBack: () -> Unit,
    viewModel: ActivityViewModel = viewModel(factory = ActivityViewModel.Factory),
) {
    val events by viewModel.events.collectAsStateWithLifecycle()
    val receivedOnly by viewModel.receivedOnly.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.activity_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            FilterChip(
                selected = receivedOnly,
                onClick = { viewModel.setReceivedOnly(!receivedOnly) },
                label = { Text(stringResource(R.string.activity_filter_received)) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (events.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(
                            if (receivedOnly) R.string.activity_empty_received
                            else R.string.activity_empty_all
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(events, key = { it.id }) { event ->
                        EventRow(event)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun EventRow(event: SyncEvent) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(event.repoName, style = MaterialTheme.typography.titleMedium)
            Text(relative(event.finishedAt), style = MaterialTheme.typography.bodySmall)
        }
        val detail = when (event.outcome) {
            SyncEventOutcome.RECEIVED -> stringResource(
                R.string.activity_received,
                Formatter.formatShortFileSize(context, event.bytesReceived),
                event.refsChanged,
            )
            SyncEventOutcome.UP_TO_DATE -> stringResource(R.string.activity_up_to_date)
            SyncEventOutcome.FAILED -> syncErrorText(event.errorCode)
                ?: stringResource(R.string.error_unknown)
            SyncEventOutcome.CANCELLED -> stringResource(R.string.activity_cancelled)
        }
        Text(detail, style = MaterialTheme.typography.bodyMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(duration(event.durationMillis), style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(
                    when (event.trigger) {
                        SyncTrigger.SCHEDULED -> R.string.activity_trigger_scheduled
                        SyncTrigger.MANUAL -> R.string.activity_trigger_manual
                    }
                ),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun duration(millis: Long): String {
    val totalSeconds = millis / 1_000
    return if (totalSeconds < 60) {
        stringResource(R.string.activity_duration_seconds, totalSeconds)
    } else {
        stringResource(R.string.activity_duration_minutes, totalSeconds / 60, totalSeconds % 60)
    }
}
```

- [ ] **Step 8: Route and entry point**

In `AppNavHost.kt`:

```kotlin
import de.nereide.strohhalm.ui.activity.ActivityScreen
```

Add to `Routes`: `const val ACTIVITY = "activity"`.

Add to `RepoListScreen(...)` call: `onOpenActivity = { navController.navigate(Routes.ACTIVITY) }`.

Add a destination after `SETTINGS`:

```kotlin
        composable(Routes.ACTIVITY) {
            ActivityScreen(onBack = { navController.popBackStack() })
        }
```

In `RepoListScreen.kt`: add `import androidx.compose.material.icons.filled.History`, add the parameter `onOpenActivity: () -> Unit,` after `onAddRepo`, and insert between the Sync-now block and the Settings button:

```kotlin
                    IconButton(onClick = onOpenActivity) {
                        Icon(Icons.Filled.History, stringResource(R.string.list_activity))
                    }
```

- [ ] **Step 9: Build, run everything, commit**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest --rerun 2>&1 | grep -E "^e:|FAILED|BUILD"; grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' app/build/test-results/testDebugUnitTest/TEST-*.xml | awk -F'"' '{t+=$2;s+=$4;f+=$6;e+=$8} END {print "total="t" skipped="s" failures="f" errors="e}'`
Expected: `BUILD SUCCESSFUL`, `failures=0 errors=0`, total at least 296 (277 before this plan plus 4+0+6+1+3+3 new, minus nothing).

```bash
git add app/src/main/java/de/nereide/strohhalm/ui/activity/ app/src/main/java/de/nereide/strohhalm/ui/common/RelativeTime.kt app/src/main/java/de/nereide/strohhalm/ui/detail/RepoDetailScreen.kt app/src/main/java/de/nereide/strohhalm/ui/nav/AppNavHost.kt app/src/main/java/de/nereide/strohhalm/ui/list/RepoListScreen.kt app/src/main/res/values/strings.xml app/src/test/java/de/nereide/strohhalm/ui/activity/
git commit -m "feat(ui): Activity screen listing sync events, received-data filter on by default"
```

---

### Task 7: Instrumented migration test, and the guide

**Files:**
- Create: `app/src/androidTest/java/de/nereide/strohhalm/data/MigrationTest.kt`
- Modify: `CLAUDE.md` (§"NOT verified on hardware")

**Interfaces:**
- Consumes: exported schemas `1.json` and `2.json` from Task 1.

- [ ] **Step 1: Write the migration test**

There is no `androidTest` source set yet; create the directory. The dependencies (`room-testing`, `androidx.test.runner`, `ext.junit`) are already declared.

```kotlin
package de.nereide.strohhalm.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opens a version-1 database with a repository row, migrates to 2, and
 * checks the row survived and the new table exists. Needs a device; never
 * marked done on the strength of the JVM suite.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StrohhalmDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate1To2KeepsReposAndAddsSyncEvents() {
        val name = "migration-test.db"
        helper.createDatabase(name, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO repos (displayName, remoteUrl, localPath, lastStatus, sizeBytes, refCount, createdAt)
                VALUES ('Alpha', 'ssh://git@host/srv/alpha.git', '/storage/emulated/0/Strohhalm/alpha.git', 'NEVER', 0, 0, 1)
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(name, 2, true)

        db.query("SELECT COUNT(*) FROM repos").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM sync_events").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
        }
    }
}
```

- [ ] **Step 2: Compile it (a device is not required for that)**

Run: `./gradlew :app:compileDebugAndroidTestKotlin -q 2>&1 | grep -E "^e:|BUILD"`
Expected: no output. If `androidx.sqlite.db.framework` is unresolved, add `androidTestImplementation(libs.androidx.room.runtime)` is not needed; `room-testing` brings it. Fix any import error rather than deleting the assertion.

- [ ] **Step 3: Run on a device if one is attached, otherwise record that it was not**

Run: `./gradlew connectedDebugAndroidTest 2>&1 | grep -E "MigrationTest|BUILD|No connected"`
Expected on a device: `BUILD SUCCESSFUL`. Without one: note the skip in the commit message; do not claim it ran.

- [ ] **Step 4: Update the guide**

In `CLAUDE.md`, under "NOT verified on hardware", add before the protocol v2 bullet:

```markdown
- **The 1→2 Room auto-migration that adds `sync_events`.** Checked by Room's compile-time
  schema diff and by `MigrationTest` in `androidTest`, which has not been run on a device.
  Also unverified there: that the Activity screen's bytes-received figures match what
  `git fetch` would report for the same pack.
```

- [ ] **Step 5: Commit**

```bash
git add app/src/androidTest/ CLAUDE.md
git commit -m "test(data): instrumented 1-to-2 migration test for sync_events"
```
