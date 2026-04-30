---
name: android-reproduce-as-test
description: Use BEFORE fixing any non-trivial bug. Write a test that fails because of the bug, watch it fail, then fix the bug until the test passes. Forces the agent to demonstrate the bug exists in code before guessing at fixes — and leaves a regression guard. Trigger whenever you'd otherwise edit production code based on a stack trace, user report, or "I think the issue is…" hypothesis.
---

# Android Reproduce-As-Test

## Why this is the default for bugs

The most common debugging failure is fixing the wrong thing. The agent sees a stack trace, theorizes a cause, edits a file, redeploys, and declares done — without evidence the change addresses the actual bug. A failing test changes that:

- **You can't fix what you can't reproduce.** If you can't write a test that fails for the bug, you don't yet understand the bug. Stop and investigate first.
- **Pass = proof.** When the test goes red→green on your fix, you have direct evidence the change is causal — not just correlated.
- **The test stays.** It's now a regression guard. Future refactors will either keep working or fail loudly.

## When to use

- Any bug with a clear repro path (stack trace, user steps, log line)
- Logic bugs in pure Kotlin/Java code
- Framework interactions that Robolectric can simulate
- Real-device behaviors via instrumentation tests

## When NOT to use

- Pure visual / styling tweaks — use `verify-android-screen` to confirm
- One-line typo fixes where the error is obvious from the stack trace
- Bugs you can't isolate yet — investigate first with `android-probe-logging` or `android-regression-diff-scan`, then come back here

## Pick the right test layer

| Layer | When | Speed | Command |
|-------|------|-------|---------|
| **JVM unit** (JUnit + MockK / Mockito) | Pure Kotlin/Java logic, ViewModels, repositories with mocked deps | Fast (sub-second per test) | `./gradlew :app:testDebugUnitTest --tests <FQN>` |
| **Robolectric** | Code that uses Android framework classes (Context, Resources, SharedPreferences) but doesn't need a real device | Fast (~1s per test) | Same as JVM unit; Robolectric runs on the JVM target |
| **Instrumentation** | Real device behavior — UI, lifecycle, IPC, real database | Slow (10s+ per test, needs emulator) | `./gradlew :app:connectedDebugAndroidTest --tests <FQN>` |

Default to the fastest layer that can express the bug. Pushing a JVM test up to instrumentation when a mock would do is a common time waster.

### Robolectric quick-setup (if the project doesn't have it yet)

If your bug needs Android framework classes but the project has no Robolectric, ephemerally add it:

```kotlin
// app/build.gradle.kts — under android { ... }
testOptions {
    unitTests {
        isIncludeAndroidResources = true   // Required for Robolectric
    }
}

// app/build.gradle.kts — under dependencies { ... }
testImplementation("org.robolectric:robolectric:4.13")
```

Annotate the test class:

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class LoginViewModelTest { /* ... */ }
```

Use `@Config(application = MyApp::class)` if your repro needs your real `Application` subclass; pass `Application::class` (the framework default) when you don't. Robolectric defaults `sdk` to the project's `targetSdk`; pin it explicitly when the bug is API-level-sensitive. Keep the dependency add scoped to this PR if the rest of the project doesn't use Robolectric — or coordinate adding it as a permanent dev dependency separately.

## Workflow

### 1. Name the test for the behavior, not the symptom

```kotlin
// Bad
@Test fun bug_1234()

// Bad
@Test fun crashOnLogin()

// Good — names the actual broken behavior
@Test fun loginRetainsEmailAfterRotation()
@Test fun fetchUserReturnsCachedValueWhenOffline()
@Test fun submitButtonDisabledUntilEmailValid()
```

The test name is the bug spec. Future readers should understand the contract from the name alone.

### 2. Write the test so it fails *for the right reason*

The test must fail because of the bug, not because of a typo or missing setup. Read the failure output — the assertion message should describe the bug, not "NullPointerException at MyTest.kt:42."

A common antipattern: writing a passing test, then "fixing" the bug, then claiming success. If the test never failed, it never proved anything.

### 3. Run the focused test loop

Run only your new test on each iteration — the full suite is wasted time during a fix:

```bash
./gradlew :app:testDebugUnitTest --tests com.example.user.LoginViewModelTest.loginRetainsEmailAfterRotation
```

For instrumentation:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.user.LoginFlowTest#emailRetainedAcrossRotation
```

### 4. Red → green → all-green

1. Run the test. It must fail. If it passes, the test is wrong — the bug isn't where you think it is. Re-investigate.
2. Fix the production code.
3. Run the focused test. It must pass.
4. Run the full suite (`./gradlew :app:testDebugUnitTest` and/or `connectedDebugAndroidTest`) to confirm no regression elsewhere.

### 5. Cleanup gate

The new test stays — it's the regression guard. But before declaring done:

- Confirm the test ran on the final code and passed (not just "I think it would").
- `git status` — no scratch files (`/tmp/`, commented-out test bodies, `@Ignore`d siblings).
- `rg '@Ignore|TODO\(.*test'` — no disabled tests left behind.
- The full suite passes.

## Anti-patterns

- **Test that passes immediately.** If your test is green before the fix, you haven't reproduced the bug. The test is wrong or the fix isn't needed.
- **Test that asserts implementation details.** Assert behavior (what the user/caller observes), not internal call counts or private state — those break on every refactor.
- **Multiple bugs, one test.** One test per bug. If you find another bug while fixing this one, write a second test for it.
- **`@Ignore` instead of fix.** Never disable a failing test. Fix the bug or the test.

## Sub-agent usage

Most of this skill is single-threaded — write, run, read short failure output. Delegate only when:

- The full test suite output is large after your final run. Spawn a Sonnet sub-agent to read `app/build/reports/tests/testDebugUnitTest/index.html` (or the equivalent XML in `app/build/test-results/`) and report `N passed, M failed` plus the first failure's class + method + message. Under 60 words.

## Common mistakes

| Mistake | Fix |
|---------|-----|
| Writing the fix first, then the test | Test FIRST. If you can't write a failing test, you don't understand the bug. Stop and investigate. |
| Test passes on the broken code | Re-read the failure (or absence of one) — the test isn't actually exercising the bug. Refine until it goes red. |
| Reaching for instrumentation when a JVM unit test would do | Push the bug down to the fastest layer that can express it; instrumentation tests are 10–100× slower |
| Running the whole suite each iteration | Use `--tests <FQN>` to target the one test until it passes; full suite once at the end |
| Test name = `bug_1234` or `testFoo` | Name it for the contract: `loginRetainsEmailAfterRotation` |
| `@Ignore`ing the failing test to "come back later" | Never disable a test instead of fixing it; that's how regressions ship |
| Asserting on `verify(mock).callMethod(any())` | Assert observable behavior, not call counts; behavior survives refactor, mocks don't |
| Skipping the full-suite run after green | Your fix may have broken something else; the focused-then-full sequence is the contract |
