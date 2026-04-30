---
name: android-trace-sections
description: Use to answer questions logging can't — "did this run, on what thread, for how long, in what overlap with frame boundaries?" Wrap suspect operations in `Trace.beginSection("AGENT_TRACE_<id>")` / `Trace.endSection()`, capture a Perfetto trace, inspect the named slice. Better than `Log.d` when the question is about timing, threading, or whether a block fits inside a frame budget. Reach for this for jank, slow startup, dropped frames, and "is this on the main thread?" investigations.
---

# Android Trace Sections — Perfetto-Backed Code Probes

## Why trace sections beat logging for performance

`Log.d` answers "did this run." Trace sections answer "did this run, on what thread, when, and how long" — with frame boundaries and other concurrent work in the same view. For any performance investigation that's the difference between guessing and knowing.

| Question | Logging answers | Tracing answers |
|----------|-----------------|-----------------|
| "Did this code run?" | Yes | Yes |
| "On what thread?" | Only via `Thread.currentThread().name` | Yes — visualized per-thread |
| "How long did it take?" | Only with manual timestamps | Microsecond-precise duration |
| "Did it block a frame?" | No | Yes — frame markers in the same view |
| "What ran concurrently?" | No | Yes — every thread visible at once |

## When to use

- "App janks during scroll" — wrap suspect adapter / Composable / RecyclerView paths
- "Cold start is slow" — wrap `Application.onCreate`, `Activity.onCreate`, first `setContent`
- "Is this expensive call on the main thread?" — wrap and read the lane it lands in
- "Does this finish within the frame budget?" — 16.6 ms (60 Hz) or 8.3 ms (120 Hz)
- "Why is this `withContext(Dispatchers.IO)` block holding things up?" — wrap and see if it's actually on IO

## When NOT to use

- "Did this run at all?" — `android-probe-logging` is simpler
- The bug is a crash or exception — read logcat
- You need to inspect specific values — tracing supports key/value but logging is more flexible

## The pattern: instrument → capture → inspect → remove

### 1. Add the import and wrap the suspect block

```kotlin
import androidx.tracing.Trace      // androidx.tracing:tracing-ktx — preferred
// or: import android.os.Trace      // platform — works without dep but no ktx helpers
```

Wrap with `traceSection` (idiomatic) or manual `begin/end`:

```kotlin
import androidx.tracing.trace

trace("AGENT_TRACE_a4f9c2e1.fetchUser") {
    val user = repository.fetchUser(id)
    cache.put(id, user)
}
```

The label format `AGENT_TRACE_<id>.<name>` keeps the sentinel prefix for cleanup grep, with a human label for the trace UI.

For begin/end style (when wrapping isn't ergonomic, e.g. across coroutine boundaries):

```kotlin
Trace.beginSection("AGENT_TRACE_a4f9c2e1.fetchUser")
try {
    val user = repository.fetchUser(id)
    // ...
} finally {
    Trace.endSection()
}
```

**Always pair `begin` with `end` in `finally`.** A missing `endSection` corrupts the trace for the rest of the process.

### 2. Capture a Perfetto trace

Three options, easiest first:

**Option A: Macrobenchmark / `record_android_trace` script** (cleanest):

```bash
# From the Perfetto project:
curl -O https://raw.githubusercontent.com/google/perfetto/main/tools/record_android_trace
chmod +x record_android_trace
./record_android_trace -o /tmp/trace.perfetto-trace -t 10s -b 32mb \
    sched freq idle am wm gfx view binder_driver hal dalvik \
    -a com.example.app
```

The `-a <pkg>` filter limits app-specific data to your package.

**Option B: On-device perfetto** (no host script):

```bash
adb shell perfetto -o /data/misc/perfetto-traces/trace.pftrace -t 10s \
    -c - --txt <<EOF
buffers { size_kb: 32768 }
data_sources {
  config {
    name: "android.surfaceflinger.frametimeline"
  }
}
data_sources {
  config {
    name: "track_event"
    track_event_config {
      enabled_categories: "*"
    }
  }
}
data_sources {
  config {
    name: "linux.ftrace"
    ftrace_config {
      ftrace_events: "sched/sched_switch"
      ftrace_events: "power/suspend_resume"
      atrace_categories: "view"
      atrace_categories: "gfx"
      atrace_apps: "com.example.app"
    }
  }
}
EOF

adb pull /data/misc/perfetto-traces/trace.pftrace /tmp/trace.perfetto-trace
```

**Option C: Studio Profiler "System Trace"** — interactive but defeats agent automation.

### 3. Drive the suspect flow during capture

Capture runs for the `-t` duration. Trigger your scenario inside that window:

```bash
./record_android_trace -o /tmp/trace.perfetto-trace -t 10s -a com.example.app &
sleep 2
adb shell input tap 540 1200          # the suspect tap
sleep 2
# ... drive any other steps ...
wait                                    # let trace recording finish
```

### 4. Inspect — open in ui.perfetto.dev

```
open https://ui.perfetto.dev/
# Drag /tmp/trace.perfetto-trace into the page
```

Search the trace UI for `AGENT_TRACE_a4f9c2e1` to jump straight to your slices. Read:

- **Lane** = thread it ran on (look for "main" if you suspected main-thread work)
- **Slice width** = duration in µs
- **Frame markers** above the lanes = frame boundaries; a slice that crosses a frame on `main` is a dropped frame
- **Concurrent slices** in other lanes = what else was happening

For agent-in-the-loop inspection, Perfetto has a SQL backend (`trace_processor`) that can be queried programmatically — see "Programmatic inspection" below for the agent-friendly path.

### 5. Programmatic inspection (sub-agent friendly)

When the agent shouldn't be opening a browser, query via `trace_processor`:

```bash
# One-time install
curl -L https://get.perfetto.dev/trace_processor -o /tmp/trace_processor
chmod +x /tmp/trace_processor

# Run a SQL query
/tmp/trace_processor /tmp/trace.perfetto-trace -q - <<'SQL' > /tmp/trace-results.txt
SELECT
  s.name,
  s.ts,
  s.dur / 1e6 AS dur_ms,
  COALESCE(t.name, 'process/async') AS thread_name
FROM slice s
LEFT JOIN thread_track tt ON s.track_id = tt.id
LEFT JOIN thread t ON tt.utid = t.utid
WHERE s.name LIKE 'AGENT_TRACE_%'
ORDER BY s.ts;
SQL
```

The `LEFT JOIN` is required: trace sections wrapped across coroutine boundaries (the `Trace.beginSection` / `endSection` form across `withContext`) sometimes land on async tracks instead of thread tracks. An inner join would silently drop those slices.

Hand the result file to a Sonnet sub-agent:

> Read `/tmp/trace-results.txt`. For each AGENT_TRACE slice, return `<label>: <dur_ms>ms on <thread_name>`. Flag any slice on `main` longer than 16ms. Under 60 words. `model: "sonnet"`.

### 6. Cleanup gate (BLOCKING)

```bash
rg 'AGENT_TRACE_'
```

Must return zero. Remove every wrapping `trace { }` block, every `Trace.beginSection`/`endSection` pair, and any imports that were added only for tracing.

```bash
rm -f /tmp/trace.perfetto-trace /tmp/trace-results.txt
```

If the project already had production trace sections (legitimate, named with the team's convention), don't touch those — only the `AGENT_TRACE_` ones.

## Common patterns

**Suspect Composable recomposition cost:**

```kotlin
@Composable
fun ExpensiveItem(data: Item) {
    androidx.tracing.trace("AGENT_TRACE_a4f9c2e1.ExpensiveItem") {
        // ... composable body ...
    }
}
```

**Coroutine block suspected of running on main:**

```kotlin
viewModelScope.launch {
    Trace.beginSection("AGENT_TRACE_a4f9c2e1.fetchAndCache.before-withContext")
    // ...
    Trace.endSection()

    withContext(Dispatchers.IO) {
        Trace.beginSection("AGENT_TRACE_a4f9c2e1.fetchAndCache.io-block")
        try {
            // ...
        } finally {
            Trace.endSection()
        }
    }
}
```

If the `io-block` slice lands on the `main` lane, your dispatcher is misconfigured.

## Common mistakes

| Mistake | Fix |
|---------|-----|
| Skipping the cleanup gate | `rg 'AGENT_TRACE_'` must return zero before commit |
| Missing `endSection` in `try/finally` | Pairs must always close — use `trace { }` lambda form when possible |
| Capturing without driving the suspect flow during the window | The trace will be empty — start the trace, sleep, trigger the scenario, wait for trace to finish |
| Reading the full Perfetto trace inline | Use `trace_processor` SQL + Sonnet sub-agent for the slice subset you care about |
| Generic label like `"work"` | Use `AGENT_TRACE_<id>.<name>` so it's unique in the UI and greppable for cleanup |
| Confusing slice duration with thread time | Default `dur` is wall time; for CPU time use `tts` (thread timestamp) columns |
| Tracing without `-a <pkg>` filter | Trace size balloons; filtering to your app keeps it under 32 MB |
| Forgetting to set buffer size on long captures | Default buffer fills and drops events; use `-b 32mb` or larger |
