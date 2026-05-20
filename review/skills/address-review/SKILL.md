---
name: address-review
description: Use when the user asks to address PR review comments — from human reviewers or automated bots (Gemini, Coderabbit, Copilot, SonarCloud, etc.) — to triage and respond to feedback on a pull request
---

Fetch, validate, triage, and address PR review comments from any reviewer — humans or automated bots. Core principle: **skeptical triage, not blind compliance.** Reviewers (especially bots) often lack cross-file context and project history — treat their suggestions as hints, not instructions.

Read `references/github-api-patterns.md` for exact `gh api` commands, field names, and reply formats.

## Workflow

### 1. Detect PR and Author Filter

- **PR**: Use `$ARGUMENTS` if provided (e.g., `/address-review 82` or `/address-review 82 coderabbitai[bot]` or `/address-review 82 alice`). Otherwise auto-detect:

  ```bash
  gh pr view --json number --jq '.number'
  ```

  Fail clearly if no PR is found.

- **Author filter**: If a login is passed as the second argument, filter to that login (works for bots like `coderabbitai[bot]` or humans like `alice`). Otherwise default to **all reviewers** on the PR — the triage table makes it easy to scope down after the fact.

### 2. Fetch Review Comments

Run both calls from the reference file (inline comments + formal reviews). Filter by the login(s) from step 1. Skip comments where `in_reply_to_id` is set (those are replies, not original comments).

**If zero comments found, say so and stop.** No further action needed.

### 3. Parse and Categorize

For each comment, extract: `id`, `path`, `line`, `start_line`, `body`, `user.login` (who said it), severity (if encoded), and any suggestion block. Group by file path.

Severity conventions vary — see the reference file. Bots typically encode badges/emoji; humans use cues like `nit:`, `question:`, `blocking:`, or plain prose. When nothing is encoded, treat as `medium`.

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
- **YAGNI check**: Is the reviewer suggesting defensive code for impossible states?
- **Cross-file blindness**: Does the reviewer see the full picture, or just this file? (Especially relevant for bots.)
- **Platform compat**: Is the suggestion actually better, or just different?

Weight human feedback more heavily than bot feedback by default — humans usually have context bots lack — but apply the same triage rubric to both.

Assign each comment: **FIX** / **DISMISS** / **DISCUSS**

### 5. Present Triage Summary — HARD GATE

Present a table:

```
| # | Author             | File       | Line | Severity | Verdict | Reason |
|---|--------------------|------------|------|----------|---------|--------|
| 1 | gemini-code-assist | src/foo.ts | 42   | medium   | FIX     | Redundant sort — suggestion is correct |
| 2 | alice              | src/bar.ts | 18   | blocking | FIX     | Missing null check on user-supplied input |
| 3 | coderabbitai       | src/baz.ts | 7    | nitpick  | DISMISS | Already handled by upstream validator |
```

Then for each comment, show the comment body (truncated), your analysis, and verdict rationale.

**ASK THE USER**: "These are my proposed verdicts. Approve, or tell me which to change."

**DO NOT implement anything until the user explicitly approves.** This gate is non-negotiable.

### 6. Implement Fixes

For approved FIX items only:
- If the reviewer provided a correct suggestion block, apply it directly
- Otherwise, write the fix independently based on your own analysis
- Run the project's test suite after all fixes are applied

### 7. Reply to Every Comment

Reply to **every** comment — including dismissed ones. The path differs by author type:

**Bot comments** (`*[bot]` logins): post directly using the reply endpoint from the reference file. The step 5 triage gate is sufficient approval.

**Human comments**: confirm each reply with the user before posting. For every human comment, call `AskUserQuestion`:

- `question`: include the author, `path:line`, a short snippet of their comment, and your proposed reply text
- `header`: e.g. `Reply 1/3` (keep under 12 chars)
- `options`:
  - `{label: "Post this reply", description: "Send the proposed text as shown", preview: "<full proposed reply text>"}`
  - `{label: "Skip this comment", description: "Don't reply to this thread"}`

The tool auto-adds an "Other" option so the user can type a custom reply. Send whichever text they pick via the endpoint from the reference file; if they choose Skip, leave the thread untouched.

Reply templates (starting point for both bot and human; the user may override for humans):

- **FIX**: `"Fixed in {sha}. {what changed}."`
- **DISMISS**: `"Not applicable — {technical reason}."`
- **DISCUSS**: `"Flagged for discussion — {why}."`

No performative language. No "Great catch!" No "Thanks for the suggestion!" Just the technical substance — same standard for humans and bots.

### 8. Commit

Single commit for all fixes: `fix: address review on PR #{number}`

If all comments were dismissed (no code changes), skip the commit — replies are sufficient.

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| Implementing before triage approval | The hard gate at step 5 exists for a reason — wait |
| Blindly applying suggestion blocks | Suggestions can be wrong. Validate against the actual code |
| Creating new top-level comments instead of thread replies | Use the `/replies` endpoint, not `/comments` |
| Hardcoding owner/repo | Always detect dynamically via `gh repo view` |
| Hardcoding a single author filter | Default to all reviewers; let the user narrow via argument |
| Performative reply language | Direct and technical only — same standard for humans and bots |
| Skipping replies for dismissed comments | Every comment gets a reply — paper trail matters |
| Treating all comments equally | Severity + reviewer context + your own judgment determine priority |
| Trusting bot suggestions over human review | Humans usually have project context bots lack — weight accordingly |
| Auto-posting replies to humans | Always confirm human replies via `AskUserQuestion` first — bots can post directly after step 5 |
