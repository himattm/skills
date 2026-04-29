---
description: Queue the plan you just wrote for implementation in a fresh context. Run, then type /clear — a SessionStart hook injects the plan path into the new session.
---

You just finished planning. The plan file lives in `~/.claude/plans/`.

1. Identify the absolute path of the plan you just wrote — check your recent ExitPlanMode / Write tool calls for a path under `~/.claude/plans/`. If you have multiple candidates or you're not sure, list the 3 most recently modified files (`ls -1t ~/.claude/plans/*.md | head -3`) and ask the user which one to queue.

2. Write the absolute path (and ONLY the absolute path — no surrounding quotes, no trailing newline noise, no commentary) to `~/.claude/.implement-on-next-start`. Use the Write tool, not `echo`, so the content is exact.

3. Tell the user, in one short line: `Queued: <path>. Type /clear now — the next session will pick it up and start implementing.` Do not summarize the plan. Do not start implementing. Do not do anything else.
