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
