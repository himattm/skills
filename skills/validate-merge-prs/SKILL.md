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
