# validate-merge-prs — Design Spec

## Problem

When using AI agents to create PRs, it's easy to accumulate many open PRs in a single repo and lose track of their status. Manually checking CI, reviews, conflicts, and merge ordering for each one is tedious and error-prone. Merging in the wrong order can break the build or create unnecessary rebase churn.

## Solution

A skill that orchestrates the entire PR-merge lifecycle in one invocation:

1. Discover all open PRs authored by the user
2. Build a dependency graph between them
3. Validate each PR in parallel (CI, reviews, code quality)
4. Compute a safe merge order
5. Present a report and merge plan for approval
6. Execute merges sequentially on approval

## Scope

- Single repo (the current working directory's repo)
- PRs authored by the current user (`--author @me`)
- Uses existing `review-cycle` skill for per-PR validation

---

## Phase 1: Discovery & Dependency Analysis

### PR Discovery

Run `gh pr list --author @me --state open --json number,title,headRefName,baseRefName,url,reviewDecision,statusCheckRollup,mergeable,body` to fetch all open PRs.

Exit early with a message if no PRs are found.

### Dependency Graph Construction

Three signal sources, combined into a directed "must merge before" graph:

1. **Branch topology (primary signal):** If PR-A's `headRefName` equals PR-B's `baseRefName`, then A must merge before B (stacked branch).

2. **File overlap analysis:** For each PR pair, compare `gh pr diff <number> --name-only` outputs. Overlapping files indicate potential conflict — used as a tie-breaking signal for merge ordering, not a hard dependency.

3. **PR metadata signals:** Scan PR body and title for patterns like `depends on #X`, `after #X`, `blocks #X`, `requires #X`. These create hard dependency edges.

### Cycle Detection

If the dependency graph contains a cycle, flag the involved PRs as errored and exclude them from the merge plan. Report the cycle to the user.

### Output

Internal data structure: list of PRs with current CI status, review status, mergeable state, dependency edges, and file overlap matrix.

---

## Phase 2: Parallel Validation

### Sub-Agent Dispatch

For each open PR, dispatch a sub-agent that:

1. Checks out the PR's branch
2. Runs `review-cycle` against it (reads review comments, runs build/tests, fixes issues, pushes, iterates — capped at 3 iterations by `review-cycle`)
3. Reports back: pass/fail, what was fixed, what remains broken, current CI status, review approval state

### Parallelism Rules

- PRs with no dependency relationship validate concurrently
- PRs in a dependency chain still validate in parallel — validation checks each PR independently on its own branch and doesn't require merge order
- If a PR has a merge conflict with its base, flag it as blocked but don't attempt manual resolution

### Ready vs. Blocked Classification

**Ready to merge:**
- All required CI status checks passing
- At least one approving review (or reviews not required by repo settings)
- No merge conflicts with base branch
- `review-cycle` completed without unresolved issues

**Blocked:**
- CI failing after `review-cycle` exhausted its iterations
- Missing required review approvals
- Merge conflicts requiring manual resolution
- Depends on another PR that is itself blocked

---

## Phase 3: Merge Plan & Report

### Report Format

```
## PR Queue Status

### Ready to Merge (in order)
1. #42 - Add user auth (base: main) — CI: pass, Reviews: 2 approved
2. #45 - Add auth middleware (base: #42) — CI: pass, Reviews: 1 approved
   ↳ depends on #42, will rebase after #42 merges
3. #38 - Fix pagination bug — CI: pass, Reviews: 1 approved

### Blocked (needs attention)
- #51 - Refactor DB layer — CI failing: test_connection timeout (review-cycle could not fix)
- #47 - Update API docs — missing required review approval

### Merge Order Rationale
- #42 before #45: stacked branch dependency
- #38 after #45: overlapping files in src/api/, reduces conflict risk
- #51, #47: blocked, excluded from merge plan
```

### Merge Order Algorithm

1. **Topological sort** on the dependency graph (hard dependencies determine base ordering)
2. **File overlap tie-break:** among PRs at the same topological level, merge those that share files with later PRs first, so later PRs can rebase cleanly
3. **Final tie-break:** oldest PR first (by creation date)

### Merge Method Detection

Auto-detect the repo's merge convention by inspecting recent merge commits on the default branch:
- If most recent merges are squash merges → use `--squash`
- If most recent merges are merge commits → use `--merge`
- If most recent merges are rebases → use `--rebase`
- If unable to determine → ask the user

---

## Phase 4: Merge Execution

Triggered only after the user approves the merge plan.

### Sequential Merge

For each PR in the computed merge order:

1. **Pre-merge re-check:** verify CI status and review approvals are still current (guards against staleness from validation phase)
2. **Merge:** `gh pr merge <number> --<method> --delete-branch`
3. **Rebase downstream:** if any queued PR has its `baseRefName` pointing at the just-merged branch, update its base to the default branch and rebase
4. **Continue** to the next PR

### Failure Handling

- If a merge fails, **stop the affected dependency chain** immediately
- **Independent PRs can still proceed** — only halt PRs that depend on the failed one
- Report what was merged successfully, what failed, and what was skipped due to the failure

---

## Safety Rails

| Rule | Rationale |
|------|-----------|
| Never force-push | Protects against overwriting others' work |
| Re-check status before each merge | Guards against stale validation results |
| Stop dependency chain on failure | Prevents cascading breakage |
| Allow independent PRs to continue | Maximizes progress despite partial failures |
| Cycle detection excludes cyclic PRs | Prevents infinite loops in merge ordering |
| Delete branch after merge | Clean up, consistent with standard `gh pr merge` behavior |

## Edge Cases

| Scenario | Handling |
|----------|----------|
| No open PRs | Report "no open PRs" and exit |
| All PRs blocked | Present blocked report, no merge plan |
| PR updated during validation | Pre-merge re-check catches staleness |
| Review dismissed between validation and merge | Pre-merge re-check catches this |
| Base branch moves after merging PR N | Downstream PRs rebase automatically |
| Single PR open | Still runs full validation, just simpler report |
| PR has draft status | Include in discovery, flag as blocked (not ready for merge) |

---

## Skill Integration

- **Invokes:** `review-cycle` (via sub-agent prompt, not `@` loaded)
- **Skill location:** `skills/validate-merge-prs/SKILL.md`
- **No supporting reference files needed** — self-contained orchestration skill
- **Tools used:** `gh` CLI, `git` for branch analysis, Agent tool for parallel sub-agents

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Single skill vs. multi-phase | Single invocation | User wants one command, full automation |
| Parallel vs. sequential validation | Parallel sub-agents | PRs are independent during validation |
| Merge order algorithm | Topo sort + overlap tie-break | Handles hard deps and soft conflict signals |
| Merge method | Auto-detect from repo history | Consistent with project conventions |
| Blocker handling | Batch and report at end | User wants one coherent picture, not interruptions |
| Merge gate | Present plan, wait for approval | Autonomous validation but human-approved merging |
| Merge execution | Sequential with rebase between | Ensures each merge builds on clean state |
