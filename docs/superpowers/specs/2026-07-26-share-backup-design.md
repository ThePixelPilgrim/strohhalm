# Strohhalm — share a backup as a zip

**Date:** 2026-07-26
**Status:** approved, not yet planned

## Purpose

Give a mirrored repository a **Share** action that packs it into a zip and hands
it to the system share sheet. Moving a backup off the phone currently means
plugging in a cable and finding the folder; this makes it a two-tap operation to
anywhere Android can send a file.

Nothing about mirroring changes. The feature only reads the mirror folder.

### Success criteria

- Sharing a mirror produces a zip that, once unpacked, is a valid bare
  repository: `git clone` restores it and `git fsck --strict` is clean. Recovery
  still needs no Strohhalm-specific tooling.
- A share abandoned part-way and started again reuses the finished archive
  instead of rebuilding it.
- A reused archive is proved intact before it is offered, and proved current
  against the mirror's refs.
- A partially written archive can never be mistaken for a complete one.

### Non-goals

- Encryption. See "Accepted risks".
- Sharing more than one repository at a time. The per-repo action is the whole
  feature; a share-everything action in Settings was considered and dropped as
  unnecessary surface area.
- Any git operation. The archive is a byte-for-byte copy of the mirror
  directory. Nothing parses an object, and `git bundle` — the git-native
  single-file format — is not an option because this engine deliberately never
  reads object bodies.
- Importing an archive back in. Restoring is `unzip` plus `git clone`, on a
  computer, which is the promise the storage design already makes.

## Decisions, and what was rejected

Each of these was chosen against named alternatives; they are recorded so they
are not re-litigated.

**One repository, from its detail screen.** Not a share-everything action in
Settings: an archive of every mirror could be hundreds of MiB, and the detail
screen already owns per-repo actions (Sync now, Delete).

**The zip is built in `cacheDir` and served through a `FileProvider`.**
Writing it next to the mirror on the backup volume was rejected because it
litters a folder the user browses, and because serving an SD-card path through
`FileProvider` alongside `MANAGE_EXTERNAL_STORAGE` is fiddly. Streaming the zip
on demand from a custom `ContentProvider` was rejected because share targets
that need a size upfront or random access would fail, and the failure would
surface inside the *other* app, where it cannot be explained.

**The cache is keyed on a fingerprint of all refs, not on HEAD.** Keying on HEAD
alone leaves a hole: a sync that adds only a tag or a side branch does not move
HEAD, so a stale archive would be reused — and it would verify clean, because
the zip is intact, merely old. Sharing an incomplete backup with no warning is
the worst outcome this feature can produce, and the full ref set costs nothing
extra to hash.

**A sidecar checksum file, not a database table.** The archive lives in
`cacheDir`, which Android may evict at any moment; a database row would then
confidently describe a file that no longer exists. A sidecar cannot outlive its
archive. It also avoids a schema migration.

**A failed sync abandons the share.** The alternative — archive the previous
good state and say so — is defensible, because a failed fetch renames no pack
into place and updates no refs, so the mirror is still valid and merely older.
It was rejected in favour of surfacing the sync error. *Asking* the user which
they want is a reasonable third option and may be adopted later; it was deferred
only because a modal that appears at the end of a long wait, potentially after
the phone has been put down, is awkward. Changing this decision affects one
branch in the ViewModel.

## Architecture

```
RepoDetailScreen ──▶ RepoDetailViewModel
                        ├─ waits on SyncRunner.running        (waiting state)
                        ├─ RefFingerprint      refs → cache key
                        ├─ ArchiveStore        lookup, verify, prune
                        │    └─ MirrorArchiver directory → zip + sha256
                        └─ ACTION_SEND via FileProvider
```

`MirrorArchiver` and `RefFingerprint` are pure JVM code and know nothing about
Android. `ArchiveStore` owns the cache directory and the sidecar format.

### `RefFingerprint`

SHA-256 over the sorted `"<objectid> <refname>"` lines — the same data
`git ls-remote` prints. `MirrorRepository.localRefs()` already produces exactly
this, so the fingerprint is one existing call plus a digest.

The digest is always SHA-256, independently of the mirror's own object format:
it identifies a *state of the ref list*, not a git object, so it has no reason
to follow the repository's hash. It must nevertheless be correct for mirrors of
both formats, since the object ids it consumes are 40 or 64 characters wide.

### `MirrorArchiver`

```kotlin
interface MirrorArchiver {
    fun archive(gitDir: File, target: File, progress: ArchiveProgress?): ArchiveResult
}
data class ArchiveResult(val sha256: String, val bytes: Long, val entries: Int)
```

Streams the directory into `target`. Two properties matter:

**The output is reproducible.** Entries are written in sorted path order with a
fixed entry timestamp, so the same mirror always yields the same bytes and the
same SHA-256. That makes the checksum a genuine content identity rather than an
artefact of when it ran, and it is what lets the test assert a stable hash.

**Pack files are stored, not re-deflated.** Entries ending `.pack` and `.idx`
are written at `Deflater.NO_COMPRESSION`; everything else takes the default.
Git packs are already deflated, so compressing them again costs real seconds on
a phone and saves approximately nothing.

Cancellation follows the mirror engine's discipline, because it is the same
problem: `runInterruptible`, the thread's interrupt flag polled between entries,
`CancellationException` rethrown out of `runCatching`.

### `ArchiveStore`

Owns `cacheDir/archives/`. For a repository whose slug is `yamiro` and whose
ref fingerprint begins `4f2a91c07b3e`, the three filenames are:

```
yamiro-4f2a91c07b3e.zip           the archive
yamiro-4f2a91c07b3e.zip.sha256    the sidecar: "<hex>  yamiro-4f2a91c07b3e.zip"
yamiro-4f2a91c07b3e.zip.part      the build in progress, never offered
```

The infix is the first 12 hex characters of the ref fingerprint. Encoding it in
the name makes a cache lookup a file-existence check, and makes every *other*
`yamiro-*.zip` identifiable as stale without reading anything.

`share(repo)` resolves to a ready file by:

1. Computing the current ref fingerprint.
2. If `<slug>-<fp12>.zip` exists **and** its SHA-256 matches the sidecar,
   returning it unchanged.
3. Otherwise: deleting this repo's other archives, building to
   `<name>.zip.part`, flushing to disk, hashing, writing the sidecar, then
   **renaming atomically** into place.

The `.part` name plus the rename is what makes "abort and start again" safe: an
incomplete archive never holds the final name, so a cancelled build cannot be
mistaken for a finished one. The checksum is the second line of defence, against
corruption or partial eviction after the fact.

Re-hashing on every share costs a second or two on a large archive. It is kept
because the whole point of reuse is that the reused file is trustworthy.

### Pruning when a sync makes an archive stale

Waiting until the next share to notice that an archive is stale means a
superseded archive can sit in the cache indefinitely — for a repository synced
every 15 minutes and shared once, that is the common case, not the edge case.

So pruning also runs **after a sync that changed the refs**. The sync already
holds the ref list it just wrote, so the new fingerprint costs a digest and no
extra I/O; every `<slug>-*.zip` whose infix differs from it is superseded, and
its sidecar with it.

This must not be observable from the UI, which constrains where it runs:

- On an **application-scoped** coroutine, not a ViewModel's. Pruning is not
  the user's business and must not be cancelled by navigating away, nor keep a
  screen alive.
- On `Dispatchers.IO`, launched **after** the sync's final database write rather
  than awaited by it. That write already runs under `NonCancellable` because it
  must outlive cancellation; putting file deletion in that critical path would
  make the sync's completion wait on unrelated work.
- Emitting no state. Nothing recomposes, no progress is reported, and a failure
  to delete is swallowed — a leftover file is worth zero user-facing noise, and
  the next share prunes it anyway.

**The archive currently being shared is never pruned.** An `ACTION_SEND` grant
stays readable until the receiving activity finishes, and a receiver that
streams rather than copies — a slow upload, say — is still reading the file
minutes later. Deleting it mid-read produces a corrupt transfer in *another*
app, which is both the worst place for it to surface and the hardest for the
user to attribute.

Two rules prevent it. `ArchiveStore` keeps the path most recently handed to a
share sheet, and skips it. Beyond that, an archive is only pruned once a
**grace period** has passed since it was last shared; the period exists because
there is no reliable signal for "the receiver is finished", so time is the only
honest proxy. A stale archive surviving one extra sync cycle costs disk; one
deleted too early costs a broken transfer.

### The waiting state

Tapping Share while a sync is running does not refuse. It enters a waiting
state:

| Event | Behaviour |
| --- | --- |
| Sync running | "Waiting for the sync to finish", showing that sync's live progress through the existing `SyncProgressBar` |
| Back | Share abandoned. Nothing is built; the sync is untouched and keeps running |
| Sync succeeds | Proceeds automatically to archiving, with progress and Stop |
| Sync fails or is cancelled | Share abandoned, the sync's error shown through the existing `SyncErrorText` mapping |

The wait observes `SyncRunner.running`, which is process-wide rather than
per-repo. That is a deliberate over-approximation: it may wait for a sync of a
*different* repository, which costs a little time and cannot cost correctness.
A per-repo signal would be `Repo.lastStatus == SYNCING`, but that value can be
left stale by a process death — the condition `SyncErrorCode.INTERRUPTED` exists
for — and waiting on it could wait forever.

Archiving a repository *while* it is being fetched is the thing being prevented.
A zip taken mid-fetch can capture a half-written pack, and it would checksum
clean: a corrupt backup that looks verified is worse than no backup.

### Android surface

- A `FileProvider` with authority `de.nereide.strohhalm.fileprovider`, its
  `<cache-path>` scoped to `archives/` so nothing else in the cache is exposed.
- `ACTION_SEND` with `type="application/zip"`, the `content://` URI, and
  `FLAG_GRANT_READ_URI_PERMISSION`.
- All new user-facing text goes in `res/values/strings.xml`, per the project
  rule.

## Error handling

`SyncErrorCode` is reused unchanged, so the existing string mapping keeps
working and no new error plumbing appears in the UI.

| Condition | Code |
| --- | --- |
| Not enough internal storage for the archive | `LOW_STORAGE` |
| Mirror folder unreachable (card not mounted) | `PERMISSION_LOST` |
| User pressed Stop | `CANCELLED` |
| Anything else | `UNKNOWN`, carrying the exception chain |

Free space is checked **before** building, against the repo row's `sizeBytes`.
This is the direct lesson of the unmounted-card failure: a storage problem must
be reported as a storage problem, up front, not as whatever happens to throw
first two minutes later.

## Testing

JVM unit tests for everything except the share sheet.

- **`MirrorArchiver`** — round-trips a directory (unzip, compare bytes);
  progress is reported; an interrupt leaves **no** file at the final name; the
  SHA-256 is identical across two runs over the same input.
- **`RefFingerprint`** — changes when a tag is added; unchanged when nothing
  moved. This is the case that motivated the cache key, so it is the case that
  must be pinned.
- **`ArchiveStore`** — builds once and reuses on the second call; a tampered zip
  forces a rebuild; a changed fingerprint forces a rebuild and prunes the old
  archive.
- **Pruning** — a changed ref list removes the superseded archive and its
  sidecar; an unchanged one removes nothing; the archive most recently handed to
  a share sheet survives even when superseded; and it becomes prunable once the
  grace period has elapsed. Time is injected, so the grace period is tested
  without waiting for it.
- **End to end** — archive a real mirror, unpack it, and run `git fsck --strict`
  and `git clone` on the result. Real git validating the output is the only
  thing that proves the archive is restorable, and it is the same technique
  `MirrorEndToEndTest` already uses.

**Needs a device, cannot be faked:** the `FileProvider` authority resolving, the
share sheet appearing, and a receiving app actually reading the URI.

## Accepted risks

- **The archive is unencrypted.** For a private repository it contains the full
  source history, and it leaves the app's sandbox the moment a share target is
  chosen. This is inherent to the feature. Mitigated only by a one-line warning
  at the point of sharing. Encryption would need a passphrase UI and a decryption
  story on the receiving end, which is a separate feature.
- **Cache growth.** Each repository ever shared leaves *at most one* archive in
  `cacheDir` — the current one — because a sync that moves the refs prunes its
  predecessor. Pruning stays per-repo rather than global, since keeping the
  latest archive is precisely what makes reuse work.
- **A shared archive outlives its usefulness by the grace period.** The window
  is deliberate: there is no signal for "the receiving app has finished reading
  the URI", so the alternative to waiting is deleting a file out from under an
  in-flight transfer.
- **Peak storage.** Building needs roughly the mirror's size free in internal
  storage, which on a device with a large mirror on an SD card may be the
  binding constraint. The precheck reports it rather than failing late.
- **`SyncRunner.running` is process-wide.** Waiting can be longer than strictly
  necessary. Correctness is unaffected.

## Implementation sequence

Each step is a task with its own test cycle, in TDD order.

1. `RefFingerprint` — sorted ref digest, over mirrors of both object formats.
2. `MirrorArchiver` — zip a directory, reproducible bytes, stored packs,
   progress, interrupt-safe.
3. `ArchiveStore` — naming, sidecar, verify, atomic rename, prune.
4. Post-sync pruning — application-scoped, off the sync's critical path,
   respecting the in-flight share and the grace period.
5. Free-space precheck and `SyncError` mapping.
6. `FileProvider`, manifest entry, `file_paths.xml`, `ACTION_SEND`.
7. `RepoDetailViewModel` — share state machine including the waiting state.
8. `RepoDetailScreen` — Share action, waiting UI, progress and Stop, strings.
9. End-to-end test: archive a mirror, unpack, `git fsck` and `git clone`.
10. Device check: the share sheet, a real receiving app, and a large mirror.

Step 10 needs hardware and must not be marked done without being run.
