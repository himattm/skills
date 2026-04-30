---
name: android-regression-diff-scan
description: Use INSTEAD of git bisect when investigating a regression between two refs (releases, branches, "it worked yesterday") — especially when builds are slow or the bug is hard to reproduce. Hand the full `git diff <good> <bad>` to a Sonnet sub-agent along with the bug description and let it surface suspect areas. Bisect exists because humans can't reason about thousands of lines at once. LLMs can. No builds, no waiting — minutes instead of an hour of compiling.
---

# Android Regression Diff Scan

## Why this beats `git bisect` for mobile

`git bisect` exists because humans can't reason about thousands of lines of change at once. The bisect dance — narrow the range, build, test, narrow again — is a workaround for human context limits.

LLMs don't share that limit. A sub-agent can read 400K diff lines and spot the suspicious patterns directly.

A motivating example: investigating a regression between two releases with **1,300 commits** and **413,032 lines changed** between them. With 2-minute builds, bisect is ~22 minutes of pure waiting. With 5-minute builds (typical for a real Android app), it's nearly an hour. A diff scan takes minutes with no builds.

This is the right tool whenever:

- The bug repros in the bad ref but not the good ref
- Builds are slow (most non-trivial Android projects)
- You don't have a reliable repro script for an automated bisect
- The diff is large enough that human reading would be guesswork

## When to use

- "It worked in release N, broken in release N+1" — release tag pair
- "Main works, my feature branch doesn't" — branch pair
- "Last week's build is fine, this week's crashes" — date-based ref pair
- Any regression where you have a known-good ref and a known-bad ref

## When NOT to use

- The bug reproduces locally and builds are fast (<1 min) — `git bisect` with a script is still the right tool
- The diff is small (<500 lines) — just read it
- You don't have a known-good ref — use `android-probe-logging` to investigate from symptoms
- The bug is non-deterministic and not in changed code — use `android-crash-repro-loop` to characterize it first

## Workflow

### 1. Identify the good and bad refs

Be precise:

- Release tags: `release_8`, `release_9`
- Commits: the last commit known to be good, the first commit known to be bad
- Branches: `main` vs `feature/foo`

If unsure which ref is "good," confirm by deploying it and checking the symptom is absent. A wrong baseline ref means a wrong scan.

### 2. Size up the change

```bash
git diff --stat <good>..<bad> | tail -20
git log <good>..<bad> --oneline | wc -l
```

The `--stat` summary tells you which files moved most — high-churn files are the first place to look. The commit count is sanity: 50 commits is normal, 1,500 commits means you're investigating a release.

### 3. Capture the artifacts

```bash
git diff <good>..<bad> > /tmp/regression-diff.patch
git log <good>..<bad> --oneline > /tmp/regression-log.txt
git diff --stat <good>..<bad> > /tmp/regression-stat.txt
```

For huge diffs, also produce focused subsets when you have a domain hint:

```bash
# If the bug is in login flow:
git diff <good>..<bad> -- 'app/src/**/login/**' '*/auth/**' > /tmp/regression-diff-auth.patch

# If the bug is UI-only:
git diff <good>..<bad> -- '*.kt' '*.xml' ':!**/test/**' > /tmp/regression-diff-ui.patch
```

### 4. Write the bug brief

The sub-agent's quality depends entirely on the bug description. Capture:

- **Symptom** — what the user sees ("crash", "wrong color", "button doesn't respond")
- **When it appears** — entry point, sequence of actions, conditions (offline, after rotation, on cold start)
- **Evidence** — stack trace if any, log fragment, screenshot description
- **What's the same** — what's *not* changed between good and bad (helps narrow)

Save to `/tmp/regression-bug.md`.

### 5. Delegate to a Sonnet sub-agent

Spawn the agent with `model: "sonnet"` and a self-contained prompt. The diff is the input — never read it in the main thread.

> Read `/tmp/regression-diff.patch`, `/tmp/regression-log.txt`, and `/tmp/regression-bug.md`.
>
> The bug described in `regression-bug.md` was introduced somewhere in this diff. Identify the **top 3–5 most suspect changes** that could explain it. For each, return:
>
> - File and line range (`path/to/File.kt:120-145`)
> - One-sentence reasoning tying the change to the bug symptom
> - Confidence: high / medium / low
>
> Prefer changes that touch: the symptom's surface area (UI for visual bugs, network for connectivity bugs, etc.), feature-flag conditions, error-handling paths, and lifecycle hooks. Skip cosmetic refactors and dependency bumps unless they directly touch the affected code.
>
> Under 250 words total.

### 6. Investigate the surfaced areas

This skill **finds the haystack, not the needle.** Take the top suspects and verify with instrumentation:

- `android-probe-logging` — confirm the suspect code path runs and inspect values
- `android-snapshot-diff` — confirm state actually changes in the suspect flow
- `android-strictmode-probe` — if the bug smells like main-thread / leak

If the top 5 suspects all check out clean, refine the bug brief (it probably needs more detail) or run a focused scan against a different file subset.

### 7. Cleanup gate

```bash
rm /tmp/regression-diff*.patch /tmp/regression-log.txt /tmp/regression-stat.txt /tmp/regression-bug.md
```

No source touched, so the gate is light. But the patch files can be large — leaving them around bloats `/tmp` over an investigation session.

## Iteration patterns

**Top suspect doesn't pan out.** Re-prompt the sub-agent with the exclusion: "I checked `path/to/Foo.kt:120-145` — it's not the cause. Re-rank the remaining suspects and add 2 new candidates."

**Diff is too large for one pass.** Split by directory and run scans in parallel against subsets, then combine the rankings:

```bash
git diff <good>..<bad> -- 'app/src/main/java/com/example/feature_a/**' > /tmp/regression-diff-a.patch
git diff <good>..<bad> -- 'app/src/main/java/com/example/feature_b/**' > /tmp/regression-diff-b.patch
```

**No obvious suspects.** The bug may not be in the diff (env / config / data change) or the bug brief is too vague. Don't escalate to bisect — re-investigate the symptom first.

## Common mistakes

| Mistake | Fix |
|---------|-----|
| Reading the diff inline | Always delegate to a Sonnet sub-agent — diffs are the entire input |
| Letting the sub-agent default to Opus | Pass `model: "sonnet"` — diff scanning is text comprehension, not reasoning |
| Vague bug brief ("it's broken") | Symptom + when + evidence + what's the same — quality of brief = quality of suspects |
| Wrong baseline ref | Confirm the "good" ref actually doesn't have the symptom before scanning |
| Falling back to bisect when one suspect doesn't pan out | Re-prompt the sub-agent excluding the dud; bisect is the *last* resort, not the second |
| Skipping `--stat` | The stat tells you which files moved; high-churn files are first place to look |
| Forgetting the commit log | `git log --oneline` gives the sub-agent commit message context — surprisingly useful |
| Leaving `/tmp/regression-*` patch files | They can be huge (100MB+ for big releases); clean up between investigations |
