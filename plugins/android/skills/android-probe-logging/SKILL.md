---
name: android-probe-logging
description: Use when you need empirical proof a code path actually executed — branch entries, callback fires, coroutine continuations, suspect early returns. Insert temporary `Log.d` calls with a unique sentinel tag, redeploy, drive the app, read filtered logcat, then remove every probe before declaring done. The default investigation skill when the question is "did this run, in what order, with what values?"
---

# Android Probe Logging — Ephemeral Log.d Instrumentation

## When to use

- Confirming a branch, callback, or lambda actually ran
- Tracing the order of async / coroutine operations
- Inspecting the actual values flowing through a function (params, return, intermediate state)
- Disambiguating "the fix didn't work" from "the fix never ran"
- Verifying a `when` / `if` arm landed where you expected

## When NOT to use

- The question is about UI state — use `verify-android-layout` instead
- The question is about main-thread blocking or leaks — use `android-strictmode-probe`
- The question is "is this code expensive / on which thread?" — use `android-trace-sections`
- The question is "which of 1000 commits broke it?" — use `android-regression-diff-scan`

## Pre-flight: detect what your project supports

Before instrumenting, confirm two things:

```bash
# 1. adb is connected and the device is authorized
adb devices                                    # expect "<id>  device"

# 2. The package builds and installs in debug
ls app/src/main/java app/src/main/kotlin 2>/dev/null    # which language?
```

**Language note.** The examples below use Kotlin (`private const val PROBE = ...`). For a Java codebase, the equivalent is:

```java
private static final String PROBE = "AGENT_PROBE_a4f9c2e1";
// ...
Log.d(PROBE, "fetchUser id=" + id + " cached=" + cache.contains(id));
```

The cleanup grep for `AGENT_PROBE_` catches both forms — sentinel hygiene is language-agnostic.

**Mixed Kotlin/Java module.** Place the constant in whichever language the file you're probing uses; mixing is fine since `String` is `String` on the JVM.

**No Android Log import in the file?** Add `import android.util.Log` (Kotlin) or it's resolvable as `Log.d(...)` once `android.util.Log` is on the classpath (always true for an app module). Lint may flag the import on cleanup — make sure `rg 'AGENT_PROBE_'` is empty before relying on lint, since stale probes can keep an unused import alive.

## The pattern: probe → run → observe → remove

The shape is non-negotiable. The most common failure mode is leaving probes in committed code. The unique sentinel tag is what makes cleanup tractable.

### 1. Pick a sentinel tag for this investigation

Generate a short unique id and bake it into the tag:

```kotlin
private const val PROBE = "AGENT_PROBE_a4f9c2e1"
```

One id per investigation. Don't reuse across sessions — leftover probes from yesterday will pollute today's logs.

### 2. Insert probes at decision boundaries

Probe at points where execution order or state is non-obvious:

- Branch entries (`if`, `when`, `else`)
- Before and after suspect calls
- Inside lambdas, callbacks, coroutine `launch` / `collect` blocks
- Early returns

**Log inputs and outputs, not "got here":**

```kotlin
// Bad — tells you nothing you didn't already guess
Log.d(PROBE, "in fetchUser")

// Good — captures the actual data
Log.d(PROBE, "fetchUser id=$id cached=${cache.contains(id)}")
// ... call ...
Log.d(PROBE, "fetchUser id=$id -> ${result.javaClass.simpleName}")
```

For exceptions, log the type and message but not full stack — `adb logcat` will already have the stack from `AndroidRuntime` if it propagates:

```kotlin
runCatching { suspectCall() }
    .onFailure { Log.d(PROBE, "suspectCall threw ${it::class.simpleName}: ${it.message}") }
    .onSuccess { Log.d(PROBE, "suspectCall ok=$it") }
```

### 3. Redeploy and drive the app

```bash
android run                             # or your usual deploy command
adb logcat -c                           # clear stale logs
# ... drive the app to the suspect state via taps/swipes/input ...
adb logcat -d -s AGENT_PROBE_a4f9c2e1 > /tmp/probe-a4f9c2e1.log
```

`-c` clears, `-d` dumps and exits, `-s <tag>` filters to your sentinel only. The output file should contain only your probe lines.

### 4. Delegate parsing for anything non-trivial

Inline-read the log if it's under ~30 lines. Otherwise spawn a Sonnet sub-agent with a self-contained prompt:

> Read `/tmp/probe-a4f9c2e1.log`. Confirm the sequence: (a) `fetchUser` called with `id=42`, (b) cache miss, (c) network branch entered, (d) result logged with class `User`. Answer YES/NO + one sentence on what's missing or out of order. Under 40 words.

Pass `model: "sonnet"` — narrow text parsing doesn't need Opus.

### 5. Cleanup gate (BLOCKING — do not skip)

Before declaring the task done, run from the repo root:

```bash
rg 'AGENT_PROBE_a4f9c2e1'
```

(Substitute your investigation's id.) **The expected output is empty.** If it's not, remove every match — including the `private const val PROBE = ...` declaration line — and run again. Only then is the task done.

If you used multiple sentinel ids across one investigation, grep for the shared prefix to catch them all:

```bash
rg 'AGENT_PROBE_'
```

This is the single most-skipped step. Sentinel tags exist precisely to make this gate trivial; use them.

## Probe placement examples

**Coroutine flow** — confirm collection actually fires:

```kotlin
viewModelScope.launch {
    Log.d(PROBE, "collect start state=${_state.value}")
    repository.observeUser()
        .onEach { Log.d(PROBE, "emit ${it.id}") }
        .collect {
            Log.d(PROBE, "collect received ${it.id}")
            _state.value = it
        }
}
```

**Conditional logic** — confirm which arm executes:

```kotlin
when (status) {
    Status.Loading -> { Log.d(PROBE, "Loading arm"); /* ... */ }
    Status.Error -> { Log.d(PROBE, "Error arm err=${error?.message}"); /* ... */ }
    Status.Success -> { Log.d(PROBE, "Success arm n=${data.size}"); /* ... */ }
}
```

**Compose recomposition** — confirm a composable re-runs:

```kotlin
@Composable
fun UserRow(user: User) {
    Log.d(PROBE, "UserRow recompose id=${user.id} v=${user.version}")
    // ...
}
```

## Common mistakes

| Mistake | Fix |
|---------|-----|
| Skipping the cleanup gate | `rg 'AGENT_PROBE_'` must return zero before commit; this is non-negotiable |
| Generic tag like `"DEBUG"` or `"TAG"` | Use a unique `AGENT_PROBE_<8-char-id>` per investigation so cleanup is greppable |
| Probe says "got here" with no data | Log the actual inputs/outputs; "got here" tells you nothing you didn't already guess |
| Reading a 500-line probe log inline | Delegate to a Sonnet sub-agent with explicit criteria and a return-format cap |
| Forgetting `adb logcat -c` before driving the app | Stale logs from earlier runs will mix in and confuse the sub-agent's verdict |
| Reusing yesterday's sentinel id | Old probes from a prior investigation pollute today's results — fresh id each time |
| Letting the sub-agent default to Opus | Always pass `model: "sonnet"` — narrow text parsing |
| Committing the `private const val PROBE` line | The sentinel constant counts as a probe; the cleanup grep catches it, remove it too |
