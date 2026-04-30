---
description: Resume from the most recent /checkpoint in the current repo. Reads .claude/checkpoints/, summarizes where we left off, and continues from the "Next concrete step".
---

Pick up work from the most recent `/checkpoint` written in this repo.

## Procedure

1. **Find checkpoints.** Run `ls -1t .claude/checkpoints/*.md 2>/dev/null | head -3` from the working directory.
   - If no files (directory missing or empty), tell the user: `No checkpoints found in .claude/checkpoints/. Run /checkpoint to create one.` Then stop.
   - If exactly one file, use it without asking.
   - If multiple, default to the newest. Show the 3 newest with their `created` timestamps and titles (read frontmatter), and ask the user to confirm or pick a different one — only if the difference matters (e.g., the second-newest is on a different branch). Otherwise just take the newest silently.

2. **Read the chosen checkpoint** with the Read tool.

3. **Summarize in 2 lines max:** "Resuming `<title>` (checkpointed <relative time, e.g. '2 hours ago'>). Next step: <one-line restatement of Next concrete step>."

4. **Begin the work.** Act on the "Next concrete step" section. Use the "Files touched" list to know what's already in flight. Treat "Blockers / open questions" as things to surface immediately if they re-occur, not as things to solve from scratch.

5. **Do not** re-plan, re-explore the repo, or interrogate the user — the checkpoint is the source of truth. If something in the checkpoint is genuinely ambiguous, ask one focused question and continue.

## Notes

- Per-repo isolation: only look in the current working directory's `.claude/checkpoints/`. Don't scan globally.
- The checkpoint file might be from a different model session entirely — trust it the same way you'd trust a teammate's handoff doc.
- If the checkpoint references files that no longer exist (deleted, branch switched), surface that and ask the user how to proceed before doing work that would fail.
