---
name: interrogation
description: Use when the user invokes `/interrogation "<prompt>"`. Ask focused clarifying questions via AskUserQuestion until you have enough certainty to act on the prompt without guessing. Stop early — three rounds at most.
---

# Interrogation

The user gave you a prompt that's likely under-specified. Drill in with structured questions before doing the work, so the output lands on the first try instead of needing a redo.

## Workflow

1. **Read the prompt carefully.** Identify the 2–4 ambiguities that, if guessed wrong, would lead to materially different output. These are the only things worth asking about.
2. **Ask via `AskUserQuestion`.** Batch related questions into one call — never one question per turn. Use multiple-choice when the answer space is bounded; use a free-form option for open ends.
3. **Outline your understanding after every answer.** Don't silently decide "I have enough." Write a short, high-level outline of what you now think the user wants — scope, output shape, key choices — so the user can see your mental model and correct it before more time is invested. Bullet points are fine. Keep it to ~5 lines.
4. **Iterate at most twice more.** If the outline reveals a remaining ambiguity, ask another batched round. If the user confirms the outline (or says "go" / "ship it"), stop. Three rounds maximum.
5. **Final confirm and proceed.** Once the outline matches, restate the agreed plan in one paragraph and begin the work.

## What to ask about

- **Scope** — what's in, what's out
- **Output shape** — file? PR? branch? message? what does "done" look like?
- **Hard constraints** — must use X, can't touch Y, deadline, audience
- **Judgement calls the user has a view on** — speed vs. polish, convention vs. novelty

## What NOT to ask

- Anything answerable from the codebase, recent context, or `CLAUDE.md`
- Stylistic micro-decisions — pick the codebase convention silently
- Things the user already answered earlier in the session
- Yes/no when a multiple-choice would be more decisive

## Stop conditions

- The user confirms your outline (mental models match)
- The user says "go", "ship it", "you have enough", or equivalent
- Three rounds in, further questions would scope-creep rather than clarify
