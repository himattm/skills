---
name: android-coroutine-trace
description: Use to find stuck coroutines, leaked jobs, and suspended awaits with no resumer — the concurrency bugs that don't crash but cause hangs, missing UI updates, or memory creep. Ephemerally add `kotlinx-coroutines-debug`, install `DebugProbes` in `Application.onCreate`, run the suspect flow, dump active coroutines with stack traces, fix, then remove the dependency and the install/dump calls. Reach for this when `Log.d` shows a `launch` ran but `collect` never received, or a screen leaves and something keeps running.
---

# Android Coroutine Trace — DebugProbes Snapshot

## When to use

- "I called `launch { ... collect ... }` but no emissions arrive" — likely a job stuck in suspension
- "Navigated away but the network call still completes 30s later" — leaked job
- "Screen rotation, then UI never updates" — old `viewModelScope` job blocking new state
- "Hangs sometimes, no crash, no log" — deadlock or missing resumer
- Any time `android-probe-logging` shows `launch` fires but the body never completes

## When NOT to use

- The bug is a crash with a coroutine in the stack — read the stack from logcat
- The bug is timing/perf — use `android-trace-sections` (Perfetto shows coroutine dispatches as scheduling events)
- You haven't confirmed the bug is in coroutine flow — start with `android-probe-logging`

## Why DebugProbes

`kotlinx-coroutines-debug` ships a `DebugProbes` API that, once installed, tracks every active coroutine — its state (`RUNNING` / `SUSPENDED`), the suspension point's stack, the coroutine's launch-time stack, and parent/child relationships. `DebugProbes.dumpCoroutines()` prints the lot.

This is the **only way** to see what your coroutines are *currently doing*. Logging tells you what they did. Profilers tell you what threads are doing. DebugProbes tells you what coroutines are *suspended on* — which is where bugs hide.

## The pattern: install ephemerally → reproduce → dump → fix → remove

### 1. Add the debug dependency (ephemeral)

```kotlin
// app/build.gradle.kts — AGENT_DEBUGPROBES_<id>: temporary, remove before commit
dependencies {
    debugImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-debug:1.8.1")
}
```

Use `debugImplementation` so it's never in release. Mark with the sentinel comment for cleanup.

### 2. Install in `Application.onCreate`

```kotlin
import kotlinx.coroutines.debug.DebugProbes

override fun onCreate() {
    super.onCreate()

    // AGENT_DEBUGPROBES_<id>: temporary probe — remove before commit
    if (BuildConfig.DEBUG) {
        DebugProbes.install()
    }
    // ...
}
```

`install()` is idempotent and adds modest overhead — fine for debug builds during a probe.

### 3. Add a dump trigger

The simplest pattern: dump on a sentinel logcat tag whenever you want a snapshot. Add a temporary dev-menu button or, simpler, expose via `adb shell am broadcast`:

```kotlin
// In Application.onCreate, AGENT_DEBUGPROBES_<id>:
import androidx.core.content.ContextCompat   // androidx.core:core 1.9.0+

ContextCompat.registerReceiver(
    this,
    object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val out = StringBuilder()
            DebugProbes.dumpCoroutines(java.io.PrintStream(object : java.io.OutputStream() {
                override fun write(b: Int) { out.append(b.toChar()) }
            }))
            android.util.Log.d("AGENT_DEBUGPROBES_a4f9c2e1", out.toString())
        }
    },
    IntentFilter("AGENT_DUMP_COROUTINES"),
    ContextCompat.RECEIVER_NOT_EXPORTED
)
```

`ContextCompat.registerReceiver` is required for `RECEIVER_NOT_EXPORTED` to work across all API levels — the `Context.RECEIVER_NOT_EXPORTED` constant was added in API 33, but `ContextCompat` handles older platforms gracefully. If your project doesn't have `androidx.core:core` 1.9+, either add it as a `debugImplementation` for the probe, or skip the receiver and write directly to `/data/data/<pkg>/files/coroutine-dump.txt` plus `adb pull`.

Then trigger from the host:

```bash
adb shell am broadcast -a AGENT_DUMP_COROUTINES
adb logcat -d -s AGENT_DEBUGPROBES_a4f9c2e1 > /tmp/coroutines-dump.txt
```

Alternatively, write directly to a file the app can write to and `adb pull` — but the broadcast/logcat path needs no permissions and works on any debug build.

### 4. Drive the suspect flow, then dump

```bash
# 1. Get to the state where you suspect a stuck/leaked coroutine
adb shell input tap 540 1200
sleep 2

# 2. Snapshot
adb logcat -c
adb shell am broadcast -a AGENT_DUMP_COROUTINES
sleep 1
adb logcat -d -s AGENT_DEBUGPROBES_a4f9c2e1 > /tmp/coroutines-dump.txt
```

For leak hunting, snapshot before *and* after navigating away. Coroutines from the prior screen that survive into the next snapshot are leaks.

### 5. Read the dump

A coroutine entry looks like:

```
Coroutine "coroutine#42":StandaloneCoroutine{Active}, state: SUSPENDED
    at kotlinx.coroutines.flow.internal.ChannelFlow.collect(ChannelFlow.kt:51)
    at com.example.app.user.UserViewModel$observe$1.invokeSuspend(UserViewModel.kt:32)
Created at:
    at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith
    at com.example.app.user.UserViewModel.observe(UserViewModel.kt:30)
```

Look for:

- **`state: SUSPENDED`** with no upstream emission expected → likely missing resumer
- **Coroutines created in `xViewModel.observe()`** still active after the screen left → cancellation bug
- **Multiple identical coroutines** in the dump → `launch` called repeatedly without cancelling the previous job
- **`state: RUNNING`** for a long-running job after navigation → blocking work that should have been cancelled

Delegate to a Sonnet sub-agent for non-trivial dumps:

> Read `/tmp/coroutines-dump.txt`. Group coroutines by state and by the first frame inside `com.example.app`. Return: (a) total active coroutines, (b) any coroutine SUSPENDED on a `Channel` or `Flow.collect`, (c) any duplicate launch points (same `Created at` line, multiple coroutines). Under 100 words. `model: "sonnet"`.

### 6. Fix and re-verify

After the fix, repeat steps 4–5. Expect:

- Stuck coroutine should be RUNNING through to completion or properly cancelled
- Leaked coroutine should not appear after navigating away
- Duplicate-launch bugs should show one coroutine where there used to be N

### 7. Cleanup gate (BLOCKING)

```bash
rg 'AGENT_DEBUGPROBES_|DebugProbes|coroutines-debug'
```

Must return zero. Specifically remove:

- `debugImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-debug:...")` from `build.gradle.kts`
- `DebugProbes.install()` call from `Application.onCreate`
- The broadcast receiver registration (if you added one)
- Any `import kotlinx.coroutines.debug.*` lines

Then:

```bash
./gradlew :app:assembleDebug   # confirm it still builds without the dep
rm -f /tmp/coroutines-dump.txt
```

## Patterns this skill catches well

**Missing cancellation in `viewModelScope`:**

Dump shows two coroutines from the same `observe()` line — one from before rotation, one from after. The first wasn't cancelled. Fix: ensure the upstream flow is `stateIn(viewModelScope, ...)` or that you cancel the previous job before launching a new one.

**Suspended on a never-completing `Channel`:**

Dump shows `SUSPENDED at Channel.receive` for a coroutine whose upstream sender is gone. The sender side closed without notifying — fix the channel close path.

**`runBlocking` on the main thread:**

`RUNNING` state, `at runBlocking` in the stack, on the main dispatcher. That's an ANR waiting to happen — should never `runBlocking` on main.

## Common mistakes

| Mistake | Fix |
|---------|-----|
| Skipping the cleanup gate | `rg 'DebugProbes\|coroutines-debug'` must return zero before commit |
| Using `implementation` instead of `debugImplementation` | The dep would ship to release; always `debugImplementation` |
| Forgetting to remove `DebugProbes.install()` | The library may not be on the classpath in release → crash. Cleanup grep catches this. |
| Dumping mid-action | `dumpCoroutines` is a snapshot; pause the flow at a steady state before dumping |
| Reading the full dump inline | Dumps are 100s of lines for non-trivial apps — delegate to Sonnet |
| Single dump for leak detection | Snapshot before AND after navigating away; coroutines that survive are the leaks |
| Treating SUSPENDED as a bug | SUSPENDED is normal for flow collectors; the bug is *unexpected* SUSPENDED, e.g. on a closed channel |
| Confusing "Created at" with "current location" | Top of the dump is current suspension point; "Created at" is launch site — both matter, for different reasons |
