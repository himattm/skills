# GitHub API Patterns for Gemini Review Comments

## Detect Owner/Repo and PR

```bash
# Owner/repo from current git context
gh repo view --json owner,name --jq '"\(.owner.login)/\(.name)"'

# PR number from current branch (if not provided)
gh pr view --json number --jq '.number'
```

## Fetch Gemini Comments

Two sources: inline diff comments and formal review bodies.

### Inline Diff Comments (primary)

```bash
# Fetch all review comments, filter to gemini-code-assist[bot]
gh api "repos/{owner}/{repo}/pulls/{pr}/comments" \
  --paginate \
  --jq '[.[] | select(.user.login == "gemini-code-assist[bot]")]'
```

**Key fields per comment object:**

| Field | Description |
|-------|-------------|
| `id` | Unique comment ID (used for replies) |
| `body` | Markdown body with severity badges and suggestion blocks |
| `path` | File path relative to repo root |
| `line` | End line in the diff (can be `null`) |
| `start_line` | Start line for multi-line comments (can be `null`) |
| `side` | `RIGHT` (new code) or `LEFT` (old code) |
| `diff_hunk` | Diff context — use for locating code when `line` is null |
| `in_reply_to_id` | If set, this is a reply (skip when triaging) |
| `created_at` | Timestamp |

### Formal Reviews (secondary — summary comments)

```bash
gh api "repos/{owner}/{repo}/pulls/{pr}/reviews" \
  --jq '[.[] | select(.user.login == "gemini-code-assist[bot]")]'
```

These contain the top-level review summary. Usually one per push. The `body` field has the overall assessment. These don't need individual replies.

## Parse Severity

Gemini uses image alt-text badges: `![critical]`, `![high]`, `![medium]`, `![low]`.

```bash
# Extract severity from body text
# Look for: ![severity](https://...) pattern
# The alt text is the severity level
```

Map to triage priority:
- `critical` / `high` — Evaluate seriously, likely FIX
- `medium` — Evaluate on merits
- `low` — Lean toward DISMISS unless trivially correct

## Parse Suggestion Blocks

Gemini embeds fix suggestions in fenced blocks:

````
```suggestion
replacement code here
```
````

A suggestion block replaces the lines indicated by `start_line..line` (or just `line` if `start_line` is null) in the file at `path`.

For multi-line suggestions: the suggestion replaces lines `start_line` through `line` inclusive.

## Reply to Comments

```bash
# Reply in the existing thread (NOT a new top-level comment)
gh api "repos/{owner}/{repo}/pulls/{pr}/comments/{comment_id}/replies" \
  --method POST \
  -f body="Fixed in abc1234. Removed redundant sort call."
```

Reply templates by verdict:

- **FIX**: `"Fixed in {short_sha}. {brief description of what changed}."`
- **DISMISS**: `"Not applicable — {one-sentence technical reason}."`
- **DISCUSS**: `"Flagged for discussion — {why this needs human input}."`

## Edge Cases

- **Null line numbers**: Use `diff_hunk` to locate the code context. Search for the last few lines of the hunk in the file.
- **Stale comments**: If `path` no longer exists or `line` is beyond file length, the comment is on deleted/moved code. DISMISS with reason.
- **Pagination**: Always use `--paginate` — PRs with many comments may span multiple pages.
- **Rate limits**: `gh api` handles rate limiting automatically with backoff. For large batches of replies, add a 1-second delay between calls if you hit 403s.
