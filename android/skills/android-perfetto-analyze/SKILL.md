---
name: android-perfetto-analyze
description: Use to extract concrete numbers and slice-level evidence from a `.perfetto-trace` captured by `android-perfetto-capture`. Run SQL queries against the trace via the `trace_processor` binary, then hand the (small) result file to a Sonnet sub-agent for verdict — never read raw trace data inline. This is where "the app feels slow" becomes "frame 42 missed budget by 8.3 ms because `MainActivity.onResume` ran 18 ms on `main` while the IO dispatcher was idle." The analysis half of the perfetto loop.
---

# Android Perfetto Analyze — SQL Queries Against a Trace

## What this skill does

You have a `.perfetto-trace` file (from `android-perfetto-capture`). This skill turns it into specific, citable findings using `trace_processor` SQL. You will:

1. Install (cache) `trace_processor` if not already present
2. Pick a query recipe matching your question
3. Run the query, write results to a small text file
4. Delegate parsing of the result file to a Sonnet sub-agent
5. Iterate — narrow the query based on the verdict

You will **never** open the trace in `ui.perfetto.dev` from within an agent loop — it requires browser interaction. The SQL backend is the agent-friendly path.

## When to use

- You have a trace (from `android-perfetto-capture`) and need numbers
- "Did this slice land on `main`?" — slice→thread query
- "How many frames missed budget during this 5s scroll?" — frame-timeline query
- "What ran during this 200 ms gap on main?" — sched query bounded by timestamps
- "What's the median / p95 duration of `MyComposable` recompositions?" — slice aggregation
- Comparing two captures (before/after a fix) — same query against both, diff the result

## When NOT to use

- You don't have a trace yet — capture first with `android-perfetto-capture`
- The question is "did this code run?" — `android-probe-logging` is faster
- You need interactive flame-graph visualization for human inspection — `ui.perfetto.dev`

## Pre-flight: install trace_processor

`trace_processor` is a self-contained C++ binary. Cached in `/tmp` for the session, it's a one-time install:

```bash
if [ ! -x /tmp/trace_processor ]; then
    curl -L https://get.perfetto.dev/trace_processor -o /tmp/trace_processor
    chmod +x /tmp/trace_processor
fi
/tmp/trace_processor --version
```

It runs queries against a trace file and exits — no daemon mode required for ad-hoc analysis. (For interactive multi-query sessions you can run `--http`, but that's overkill for the single-query agent loop.)

## The pattern: query → small result → sub-agent → narrow

The trace is large (10–100 MB binary). The query result should be small (under ~10 KB text). If your query returns 5,000 rows, narrow it — group, aggregate, or filter — until the result fits in a sub-agent's context with room to reason.

```bash
/tmp/trace_processor /tmp/trace.perfetto-trace -q - <<'SQL' > /tmp/trace-<question>.txt
<your query>
SQL
wc -l /tmp/trace-<question>.txt
```

Then:

> Read `/tmp/trace-<question>.txt`. Return: <very specific question, with units>. Under 80 words. `model: "sonnet"`.

## Query recipes

### Recipe 1: agent-emitted slices, with thread and duration

When you instrumented your code with `Trace.beginSection("AGENT_TRACE_<id>....")` (see `android-trace-sections`), this query lists every emitted slice with its thread and duration:

```sql
SELECT
  s.name,
  s.ts AS ts_ns,
  s.dur / 1e6 AS dur_ms,
  COALESCE(t.name, 'process/async') AS thread_name,
  CASE
    WHEN t.name = 'main' OR t.name LIKE '%.main' THEN 'MAIN'
    WHEN t.is_main_thread = 1 THEN 'MAIN'
    ELSE 'other'
  END AS lane
FROM slice s
LEFT JOIN thread_track tt ON s.track_id = tt.id
LEFT JOIN thread t ON tt.utid = t.utid
WHERE s.name LIKE 'AGENT_TRACE_%'
ORDER BY s.ts;
```

`LEFT JOIN` is critical — slices wrapped across coroutine `withContext` boundaries land on async tracks, not thread tracks, and an inner join would silently drop them.

### Recipe 2: frame budget compliance

For "did frames meet the 60 Hz / 120 Hz budget?" use the actual frame timeline (requires `surfaceflinger_frame_timeline` in the capture config — see `android-perfetto-capture` Strategy B):

```sql
WITH app_frames AS (
  SELECT
    name AS frame_id,
    ts,
    dur / 1e6 AS dur_ms,
    jank_type,
    jank_severity_type,
    on_time_finish
  FROM actual_frame_timeline_slice
  WHERE upid = (
    SELECT upid FROM process WHERE name = 'com.example.app' LIMIT 1
  )
)
SELECT
  COUNT(*) AS total_frames,
  SUM(CASE WHEN on_time_finish = 1 THEN 1 ELSE 0 END) AS on_time,
  SUM(CASE WHEN on_time_finish = 0 THEN 1 ELSE 0 END) AS late,
  SUM(CASE WHEN jank_type != 'None' THEN 1 ELSE 0 END) AS janky,
  PRINTF('%.2f', AVG(dur_ms)) AS avg_dur_ms,
  PRINTF('%.2f', MAX(dur_ms)) AS p100_dur_ms
FROM app_frames;
```

For per-frame detail (only when total frames < ~60, otherwise this is too verbose):

```sql
SELECT
  frame_id,
  PRINTF('%.2f', dur_ms) AS dur_ms,
  jank_type,
  on_time_finish
FROM app_frames
WHERE on_time_finish = 0 OR jank_type != 'None'
ORDER BY ts
LIMIT 50;
```

### Recipe 3: what was the main thread doing during a slow window?

Bound the query by timestamps once you've identified a slow region from Recipe 2:

```sql
-- Replace 1234567890 / 1244567890 with your slow-frame ts and ts+dur
SELECT
  s.name,
  s.ts AS ts_ns,
  s.dur / 1e6 AS dur_ms
FROM slice s
JOIN thread_track tt ON s.track_id = tt.id
JOIN thread t ON tt.utid = t.utid
JOIN process p ON t.upid = p.upid
WHERE
  p.name = 'com.example.app'
  AND t.is_main_thread = 1
  AND s.ts >= 1234567890
  AND s.ts <= 1244567890
ORDER BY s.dur DESC
LIMIT 30;
```

The result is the top 30 longest slices on the main thread inside that window — that's where the budget went.

### Recipe 4: sched-state breakdown (which threads ran, for how long?)

```sql
SELECT
  t.name AS thread_name,
  PRINTF('%.2f', SUM(s.dur) / 1e9) AS total_running_s,
  COUNT(*) AS num_slices
FROM sched s
JOIN thread t USING (utid)
JOIN process p USING (upid)
WHERE p.name = 'com.example.app'
GROUP BY t.name
ORDER BY SUM(s.dur) DESC
LIMIT 20;
```

Shows the most CPU-hungry threads in your app over the entire trace. Useful for "which thread is doing surprisingly much work?"

### Recipe 5: GC pauses

```sql
SELECT
  s.name,
  s.ts AS ts_ns,
  s.dur / 1e6 AS dur_ms,
  t.name AS thread_name
FROM slice s
JOIN thread_track tt ON s.track_id = tt.id
JOIN thread t ON tt.utid = t.utid
JOIN process p ON t.upid = p.upid
WHERE
  p.name = 'com.example.app'
  AND s.name IN ('Concurrent mark sweep GC', 'Concurrent copying GC', 'Explicit concurrent copying GC')
ORDER BY s.dur DESC
LIMIT 20;
```

Long GC pauses (over ~10 ms) on the main thread are visible in the trace as scheduled-out segments. This recipe surfaces them by name.

### Recipe 6: binder transactions (IPC overhead)

```sql
SELECT
  s.name,
  COUNT(*) AS calls,
  PRINTF('%.2f', SUM(s.dur) / 1e6) AS total_ms,
  PRINTF('%.2f', AVG(s.dur) / 1e6) AS avg_ms,
  PRINTF('%.2f', MAX(s.dur) / 1e6) AS max_ms
FROM slice s
JOIN thread_track tt ON s.track_id = tt.id
JOIN thread t ON tt.utid = t.utid
JOIN process p ON t.upid = p.upid
WHERE
  p.name = 'com.example.app'
  AND s.name LIKE 'binder%'
GROUP BY s.name
ORDER BY SUM(s.dur) DESC
LIMIT 20;
```

If `binder transaction` shows up as a multi-millisecond average inside a tight UI loop, you have an IPC perf issue.

### Recipe 7: Compose recomposition count

If your app uses Compose's tracing (Compose 1.5+ instruments `Recomposer` slices automatically when `androidx.tracing` is on the classpath):

```sql
SELECT
  s.name,
  COUNT(*) AS recompose_count,
  PRINTF('%.2f', AVG(s.dur) / 1e6) AS avg_dur_ms,
  PRINTF('%.2f', SUM(s.dur) / 1e6) AS total_ms
FROM slice s
JOIN thread_track tt ON s.track_id = tt.id
JOIN thread t ON tt.utid = t.utid
WHERE s.name LIKE '%recompose' OR s.name LIKE '%Recompose%'
GROUP BY s.name
ORDER BY total_ms DESC
LIMIT 20;
```

A composable recomposing 200 times across a 10s capture is a stability bug, even if each recomposition is fast.

## Aggregating across multiple captures

When `android-perfetto-capture` produced N traces (Recipe section "Repetition for noisy phenomena"), use a shell loop to extract one number per trace and compare the distribution:

```bash
echo "trace,p50_ms,p95_ms,max_ms" > /tmp/trace-aggregate.csv
for f in /tmp/trace-*.perfetto-trace; do
    /tmp/trace_processor "$f" -q - <<'SQL' | tail -1 | awk -v f="$f" '{print f","$0}' >> /tmp/trace-aggregate.csv
SELECT
  PRINTF('%.2f', PERCENTILE(dur_ms, 50)) AS p50,
  PRINTF('%.2f', PERCENTILE(dur_ms, 95)) AS p95,
  PRINTF('%.2f', MAX(dur_ms)) AS max_ms
FROM (
  SELECT s.dur / 1e6 AS dur_ms
  FROM slice s
  WHERE s.name = 'YOUR_SLICE_NAME'
);
SQL
done
```

(Note: trace_processor's SQLite has `PERCENTILE_CONT` available in newer builds — fall back to `MIN`/`AVG`/`MAX` if your version doesn't.)

## Verdict workflow

After every query, the verdict step is:

1. `wc -l <result-file>` — confirm the result is small
2. Spawn a Sonnet sub-agent with **explicit, bounded** parsing instructions

Bad sub-agent prompt:

> Read the trace results and tell me what's slow.

Good sub-agent prompt:

> Read `/tmp/trace-mainthread-window.txt`. Return: (a) the single longest slice and its duration in ms, (b) the count of slices longer than 8 ms, (c) the most-frequent slice name. Under 60 words. `model: "sonnet"`.

The sub-agent's verdict tells you whether to commit a fix, capture again, or run a different query.

## Cleanup gate

The trace files and their analysis artifacts:

```bash
rm -f /tmp/trace*.perfetto-trace /tmp/trace-*.txt /tmp/trace-aggregate.csv
# Keep /tmp/trace_processor — it's reusable across sessions
```

If you wrote any custom shell wrappers around the queries, clean those too. The cleanup grep is light — no source touched, the only state is in `/tmp`.

## Common mistakes

| Mistake | Fix |
|---------|-----|
| Reading a trace file inline | Always go through `trace_processor` SQL — the binary file is unreadable to LLMs |
| Inner JOIN on `thread_track` for slices | Async slices have no thread; use `LEFT JOIN` and coalesce thread name |
| Query returning 5,000 rows | Aggregate, group, or filter; the sub-agent needs a small result, not raw data |
| Forgot to capture `surfaceflinger_frame_timeline` | Frame analysis (Recipe 2) returns empty; recapture with `frametimeline` data source |
| Comparing wall-clock dur to thread time | `dur` is wall-clock; `tts` (thread_ts) gives CPU time — use the right one for the question |
| Querying release build trace | Trace sections may be stripped; analysis returns empty for app slices; capture against debug |
| One trace, one number, declaring victory | Capture multiple times, look at the distribution; one capture is one sample |
| Sub-agent given the trace file directly | Sub-agents can't parse `.perfetto-trace`; always feed them the SQL result file |
| `trace_processor` not refreshed across major Perfetto versions | Some queries break across versions; if a query errors, refresh: `rm /tmp/trace_processor` and re-curl |
