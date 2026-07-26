# Strohhalm

Keeps offline mirror copies of remote git repositories on an Android device.

> ## ⚠️ Work in progress — not usable yet
>
> This repository currently contains the design, the implementation plan, the
> project scaffold and the pure-logic components with their tests. **The app does
> not yet sync anything.** The SSH transport, the git mirror engine, persistence
> and the entire user interface are not implemented.
>
> **`v0.1.2` should be able to mirror a repository — but that has never been
> proven against a real server.** Everything is in place: the git engine, host
> key confirmation, persistence, and an add/list/detail flow with a manual sync.
> Whether JGit and MINA SSHD actually work on Android is exactly what this build
> is for.
>
> **Current state**
>
> | Working | Not yet |
> | --- | --- |
> | Onboarding, permissions, folder picker | Background sync worker |
> | Ed25519 key generation, encrypted at rest | Sync interval setting |
> | Public key display and copy | Notifications |
> | Add repository with host-key confirmation | Verified against a real server |
> | Mirror clone/fetch, manual sync, ref listing | |
>
> 74 unit tests pass. Syncing is manual only — a background scheduler would hide
> the failures this build exists to surface.
>
> ### Reporting a failure
>
> Every error screen shows the library's own message and the exception class
> chain, with a **Copy diagnostics** button. That detail is deliberately visible
> rather than hidden behind a friendly message: the app is being developed
> without adb, so the screen is the only channel back.
>
> ### The path probe
>
> When you choose a backup folder, Strohhalm writes `strohhalm-path-probe.txt`
> into it, recording the path it *believes* it wrote to plus a random nonce.
>
> This exists because deriving a real filesystem path from the Android folder
> picker is an educated guess: the picker returns an opaque document id such as
> `primary:Strohhalm`, and mapping that to `/storage/emulated/0/Strohhalm` relies
> on undocumented behaviour. A plain write test would pass even if the guess
> resolved to a *different* real, writable directory. Locating the file yourself
> and comparing it against the recorded path is what actually proves the mapping
> — and confirms the folder is reachable from outside the app, which is the whole
> reason all-files access is requested.
>
> The file is safe to delete.

---

## What it will do

Strohhalm pulls; it never pushes. Each repository is stored as a bare
`git clone --mirror`, so every branch, tag and ref is captured — not just the
default branch — and upstream deletions are pruned on the next sync.

The device is the backup target. Data flows remote → device only. There is no
commit authoring, no conflict resolution and no way for the app to modify a
remote, by design.

## Recovery

Mirrors are ordinary bare repositories. Restoring one will need no Strohhalm:

    adb pull /storage/emulated/0/Strohhalm/myrepo.git .
    git clone myrepo.git myrepo

Keeping that property true is the point of the whole project. A backup you can
only read with the tool that made it is not a backup.

## Planned setup

1. Grant all-files access when prompted. Mirrors are stored in a folder you
   choose so they survive uninstalling the app; Android only permits that with
   this permission.
2. Choose or create a backup folder.
3. Copy the public key from Settings into your server's `authorized_keys`, or
   add it as a read-only deploy key.
4. Add a repository by its `ssh://` URL and confirm the host key fingerprint.

## Security design

- An Ed25519 key is generated on device. The private key never leaves it: only
  the 32-byte seed is stored, encrypted with AES-256-GCM under a key held in the
  Android Keystore, in internal storage — never in the backup folder, which is
  browsable and copied off-device by design.
- Host keys are pinned on first use. If a server later presents a different key,
  syncing stops and you are notified rather than silently trusting it.
- `android:allowBackup` is off: a restored backup would contain a key blob that
  cannot be decrypted on the new device.

## Sync behaviour

The interval will be configurable from 15 minutes to daily, or manual only.

Syncing is registered without WorkManager constraints on purpose — a constraint
defers work *silently*, and Strohhalm is built to tell you when a backup could
not run. The worker checks free space, storage access and connectivity itself,
and notifies when any of them blocks a sync.

Notifications appear only on failure. Success is silent.

## Build

Requires JDK 17 — Gradle 8.9's Kotlin DSL compiler cannot parse newer Java
version strings, and this fails before any toolchain setting applies:

```bash
export JAVA_HOME=/path/to/jdk-17
./gradlew assembleDebug
./gradlew :app:testDebugUnitTest --rerun
```

A release build is signed when `app/keystore.properties` exists (gitignored,
with `storeFile`, `storePassword`, `keyAlias`, `keyPassword`); without it,
`assembleRelease` produces an unsigned APK.

## Documentation

- `docs/superpowers/specs/` — the design, including alternatives that were
  rejected and why
- `docs/superpowers/plans/` — the task-by-task implementation plan
- `CLAUDE.md` — conventions and constraints for agents working in this repo

## Licence

See `LICENSE`.
