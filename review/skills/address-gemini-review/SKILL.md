---
name: address-gemini-review
description: Use when the user asks to address Gemini review comments, handle gemini-code-assist bot feedback, triage Gemini's automated code review suggestions, or respond to gemini-code-assist bot comments on a pull request
---

Fetch, validate, triage, and address `gemini-code-assist[bot]` PR review comments. Core principle: **skeptical triage, not blind compliance.** Gemini lacks cross-file context and project history — treat its suggestions as hints, not instructions.

Read `references/github-api-patterns.md` for exact `gh api` commands, field names, and reply formats.

## Workflow

### 1. Detect PR

Use `$ARGUMENTS` if provided (e.g., `/address-gemini-review 82`). Otherwise auto-detect:

```bash
gh pr view --json number --jq '.number'
```

Fail clearly if no PR is found.

### 2. Fetch Gemini Comments

Run both calls from the reference file (inline comments + formal reviews). Filter to `gemini-code-assist[bot]`. Skip comments where `in_reply_to_id` is set (those are replies, not original comments).

**If zero comments found, say so and stop.** No further action needed.

### 3. Parse and Categorize

For each comment, extract: `id`, `path`, `line`, `start_line`, `body`, severity badge, and any suggestion block. Group by file path.

### 4. Validate and Triage

For each comment, **read the actual file** at the referenced lines. Then evaluate:

| Question | If No |
|----------|-------|
| Is the comment technically correct about the current code? | DISMISS |
| Is the suggestion syntactically and semantically correct? | DISMISS the suggestion (may still FIX differently) |
| Is this worth the churn? (severity vs effort vs risk) | DISMISS |
| Does it conflict with existing architecture or project conventions? | DISMISS |
| Does it require human judgment (trade-off, product decision)? | DISCUSS |

Apply `receiving-code-review` skepticism:
- **YAGNI check**: Is Gemini suggesting defensive code for impossible states?
- **Cross-file blindness**: Does Gemini see the full picture, or just this file?
- **Platform compat**: Is the suggestion actually better, or just different?

Assign each comment: **FIX** / **DISMISS** / **DISCUSS**

### 5. Present Triage Summary — HARD GATE

Present a table:

```
| # | File | Line | Severity | Verdict | Reason |
|---|------|------|----------|---------|--------|
| 1 | src/foo.ts | 42 | medium | FIX | Redundant sort — suggestion is correct |
| 2 | src/bar.ts | 18 | low | DISMISS | Already handled by upstream validator |
```

Then for each comment, show the Gemini comment body (truncated), your analysis, and verdict rationale.

**ASK THE USER**: "These are my proposed verdicts. Approve, or tell me which to change."

**DO NOT implement anything until the user explicitly approves.** This gate is non-negotiable.

### 6. Implement Fixes

For approved FIX items only:
- If Gemini provided a correct suggestion block, apply it directly
- Otherwise, write the fix independently based on your own analysis
- Run the project's test suite after all fixes are applied

### 7. Reply to Every Comment

Using the reply endpoint from the reference file, reply to **every** Gemini comment — including dismissed ones:

- **FIX**: `"Fixed in {sha}. {what changed}."`
- **DISMISS**: `"Not applicable — {technical reason}."`
- **DISCUSS**: `"Flagged for discussion — {why}."`

No performative language. No "Great catch!" No "Thanks for the suggestion!" Just the technical substance.

### 8. Commit

Single commit for all fixes: `fix: address gemini review on PR #{number}`

If all comments were dismissed (no code changes), skip the commit — replies are sufficient.

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| Implementing before triage approval | The hard gate at step 5 exists for a reason — wait |
| Blindly applying suggestion blocks | Suggestions can be wrong. Validate against the actual code |
| Creating new top-level comments instead of thread replies | Use the `/replies` endpoint, not `/comments` |
| Hardcoding owner/repo | Always detect dynamically via `gh repo view` |
| Performative reply language | Direct and technical only |
| Skipping replies for dismissed comments | Every comment gets a reply — paper trail matters |
| Treating all Gemini comments equally | Severity + your own judgment determine priority |
