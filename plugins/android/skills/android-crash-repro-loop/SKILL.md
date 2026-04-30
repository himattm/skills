---
name: android-crash-repro-loop
description: Use to flush out intermittent crashes and ANRs ("it crashes sometimes," "fails on slow devices," "happens after rotation a few times") by scripting the trigger sequence with `adb shell input` and running it in a shell loop against a cleared logcat, stopping on first match for `FATAL EXCEPTION` or `ANR in`. Inject deterministic stress (rotation, slow network, low memory) to surface latent races. The empirical answer to "is this still a bug, and under what conditions?"
---

# Android Crash Repro Loop

## When to use

- "It crashes sometimes" — intermittent failure with no reliable repro
- "It fails on slow devices" — a race condition that's invisible at fast speed
- "After rotation a few times" — lifecycle race
- "Happens after a network blip" — flaky connectivity edge case
- Confirming a "fixed" intermittent bug — 50 clean iterations is meaningful evidence
- Characterizing a regression before instrumenting — knowing the trigger sequence is required input for `android-probe-logging`

## When NOT to use

- The bug reproduces 100% of the time — just trigger it once and read logs
- The bug is purely visual / behavioral with no crash — use `android-snapshot-diff`
- You don't have a hypothesis about the trigger — investigate first with `android-regression-diff-scan` or read recent crash reports

## Pre-flight: detect what your project supports

```bash
# 1. adb device authorized
adb devices                              # expect "<id>  device"

# 2. Package and main activity name (the script needs both)
adb shell pm list packages | grep -i <fragment>
adb shell cmd package resolve-activity --brief <pkg> | tail -1

# 3. The build is installed (and ideally debuggable so you can see logcat
#    with full tag visibility)
adb shell dumpsys package <pkg> | grep -i 'flags=.*DEBUGGABLE'
```

**Stressors require API-level awareness:**

| Stressor command | Min API |
|------------------|---------|
| `settings put system user_rotation` | 17 |
| `setprop debug.cpu.throttle` | 21 *(some devices ignore on prod builds)* |
| `am send-trim-memory <pkg> COMPLETE` | 21 |
| `wm density <dpi>` | 21 |
| `svc data disable / enable` | always |

**User-debug vs prod build.** Some `setprop` flags are ignored on locked-down production OS images (`ro.build.type=user`). Check `adb shell getprop ro.build.type` — `userdebug` or `eng` images honor the full set; `user` images may silently no-op `debug.cpu.throttle` and others. The `settings put` and `wm` commands work on every build type.

**Skip force-stop if your scenario depends on warm process state.** The default loop force-stops between iterations to test cold-start paths. If your bug only repros warm (e.g. after several swipes during the same process lifetime), drop `am force-stop` from the trigger script and let process state persist.

## Pattern: clear → loop → drive → watch → stop

### 1. Pin down the suspected trigger

You need a deterministic action sequence. "Open the app and click around" is not a sequence. Specify:

- Starting state (cold start? logged in? on which screen?)
- Exact taps / inputs / system events
- Any timing (sleeps, waits)

Use `verify-android-layout` first to find element coordinates, then translate to `adb shell input`.

### 2. Build the trigger script

```bash
#!/usr/bin/env bash
# /tmp/repro-trigger.sh
set -e
PKG=com.example.app
ACT=.MainActivity

adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/$ACT" >/dev/null
sleep 2
adb shell input tap 540 1200          # open detail
sleep 1
adb shell input tap 100 100            # back
sleep 1
adb shell input keyevent KEYCODE_APP_SWITCH
sleep 1
adb shell input keyevent KEYCODE_APP_SWITCH   # back to app
```

The `-W` on `am start` waits for launch to settle. The sleeps are critical — too fast and you're testing your `adb` throughput, not the app.

### 3. Wrap in the loop with logcat watch

The canonical shape:

```bash
adb logcat -c
N=50
for i in $(seq 1 "$N"); do
    echo "--- iteration $i ---"
    bash /tmp/repro-trigger.sh
    if adb logcat -d | grep -qE 'FATAL EXCEPTION|ANR in|tombstone'; then
        echo "REPRO ON ITERATION $i"
        adb logcat -d > /tmp/repro-crash.log
        break
    fi
done
```

After the loop, `/tmp/repro-crash.log` has the full crash output for the iteration that hit. If the loop completes clean, that's also signal — see "Stop rule" below.

### 4. Inject deterministic stress

Latent races often need help to surface. Layer one stressor at a time:

| Stressor | Command | Surfaces |
|----------|---------|----------|
| **Rotation** | `adb shell settings put system accelerometer_rotation 0 && adb shell settings put system user_rotation 1` (cycle 0/1/2/3 between iterations) | Lifecycle / config-change races |
| **Density change** | `adb shell wm density 320` *(reset with `adb shell wm density reset`)* | Configuration-change recreation paths |
| **Slow CPU** | `adb shell setprop debug.cpu.throttle 50` *(reset with `""`)* | Timing-dependent bugs |
| **Network drop** | `adb shell svc data disable; sleep 3; adb shell svc data enable` | Connectivity edge cases |
| **Low memory** | `adb shell am send-trim-memory <pkg> COMPLETE` | Process-death restoration |
| **Background/foreground** | `adb shell input keyevent KEYCODE_HOME` then re-launch | Lifecycle restoration |
| **Slow animations** | `adb shell settings put global window_animation_scale 5` *(reset to 1)* | Animation-frame races |

Add the stressor inside the loop body, not outside it. Reset any toggled property at the end of each iteration so iteration N+1 starts clean.

### 5. Delegate the crash log

Crash logs include AndroidRuntime + StackTrace + ProcessRecord — typically 50+ lines. Spawn a Sonnet sub-agent:

> Read `/tmp/repro-crash.log`. Find the FATAL EXCEPTION block (or ANR in block). Return: (a) exception class, (b) message, (c) the first 3 stack frames inside our package (filter out `android.*`, `kotlin.*`, `java.*`), (d) any "Caused by" chain. Under 80 words. `model: "sonnet"`.

### 6. Stop rules

- **Repro hit** → fix the bug. Re-run the loop after the fix; expect zero hits across 50+ iterations.
- **50 iterations clean** → the bug is not in this trigger path. Either the hypothesis is wrong (re-investigate the symptom), or the bug needs a different stressor.
- **Repro hit on iteration 1** → the bug is *not* intermittent; you have a deterministic repro. Drop the loop, switch to `android-probe-logging`.

For "is it really fixed?" verification, 100 clean iterations under realistic stress is the gold standard — but 50 is usually convincing.

### 7. Cleanup gate (BLOCKING)

```bash
# Reset any system properties / settings you toggled
adb shell setprop debug.cpu.throttle ""
adb shell settings put global window_animation_scale 1
adb shell settings put system accelerometer_rotation 1
adb shell settings put system user_rotation 0
adb shell wm density reset
adb shell svc data enable

# Delete scratch files
rm -f /tmp/repro-trigger.sh /tmp/repro-crash.log

# Kill any background loop processes (if you ran in background)
jobs -p | xargs -r kill 2>/dev/null
```

The most common failure: leaving `window_animation_scale 5` or `svc data disable` on the device, which then confuses every subsequent investigation. Reset everything.

## Common mistakes

| Mistake | Fix |
|---------|-----|
| Skipping the cleanup gate | Reset toggled props/settings; delete scratch files; orphaned `disable data` will haunt the next session |
| Sleeps too short between actions | You're testing `adb` round-trip, not the app; use 1–2s minimum, more for animations |
| No `am force-stop` between iterations | Stale process state masks process-restoration bugs |
| Reading the crash log inline | Delegate to Sonnet; crash logs are 50+ lines of mostly framework noise |
| Layering all stressors at once | One stressor at a time; otherwise you can't attribute the repro |
| `for i in {1..50}; do ... done` without `logcat -c` first | Stale crashes from earlier runs trigger false positives |
| Stopping at first iteration's crash and assuming it's the same bug | Compare with the original symptom — repro loops sometimes surface unrelated crashes |
| Loop completes clean, declaring bug "fixed" without the fix | Clean repro after a fix is meaningful; clean repro without a fix means your trigger is wrong |
