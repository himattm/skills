---
name: android-runtime-flag-probe
description: Use to flip framework-level diagnostics without code changes — verbose framework logging (`log.tag.<Tag> VERBOSE`), view-bounds overlay (`debug.layout`), overdraw flashes, frame-time bars, slow-animation toggles. Set via `adb shell setprop` for the duration of an investigation, then reset every flag. Reach for this when the question is about framework behavior or rendering — and code changes feel like overkill for a quick look.
---

# Android Runtime Flag Probe — Framework Debug Without Code Changes

## When to use

- "Is the view bigger than I think?" — `debug.layout true` overlays bounds
- "Is overdraw killing scroll perf?" — `debug.hwui.show_layers_updates true`
- "Are frames missing the budget?" — `debug.hwui.profile true`
- "Is this animation hiding a logic bug?" — `window_animation_scale 5` slows everything
- "Is `Choreographer` complaining about slow frames?" — `log.tag.Choreographer VERBOSE`
- "Is `ActivityManager` firing the lifecycle transition I expect?" — `log.tag.ActivityManager VERBOSE`

## When NOT to use

- The diagnostic needs your code's data — use `android-probe-logging`
- The diagnostic needs precise timing — use `android-trace-sections`
- The diagnostic should ship — that's a permanent change behind `BuildConfig.DEBUG`, not an ephemeral probe

## Pre-flight: detect what your device allows

```bash
# 1. adb device authorized
adb devices                              # expect "<id>  device"

# 2. Build type — userdebug/eng images honor every flag; user (production)
#    images silently no-op some setprops
adb shell getprop ro.build.type          # expect "userdebug" or "eng"

# 3. Some debug.* properties require root or system_app context — check before
#    trusting that a flip took
adb shell getprop ro.debuggable          # 1 = full debug; 0 = production-locked
```

**On `user` (production) builds**, the following often silently no-op (the `setprop` returns success but the system ignores the value):

- `debug.cpu.throttle` (any value)
- `debug.hwui.show_layers_updates` (some OEM skins)
- `debug.atrace.tags.enableflags`

The `settings put global window_animation_scale` family always works because it lives in the Settings provider, not in system properties. `debug.layout` and `log.tag.<Tag>` work everywhere.

**`getprop` returning empty after `setprop`** is a common gotcha — it usually means the property exists but no consumer is reading it on this device. The flag isn't broken on your end; the OS image just doesn't honor it. Try the equivalent flag from the Settings provider, or use `android-trace-sections` for the same diagnostic.

**No root, no userdebug.** That's the common case. Stick to `debug.layout`, `log.tag.<Tag>`, `debug.hwui.profile`, and the `settings put global ..._animation_scale` family — those work on every device.

## The flag catalog

### Logging verbosity (per tag)

```bash
adb shell setprop log.tag.<Tag> VERBOSE
```

The framework reads this at `Log.isLoggable(tag, level)` time, so it works without app restart for tags checked dynamically. For tags checked once at startup, force-stop and re-launch the app.

| Tag | Surfaces |
|-----|----------|
| `Choreographer` | Frame skips, "Skipped N frames" warnings escalate to verbose |
| `ViewRootImpl` | Touch dispatch, layout passes, draw scheduling |
| `ActivityManager` | Activity lifecycle transitions, ANR details |
| `ConnectivityService` | Network state changes |
| `WindowManager` | Window add/remove, focus changes |
| `InputDispatcher` | Touch dispatch internals |

**HTTP libraries don't honor `log.tag.<Tag>`.** OkHttp, Ktor, Retrofit, etc. don't read Android's `Log.isLoggable` — they use their own logger interfaces (e.g. `HttpLoggingInterceptor` for OkHttp). To get HTTP logging you have to wire the library's interceptor in code, which makes it a `android-probe-logging` task, not a runtime-flag one.

### Layout / rendering diagnostics

```bash
adb shell setprop debug.layout true
```

Overlays a yellow border around every view's bounds. Excellent for "padding looks wrong but I'm not sure what's actually rendering."

```bash
adb shell setprop debug.hwui.show_layers_updates true
```

Flashes the screen green where layers update — useful for finding overdraw or unnecessary invalidations.

```bash
adb shell setprop debug.hwui.profile true
```

Renders frame-time bars at the bottom of the screen (the "GPU profiling" toggle from Developer Options). The colored stacks correspond to frame phases; bars over the green line are dropped frames.

```bash
adb shell setprop debug.hwui.overdraw show
```

Tints surfaces by overdraw count (blue = 1×, green = 2×, red = 4×+). Pairs well with scroll-jank investigations.

### System Settings (longer-lived)

These use `settings put` rather than `setprop`:

```bash
adb shell settings put global window_animation_scale 5
adb shell settings put global transition_animation_scale 5
adb shell settings put global animator_duration_scale 5
```

5× animation slowdown surfaces logic bugs that flash by at normal speed. Reset to `1` when done.

```bash
adb shell settings put global ANGLE_GLES_DRIVER_ALL_ANGLE 1
```

(Forces the ANGLE GL driver — niche, for graphics regressions.)

## Workflow

### 1. Pick the smallest set of flags that answers the question

Don't flip everything. One or two flags per investigation. If you flip ten flags, you can't attribute observations to any one of them.

### 2. Apply the flags

```bash
# Track what you flipped — write a reset script first
cat > /tmp/runtime-flag-reset.sh <<'EOF'
#!/usr/bin/env bash
adb shell setprop log.tag.Choreographer ""
adb shell setprop debug.layout false
adb shell settings put global window_animation_scale 1
EOF
chmod +x /tmp/runtime-flag-reset.sh

# Then apply
adb shell setprop log.tag.Choreographer VERBOSE
adb shell setprop debug.layout true
adb shell settings put global window_animation_scale 5
```

**Always write the reset script first.** That's the cleanup gate's input — and it ensures you can't forget what you toggled.

### 3. Restart the app for flags read at startup

Some flags only take effect on cold start:

```bash
adb shell am force-stop com.example.app
android run                              # or your usual deploy
```

`debug.layout` is live; `log.tag.<Tag>` is live for `Log.isLoggable` checks but cached for tags evaluated at class load.

### 4. Drive the suspect flow + observe

For visual flags (`debug.layout`, `hwui.profile`, `hwui.overdraw`), capture a screenshot:

```bash
android screen capture -o /tmp/runtime-flag-screen.png
```

For log-tag flags, dump filtered logcat:

```bash
adb logcat -d -s Choreographer:V ActivityManager:V > /tmp/runtime-flag-log.txt
```

Delegate parsing to a Sonnet sub-agent if either output is non-trivial.

### 5. Cleanup gate (BLOCKING — most-skipped step in the suite)

```bash
bash /tmp/runtime-flag-reset.sh
```

Then verify each flag is actually back to default:

```bash
adb shell getprop debug.layout                # expect empty or false
adb shell getprop log.tag.Choreographer       # expect empty
adb shell settings get global window_animation_scale   # expect 1
```

The classic failure mode of this skill: leaving `debug.layout true` on the emulator. Every screenshot in the *next* investigation has yellow borders, the agent thinks the UI is broken, and chases ghosts. **Always verify the reset, don't just run the script.**

```bash
rm -f /tmp/runtime-flag-reset.sh /tmp/runtime-flag-screen.png /tmp/runtime-flag-log.txt
```

## Reset reference (copy-paste)

If you've forgotten what you toggled, this resets the common flags:

```bash
adb shell setprop debug.layout ""
adb shell setprop debug.hwui.show_layers_updates ""
adb shell setprop debug.hwui.profile ""
adb shell setprop debug.hwui.overdraw ""
adb shell settings put global window_animation_scale 1
adb shell settings put global transition_animation_scale 1
adb shell settings put global animator_duration_scale 1

# Reset all log.tag overrides — there's no batch reset, so list yours
for tag in Choreographer ViewRootImpl ActivityManager ConnectivityService WindowManager InputDispatcher; do
    adb shell setprop log.tag.$tag ""
done
```

## Common mistakes

| Mistake | Fix |
|---------|-----|
| Skipping the cleanup gate | Verify each flag is reset with `getprop` / `settings get` — the reset script is necessary but not sufficient |
| Flipping ten flags at once | One or two flags per investigation; otherwise observations aren't attributable |
| Forgetting to write the reset script first | Toggle and reset in one breath; otherwise you'll forget what you flipped |
| Forgetting `force-stop` after a tag flip | Some tags are cached at class load; restart the app to pick up the new level |
| Leaving `window_animation_scale 5` on | Every later investigation runs in slow-motion — devastating to productivity |
| Trusting the reset script ran cleanly | A typo in the path silently no-ops; always verify with `getprop` |
| Flipping `debug.layout` and not capturing a screenshot | The overlay is *visual* — you need a screenshot to read it |
| Using `setprop` for `window_animation_scale` (it's a `settings`) | Animation scales live in `settings global`, not system properties |
