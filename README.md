# Strohhalm

Keeps offline mirror copies of remote git repositories on an Android device.

Strohhalm pulls; it never pushes. Each repository is stored as a bare mirror,
so every branch, tag and note is captured — not just the default branch — and
upstream deletions are pruned on the next sync. Data flows remote → device
only. There is no commit authoring, no conflict resolution and no way for the
app to modify a remote, by design.

The mirror engine is Strohhalm's own implementation of git's protocol v2 over
SSH. It follows whatever object format the server negotiates, so SHA-1 and
SHA-256 repositories both mirror correctly.

## Recovery

Mirrors are ordinary bare repositories in a folder you choose, readable by any
file manager and reachable over USB. Restoring one needs no Strohhalm:

    git clone /path/to/myrepo.git myrepo

Each repository also has a **Share** action that packs the mirror into a plain
zip — named `myrepo-git-backup-2026-07-28.zip` — verifies it, and hands it to
the system share sheet, so a backup can leave the phone by mail, chat, cable
or cloud drive. A sidecar checksum file is valid `sha256sum -c` input. The
zip is **not encrypted**; anyone you send it to can read the repository.

Keeping all of that true is the point of the whole project. A backup you can
only read with the tool that made it is not a backup.

## Setup

1. Grant all-files access when prompted. Mirrors live in a folder you choose
   so they survive uninstalling the app; Android only permits that with this
   permission.
2. Choose or create a backup folder.
3. Copy the public key from Settings into your server's `authorized_keys`, or
   add it as a read-only deploy key.
4. Add a repository by its `ssh://` or `git@host:path` URL. Adding never
   touches the network: the server is verified in the background from the
   repository's page, where you confirm the host key fingerprint — offered
   even while the server still refuses authentication, so a repository can
   be added before its key is installed. When authentication fails, the app
   shows the fix: copy the public key, and for GitHub or Codeberg a link to
   the exact settings page it belongs on.

### Server requirements

The server needs git 2.18 or newer — Strohhalm speaks protocol v2 only, and
says so rather than silently falling back to a weaker protocol. Hosted forges
(GitHub, GitLab, Codeberg, …) work as-is. A self-managed OpenSSH server must
have `AcceptEnv GIT_PROTOCOL` in its `sshd_config`; without it the server
answers with protocol v0 and Strohhalm reports that clearly instead of
mirroring.

## Sync behaviour

The interval is configurable from 15 minutes to daily, or manual only.
Notifications appear only on failure; success is silent. A running sync shows
a foreground notification with a Stop action.

Syncing is registered without WorkManager constraints on purpose — a
constraint defers work *silently*, and Strohhalm is built to tell you when a
backup could not run. The worker checks free space, storage access and
connectivity itself, and notifies when any of them blocks a sync.

A sync narrates itself: connecting, authenticating, reading the ref list,
waiting for the server to gather objects, receiving the pack (with a running
megabyte count), indexing, resolving deltas. Whatever the label under the
ticking clock says is what the sync is actually doing — a slow network, a
slow server and a hung transfer all look different.

## Security design

- An Ed25519 key is generated on device. The private key never leaves it:
  only the 32-byte seed is stored, encrypted with AES-256-GCM under a key held
  in the Android Keystore, in internal storage — never in the backup folder,
  which is browsable and copied off-device by design.
- Host keys are pinned when a repository is added. If a server later presents
  a different key, syncing stops and you are notified rather than silently
  trusting it.
- `android:allowBackup` is off: a restored backup would contain a key blob
  that cannot be decrypted on the new device.

## What has been proven, and what has not

Mirroring, host-key pinning, key handling and the storage flow are verified
on real hardware against a real server, and a shared archive has been
restored on a laptop: unpacked, `git fsck` clean, cloned, checked out.

Two things have not been proven on a device yet: mirroring a genuinely large
repository (tens of megabytes, tens of thousands of objects — covered by
tests, never timed on a phone), and backup folders on removable SD cards
(only device storage has been exercised). Treat both with appropriate
suspicion.

When something fails, the error screen shows the underlying message and
exception chain with a **Copy diagnostics** button — deliberately visible
rather than hidden behind a friendly summary.

### The path probe

When you choose a backup folder, Strohhalm writes `strohhalm-path-probe.txt`
into it, recording the path it *believes* it wrote to. Deriving a real
filesystem path from the Android folder picker relies on undocumented
behaviour; finding that file yourself at the recorded path is what proves the
mapping, and confirms the folder is reachable from outside the app. The file
is safe to delete.

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
