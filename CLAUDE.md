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

```bash
./gradlew assembleDebug                  # build
./gradlew :app:testDebugUnitTest         # JVM unit tests — fast, no device
./gradlew connectedDebugAndroidTest      # instrumented — needs a device/emulator
./gradlew installDebug                   # install on a connected device
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

---

## Current state

Spec and plan are written and approved. Implementation follows the 13 tasks in the plan,
in order. Task 2 gates everything that touches JGit: it pins the dependency versions and
confirms the `ServerKeyDatabase` API shape the mirror engine depends on.
