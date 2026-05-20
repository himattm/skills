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

## Pre-flight: detect what your project supports

```bash
# 1. androidx.tracing on the classpath? (preferred for the trace { } lambda)
grep -r 'androidx.tracing' app/build.gradle* gradle/libs.versions.toml 2>/dev/null

# 2. minSdk — Trace.beginSection works on all APIs; Trace.beginAsyncSection
#    requires API 29+
grep -E 'minSdk' app/build.gradle* gradle/libs.versions.toml 2>/dev/null

# 3. Build is debuggable (Trace sections fire only in debuggable apps unless
#    the device is rooted)
grep -A2 'buildTypes' app/build.gradle* | grep -i 'debuggable'
```

**No `androidx.tracing`?** Two options:

- Add `implementation("androidx.tracing:tracing-ktx:1.2.0")` (or `tracing` for the Java-only API). Keep this even after the probe — it's tiny and useful long-term.
- Use the platform `android.os.Trace` directly: `Trace.beginSection("...")` / `Trace.endSection()` work on every API level without any dependency. You lose the `trace { }` lambda but the slices come through identically.

**Groovy DSL.** Use single-quoted strings:

```groovy
implementation 'androidx.tracing:tracing-ktx:1.2.0'
```

**Java codebase.** `Trace.beginSection("AGENT_TRACE_<id>....")` / `Trace.endSection()` in a try/finally — same as Kotlin. The `trace { }` lambda is Kotlin-only; in Java, fall back to the manual begin/end form everywhere.

**R8/ProGuard stripping `Trace` calls.** Aggressive minification can elide `Trace.beginSection` calls in release. For the probe loop this isn't an issue (debug builds don't minify), but if you instrument a release build keep `-keep class android.os.Trace { *; }` in your ProGuard rules.

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

### 2. Capture a Perfetto trace and analyze it

Capturing the trace and querying it are separate concerns — covered by dedicated skills:

- **`android-perfetto-capture`** — strategies (one-shot host script, on-device long-running, in-app start/stop), data-source selection, capture verification
- **`android-perfetto-analyze`** — `trace_processor` SQL recipes, including the slice-by-thread query you'll want for `AGENT_TRACE_*` markers

The minimum viable loop, with the sentinel-aware slice query baked in:

```bash
# 1. Capture (see android-perfetto-capture for strategy detail)
/tmp/record_android_trace -o /tmp/trace.perfetto-trace -t 10s -b 32mb \
    -a com.example.app sched freq am wm gfx view binder_driver dalvik &
CAPTURE_PID=$!
sleep 2
adb shell input tap 540 1200          # drive the suspect flow
sleep 6
wait $CAPTURE_PID

# 2. Query for your sentinel slices (see android-perfetto-analyze Recipe 1)
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

`LEFT JOIN` is required: slices wrapped across coroutine `withContext` boundaries land on async tracks, not thread tracks, and an inner join would silently drop them.

### 3. Delegate the verdict

Hand the (small) result file to a Sonnet sub-agent:

> Read `/tmp/trace-results.txt`. For each AGENT_TRACE slice, return `<label>: <dur_ms>ms on <thread_name>`. Flag any slice on `main` longer than 16ms. Under 60 words. `model: "sonnet"`.

For frame-budget questions, jank attribution, or main-thread breakdowns, see `android-perfetto-analyze` Recipes 2–4.

### 4. Cleanup gate (BLOCKING)

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
| Generic label like `"work"` | Use `AGENT_TRACE_<id>.<name>` so it's unique in the UI and greppable for cleanup |
| Confusing slice duration with thread time | Default `dur` is wall time; for CPU time use `tts` (thread timestamp) columns |
| Wrapping the wrong block | Sections need to bracket the work — wrap the call, not just its declaration |
| Inner JOIN on `thread_track` when querying | Async slices have no thread; use `LEFT JOIN` (see `android-perfetto-analyze`) |

For the capture-side equivalents (no `-a` filter, buffer too small, didn't drive the flow during the window) see `android-perfetto-capture`.
