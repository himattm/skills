# validate-merge-prs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create a skill that autonomously validates all open PRs in a repo, builds a dependency graph, runs review-cycle on each, and presents a merge plan for user approval before executing merges.

**Architecture:** Single SKILL.md file at `skills/validate-merge-prs/SKILL.md`. The skill is an orchestration workflow — it tells Claude how to use `gh` CLI and sub-agents to discover, validate, and merge PRs. No application code; the deliverable is a well-structured skill document following the patterns established by `review-cycle` and `address-gemini-review`.

**Tech Stack:** Markdown skill file, `gh` CLI, `git`, Claude sub-agents via Agent tool.

---

### Task 1: Create Skill File with Frontmatter and Overview

**Files:**
- Create: `skills/validate-merge-prs/SKILL.md`

- [ ] **Step 1: Create the skill directory**

```bash
mkdir -p skills/validate-merge-prs
```

- [ ] **Step 2: Write the frontmatter and overview**

Create `skills/validate-merge-prs/SKILL.md` with this content:

```markdown
---
name: validate-merge-prs
description: Use when you have multiple open PRs and want to validate them all (CI, reviews, code quality) and plan a safe merge order. Also use when you need to batch-process agent-created PRs for merging.
---

Orchestrates the full PR-merge lifecycle for all open PRs in the current repo. Discovers PRs, builds a dependency graph, validates each in parallel via review-cycle, computes a safe merge order, and presents a merge plan for approval before executing.

## Workflow

```dot
digraph validate_merge {
    rankdir=TB;
    discover [label="Discover open PRs\n(gh pr list)" shape=box];
    graph [label="Build dependency graph\n(branches + files + metadata)" shape=box];
    validate [label="Parallel validation\n(sub-agent per PR\nrunning review-cycle)" shape=box];
    classify [label="Classify: ready vs blocked" shape=diamond];
    order [label="Compute merge order\n(topo sort + overlap tie-break)" shape=box];
    report [label="Present report\n+ merge plan" shape=box];
    approve [label="User approves?" shape=diamond];
    merge [label="Sequential merge\n(rebase between steps)" shape=box];
    done [label="Done — report results" shape=doublecircle];
    blocked [label="Report blockers only" shape=doublecircle];

    discover -> graph;
    graph -> validate;
    validate -> classify;
    classify -> order [label="some ready"];
    classify -> blocked [label="all blocked"];
    order -> report;
    report -> approve;
    approve -> merge [label="yes"];
    approve -> done [label="no / adjust"];
    merge -> done;
}
```
```

- [ ] **Step 3: Verify the file exists and frontmatter is valid**

```bash
head -5 skills/validate-merge-prs/SKILL.md
```

Expected output should show the YAML frontmatter with `name: validate-merge-prs` and a description starting with "Use when".

- [ ] **Step 4: Commit**

```bash
git add skills/validate-merge-prs/SKILL.md
git commit -m "feat: scaffold validate-merge-prs skill with frontmatter and workflow diagram"
```

---

### Task 2: Write Phase 1 — Discovery & Dependency Analysis

**Files:**
- Modify: `skills/validate-merge-prs/SKILL.md`

- [ ] **Step 1: Append the Discovery section**

Add the following after the workflow diagram in `skills/validate-merge-prs/SKILL.md`:

```markdown
## Phase 1: Discovery & Dependency Analysis

### 1. Discover Open PRs

```bash
gh pr list --author @me --state open --json number,title,headRefName,baseRefName,url,reviewDecision,statusCheckRollup,mergeable,body,isDraft,createdAt
```

If zero PRs are found, report "No open PRs found" and stop.

Print a summary table of discovered PRs before continuing:

```
| # | Title | Branch | Base | CI | Reviews | Draft |
```

### 2. Build Dependency Graph

Construct a directed "must merge before" graph from three signal sources:

**Branch topology (hard dependency):** If PR-A's `headRefName` equals PR-B's `baseRefName`, A must merge before B.

**PR metadata (hard dependency):** Scan each PR's body and title for patterns: `depends on #N`, `after #N`, `blocks #N`, `requires #N`. These create directed edges.

**File overlap (soft signal):** For each PR pair, compare changed files:

```bash
gh pr diff <number> --name-only
```

Compute pairwise file intersections. Overlapping files don't create hard dependencies — they inform merge ordering as a tie-breaker.

### 3. Cycle Detection

Check the dependency graph for cycles. If found:
- Flag all PRs in the cycle as errored
- Exclude them from the merge plan
- Report the cycle: "Dependency cycle detected: #A → #B → #C → #A"
```

- [ ] **Step 2: Verify the section renders correctly**

```bash
grep -c "^###" skills/validate-merge-prs/SKILL.md
```

Expected: at least 3 subsection headers (Discover, Build Dependency Graph, Cycle Detection).

- [ ] **Step 3: Commit**

```bash
git add skills/validate-merge-prs/SKILL.md
git commit -m "feat: add Phase 1 discovery and dependency analysis to validate-merge-prs"
```

---

### Task 3: Write Phase 2 — Parallel Validation

**Files:**
- Modify: `skills/validate-merge-prs/SKILL.md`

- [ ] **Step 1: Append the Parallel Validation section**

Add after Phase 1:

```markdown
## Phase 2: Parallel Validation

Dispatch one sub-agent per PR to validate it. All PRs validate concurrently — validation happens on each PR's own branch and doesn't require merge order.

### 4. Dispatch Validation Agents

For each PR, spawn a sub-agent (via Agent tool) with this prompt template:

```
You are validating PR #<number> (<title>) on branch <headRefName>.

1. Check out the branch: git checkout <headRefName>
2. Run /review-cycle <number>
3. After review-cycle completes, check current status:
   - gh pr view <number> --json statusCheckRollup,reviewDecision,mergeable
4. Report back with:
   - PASS or FAIL
   - What review-cycle fixed (if anything)
   - What remains broken (if anything)
   - Current CI status (passing/failing/pending)
   - Current review status (approved/changes_requested/review_required/none)
   - Mergeable state (mergeable/conflicting/unknown)
```

**Parallelism:** Launch all validation agents simultaneously using multiple Agent tool calls in a single message. Use `run_in_background: true` for each agent.

**Wait for all agents to complete** before proceeding to Phase 3.

### 5. Classify Results

After all validation agents report back, classify each PR:

**Ready to merge:**
- CI passing (all required status checks green)
- At least one approving review (or reviews not required per repo settings)
- No merge conflicts with base branch
- review-cycle completed without unresolved issues

**Blocked (with reason):**
- CI failing after review-cycle exhausted its iterations → `"CI failing: <failure summary>"`
- Missing required review approvals → `"Missing required review approval"`
- Merge conflicts → `"Merge conflict with base branch"`
- Draft PR → `"PR is in draft status"`
- Depends on a blocked PR → `"Blocked by #<number> which is also blocked"`
```

- [ ] **Step 2: Verify sub-agent prompt template is complete**

```bash
grep -c "review-cycle" skills/validate-merge-prs/SKILL.md
```

Expected: at least 4 occurrences (overview, workflow, prompt template, classification).

- [ ] **Step 3: Commit**

```bash
git add skills/validate-merge-prs/SKILL.md
git commit -m "feat: add Phase 2 parallel validation to validate-merge-prs"
```

---

### Task 4: Write Phase 3 — Merge Plan & Report

**Files:**
- Modify: `skills/validate-merge-prs/SKILL.md`

- [ ] **Step 1: Append the Merge Plan section**

Add after Phase 2:

```markdown
## Phase 3: Merge Plan & Report

### 6. Compute Merge Order

For all "ready" PRs, compute the merge order:

1. **Topological sort** on the dependency graph — hard dependencies determine base ordering
2. **File overlap tie-break** — among PRs at the same topological level, merge those that share files with later PRs first (so later PRs can rebase cleanly)
3. **Final tie-break** — oldest PR first (by `createdAt`)

### 7. Detect Merge Method

Auto-detect the repo's merge convention:

```bash
git log --merges --oneline -20 <default-branch>
```

- If most recent merges show "Squash" pattern → use `--squash`
- If most recent merges show standard merge commits → use `--merge`
- If no merge commits found (linear history) → use `--rebase`
- If unable to determine → ask the user

### 8. Present Report — HARD GATE

Present the full report in this format:

```
## PR Queue Status

### Ready to Merge (in order)
1. #<number> - <title> (base: <baseRefName>) — CI: <status>, Reviews: <count> approved
   ↳ depends on #<dep>, will rebase after merge  [only if applicable]

### Blocked (needs attention)
- #<number> - <title> — <block reason>

### Merge Order Rationale
- #X before #Y: <reason>

### Merge Method
Detected: <squash|merge|rebase> (based on repo history)
```

**ASK THE USER:** "This is the proposed merge plan. Approve to proceed, or tell me what to adjust."

**DO NOT merge anything until the user explicitly approves.** This gate is non-negotiable.
```

- [ ] **Step 2: Verify the hard gate is present**

```bash
grep "non-negotiable" skills/validate-merge-prs/SKILL.md
```

Expected: one match in the merge plan approval gate.

- [ ] **Step 3: Commit**

```bash
git add skills/validate-merge-prs/SKILL.md
git commit -m "feat: add Phase 3 merge plan and report to validate-merge-prs"
```

---

### Task 5: Write Phase 4 — Merge Execution

**Files:**
- Modify: `skills/validate-merge-prs/SKILL.md`

- [ ] **Step 1: Append the Merge Execution section**

Add after Phase 3:

```markdown
## Phase 4: Merge Execution

Triggered only after the user approves the merge plan.

### 9. Sequential Merge

For each PR in the computed merge order:

**a. Pre-merge re-check:**

```bash
gh pr view <number> --json statusCheckRollup,reviewDecision,mergeable
```

Verify CI is still passing, reviews are still approved, and PR is still mergeable. If any check fails, skip this PR and report why — do not merge stale PRs.

**b. Merge:**

```bash
gh pr merge <number> --<method> --delete-branch
```

Where `<method>` is the detected merge method from step 7.

**c. Rebase downstream PRs:**

If any queued PR had its `baseRefName` pointing at the just-merged branch:

```bash
gh pr edit <downstream-number> --base <default-branch>
```

The downstream PR's CI will re-run automatically after the base change.

**d. Continue** to the next PR in the merge order.

### 10. Failure Handling

If a merge fails:
- **Stop the affected dependency chain** — do not merge any PR that depends on the failed one
- **Continue with independent PRs** — PRs with no dependency on the failed one can still merge
- After all possible merges are attempted, present a final status report

### 11. Final Report

After all merges complete (or fail), present:

```
## Merge Results

### Successfully Merged
- #<number> - <title> ✓

### Failed
- #<number> - <title> — <failure reason>

### Skipped (dependency on failed PR)
- #<number> - <title> — blocked by #<failed-number>

### Still Blocked (from Phase 2)
- #<number> - <title> — <original block reason>
```
```

- [ ] **Step 2: Verify all phases are present**

```bash
grep "^## Phase" skills/validate-merge-prs/SKILL.md
```

Expected: 4 lines — Phase 1, Phase 2, Phase 3, Phase 4.

- [ ] **Step 3: Commit**

```bash
git add skills/validate-merge-prs/SKILL.md
git commit -m "feat: add Phase 4 merge execution to validate-merge-prs"
```

---

### Task 6: Write Safety Rails and Common Mistakes

**Files:**
- Modify: `skills/validate-merge-prs/SKILL.md`

- [ ] **Step 1: Append the design decisions and safety sections**

Add after Phase 4:

```markdown
## Key Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Single invocation | One skill does discovery through merge | User wants full automation, not multi-step manual process |
| Parallel validation | Sub-agent per PR via Agent tool | PRs validate independently on their own branches |
| Topo sort + overlap | Dependency-aware merge ordering | Handles stacked branches and minimizes rebase conflicts |
| Merge method auto-detect | Inspect recent git history | Consistent with existing project conventions |
| Batch blocker reporting | One report at the end | User wants a coherent picture, not interruptions |
| Approval gate before merge | Present plan, wait for explicit approval | Autonomous validation but human-approved merging |

## Safety Rails

- **Never force-push.** If a rebase is needed, use `gh pr edit --base` to retarget the PR. Let CI re-run naturally.
- **Re-check before every merge.** CI and review status can change between validation and merge. Always verify immediately before merging.
- **Stop the chain on failure.** If PR #42 fails to merge, do not merge #45 that depends on it. Independent PRs can still proceed.
- **Skip draft PRs.** Flag them as blocked — they are not ready for merge.
- **Detect cycles.** If the dependency graph has a cycle, exclude those PRs and report the cycle rather than entering an infinite loop.

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| Merging without user approval | The hard gate at step 8 is non-negotiable — always wait for explicit approval |
| Merging in wrong order | Always follow the computed topological sort — never skip ahead |
| Force-pushing during rebase | Use `gh pr edit --base` to retarget, never force-push |
| Ignoring draft PRs | Draft PRs must be flagged as blocked, not validated and merged |
| Trusting stale CI results | Always re-check `statusCheckRollup` and `reviewDecision` immediately before merging |
| Continuing a dependency chain after failure | If a parent PR fails, all downstream PRs in that chain must be skipped |
| Merging PRs that are in a cycle | Exclude cyclic PRs and report the cycle to the user |
```

- [ ] **Step 2: Verify tables are well-formed**

```bash
grep -c "^|" skills/validate-merge-prs/SKILL.md
```

Expected: at least 20 table rows across all tables.

- [ ] **Step 3: Commit**

```bash
git add skills/validate-merge-prs/SKILL.md
git commit -m "feat: add design decisions, safety rails, and common mistakes to validate-merge-prs"
```

---

### Task 7: Review Complete Skill Against Spec

**Files:**
- Read: `skills/validate-merge-prs/SKILL.md`
- Read: `docs/superpowers/specs/2026-04-15-validate-merge-prs-design.md`

- [ ] **Step 1: Read the complete skill file end-to-end**

```bash
cat skills/validate-merge-prs/SKILL.md
```

- [ ] **Step 2: Verify spec coverage**

Check each spec requirement against the skill:

| Spec Requirement | Skill Section |
|-----------------|---------------|
| PR discovery via `gh pr list` | Phase 1, step 1 |
| Branch topology detection | Phase 1, step 2 |
| File overlap analysis | Phase 1, step 2 |
| PR metadata dependency signals | Phase 1, step 2 |
| Cycle detection | Phase 1, step 3 |
| Parallel sub-agent validation | Phase 2, step 4 |
| review-cycle integration | Phase 2, step 4 |
| Ready vs blocked classification | Phase 2, step 5 |
| Topological sort merge ordering | Phase 3, step 6 |
| File overlap tie-breaking | Phase 3, step 6 |
| Merge method auto-detection | Phase 3, step 7 |
| Report format with rationale | Phase 3, step 8 |
| User approval gate | Phase 3, step 8 |
| Pre-merge re-check | Phase 4, step 9a |
| Sequential merge execution | Phase 4, step 9b |
| Downstream rebase | Phase 4, step 9c |
| Failure handling (stop chain) | Phase 4, step 10 |
| Independent PRs continue on failure | Phase 4, step 10 |
| Final status report | Phase 4, step 11 |
| Never force-push | Safety Rails |
| Draft PR handling | Safety Rails + Phase 2 |
| Edge cases (no PRs, all blocked, etc.) | Covered across phases |

If any spec requirement is missing, add it to the skill file.

- [ ] **Step 3: Check for placeholder language**

```bash
grep -iE "(TBD|TODO|implement later|fill in|add appropriate|similar to task)" skills/validate-merge-prs/SKILL.md
```

Expected: zero matches.

- [ ] **Step 4: Verify skill consistency**

Check that step numbers are sequential (1-11), all `gh` commands use consistent flag patterns, and all PR fields referenced in later phases were fetched in the discovery step.

- [ ] **Step 5: Commit any fixes**

If any fixes were needed:

```bash
git add skills/validate-merge-prs/SKILL.md
git commit -m "fix: address spec coverage gaps in validate-merge-prs skill"
```

If no fixes needed, skip this step.

---

### Task 8: Test Skill Recognition and Application

**Files:**
- Read: `skills/validate-merge-prs/SKILL.md`

- [ ] **Step 1: Verify skill is discoverable**

Check that the symlink from `~/.claude/skills` to the skills directory is in place:

```bash
ls -la ~/.claude/skills
```

Expected: symlink pointing to `/Users/mmckenna/Dev/skills/skills`.

- [ ] **Step 2: Verify skill file is accessible via the symlink**

```bash
ls ~/.claude/skills/validate-merge-prs/SKILL.md
```

Expected: file exists and is readable.

- [ ] **Step 3: Validate frontmatter**

Check that:
- `name` contains only letters, numbers, and hyphens
- `description` starts with "Use when"
- `description` describes triggering conditions, not workflow
- Total frontmatter is under 1024 characters

```bash
head -4 skills/validate-merge-prs/SKILL.md
```

- [ ] **Step 4: Word count check**

```bash
wc -w skills/validate-merge-prs/SKILL.md
```

Target: under 1500 words (orchestration skills can be longer than typical skills, but should still be focused). If over 1500, look for sections that can be tightened.

- [ ] **Step 5: Final commit**

```bash
git add -A
git commit -m "feat: complete validate-merge-prs skill — ready for use"
```
