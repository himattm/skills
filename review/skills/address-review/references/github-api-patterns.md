# GitHub API Patterns for PR Review Comments

## Detect Owner/Repo and PR

```bash
# Owner/repo from current git context
gh repo view --json owner,name --jq '"\(.owner.login)/\(.name)"'

# PR number from current branch (if not provided)
gh pr view --json number --jq '.number'
```

## Fetch Review Comments

Two sources: inline diff comments and formal review bodies.

### Inline Diff Comments (primary)

```bash
# All review comments — humans and bots (default)
gh api "repos/{owner}/{repo}/pulls/{pr}/comments" \
  --paginate

# Filter to a single author (works for humans and bots)
gh api "repos/{owner}/{repo}/pulls/{pr}/comments" \
  --paginate \
  --jq '[.[] | select(.user.login == "alice")]'

# Filter to bot accounts only
gh api "repos/{owner}/{repo}/pulls/{pr}/comments" \
  --paginate \
  --jq '[.[] | select(.user.login | endswith("[bot]"))]'

# Filter to human accounts only
gh api "repos/{owner}/{repo}/pulls/{pr}/comments" \
  --paginate \
  --jq '[.[] | select(.user.login | endswith("[bot]") | not)]'
```

Common review-bot logins you may see:
- `gemini-code-assist[bot]` — Google Gemini Code Assist
- `coderabbitai[bot]` — CodeRabbit
- `copilot-pull-request-reviewer[bot]` — GitHub Copilot PR review
- `sonarcloud[bot]` — SonarCloud
- `deepsource-io[bot]` — DeepSource

**Key fields per comment object:**

| Field | Description |
|-------|-------------|
| `id` | Unique comment ID (used for replies) |
| `user.login` | Reviewer account (human or bot) |
| `body` | Markdown body — may contain severity badges, prose cues, and/or suggestion blocks |
| `path` | File path relative to repo root |
| `line` | End line in the diff (can be `null`) |
| `start_line` | Start line for multi-line comments (can be `null`) |
| `side` | `RIGHT` (new code) or `LEFT` (old code) |
| `diff_hunk` | Diff context — use for locating code when `line` is null |
| `in_reply_to_id` | If set, this is a reply (skip when triaging) |
| `created_at` | Timestamp |

### Formal Reviews (secondary — summary comments)

```bash
# All formal reviews
gh api "repos/{owner}/{repo}/pulls/{pr}/reviews"

# Filter by author as above
gh api "repos/{owner}/{repo}/pulls/{pr}/reviews" \
  --jq '[.[] | select(.user.login == "alice")]'
```

These contain the top-level review summary. Humans submit them on Approve / Request Changes / Comment; bots usually post one per push. The `body` field has the overall assessment. These don't need individual replies unless they raise a specific point.

## Parse Severity / Priority

Conventions differ by reviewer — parse what's there, default to `medium` when nothing is encoded.

| Reviewer | Convention |
|----------|------------|
| Humans | Prose cues at the start of the comment — `nit:`, `question:`, `suggestion:`, `must fix:`, `blocking:`, `🚨`, `❓`. When absent, infer from tone/content. |
| `gemini-code-assist[bot]` | Image alt-text badges: `![critical]`, `![high]`, `![medium]`, `![low]` |
| `coderabbitai[bot]` | Inline emoji + label: `_⚠️ Potential issue_`, `_🛠️ Refactor suggestion_`, `_📝 Nitpick_` |
| `copilot-pull-request-reviewer[bot]` | No explicit severity — treat as `medium` |
| `sonarcloud[bot]` | Severity word in body (`Blocker`, `Critical`, `Major`, `Minor`, `Info`) |
| Others | Parse if present, otherwise `medium` |

Map to triage priority:
- `critical` / `high` / `blocker` / `must fix` / `blocking` / `⚠️ potential issue` — Evaluate seriously, likely FIX
- `medium` / `major` / `refactor` / `suggestion` — Evaluate on merits
- `low` / `minor` / `info` / `nit` / `nitpick` / `question` — Lean toward DISMISS unless trivially correct (questions usually become DISCUSS)

## Parse Suggestion Blocks

GitHub's native ` ```suggestion ` block is rendered as a one-click apply by GitHub and produced by humans and bots alike:

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
- **Mixed authors on one PR**: When defaulting to all reviewers, the triage table should include the `Author` column so the user can see who raised each item.
- **PR author self-comments**: The PR author may leave inline notes on their own PR. These are usually self-reminders or context, not requests for action — still surface them; default verdict often becomes DISMISS or DISCUSS.
