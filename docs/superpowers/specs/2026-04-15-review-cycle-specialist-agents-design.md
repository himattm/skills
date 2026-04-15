# Review-Cycle: Specialist Agent Team

## Summary

Replace the review-cycle skill's single monolithic review agent with a dynamically composed team of specialist review agents. The calling LLM reads the PR diff, decides which domains need focused review (e.g., security, performance, input validation), spawns parallel specialist agents, then merges and triages their findings before proceeding to fixes.

## Design Decisions

| Decision | Rationale |
|----------|-----------|
| Fully dynamic agent selection | No predefined menu — the LLM picks specialist domains based on what it sees in the diff. A one-file config change gets different specialists than a PR touching auth middleware. |
| LLM as orchestrator | The calling LLM reads the diff, selects domains, writes specialist prompts, merges findings, and triages. It sees the big picture and delegates depth. |
| Parallel specialist execution | All specialists run concurrently — each gets the full diff but reviews through a single focused lens. |
| Merged findings, single triage | All specialist output is combined into one list, deduplicated, then triaged once. Avoids duplicate fixes and keeps the fix cycle simple. |
| Full re-selection each round | Every iteration re-analyzes the full current diff and re-picks specialists. Fixes in one domain may introduce issues in another, and the diff changes each round. |
| Holistic view per round | Each round looks at the complete PR diff including all prior changes, not just the delta from the last round. |

## Workflow

```
Get PR number
  -> Diff analysis & agent selection
  -> Parallel specialist reviews
  -> Merge & deduplicate findings
  -> Triage (fix vs skip)
  -> Implement fixes
  -> Build verification
  -> Commit & push
  -> Loop (re-analyze, re-pick specialists) or stop at cap
```

## Step Details

### 1. Get PR Number

Unchanged from current skill. Use `$ARGUMENTS` if provided, otherwise auto-detect via `gh pr view --json number --jq '.number'`.

### 2. Diff Analysis & Agent Selection

The calling LLM reads the PR metadata and diff:

```bash
gh pr view <number>
gh pr diff <number>
```

Then analyzes what's changing — which files, what domains are touched (API routes, database queries, UI components, auth logic, config changes, etc.) — and decides which specialist agents to spawn.

**Guidance for specialist selection:**
- Each specialist should cover a coherent domain where deep focused attention adds value (e.g., "security", "performance", "input validation", "error handling", "accessibility")
- Avoid overlapping mandates — each specialist should cover something the others won't
- Scale the number of agents to the PR's complexity — a one-file typo fix doesn't need 5 specialists
- The LLM writes a focused prompt for each specialist including:
  - The specific domain/lens to review through
  - Concrete examples of what to look for in that domain
  - The PR number so the agent can fetch the diff
  - Instructions to categorize findings as "actionable" vs "informational"
  - For round 2+, a summary of what was fixed in prior rounds

### 3. Parallel Specialist Reviews

Each specialist is spawned as an **opus** agent in parallel. Each agent:

1. Fetches the PR diff via `gh pr diff <number>`
2. Reviews exclusively through its assigned lens
3. Returns findings in a consistent format:

```
## [Domain] Review

### Actionable
- **[file:line]** — Description of the issue and why it matters

### Informational
- **[file:line]** — Observation (no fix needed)
```

**Specialist constraints:**
- Stay in your lane — don't flag issues outside your domain
- Be specific — cite file paths, line numbers, and what's wrong
- Actionable means "fix before merge"; informational means "be aware"
- Don't suggest refactors or style changes unless genuinely in-domain (e.g., a performance agent flagging an O(n^2) loop is in-domain; suggesting a variable rename is not)

### 4. Merge, Deduplicate & Triage

After all specialists return, the calling LLM:

1. **Merges** all findings into a single list
2. **Deduplicates** — if two agents flag the same location for related reasons, combine into one finding with the stronger rationale
3. **Triages** into Fix (genuine issues) vs Skip (informational, style, not worth churn)

If nothing is actionable, the PR is clean — stop.

### 5. Implement Fixes

Unchanged from current skill. Group fixes by independence, spawn parallel agents for independent changes, sequential for dependent ones. Each fix agent gets exact file paths, line numbers, what to change and why, and the build command.

### 6. Build Verification

Unchanged from current skill. Detect build system, run appropriate command, fix errors before proceeding.

### 7. Commit and Push

Unchanged from current skill. Stage changed files, concise commit message, push to PR branch.

### 8. Loop or Stop

Unchanged iteration cap of **5**. Each new round starts fresh: re-read the full diff, re-analyze, re-pick specialists, spawn new agents. The diff is viewed holistically — the full PR diff including all prior changes, not just the delta.

## What Changes vs Current Skill

| Aspect | Current | New |
|--------|---------|-----|
| Review step | Single opus agent reviewing everything | LLM picks specialist domains, spawns parallel focused agents |
| Agent selection | N/A — always one agent | Dynamic — LLM decides based on diff content |
| Finding format | Single agent output | Merged, deduplicated output from multiple specialists |
| Triage | On single agent's findings | On merged multi-agent findings |
| Per-round behavior | Fresh single agent | Fresh diff analysis, fresh specialist selection, fresh agents |
| Fix/build/commit/loop | Unchanged | Unchanged |
