# Strohhalm — SHA-256 mirror engine

**Date:** 2026-07-26
**Status:** approved, planned in `docs/superpowers/plans/2026-07-26-sha256-mirror-engine.md`
**Supersedes:** §"Git and SSH engine: JGit + Apache MINA SSHD" in
`2026-07-25-strohhalm-design.md`. Everything else in that spec stands.

## Purpose

Strohhalm cannot mirror repositories that use the SHA-256 object format. This
replaces JGit with a hash-agnostic mirror engine, written in Kotlin over the
Apache MINA SSHD transport the app already uses.

Nothing about the product changes. The engine is swapped behind the existing
`GitMirror` interface; the scheduler, the storage design, key handling, host key
pinning, the notification policy and the UI are untouched.

### The failure being fixed

A SHA-256 remote dies on device with `EOFException: Short read of block` inside
`BasePackConnection.lsRefsImpl`. That reads like a truncated stream. It is not.
`GIT_TRACE_PACKET` against the same remote shows:

```
ls-remote< agent=git/2.55.0-Linux
ls-remote< object-format=sha256
```

JGit is parsing 64-hex-character object IDs with a parser built for 40. The
message describes the symptom at the socket, never the cause above it — the same
trap already recorded for `remote hung up unexpectedly`.

### Success criteria

- A SHA-256 remote mirrors to device storage as a valid bare repository.
- `git clone /path/from/phone repo` restores it, and `git fsck` reports no
  errors. Recovery still needs no Strohhalm-specific tooling.
- Existing SHA-1 mirrors, created by the JGit engine, keep refreshing in place.
- Whatever hash a remote negotiates, the engine follows it. A future format is a
  new `ObjectHash` entry, not a redesign.

### Non-goals

- Reading objects. The engine never parses a tree, commit, blob or tag body.
- Any local git operation: no checkout, diff, merge, revision resolution or gc.
- Writing upstream. Only `git-upload-pack` is ever invoked, so push does not
  exist in the codebase to be called by mistake.
- Protocol v0/v1. See "Accepted risks".

## Why not the alternatives

Each of these was investigated against current sources rather than recalled.

**Wait for JGit.** [Issue #73](https://github.com/eclipse-jgit/jgit/issues/73) —
opened 2024-07-19, last touched 2026-01-28, still open, no assignee, no
milestone, no linked patch. Two years, no implementation work. Not a plan.

**Fork JGit and add SHA-256.** Measured against a fresh clone:
`Constants.OBJECT_ID_LENGTH = 20` is a compile-time constant used in **153 places
across 56 files**, many in static contexts with no repository in scope;
`AnyObjectId` is five package-private `int` fields poked directly by
`ObjectIdOwnerMap` and the pack index readers; **306 files** reference `ObjectId`
in a 971-file, 238k-line core. Scoping the fork to the mirror path still means
working inside ~109k lines of unfamiliar security-relevant code, then rebasing it
forever. For comparison, gitoxide — designed from the start with a hash `Kind`
enum — has had a dedicated contributor on SHA-256 since roughly October 2025 and
as of May 2026 still does not cover the transport and protocol layers.

**libgit2 via NDK/JNI** (directly, or through the `git24j` binding). Works:
SHA-256 is available under `-DEXPERIMENTAL_SHA256=ON`, and its supported subset —
bare repositories including remote operations — is exactly this workload. Costs
libssh2 and OpenSSL cross-compiled for four ABIs, discards the SSH and keystore
work that is the only part of the app verified on real hardware, and ships three
C libraries where the app currently ships none.

**gitoxide wholesale.** Still shells out to an external `ssh` binary
(`[ ] ssh:// without an external ssh binary`, unchecked), which does not exist on
Android. Its SHA-256 support does not yet reach the protocol layer.

**A Rust SSH crate.** `russh` cannot build without `ring` or `aws-lc-rs`, both C
and assembly — the opposite of the reason to reach for Rust. `makiko` is genuinely
pure Rust but is a solo project, last released 2025-03-29, and states its crypto
is unaudited. `anvil-ssh` is a wrapper over `russh` (so the C remains), is
GPL-3.0-or-later against this app's MIT, and was two months old with 545
downloads when evaluated. MINA SSHD is already memory-safe, mature, and working
on device; every Rust option is a downgrade on the axis that motivated the look.

**Kotlin protocol + `gix-pack` over JNI.** The near miss, and the strongest
alternative. `gix_pack::bundle::write::write_to_directory` is `git index-pack` as
a library call, hash-parameterised, with `should_interrupt: &AtomicBool` and a
progress trait that map cleanly onto this app's cancellation and progress
designs — roughly 400 lines of new code instead of 1,500. Rejected for
distribution reasons, not technical ones: native code triggers Google Play's
16 KB page-size requirement (mandatory since 2025-11-01, and *"apps with no
native code should be compatible without any changes at all"*), needs a new build
target for each future ABI while `riscv64` is still provisional in the NDK, and
adds friction to reproducible F-Droid builds.

It is **not discarded**. `PackIndexer` (below) is the seam it would drop into.

## Scope

The engine implements exactly enough of git to mirror:

| Needed | Not needed |
| --- | --- |
| pkt-line framing | object body parsing |
| protocol v2 `ls-refs`, `fetch` | revision resolution |
| packfile parse + delta resolution | working tree, index |
| pack index (`.idx` v2) writing | merge, diff, gc, repack |
| bare repo layout, refs, `packed-refs` | any write to a remote |

That table is the reason this is tractable. Almost everything that makes SHA-256
hard in a general git library lives in the right-hand column.

## Architecture

```
SyncRunner ─▶ GitMirror (unchanged interface)
                └─ ProtocolMirror
                     ├─ UploadPackChannel   SSH exec channel, host key pinning
                     ├─ PktLine             framing + sideband demux
                     ├─ UploadPackV2        ls-refs / fetch negotiation
                     ├─ PackIndexer         pack datastream → .pack + .idx
                     └─ MirrorRepository    bare layout, refs, pruning
                        ObjectHash          hash kind, used by all of the above
```

Layers above `GitMirror` do not change. `SyncRunner`, `SyncForegroundService`,
`SyncNotifier`, `SyncPreconditions`, the ViewModels and every Compose screen keep
working against `MirrorOutcome` and `MirrorProgress` as they do today.

### `ObjectHash`

```kotlin
enum class ObjectHash(val configName: String, val rawLength: Int) {
    SHA1("sha1", 20),
    SHA256("sha256", 32);
    val hexLength: Int get() = rawLength * 2
    fun newDigest(): MessageDigest
}
```

Every component takes the hash as a parameter. There is no default and no
compile-time constant — that is the specific mistake being designed out.

### `UploadPackChannel`

Owns one SSH session and one exec channel running
`git-upload-pack '<path>'`. Built on MINA SSHD's `SshClient` directly rather than
through JGit's `SshdSessionFactory`.

- **Host key pinning** moves from JGit's `ServerKeyDatabase` to SSHD's
  `ServerKeyVerifier`. The decision logic in `HostKeyVerifier` is pure domain code
  and is reused unchanged, including the recording of a refusal reason in a field
  rather than an exception — SSHD still closes the session without propagating a
  cause.
- **Authentication** is publickey only, with the in-memory `KeyPair` from
  `keyPairProvider`. The private key still never touches the filesystem.
- **Exposes** `stdin`, `stdout` and `stderr` as separate streams.

That last point removes a whole class of bug. The diagnostic probe in
`JGitMirror.readServerMessage` exists only because JGit consumes stdout and
discards stderr, so a host's own explanation had to be harvested by opening a
*second* connection and racing a watchdog against a read that would otherwise
park forever. Owning the channel means stderr is drained concurrently with the
transfer, on the same connection. The probe, the watchdog, the 15-second deadline
and the leaked-session failure mode all go away.

### `PktLine`

Reader and writer for git's framing: a 4-hex length prefix, `0000` flush,
`0001` delimiter, `0002` response-end. Includes sideband demultiplexing — band 1
pack data, band 2 progress text, band 3 fatal error. Band 2 feeds
`MirrorProgress`; band 3 becomes a `REMOTE_ERROR` carrying the server's words.

### `UploadPackV2`

1. Read the capability advertisement. Require `version 2`; read the server's
   `object-format` (absent means `sha1`) and select the matching `ObjectHash`.
2. `ls-refs` with `peel`, `symrefs`, `unborn` → the full ref list.
3. `fetch`: `want` per remote ref, `have` per local ref tip, `done`.
4. Read the response sections, then stream the `packfile` section to
   `PackIndexer`.

Two deliberate choices:

**Do not advertise `thin-pack`.** It is an optional client capability; declining
it obliges the server to send a self-contained pack. That removes fix-thin —
patching deltas against objects only present locally — which is the single
nastiest part of index-pack, and the part that would otherwise force the engine
to read local objects it is designed never to read.

**Accept `ofs-delta`.** Offset deltas are resolved within the pack anyway and
meaningfully reduce transfer size on a mobile connection.

**Negotiation is tips-only.** The client sends one `have` per local ref rather
than walking history, because walking history means parsing commits. For the
mirror case — a local copy strictly behind the remote — the tips are enough for
the server to compute a good pack. The cost of a poor negotiation is a larger
download, never a wrong result. Recorded under accepted risks.

### `PackIndexer`

```kotlin
interface PackIndexer {
    fun consume(
        pack: InputStream,
        hash: ObjectHash,
        objectsDir: File,
        progress: MirrorProgress?,
    ): PackResult   // pack name, object count, bytes
}
```

**This interface is the seam.** `KotlinPackIndexer` is the first implementation;
a `GixPackIndexer` calling `write_to_directory` over JNI is the second, if
measurement ever justifies it. Nothing above this interface would change.

`KotlinPackIndexer` streams the pack to disk while parsing it: header and object
count, then per object a type and inflated size, `java.util.zip.Inflater` for the
body, offset and ref delta resolution, hash per object, and finally a `.idx`
version 2 written with `hash.rawLength`-wide entries. The pack trailer checksum
is verified against the bytes received.

Memory discipline matters here — a 72k-object pack must not be held in RAM. The
implementation is disk-backed: the `.pack` lands on disk first, and delta
resolution reads back through it.

### `MirrorRepository`

Creates and maintains the bare layout: `HEAD`, `objects/pack/`, `refs/`, and a
`config` carrying

```
[core]
	repositoryformatversion = 1
	bare = true
[extensions]
	objectFormat = sha256
```

for SHA-256 repositories, which is what makes the folder a valid repository to
the user's own `git`. Refs are written to `packed-refs` in one pass, with refs
absent from the remote removed — the mirror semantics `setRemoveDeletedRefs(true)`
provided before.

Fetches accumulate packs rather than repacking. For a backup that is a feature:
each sync appends, and nothing rewrites what is already on disk.

It also answers the two `GitMirror` methods that are not `sync`. `refNames` reads
`packed-refs` and any loose refs under `refs/` — JGit's `refDatabase` is gone, and
mirrors created by the old engine may hold refs in either place, so both must be
read. `sizeBytes` is a directory walk and is unchanged.

### `ProtocolMirror`

The `GitMirror` implementation, and the only class the rest of the app sees.

`sync` distinguishes clone from fetch exactly as today — `HEAD` present in the
destination means fetch — and keeps the existing recovery behaviour: a directory
without `HEAD` is a failed partial clone and is removed rather than left to wedge
the repository permanently.

`probeHostKey` no longer needs `ls-remote`. It opens an `UploadPackChannel`,
which completes the handshake and captures the host key, reads the capability
advertisement, and closes. That is strictly cheaper than the old path and reaches
the same point: a fingerprint captured before authentication, so a reachable host
behind an unreachable repository is still reported as a failure rather than
silently accepted.

Cancellation keeps the current two-part mechanism, because it is still blocking
socket reads that have to be unwound: `runInterruptible` around the transfer, the
thread's interrupt flag polled between units of work, `CancellationException`
rethrown out of `runCatching`, and the final database write under
`NonCancellable`.

## Compatibility with existing mirrors

Repositories created by the JGit engine are ordinary SHA-1 bare repositories. The
new engine reads their refs to build `have` lines and appends a new pack; no
migration, conversion or re-clone is required. This must be covered by a test
that fetches into a JGit-created fixture.

## Error handling

`SyncErrorCode` is unchanged — the existing codes still describe every outcome,
and the UI's string mapping keeps working. What changes is where the information
comes from:

| Condition | Before | Now |
| --- | --- | --- |
| Server refuses the repo | second connection, watchdog, stderr scrape | sideband band 3, or stderr, on the live channel |
| Host key mismatch | recorded in a field; JGit reports bare EOF | unchanged mechanism, same reason |
| Read stalls | JGit `setTimeout` re-arming per read | SSHD socket timeout, same re-arming semantics |
| Corrupt transfer | JGit's own check | pack trailer checksum mismatch → `LOCAL_CORRUPT` |

`SyncErrors.fromException` loses its JGit imports and keeps its structure. The
`ProbeRejectedException` path stays, since `probeHostKey` still needs to explain a
handshake that succeeded against a repository that did not.

## Integrity and the failure model

Worth stating plainly, because it sets how much this design can hurt if it is
wrong. The `.pack` is validated by a trailer checksum the **server** computes over
the pack bytes; the engine verifies it without interpreting a single object. The
`.idx` is the only artifact the engine authors, and it is fully derivable — `git
index-pack -f` regenerates it from the pack.

So a bug in the hardest, newest code produces a mirror that needs one command to
repair, not a mirror that silently lost data. That asymmetry is the reason this
design is acceptable in a backup tool at all.

## Testing

TDD throughout, per the project's working method. JVM unit tests except where a
device is genuinely required.

- **`ObjectHash`, `PktLine`, `.idx` writer** — plain unit tests, both hashes.
- **Pack fixtures** — real packs generated by the system `git` (2.53 is present)
  for both SHA-1 and SHA-256, checked in small. JGit may still be used to build
  SHA-1 fixtures; the project already permits JGit in tests.
- **Protocol** — a local MINA `SshServer` serving a canned `git-upload-pack`
  conversation, extending the harness already built for
  `JGitMirrorDiagnosticHangTest`. This is where sideband demux, band-3 errors and
  a stalled stream get their regression tests.
- **End to end, offline** — mirror a local SHA-256 repository over the local SSH
  server, then assert `git fsck` and `git verify-pack -v` on the result. Real git
  validating the output is the strongest evidence available without a device, and
  guards the one artifact the engine authors.
- **Existing-mirror fetch** — fetch into a JGit-created SHA-1 fixture.
- **Live** — a gated check against a real SHA-256 remote (`LIVE_CLONE=1`). Live
  tests name a specific remote and an operator's own SSH key, so they stay **out
  of the repository** and live only in the working tree; `.gitignore` enforces
  this. Treat it as a local verification step, not a checked-in test.
- **Device** — the 44 MiB / 72k-object repository, which has never completed a
  mirror. It is the benchmark for both correctness and memory behaviour, and
  cannot be faked.

## Migration

1. `ProtocolMirror` lands alongside `JGitMirror`; `AppContainer` chooses.
2. Once the live and device checks pass, `JGitMirror` and `AndroidSystemReader`
   are deleted and the JGit dependencies dropped.
3. `gradle/libs.versions.toml` gains `sshd-core` and `sshd-common` explicitly —
   they arrive transitively through `jgit-ssh-apache` today — and loses `jgit`
   and `jgit-ssh-apache`. The EdDSA provider stays.

Two constraints survive the swap and must not be "cleaned up":

- **`isMinifyEnabled = false`.** MINA SSHD still resolves implementations through
  `ServiceLoader` and reflection. The reason narrows; the rule does not change.
- **`SshdEnvironment.install(filesDir)` from `StrohhalmApp.onCreate`.** SSHD's
  `~` resolution still fails on Android, class-initialisation failure is still
  permanent for the process, and it must still run before `AppContainer`.

The project rule that one file owns the engine's imports carries over: the
engine's package is the only place in `src/main` that may import MINA SSHD, and
library exceptions are still mapped to `SyncError` before they escape it.

## Performance

The two hot paths are already native on Android and stay that way:
`java.util.zip.Inflater` wraps the platform zlib, and
`MessageDigest.getInstance("SHA-256")` reaches BoringSSL and the ARMv8 SHA-2
instructions. What runs as bytecode is delta application and bookkeeping, where
copying is under this code's control.

The workload is also network-bound — command-line `git` mirrors the benchmark
repository in under 100 seconds — which is the same reasoning the original spec
used to reject native code, and it still holds. Measure on the 44 MiB repository
before considering the `gix-pack` seam.

## Accepted risks

- **Protocol v2 only.** Servers older than git 2.18 (2018) are unsupported and
  get a clear `REMOTE_ERROR` rather than a fallback. Codeberg, GitHub, GitLab and
  Forgejo all speak v2. If a self-hosted remote ever needs v0, it is an additive
  change to `UploadPackV2`.
- **Tips-only negotiation** may produce a larger pack than git would on a
  divergent history. Correctness is unaffected. Revisit only if measurement shows
  it matters.
- **Pack accumulation.** Many small packs after many fetches. Acceptable, and
  arguably right for an append-only backup, but worth watching on a repository
  that syncs for a year.
- **A hand-written `.idx`** is new code in the position of highest consequence.
  Mitigated by real-git validation in tests and by the fact that the index is
  regenerable.

## Implementation sequence

Each step is a task with its own test cycle, in TDD order.

1. `ObjectHash` — both hashes, digest construction, hex lengths.
2. `PktLine` — framing, flush/delim/response-end, sideband demux.
3. `UploadPackChannel` — SSHD client, publickey auth, `ServerKeyVerifier` wired
   to `HostKeyVerifier`, exec channel, concurrent stderr drain.
4. `UploadPackV2` — capability advertisement and `object-format` selection.
5. `UploadPackV2` — `ls-refs`.
6. `PackIndexer` interface + `KotlinPackIndexer`: pack parse and non-delta
   objects, `.idx` v2 writing, trailer verification.
7. `KotlinPackIndexer`: `ofs-delta` and `ref-delta` resolution.
8. `UploadPackV2` — `fetch`, want/have/done, section parsing, pack streaming.
9. `MirrorRepository` — bare layout, `extensions.objectFormat`, `packed-refs`,
   pruning.
10. `ProtocolMirror` — the `GitMirror` implementation: clone vs fetch, progress,
    cancellation via `runInterruptible`, `SyncError` mapping.
11. End-to-end offline test with `git fsck` / `git verify-pack` validation.
12. Fetch into a JGit-created SHA-1 mirror.
13. Live check against the real SHA-256 remote.
14. Device check: the 44 MiB repository, plus cancellation on device.
15. Remove `JGitMirror`, `AndroidSystemReader` and the JGit dependencies.

Steps 13 and 14 need a real remote and real hardware. Like Task 2 and Task 13 of
the original plan, they cannot be automated and must not be marked done without
being run.
