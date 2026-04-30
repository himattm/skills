---
name: android-perfetto-capture
description: Use to capture Perfetto system traces from a connected Android device — the input for any timing, threading, jank, or "is this on the main thread?" investigation. Writes a self-contained `.perfetto-trace` file to `/tmp` that downstream skills (`android-perfetto-analyze`, `android-trace-sections`) read with the `trace_processor` SQL backend. Pick the strategy that matches the question (one-shot, repeated, on-the-fly), drive the suspect flow during the recording window, and verify the trace contains the expected slices before declaring capture done.
---

# Android Perfetto Capture — Get a Trace From the Device

## What this skill does (and doesn't do)

This skill is the **capture half** of the perfetto loop. It produces a `.perfetto-trace` file. It does **not** parse the trace — that's `android-perfetto-analyze`.

A trace file is binary, typically 5–50 MB, captured over a fixed time window. It contains every CPU scheduling event, every framework atrace slice (gfx/view/wm/am/...), every `Trace.beginSection` slice your app emits, and every Java/Kotlin `track_event` your app emits via `androidx.tracing`. It is the single most informative debugging artifact Android exposes.

## When to use

- Any time `android-trace-sections` instrumented your app and now needs a trace
- "Is this animation dropping frames?" — capture during the animation, analyze with frame-timeline queries
- "Cold start is slow" — capture from before launch through first frame drawn
- "Scroll janks at row N" — capture during scroll, compare frame durations across the timeline
- "Is this work landing on `main`?" — capture, query slice→thread mapping
- Comparing before/after a perf fix — capture A, fix, capture B, run the same analysis

## When NOT to use

- "Did this code run?" — `android-probe-logging` is faster and cheaper
- "What state is the UI in right now?" — `verify-android-layout` / `android-snapshot-diff`
- "What violations does StrictMode see?" — `android-strictmode-probe`
- A single tap is enough to repro and you only need a stack trace — read logcat

## Pre-flight: pick a capture strategy

| Strategy | When | Recording lifecycle |
|----------|------|---------------------|
| **One-shot host script** (`record_android_trace`) | Default; best for scripted captures driven by `adb input` | Starts → captures for `-t` seconds → stops → pulls file |
| **On-device long-running** (`adb shell perfetto`) | Captures longer than 30s; flexible config; persistent past adb disconnect | Starts on device → you drive flow → you stop with `kill -TERM <pid>` or wait for `-t` to elapse → pull from `/data/misc/perfetto-traces/` |
| **In-app `Tracing` API** | You want to start/stop a trace from inside the app on a specific signal | App writes to a public location → you `adb pull` afterwards |
| **Macrobenchmark** | You're measuring with a Macrobenchmark test class already | The test framework manages capture |

For agent-driven investigations, **default to the one-shot host script**. The on-device form is for captures longer than ~30s where the host script's adb-tunnel can be flaky.

## Pre-flight: detect what your project supports

Before instrumentation, confirm:

```bash
# 1. Device is connected and authorized
adb devices                      # expect "<id>  device" (not "unauthorized" or "offline")

# 2. Device API level — perfetto is built in on API 28+; older devices need atrace fallback
adb shell getprop ro.build.version.sdk

# 3. Package is installed and the app is built debuggable (perfetto only sees app slices for debuggable apps unless the system is rooted)
adb shell pm list packages | grep -i <your-package-fragment>
adb shell dumpsys package <your-package> | grep -i 'flags=.*DEBUGGABLE'
```

Also check whether your `app/build.gradle` declares `androidx.tracing:tracing` (or `tracing-ktx`). If not, the framework atrace categories (`gfx`, `view`, `wm`, `am`, `binder_driver`, …) still capture — but your custom `Trace.beginSection` calls won't show up unless the platform fallback `android.os.Trace` is used.

```bash
grep -r 'androidx.tracing' app/build.gradle* gradle/libs.versions.toml 2>/dev/null
```

## Strategy A: one-shot host script (default)

### 1. Get the recorder

```bash
curl -L https://raw.githubusercontent.com/google/perfetto/main/tools/record_android_trace -o /tmp/record_android_trace
chmod +x /tmp/record_android_trace
```

The script is a thin wrapper around `adb shell perfetto` plus pull. Cache it in `/tmp` for the session — no install required.

### 2. Capture

```bash
/tmp/record_android_trace \
    -o /tmp/trace.perfetto-trace \
    -t 10s \
    -b 32mb \
    -a com.example.app \
    sched freq idle am wm gfx view binder_driver hal dalvik input res memory power
```

| Flag | Why |
|------|-----|
| `-o <path>` | Output `.perfetto-trace` file path on host |
| `-t 10s` | Recording duration; long enough to cover your scenario plus ~2s padding |
| `-b 32mb` | Per-buffer size; raise to 64–128 MB for 30s+ captures or busy apps |
| `-a <pkg>` | Restrict atrace events to your app — keeps the trace small |
| `sched freq idle ...` | Atrace categories — see below |

The atrace categories you want by default:

- `sched` — every thread scheduling event (which thread ran when, on which core)
- `freq` — CPU frequency over time (helps spot governor throttling)
- `idle` — CPU idle states
- `am` — `ActivityManager` (lifecycle transitions)
- `wm` — `WindowManager`
- `gfx` — graphics (frame draw, surface flinger, vsync)
- `view` — view-system calls (measure, layout, draw, RecyclerView, Compose)
- `binder_driver` — IPC calls
- `hal` — hardware abstraction layer
- `dalvik` — JIT compile, GC pause, classloading
- `input` — touch dispatch
- `res` — resource loading
- `memory` — Java heap stats
- `power` — wake locks, doze

Add `database` if you suspect SQLite, `audio` for AudioFlinger work.

### 3. Drive the flow inside the recording window

```bash
# Start capture in background
/tmp/record_android_trace -o /tmp/trace.perfetto-trace -t 10s -b 32mb \
    -a com.example.app sched freq idle am wm gfx view binder_driver hal dalvik &

CAPTURE_PID=$!
sleep 2                                 # let perfetto warm up
adb shell input tap 540 1200            # the suspect tap / scenario
sleep 1
adb shell input swipe 540 1500 540 500  # scroll
sleep 4                                 # leave room before -t expires

wait $CAPTURE_PID                       # let the recording stop and pull complete
```

The `sleep 2` warm-up is non-negotiable: starting `record_android_trace` opens an adb shell, configures perfetto, and starts the trace daemon. If you trigger your scenario before perfetto is recording, you'll get an empty trace.

### 4. Verify the trace before declaring capture done

A trace file can exist but be empty (perfetto failed silently, the buffer was too small, the app was force-stopped before it could emit). Always verify:

```bash
# 1. File exists and is non-trivially sized
ls -la /tmp/trace.perfetto-trace
# Expect at least 100 KB; under that means almost certainly empty

# 2. Quick slice count via trace_processor (see android-perfetto-analyze for setup)
/tmp/trace_processor /tmp/trace.perfetto-trace -q - <<'SQL'
SELECT COUNT(*) AS slice_count FROM slice;
SELECT COUNT(*) AS sched_count FROM sched;
SELECT COUNT(DISTINCT name) AS distinct_slice_names FROM slice;
SQL
```

A healthy 10-second capture of a moderately busy app should report:

- `slice_count`: 10,000+
- `sched_count`: 100,000+
- `distinct_slice_names`: 200+

If any of these are zero or near-zero, **your capture is broken** — fix the capture before analyzing. Common causes are listed under "Common mistakes."

## Strategy B: on-device long-running

Use when you need >30s captures or when the host-script's adb tunnel keeps dropping.

```bash
# 1. Build a config file
cat > /tmp/perfetto.cfg <<'EOF'
buffers { size_kb: 65536 }
buffers { size_kb: 8192 }

data_sources {
  config {
    name: "linux.process_stats"
    target_buffer: 1
    process_stats_config { scan_all_processes_on_start: true }
  }
}

data_sources {
  config {
    name: "linux.ftrace"
    ftrace_config {
      ftrace_events: "sched/sched_switch"
      ftrace_events: "sched/sched_wakeup"
      ftrace_events: "sched/sched_wakeup_new"
      ftrace_events: "sched/sched_waking"
      ftrace_events: "power/cpu_frequency"
      ftrace_events: "power/cpu_idle"
      atrace_categories: "gfx"
      atrace_categories: "view"
      atrace_categories: "wm"
      atrace_categories: "am"
      atrace_categories: "binder_driver"
      atrace_categories: "dalvik"
      atrace_apps: "com.example.app"
    }
  }
}

data_sources {
  config {
    name: "android.surfaceflinger.frametimeline"
  }
}

duration_ms: 60000
EOF

# 2. Push and start
adb push /tmp/perfetto.cfg /data/local/tmp/perfetto.cfg
adb shell 'cat /data/local/tmp/perfetto.cfg | perfetto --txt -c - -o /data/misc/perfetto-traces/trace.pftrace' &
PERFETTO_PID=$!

# 3. Drive your flow in the host shell
sleep 3
adb shell input tap 540 1200
# ... etc

# 4. Wait for the capture to finish, pull, clean up
wait $PERFETTO_PID
adb pull /data/misc/perfetto-traces/trace.pftrace /tmp/trace.perfetto-trace
adb shell rm /data/misc/perfetto-traces/trace.pftrace
adb shell rm /data/local/tmp/perfetto.cfg
```

The `frametimeline` data source is what makes Studio Profiler's "Janky frames" view work — include it whenever frame analysis is the goal.

## Strategy C: in-app start/stop (advanced)

When the suspect scenario is hard to time externally — e.g. a background sync that fires unpredictably — start/stop tracing from inside the app:

```kotlin
// Add the dependency (debugImplementation only)
// debugImplementation("androidx.tracing:tracing-perfetto:1.0.0")
// debugImplementation("androidx.tracing:tracing-perfetto-binary:1.0.0")

import androidx.tracing.perfetto.PerfettoSdkTrace
import androidx.tracing.perfetto.handshake.PerfettoSdkHandshake

// AGENT_PERFETTO_<id>: temporary, remove before commit
class DebugPerfettoStarter(private val ctx: Context) {
    fun startTrace() {
        // Trigger captured trace via Perfetto SDK; ships with androidx.tracing-perfetto
        PerfettoSdkTrace.start()
    }
    fun stopTrace() {
        PerfettoSdkTrace.stop()
    }
}
```

This path is the most fiddly — only use it when wall-clock-driven captures aren't viable.

## Repetition for noisy phenomena

A single capture is one sample. For "is this consistently slow?" or "is the regression real or noise?" capture N times and feed all N to the analysis:

```bash
for i in $(seq 1 5); do
    /tmp/record_android_trace -o /tmp/trace-$i.perfetto-trace -t 5s -b 16mb \
        -a com.example.app sched freq am wm gfx view dalvik &
    PID=$!
    sleep 1
    adb shell am force-stop com.example.app
    adb shell am start -W -n com.example.app/.MainActivity >/dev/null
    wait $PID
done
```

Then run the same `trace_processor` query against each trace and compare distributions (median, p95) — see `android-perfetto-analyze` for the aggregation pattern.

## Cleanup gate

Captures live in `/tmp` and (for Strategy B) on the device:

```bash
# Host
rm -f /tmp/trace*.perfetto-trace /tmp/perfetto.cfg /tmp/trace_processor

# Device — only relevant for Strategy B
adb shell ls /data/misc/perfetto-traces/    # any leftover files from a crashed capture
adb shell rm /data/misc/perfetto-traces/trace.pftrace 2>/dev/null

# If you used Strategy C, run the trace-sections cleanup grep too:
rg 'AGENT_PERFETTO_'                         # expect empty
```

Trace files are large — leaving them in `/tmp` can chew through tens of GB across a long session. Always clean up.

## Common mistakes

| Mistake | Fix |
|---------|-----|
| No warm-up sleep before driving the scenario | Always `sleep 2` after starting the recorder before the first `adb input` |
| Buffer too small for capture duration | Use `-b 32mb` for 10s, `-b 64mb` for 30s, `-b 128mb` for 60s+ |
| Forgot `-a <pkg>` filter | Trace balloons with system noise; always filter to your app for app-perf investigations |
| Trace file is 0 bytes | Almost always means perfetto crashed at startup — check `adb logcat | grep perfetto` for the cause |
| Missing `frametimeline` data source for jank investigation | Frame analysis needs it; the host script doesn't add it by default — use Strategy B or specify `surfaceflinger_frame_timeline` |
| Captured but `slice_count = 0` in verify step | `androidx.tracing` not in classpath, or app crashed before emitting; check the app's logcat |
| Captured a release build | Release builds may strip `Trace.beginSection` calls; capture against `debug` |
| Driving the scenario after `-t` expired | The capture stops at `-t`; your scenario must complete *inside* the window |
| Not verifying the capture before analyzing | Ten minutes of analysis on an empty trace is the worst kind of debug session |
| Leaving `.perfetto-trace` files in `/tmp` | They're large; clean up between investigations |
