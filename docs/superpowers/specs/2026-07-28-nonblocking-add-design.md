# Non-blocking add, background verification, and a copyable remote

*2026-07-28*

## Problems

1. **Adding a repository blocks on the network.** The add screen probes the
   server and holds the user on "Contacting the server…" until it answers. If
   the server is not fully set up — typically the public key is not yet in
   `authorized_keys` — the add fails and the repository cannot be added at
   all. The user is stuck watching a screen they cannot leave.
2. **After an SSH auth failure the user is on their own.** The fix is always
   the same — put the public key on the server — but the app leaves finding
   the right settings page to the user.
3. **The remote URL in the detail view can neither be copied nor selected.**

## What does not change

The host-key trust model. A human still looks at every fingerprint before it
is pinned, and a pinned key mismatch still aborts loudly. Only the *timing*
of confirmation moves out of the user's way. The rule that host-key problems
are never folded into generic failures applies to *mismatches*; a key that
was never pinned is a pending state, not an alarm.

## Design

### Add saves immediately

The add screen keeps URL and name fields and a single **Add repository**
button. Tapping it validates only the URL's shape (the `GitRemote.parse`
rules — same rejection of non-SSH URLs as today), saves the row with
`hostKeyFingerprint = null`, and navigates to the new repository's detail
view. No network I/O happens on the add screen, so there is nothing to wait
behind. The probe button, the in-screen spinner and the in-screen fingerprint
dialog go away.

No "clone immediately" checkbox: nothing blocks any more, and the first sync
starts by itself the moment the fingerprint is accepted (below), which is
what the checkbox was really asking for.

### The detail view verifies in the background

`hostKeyFingerprint == null` puts the detail view in a *verification* state,
driven by the existing `recheckHostKey` machinery:

- On entering the screen (or tapping retry), the probe runs in the
  background. A card shows "Verifying the server's identity…". The user can
  leave at any time; nothing is modal.
- **Probe succeeds** → the existing fingerprint dialog. Accept pins the key
  *and* launches the first sync in the background. Decline leaves the
  repository unverified, with the card offering to check again.
- **Auth refused, key seen** — the missing-public-key case. The host key is
  observed during the SSH key exchange, before authentication, so the
  fingerprint is available even though the probe failed. The dialog appears
  with a note: the server refused authentication, likely because the key is
  not installed there yet; accepting pins the identity now so syncing works
  as soon as the key is in place.
- **Server unreachable** → the ordinary diagnostic card with a retry button.

`probeHostKey` today returns the fingerprint only on full success, and its
auth-failure enrichment (`ProbeRejectedException`) only fires when the server
wrote to stderr. It is extended to carry the observed fingerprint out of
*every* failure where one was seen, so the UI can distinguish "auth failed,
identity known" from "nothing answered".

### Unverified repositories and sync

- Manual and scheduled syncs **skip** repositories with a null fingerprint —
  quietly, with no failure notification. Failing loudly every 15 minutes
  would repeat what the UI already shows.
- The list row of an unverified repository states that it is not verified
  yet, as its status line.
- The detail view's Sync and Share actions are disabled while unverified —
  the verification card is the one path forward, and a disabled button beats
  one that is silently skipped.

### The key-setup link

When the current failure is `AUTH_FAILED` — persisted `lastErrorCode` or a
failed probe — and the remote's host is one whose SSH-key settings page is
known, the failure card gains two actions:

- **Copy public key** — the same key Settings shows.
- **Open \<host\>'s key settings** — a browser intent to:
  - `github.com` → `https://github.com/settings/keys`
  - `codeberg.org` → `https://codeberg.org/user/settings/keys`

The mapping lives in one pure object (`KeySetupLinks`) keyed by the parsed
host, so adding a forge is one line. The card is not a one-shot hint: it
renders whenever the state says auth is failing, and disappears only when a
successful probe or sync clears that state.

For unknown hosts the card shows the two-step fix in words (copy the key,
add it to the server) with only the copy button.

### Copyable remote URL

In the detail view, the Remote row gains a copy icon (one tap → clipboard,
the pattern the public key uses elsewhere), and field values are wrapped in a
`SelectionContainer` so they are long-press selectable — which also covers
the local path and the fingerprint.

## Error handling

- A URL that does not parse is rejected at the add screen with the existing
  message; nothing is saved.
- Deleting an unverified repository needs no special handling — there is no
  mirror directory yet, and the delete flow already tolerates that.
- A probe running when the user leaves the detail view is abandoned; its
  result would only have updated UI state. Re-entering probes again.

## Testing

JVM tests, TDD as always:

- Add flow: saving without probing; URL-shape rejection; no fingerprint.
- `probeHostKey`: auth failure against a local SSHD that rejects the key
  still yields the observed fingerprint.
- Sync skipping: `SyncRunner`/worker never touch a null-fingerprint repo and
  record no failure for it.
- `KeySetupLinks`: host mapping, including unknown hosts and scp-style URLs.
- Detail state machine: verification states, accept-pins-and-syncs,
  auth-failed card persistence (state-driven, not event-driven).

What only a device can prove: the browser intent, the clipboard, and the
end-to-end flow against a real forge with the key genuinely absent.
