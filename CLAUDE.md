# Strohhalm — agent guide

Android app that keeps **offline bare-mirror backups of remote git repositories**,
pulled over SSH on a configurable schedule. Data flows remote → device only.

Kotlin · Jetpack Compose + Material 3 · Room · DataStore · WorkManager · JGit + Apache MINA SSHD

---

## Read this before you touch anything

| If you are about to… | Read first | Why |
| --- | --- | --- |
| implement any numbered task | `docs/superpowers/plans/2026-07-25-strohhalm.md` — **that task only** | Each task carries exact file paths, full code, and its own test cycle. Do not read the whole plan; it is ~5,600 lines. |
| question *why* something is designed a certain way | `docs/superpowers/specs/2026-07-25-strohhalm-design.md` | Records rejected alternatives (Rust FFI, JSch, plain clone) with reasons. Re-litigating them wastes a turn. |
| write a Gradle file, ViewModel, DAO, or Compose screen | the matching file in `~/Projects/stromschnelle` | Sibling app, same author, same conventions. Copy its shape rather than inventing one. |
| touch SSH, keys, or host-key logic | §"Key handling" and §"Host key trust" in the spec | Security-critical, and the reasoning is not obvious from the code. |
| add a dependency | `gradle/libs.versions.toml` | Version catalog only. Never inline a coordinate in `app/build.gradle.kts`. |

---

## Non-negotiable constraints

These hold for every task. Violating one is a defect even if tests pass.

- **Package**: `de.nereide.strohhalm`. `minSdk 26`, `compileSdk`/`targetSdk 35`, Java 17.
- **`isMinifyEnabled = false`** for release. JGit and MINA SSHD resolve implementations
  through reflection and `ServiceLoader`; R8 would strip them. Do not enable minification
  and do not "fix" this with keep rules.
- **Never push, commit, or write to a remote.** Strohhalm mirrors read-only. Any code
  path that could write upstream is a bug, not a feature.
- **`JGitMirror.kt` is the only file in `src/main` that may import JGit or MINA SSHD.**
  Same rule for crypto in `EncryptedSshKeyStore`/`KeystoreCipher`. Tests may import JGit
  to build fixtures.
- **Library exceptions never escape the domain layer.** Map them to `SyncError` inside
  `JGitMirror`. No ViewModel may pattern-match a `TransportException`.
- **The private key never leaves internal storage.** It must never be written to the
  user-chosen mirror folder, which is browsable and copied off-device by design.
- **`SyncWorker` registers with `Constraints.NONE`.** A WorkManager constraint defers work
  *silently*, which contradicts the requirement to notify the user when a sync cannot run.
  All condition checks live in `SyncPreconditions`, inside the worker.
- **No sync interval below 15 minutes** — WorkManager's periodic floor. `SyncInterval` is
  an enum so this cannot be violated by a bad write.
- **No hardcoded user-facing strings** in Compose. Everything goes through
  `res/values/strings.xml`.
- **`versionName` lives only in `version.properties`.** `versionCode` is derived
  (`major*10000 + minor*100 + patch`). Never hand-edit `versionCode`.

---

## Working method

- **TDD, strictly.** Write the failing test, run it and *see it fail*, implement the
  minimum, run it and see it pass, commit. The plan's steps are already in this order.
- **Never claim a test passes without running it** and reading the output. If something
  fails, say so with the output.
- **Commit per task**, conventional-commit style: `feat(domain):`, `test(data):`,
  `docs:`, `fix(ui):`.
- **Prefer JVM unit tests** (`app/src/test`). Use instrumented tests
  (`app/src/androidTest`) only where the Android Keystore, Room, or the framework is
  genuinely required.

---

## Commands

**Every Gradle invocation must set `JAVA_HOME`.** This machine's default JDK is
Java 25, and Gradle 8.9's embedded Kotlin DSL compiler cannot parse that version
string — a bare `./gradlew` dies with `java.lang.IllegalArgumentException: 25.0.2`
at `JavaVersion.parse` *before any task runs*. A Kotlin `jvmToolchain` does not
help: the failure happens while compiling the build script itself, before
toolchain resolution.

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk

./gradlew assembleDebug                    # build
./gradlew :app:testDebugUnitTest           # JVM unit tests — fast, no device
./gradlew :app:testDebugUnitTest --rerun   # force re-run; see caveat below
./gradlew connectedDebugAndroidTest        # instrumented — needs a device/emulator
./gradlew installDebug                     # install on a connected device
```

**`UP-TO-DATE` is not evidence.** Gradle skips a test task whose inputs are
unchanged, so a green `BUILD SUCCESSFUL` can mean nothing ran. To actually verify,
pass `--rerun` and read the per-class counts:

```bash
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' \
  app/build/test-results/testDebugUnitTest/TEST-*.xml
```

If Gradle cannot find the SDK, create `local.properties` with
`sdk.dir=/home/christoph/Android/Sdk`.

---

## Environment gotchas

- **`gh` and git credentials need the D-Bus session bus.** `gh` stores its token in the
  system keyring (Secret Service), not in `~/.config/gh/hosts.yml`. Shells without a
  session bus get `DBUS_SESSION_BUS_ADDRESS=disabled:` and `gh` then reports the token as
  *invalid* when it simply cannot reach it. Prefix such calls:

  ```bash
  DBUS_SESSION_BUS_ADDRESS="unix:path=$XDG_RUNTIME_DIR/bus" gh ...
  ```

- **Two tasks cannot be automated.** Task 2 (the JGit/SSHD spike) needs a physical device
  and a real git remote whose `authorized_keys` you can edit. Task 13's periodic-sync
  verification needs a device. Do not fake these or mark them done.

- **MINA SSHD needs a home directory on Android, set before it is first touched.**
  SSHD resolves `~` in a chain of static holders ending at
  `PathUtils$LazyDefaultUserHomeFolderHolder`, which throws on Android because there is
  no `user.home`. `SshdEnvironment.install(filesDir)` calls
  `PathUtils.setUserHomeFolderResolver` and runs from `StrohhalmApp.onCreate` for a
  reason: **class-initialisation failure is permanent for the process.** Once those
  holders have failed, every later SSH attempt throws `NoClassDefFoundError` and setting
  the resolver afterwards cannot repair it — only restarting the app can. Never make this
  lazy, and never move it after `AppContainer` construction.

  The symptom is also misleading: JGit reports `remote hung up unexpectedly`, which reads
  like a network fault. Check the cause chain before believing the message.

- **Never read a remote stream to EOF without a deadline.** `readServerMessage`
  execs `git-upload-pack` to harvest the server's stderr, which is the only way to see
  a git host's own explanation. But against a **healthy** repository upload-pack writes
  its advertisement to *stdout*, writes **nothing** to stderr, and waits for a request
  the probe never sends. Reading stderr to EOF then parks forever — sshd keepalives keep
  the session alive, and `RemoteSession.exec`'s timeout bounds only channel *open*, not
  the streams. The result was a sync wedged inside its own error handler with no error,
  no progress and no end, and a leaked SSH connection per attempt (the `finally` never
  ran), which made the *next* attempt likelier to fail — self-reinforcing. A watchdog now
  disconnects the session at the deadline. Note `RemoteSession.exec` takes **seconds**;
  passing a millisecond constant silently yields a multi-hour timeout.

- **JGit installs no read timeout unless you ask.** `BasePackConnection.init` only wraps
  the stream in a `TimeoutInputStream` when `transport.getTimeout() > 0`. With the default
  of 0, a silently dropped connection blocks a read forever. Every clone/fetch/ls-remote
  must call `setTimeout(...)` — the timer re-arms per read, so large transfers are safe.

- **Cancelling a sync needs a thread interrupt, not just a cancelled coroutine.** The
  mirror spends its time in blocking JGit socket reads; `job.cancel()` alone would flip
  the flag and clear the UI while the transfer kept running. `JGitMirror` wraps the
  transfer in `runInterruptible`, and `ProgressMonitor.isCancelled()` reports the thread's
  interrupt flag so JGit also stops cleanly when progress is flowing. Two matching traps:
  `runCatching` swallows `CancellationException` unless it is rethrown, and the row's
  final DB write must run under `NonCancellable` or it never happens.

- **Kotlin block comments nest.** Writing a git refspec such as the literal
  `+refs/<star>:refs/<star>` inside a KDoc opens a nested comment that never closes, and
  the file fails with `Unclosed comment` — a confusing error pointing at the end of the
  file rather than the comment. Write refspecs in prose (`+refs/…:refs/…`) inside
  comments; the literal glob is fine inside string literals.

- **Parallel agents cannot share this repo's build directory.** Kotlin compiles the whole
  test source set at once, so one agent's not-yet-implemented test breaks *everyone's*
  `compileDebugUnitTestKotlin`; and concurrent `testDebugUnitTest` runs clobber each
  other's `app/build/test-results`, producing `NoSuchFileException: …/output.bin.idx`.
  If work is parallelised, either give each agent its own worktree, or have agents write
  files only and let a single final stage build, test and commit.

---

## Current state

Released so far: `v0.1.0` (pipeline test), `v0.1.1` (onboarding, key generation, path probe),
through `v0.1.9` (mirror engine, SSH transport, host-key pinning, live progress, foreground
service, cancellable syncs), and `v0.2.0` (the hash-agnostic mirror engine).

**The mirror engine has been replaced.** `AppContainer` now builds a `ProtocolMirror`, a
Kotlin implementation of git's protocol v2 over MINA SSHD that follows whatever hash the
remote negotiates — so SHA-256 remotes work. All 15 tasks of
`docs/superpowers/plans/2026-07-26-sha256-mirror-engine.md` are implemented; the spec is
`docs/superpowers/specs/2026-07-26-sha256-mirror-engine-design.md`.

`JGitMirror` and `AndroidSystemReader` are **deliberately still present but unused**. The
plan gates their deletion on the hardware checks below, and deleting the only engine ever
verified on a device on the strength of unit tests is the mistake that gate exists to
prevent. Do not remove them, or the JGit `implementation` dependencies, until those checks
pass. JGit stays a test dependency permanently — it builds SHA-1 pack fixtures.

### Verified on real hardware

Do not re-litigate these; they were confirmed on device, not just by unit tests.

- **`MANAGE_EXTERNAL_STORAGE` grant flow** works via
  `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`.
- **SAF document-id → real path derivation works for the `primary` volume.** Confirmed
  by `StorageProbe`: the marker file was found on the filesystem at exactly the path the
  app recorded. This is the undocumented mapping the whole storage design rests on.
- **The chosen folder is writable, persists writes, and is reachable from outside the
  app** — the property that makes mirrors recoverable after an uninstall.
- **`KeystoreCipher` + `EncryptedSshKeyStore` work on device.** An Ed25519 key generates,
  the seed encrypts under an AndroidKeyStore AES-GCM key, persists, and reads back — the
  public key renders, copies to the clipboard, and regeneration produces a working new
  key. Confirmed on hardware, not merely by the instrumented tests (which have still
  never been run).

### NOT verified on hardware

- **The removable-volume branch of `StorageRootResolver`** (`StorageVolume.uuid` lookup,
  API 30+). Only the `primary` branch has been exercised. Keep `StorageProbe` — it is how
  this gets checked if anyone points Strohhalm at an SD card.
- **Large-repository mirroring.** A 4 MB repo mirrors successfully over SSH on device.
  A 44 MiB / 72k-object repo has never completed: it wedged in the unbounded diagnostic
  probe described above. The fix is covered by `JGitMirrorDiagnosticHangTest` (a local
  MINA `SshServer` reproducing the exact device failure) but has **not** been confirmed
  on hardware.
- **Cancelling a sync on device.** Covered by `SyncRunnerCancelTest`, unverified on
  hardware — including whether the notification's Stop action reaches the service.
- **The entire `ProtocolMirror` engine, shipped in `v0.2.0`.** No part of it has run on a
  device or against a real forge. What *is* verified: `MirrorEndToEndTest` mirrors a real
  SHA-256 repository over a local MINA `SshServer` and the system `git` then accepts the
  result under `git fsck --strict` and `git verify-pack -v` — real git validating the one
  artifact the engine authors, which is the strongest evidence obtainable without hardware.
  Four things that test cannot reach, and that gate deleting JGit:
  1. A real SHA-256 remote mirrors end to end.
  2. `git clone` from the mirror folder restores a working tree.
  3. The 44 MiB / 72k-object repository completes. Watch memory — `KotlinPackIndexer` is
     disk-backed (a temp `.pack` read back through two `RandomAccessFile` passes) precisely
     so this cannot blow the heap, and this is the benchmark that proves it.
  4. Cancelling a running sync stops it, including via the notification's Stop action.
- **That protocol v2 activation reaches a real server.** The engine requests v2 out-of-band
  via the `GIT_PROTOCOL=version=2` SSH channel env var, exactly as git does. Hosted forges
  accept it; a self-managed OpenSSH server needs `AcceptEnv GIT_PROTOCOL` in `sshd_config`,
  and without it the engine reports a clear v0 refusal rather than mirroring. Only a local
  test server has ever answered this.
