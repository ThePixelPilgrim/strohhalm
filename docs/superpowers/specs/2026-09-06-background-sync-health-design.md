# Strohhalm — background sync health

**Date:** 2026-09-06
**Status:** approved, implemented in the same session
**Builds on:** `2026-07-25-strohhalm-design.md` §Scheduling; the sync activity log spec.

## Purpose

On a rebooted phone the scheduled sync did not run until the app was opened.
The WorkManager wiring is complete (boot receiver, persisted job); what holds
the job back is platform policy: App Standby buckets and Doze defer a rarely
opened app's jobs for hours, and opening the app moves it to the active bucket,
which is exactly when the overdue job runs.

Two things fix and expose this. An exemption from battery optimisation, which
takes the app out of the bucket system and lets its jobs run in Doze. And a
status line in Settings that says whether the periodic work is registered, when
it is next due, and whether the exemption is granted, so the next report is a
screenshot rather than a guess.

Force Stop is out of reach: it puts the app in stopped state and Android runs
nothing for it until the next launch. Settings says so in one sentence.

## Architecture

**`ScheduleHealth`** (`work/`, pure Kotlin): `interval`, `registered`,
`nextRunAt: Long?`, `batteryExempt`. `verdict(now)` returns one of `MANUAL`,
`NOT_REGISTERED`, `RESTRICTED` (not exempt), `OVERDUE` (next run more than
five minutes in the past), `HEALTHY`, checked in that order. Unit-tested.

**`ScheduleHealthSource`** (`work/`): `observe(): Flow<ScheduleHealth>` and
`refresh()`. `AndroidScheduleHealthSource` combines
`WorkManager.getWorkInfosForUniqueWorkFlow(SyncScheduler.UNIQUE_WORK_NAME)`,
`SettingsRepository.syncInterval`, and a refresh-triggered read of
`PowerManager.isIgnoringBatteryOptimizations`. Registered means any info in
`ENQUEUED` or `RUNNING`; next run is that info's `nextScheduleTimeMillis`.

**`BatteryOptimisation`** (`work/`): `isExempt(context)` and
`requestIntent(context)` building `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
with the package URI. Manifest gains `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.

**Settings**: `SettingsViewModel` takes a `ScheduleHealthSource`, exposes
`scheduleHealth: StateFlow<ScheduleHealth?>`, and `refreshHealth()` is called
on resume. A "Background sync" section renders the verdict, the next run time,
the Force Stop sentence, and a button that fires the request intent when not
exempt.

**Onboarding**: a fourth step, "Run in the background", satisfied when exempt,
whose action fires the same intent; `refresh()` re-reads exemption like the
other permissions. Onboarding completion does not depend on it.

## Accepted risks

- The exemption dialog is a system decision; a refusal leaves the verdict at
  `RESTRICTED` with the button still offered.
- Manufacturer battery managers (Xiaomi, Huawei, Samsung) have their own
  kill lists the exemption does not cover. Out of scope.
- `nextScheduleTimeMillis` is WorkManager's estimate, not JobScheduler's; the
  actual run can be later under Doze, which is what the exemption addresses.
