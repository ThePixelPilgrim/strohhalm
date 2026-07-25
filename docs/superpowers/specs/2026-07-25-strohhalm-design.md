# Strohhalm — Design

**Date:** 2026-07-25
**Status:** approved, ready for planning

## Purpose

Strohhalm keeps bare mirror clones of remote git repositories on an Android device
as offline backups. It refreshes them on a configurable schedule over SSH, using a
keypair it generates itself.

The device is the backup target: data flows remote → phone only. Strohhalm never
pushes, never commits, and never modifies a remote.

### Success criteria

- A repository added in the app ends up on device storage as a valid bare repo.
- Recovery requires no Strohhalm-specific tooling: `git clone /path/from/phone repo`
  restores a working copy with every branch and tag intact.
- Mirrors refresh unattended on the configured interval.
- Any condition that prevents a sync is surfaced to the user as a notification.

### Non-goals

- Pushing, committing, or any write to a remote.
- Browsing repository contents or file previews on device.
- Merge or conflict resolution — impossible by construction, as nothing local changes.
- Non-Android targets. Android-only is a settled decision; it is what rules out a
  shared Rust core.

## Technology decisions

### Git and SSH engine: JGit + Apache MINA SSHD

`org.eclipse.jgit` with the `org.eclipse.jgit.ssh.apache` transport. Both are pure
Java, so the app ships no native code. MINA SSHD provides the two extension points
the design depends on: `ServerKeyVerifier` for host key pinning, and programmatic
key loading so the private key is never written to a `~/.ssh`-style path.

Rejected alternatives:

- **JGit + the maintained JSch fork (`com.github.mwiede:jsch`)** — lighter and
  well proven on Android, but requires hand-written glue and manual host key
  handling. Retained as the fallback if the spike fails.
- **Rust via JNI** — gitoxide delegates `ssh://` to an external `ssh` binary, which
  does not exist on Android, so the pure-Rust path is unusable without also writing
  a `russh`-based transport. The remaining option, `git2-rs`, drags in
  libgit2 + libssh2 + OpenSSL cross-compiled for four ABIs. Choosing Rust for
  safety would mean shipping three C libraries where JGit ships none. The workload
  is network-bound, so native code buys little. No NDK or Rust Android toolchain
  exists in this environment today.
- **Bundling a `git` binary** — Android blocks executing files from app-writable
  directories on API 29+.

**Open risk:** the newest JGit release that works cleanly on Android with
`minSdk 26` is not established. The first implementation task is a throwaway spike
— generate a key, connect to a real remote, mirror it — before any UI exists. If it
fails, only `JGitMirror` changes.

### Clone mode: bare mirror

`clone --mirror`, giving `remote.origin.fetch = +refs/*:refs/*`, so every ref maps
1:1 into the local repo and pruning propagates upstream deletions. A plain clone
tracks only `refs/heads/*` and silently omits everything else, which is how backups
turn out incomplete. No working tree: roughly half the disk usage, and browsing
files on device is a non-goal.

### Storage: user-chosen folder via `MANAGE_EXTERNAL_STORAGE`

Mirrors live in a user-chosen directory on shared storage so they survive uninstall
and can be copied off the device. JGit needs a real `java.io.File` path, which the
Storage Access Framework cannot provide, so all-files access is required. It is
granted by hand through `ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION`; onboarding
explains this rather than firing the intent unannounced.

### Key: Ed25519, encrypted at rest

Generated on device via the EdDSA provider MINA SSHD already requires. The private
key is stored in **internal** app storage, encrypted with AES-256-GCM under an
AndroidKeyStore key — deliberately not in the user-chosen mirror folder, which is by
design browsable, copied off-device, and swept up by other backup tools.

The AES-GCM wrapper is hand-written (~40 lines) rather than depending on
`androidx.security:security-crypto`, which has remained in alpha and effectively
unmaintained. Fewer dependencies, no deprecation cliff, directly testable.

## Architecture

Layered as in the sibling project `stromschnelle`. Package `de.nereide.strohhalm`.

```
data/      Repo, RepoDao, StrohhalmDatabase, SettingsRepository, SyncInterval
domain/    RepoRepository, GitMirror, SshKeyStore, HostKeyVerifier (+ impls)
work/      SyncWorker, SyncScheduler, SyncNotifier
ui/        theme, nav, list, add, detail, settings
```

Three interfaces isolate every uncertain or security-critical dependency:

| Interface         | Responsibility                  | Isolates                          |
| ----------------- | ------------------------------- | --------------------------------- |
| `GitMirror`       | clone, fetch, prune, measure    | the only file importing JGit      |
| `SshKeyStore`     | generate, persist, export pubkey| the only file performing crypto   |
| `HostKeyVerifier` | TOFU accept / pin / reject      | pure logic, no I/O                |

`GitMirror` exists so a failed spike costs one file. `HostKeyVerifier` is reduced to
`(storedFingerprint, presentedKey) -> Accept | Reject | FirstUse` so that
security-critical logic — including the mismatch branch — is covered by plain JVM
unit tests instead of being exercised once by hand against a live server.

Dependency wiring follows `stromschnelle`: a manual `AppContainer`, no DI framework.

## Data model

### Room entity `Repo`

| Field                | Type      | Notes                                     |
| -------------------- | --------- | ----------------------------------------- |
| `id`                 | Long      | autogenerated primary key                 |
| `displayName`        | String    | user-facing label                         |
| `remoteUrl`          | String    | `ssh://user@host:port/path` or scp-style  |
| `localPath`          | String    | `<storageRoot>/<slug>.git`                |
| `hostKeyFingerprint` | String?   | `SHA256:…`, null until accepted           |
| `lastSyncAt`         | Long?     | epoch millis of last **successful** sync  |
| `lastAttemptAt`      | Long?     | epoch millis of last attempt, success or not |
| `lastStatus`         | enum      | `NEVER｜OK｜FAILED｜SYNCING`               |
| `lastError`          | String?   | rendered `SyncError` message              |
| `sizeBytes`          | Long      | on-disk mirror size                       |
| `createdAt`          | Long      | epoch millis                              |

`localPath` is `<storageRoot>/<slug>.git`, where `slug` is derived from the last path
segment of the remote URL, lowercased with non-alphanumerics collapsed to `-`. A
numeric suffix is appended on collision, and `localPath` carries a uniqueness
constraint so two repositories can never share a directory.

### Settings (DataStore Preferences)

- `syncInterval` — `SyncInterval` enum
- `storageRoot` — absolute path to the mirror directory
- `notifyOnFailure` — Boolean, default true

`SyncInterval` is an enum (`MANUAL, M15, M30, H1, H3, H6, H12, D1`), not a raw
integer. Every value is WorkManager-legal by construction, so the 15-minute periodic
floor cannot be violated by a bad write, and the settings dropdown is just `entries`.
`MANUAL` cancels the periodic work entirely.

## Key handling

One app-wide keypair, not one per repository.

- Generated lazily on first need.
- Public key rendered as `ssh-ed25519 AAAA… strohhalm@<device model>` in Settings,
  with a copy-to-clipboard button.
- "Regenerate" sits behind a confirmation dialog warning that every server needs the
  new key.

On Android 13+ the system shows its own copy confirmation; on API ≤ 12 the app shows
a snackbar, so the user gets exactly one confirmation on every version.

## Host key trust: TOFU with pinning

Adding a repository:

1. User enters remote URL and display name.
2. The app makes a probe connection that runs **no git operation**, purely to read
   the host key.
3. A dialog shows the `SHA256:…` fingerprint and key type for confirmation.
4. On accept, the fingerprint is written to the `Repo` row and the initial mirror
   clone is enqueued.

Every later connection compares the presented key against the pinned value. A
mismatch aborts the sync, records `HOST_KEY_MISMATCH`, and raises a distinct, loud
notification. It is never folded into a generic "sync failed", because unlike every
other failure it may indicate an active attack.

## Sync flow

`SyncWorker` is a `CoroutineWorker` registered as unique periodic work named
`strohhalm-sync` with `ExistingPeriodicWorkPolicy.UPDATE`.

### No WorkManager constraints

The work is registered with **no constraints at all** — not storage, not network.

A `Constraint` defers the worker silently: the OS holds it in `ENQUEUED`, the code
never runs, and the app cannot report why. That directly contradicts the requirement
to notify the user when a sync cannot run. Inverting it — always run, check
conditions in the worker, notify and back off — gives the same protective effect
while remaining observable.

### Worker sequence

1. Free space on the storage root below `MIN_FREE_BYTES` (250 MB) → notify,
   `Result.retry()`.
2. Storage root missing or all-files permission revoked → notify, `Result.failure()`.
3. No network → notify, `Result.retry()`.
4. Ensure the keypair exists.
5. For each repository, oldest-synced first:
   - no local directory → `clone --mirror`
   - otherwise → `fetch +refs/*:refs/*` with prune
   - on success: set `lastStatus = OK`, clear `lastError`, update `lastSyncAt`,
     `lastAttemptAt` and `sizeBytes`
   - on failure: set `lastStatus = FAILED` and `lastError`, update `lastAttemptAt`
     only — `lastSyncAt` continues to report when the mirror was last actually good.
6. If any repository failed, post one summarising notification.

A failure in one repository never aborts the others.

`Result.failure()` ends only that execution; periodic work remains scheduled and runs
again at the next interval. It is used where a retry within the same window cannot
help — a revoked permission needs user action — while `Result.retry()` is used for
conditions that may clear on their own.

### Long syncs

The first mirror of a large repository will exceed WorkManager's ten-minute
execution window, so the worker calls `setForeground()` with a progress
notification. This requires `foregroundServiceType="dataSync"` and
`FOREGROUND_SERVICE_DATA_SYNC` on API 34+.

### Notification policy

One fixed notification ID per failure *category*, so a repeated failure replaces its
predecessor instead of stacking, and the notification is cancelled on the next
success. Without this, a phone left offline overnight on a 15-minute interval would
produce 96 notifications.

## Error handling

A sealed `SyncError` with cases: `NoNetwork`, `LowStorage`, `PermissionLost`,
`AuthFailed`, `HostKeyMismatch`, `HostUnreachable`, `RemoteError`, `LocalCorrupt`.

Exceptions from JGit and MINA SSHD are mapped to `SyncError` in exactly one place,
inside `JGitMirror`. Library exception types never escape the domain layer, so no
ViewModel pattern-matches a `TransportException`, and each case can carry an
actionable message — `AuthFailed` reads "the server rejected this key — did you add
the public key from Settings?" rather than surfacing a stack trace.

## User interface

Compose with Material 3, navigation via `AppNavHost`, mirroring `stromschnelle`.

- **Repo list** — one row per repository: name, last sync time, status, size.
  Manual "sync now" action. FAB to add.
- **Add repo** — remote URL, display name, host key confirmation dialog.
- **Repo detail** — last sync, size, last error, sync now, delete. Deleting asks
  whether to remove the local mirror as well.
- **Settings** — sync interval dropdown, storage root picker, SSH public key with
  copy button, regenerate key, notification toggle.
- **Onboarding** — explains and requests all-files access, notification permission,
  and storage root selection on first run.

### Storage root picker

The system folder picker is used purely as a *chooser*, and its URI is discarded:

1. Launch `ACTION_OPEN_DOCUMENT_TREE` with a sensible `EXTRA_INITIAL_URI`.
2. Derive the real path from the returned tree URI:
   `DocumentsContract.getTreeDocumentId(uri)` yields e.g. `"primary:Strohhalm"`;
   split on `:`, mapping `primary` to `Environment.getExternalStorageDirectory()`.
   Non-primary volume ids match a `StorageVolume` uuid via `StorageManager`,
   resolvable through `StorageVolume.directory` from API 30.
3. Validate by attempting a test write at the derived path.
4. Persist only on success; on failure fall back to manual text entry.

Access itself comes from `MANAGE_EXTERNAL_STORAGE` and ordinary `java.io.File`, not
from the URI — so path derivation is a guess that is always verified before use
rather than an unchecked assumption.

Android 11+ refuses to return the root of primary storage from the picker, so the UI
reads "pick or create a folder for your mirrors". Creating `Strohhalm/` inside the
picker is the expected flow.

## Testing

**JVM unit tests**

- `HostKeyVerifier`: first use, match, mismatch.
- `SyncInterval` → `Duration` mapping, including `MANUAL`.
- Exception → `SyncError` mapping.
- `SettingsRepository` against a `FakeDataStore` (existing `stromschnelle` pattern).
- `DefaultRepoRepository` against a `FakeRepoDao`.
- Notification de-duplication policy.
- Tree-URI → path derivation, including the fallback branch.
- Slug derivation from remote URLs, including collision suffixing.
- `lastSyncAt` is preserved across a failed sync while `lastAttemptAt` advances.

**JVM unit tests against real JGit, no network**

`JGitMirror` driven against a `file://` bare repository in a temp directory. This
verifies the actual backup guarantee — that a branch never checked out still
arrives, that tags arrive, and that a branch deleted upstream is pruned locally —
without requiring a server or SSH.

**Instrumented tests**

- `SshKeyStore` encrypt/decrypt roundtrip (needs a real Keystore).
- Room migration test, following the existing `stromschnelle` pattern.

**Manual**

The spike, once, against a real remote.

## Permissions

- `INTERNET`
- `MANAGE_EXTERNAL_STORAGE` — manual grant, explained during onboarding
- `POST_NOTIFICATIONS` — runtime request, API 33+
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`

## Build

Copied from `stromschnelle`: `version.properties` as the single source of truth with
`versionCode` derived as `major * 10000 + minor * 100 + patch`; optional
`app/keystore.properties` release signing that degrades to an unsigned APK when
absent; `libs.versions.toml` version catalog; `minSdk 26`, `compileSdk`/`targetSdk`
35; Java 17; Compose BOM; Room with KSP; WorkManager; DataStore Preferences.

New dependencies: JGit, `jgit.ssh.apache`, an EdDSA provider. Exact versions are
determined by the spike.

`isMinifyEnabled = false` for release, as in `stromschnelle`. This is convenient
here: JGit and MINA SSHD rely heavily on reflection and `ServiceLoader`, so no
ProGuard keep rules are needed.

## Implementation sequence

1. **Spike** — key generation, SSH connection, mirror clone against a real remote.
   Throwaway; de-risks the JGit choice before any other work.
2. Project scaffold copied from `stromschnelle`.
3. `SshKeyStore` with encrypted storage.
4. `GitMirror` and `JGitMirror`, with `file://` tests.
5. `HostKeyVerifier` and its tests.
6. Room entity, DAO, database, `RepoRepository`.
7. `SettingsRepository` and `SyncInterval`.
8. `SyncWorker`, `SyncScheduler`, `SyncNotifier`.
9. UI: onboarding, list, add, detail, settings.
10. Instrumented tests, release signing, README.
