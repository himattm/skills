---
description: Save a self-contained handoff doc for the current session before stepping away. Writes to .claude/checkpoints/ in the current repo and prints it in chat. Resume later with /clear then /resume.
---

The user is about to step away. Capture the state of this conversation as a checkpoint file so they (or a future session) can resume without re-exploring everything.

## What to write

A markdown file with this exact shape:

````markdown
---
created: <ISO-8601 UTC timestamp, e.g. 2026-04-29T23:45:12Z>
repo: <repo name from `basename "$(git rev-parse --show-toplevel 2>/dev/null || pwd)"`>
branch: <output of `git branch --show-current 2>/dev/null` — empty string if not a git repo>
slug: <kebab-case slug of the title, ~3-5 words>
---

# <Short title — what we were working on, ~6 words>

## Goal
<1–2 sentences in user-facing terms — what we were trying to accomplish>

## What we tried (chronology)
- <bullet 1: thing attempted, one line>
- <bullet 2>
- <…3–6 bullets total, in rough order>

## Key decisions
- <bullet: choice made and why — skip section if no real decisions>

## Files touched
- `<path>` — <one-line role, optional>
- `<path>`
- <…>

## Blockers / open questions
- <thing stuck on or needing user input — skip section if none>

## Next concrete step
<1–3 sentences: the very next action when we resume. Be specific — name files, commands, what "done" looks like for this step.>

---

## To resume in a fresh window, paste this:

```text
Read the checkpoint at <ABSOLUTE PATH OF THIS FILE> and continue from the "Next concrete step" section. Don't re-plan or re-ask for context — the checkpoint has what you need.
```
````

The triple-backtick `text` block above is part of the checkpoint file you'll write — keep it as a real fenced code block in the output.

## Procedure

1. **Compute the path.** Working directory is the repo root (or wherever the user invoked from). Path = `.claude/checkpoints/<UTC-timestamp>-<slug>.md` where timestamp is `YYYYMMDDTHHMMSSZ` (compact, sortable). `mkdir -p .claude/checkpoints/` first.
2. **Introspect the conversation.** Pull from your conversation memory — what was the user's goal, what tool calls happened, what files were edited, what was decided, what's the immediate next step? Be honest about blockers.
3. **Write the file** with the Write tool. Compute the absolute path (via `pwd` joined with the relative path) and embed it in the resume paste-block at the bottom — the paste-block must be self-contained.
4. **Print to chat:** the absolute file path, then the full body of the file inline, then this exact line: `Saved. Type /clear, then /resume in the fresh session — or paste the block above.`
5. **Stop.** Do not start new work. Do not ask follow-ups.

## Notes

- Do NOT touch `.gitignore`. The user has explicitly opted to manage that themselves.
- If the conversation has too little material to checkpoint meaningfully (e.g., user just opened the session), say so and ask if they still want a checkpoint before writing one.
- The checkpoint is per-repo. Don't try to merge state across multiple working directories.
