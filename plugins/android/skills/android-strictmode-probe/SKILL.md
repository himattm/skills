---
name: android-strictmode-probe
description: Use to surface invisible main-thread I/O, leaked closeables, leaked activities, and other policy violations that don't crash but cause jank, ANRs, or memory creep. Temporarily install StrictMode in `Application.onCreate` with `.penaltyLog()`, drive the suspect flow, read logcat for `StrictMode policy violation`, fix the violations, then remove the StrictMode setup. Trigger when the symptom is "feels slow," "occasionally janks," "leaks across rotations," or any concern about silent main-thread work.
---

# Android StrictMode Probe

## Why StrictMode catches what nothing else does

StrictMode is the only built-in tool that fires for the **silent killers** — operations that don't throw but ruin perceived performance:

- Disk I/O on the main thread
- Network calls on the main thread
- Leaked SQLite cursors, file streams, registered receivers
- Activity / fragment leaks
- Untagged sockets

A `Log.d` probe won't surface these. An espresso test won't fail. The app runs — just badly. StrictMode logs each violation with a stack trace pointing at the exact call.

## When to use

- "The app feels slow on cold start" — likely main-thread I/O during `Application.onCreate` or first activity
- "Scrolling janks once" — likely lazy disk read on the main thread
- "Memory grows across rotations" — likely activity leak via static reference, listener, or executor
- Before shipping a new feature that touches storage, prefs, network, or long-lived listeners

## When NOT to use

- The bug crashes — use `android-probe-logging` or read `adb logcat *:E`
- The bug is in pure Kotlin logic — use `android-reproduce-as-test`
- The question is "did this code run?" — use `android-probe-logging`

## The pattern: install → run → read → fix → remove

### 1. Install temporarily, with `.penaltyLog()` only

At the top of `Application.onCreate`, before your own initialization:

```kotlin
override fun onCreate() {
    super.onCreate()

    // AGENT_STRICTMODE_<id>: temporary probe — remove before commit
    StrictMode.setThreadPolicy(
        StrictMode.ThreadPolicy.Builder()
            .detectDiskReads()
            .detectDiskWrites()
            .detectNetwork()
            .detectCustomSlowCalls()
            .penaltyLog()
            .build()
    )
    StrictMode.setVmPolicy(
        StrictMode.VmPolicy.Builder()
            .detectLeakedSqlLiteObjects()
            .detectLeakedClosableObjects()
            .detectLeakedRegistrationObjects()
            .detectActivityLeaks()
            .detectFileUriExposure()
            .penaltyLog()
            .build()
    )
    // ... existing init ...
}
```

**Critical rules:**

- **Always `.penaltyLog()`. Never `.penaltyDeath()` for a probe** — death penalty crashes the app on first violation, which masks every later one.
- **Mark the block with a sentinel comment** (`AGENT_STRICTMODE_<id>`) so the cleanup grep finds it.
- **Install at the top of `Application.onCreate`** so it covers your own init code. Note: `ContentProvider.onCreate` runs *before* `Application.onCreate` and won't be caught here — if you suspect violations during ContentProvider init (Room, WorkManager initializers, etc.), install in `attachBaseContext` instead, or add a `Configuration.Provider` with StrictMode set up before initialization.

### 2. Drive the suspect flow

Cold-start the app for cold-start violations. For specific flows, navigate to the screen / trigger the action that you suspect.

```bash
adb logcat -c
android run                              # cold start
# ... drive the suspect flow ...
adb logcat -d -s StrictMode > /tmp/strictmode-<id>.log
```

The `-s StrictMode` filter captures every policy violation with stack.

### 3. Read the violations

Each violation looks like:

```
StrictMode policy violation; ~duration=120 ms: android.os.strictmode.DiskReadViolation
    at android.os.StrictMode$AndroidBlockGuardPolicy.onReadFromDisk
    at java.io.UnixFileSystem.checkAccess
    at java.io.File.exists
    at com.example.app.ConfigLoader.loadFromCache(ConfigLoader.kt:42)
    at com.example.app.MyApplication.onCreate(MyApplication.kt:18)
```

Look for:

- The violation type (`DiskReadViolation`, `NetworkViolation`, `LeakedClosableViolation`, `InstanceCountViolation` for activity leaks).
- The first frame inside *your* package — that's where to fix.
- `~duration=` — anything over a few ms on the main thread is jank-relevant.

For multi-violation logs (>30 lines), delegate to a Sonnet sub-agent:

> Read `/tmp/strictmode-<id>.log`. Group violations by type and first project-package frame. Return the top 3 unique violations with `file:line` and the violation class. Under 80 words. `model: "sonnet"`.

### 4. Fix each violation

| Violation | Typical fix |
|-----------|-------------|
| `DiskReadViolation` / `DiskWriteViolation` on main | Move to `Dispatchers.IO` coroutine, `WorkManager`, or `LifecycleScope` |
| `NetworkViolation` on main | Move to a coroutine on `Dispatchers.IO`, or use OkHttp/Ktor async |
| `LeakedClosableViolation` | Wrap in `use { }` or close in a `finally` |
| `LeakedRegistrationObjects` | Unregister receivers / listeners in `onDestroy` / `onStop` |
| `InstanceCountViolation` (activity leak) | Find the static / singleton holding the activity ref; usually a callback registered without unregister |

Re-run after each fix until logcat is clean.

### 5. Cleanup gate (BLOCKING)

```bash
rg 'AGENT_STRICTMODE_'
git diff app/src/main/java/.../MyApplication.kt
```

Both must show that the temporary `setThreadPolicy` / `setVmPolicy` block is **completely removed**. If the project already had a debug-only StrictMode setup, restore it to its pre-probe state — the diff against `git` should be exactly the lines you didn't touch.

If the project *should* have StrictMode behind `BuildConfig.DEBUG` permanently, that's a separate change — propose it explicitly, don't smuggle it in under the probe.

## Scoping the probe

When the violation log is overwhelming (cold start can produce 100+), narrow:

**Scope to one suspect operation** with `permitAll()` baseline + targeted detect:

```kotlin
StrictMode.setThreadPolicy(
    StrictMode.ThreadPolicy.Builder()
        .permitAll()
        .detectDiskReads()
        .penaltyLog()
        .build()
)
```

**Wrap a single suspect block** with `allowThreadDiskReads` / `allowThreadDiskWrites` to confirm a specific call is the offender:

```kotlin
val original = StrictMode.allowThreadDiskReads()
try {
    suspectCall()
} finally {
    StrictMode.setThreadPolicy(original)
}
```

If wrapping silences the violation, you found it.

## Common mistakes

| Mistake | Fix |
|---------|-----|
| Skipping the cleanup gate | `rg 'AGENT_STRICTMODE_'` must return zero before commit; the temporary block must be fully removed |
| Using `.penaltyDeath()` | Crashes on first violation, masks later ones — always `.penaltyLog()` for a probe |
| Installing after `super.onCreate()` | Misses framework-level violations during app init; install first |
| Reading 100+ violation logcat inline | Delegate to a Sonnet sub-agent with grouping criteria |
| Fixing the first violation, declaring done | Re-run after each fix — fixes often unmask later violations |
| Smuggling permanent StrictMode in under "probe" cleanup | Probe = remove. Permanent debug StrictMode is a separate, explicit change |
| Wrapping production code in `allowThreadDiskReads` to "silence" | That's hiding the bug, not fixing it; move the work off the main thread |
| Forgetting to clear `adb logcat -c` before the run | Mixes prior-run violations into the diagnosis |
