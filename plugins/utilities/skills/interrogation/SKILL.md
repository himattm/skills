---
name: interrogation
description: Use when the user invokes `/interrogation "<prompt>"`. Ask focused clarifying questions via AskUserQuestion until you have enough certainty to act on the prompt without guessing. Stop early — three rounds at most.
---

# Interrogation

The user gave you a prompt that's likely under-specified. Drill in with structured questions before doing the work, so the output lands on the first try instead of needing a redo.

## Workflow

1. **Read the prompt carefully.** Identify the 2–4 ambiguities that, if guessed wrong, would lead to materially different output. These are the only things worth asking about.
2. **Ask via `AskUserQuestion`.** Batch related questions into one call — never one question per turn. Use multiple-choice when the answer space is bounded; use a free-form option for open ends.
3. **Iterate at most twice more.** After each answer, ask: "could I act now without surprising the user?" If yes, stop. If a follow-up would only marginally improve quality, stop.
4. **Confirm and proceed.** End with a one-paragraph restatement of what the user wants. Pause for any final correction, then begin the work.

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

- You can act on the prompt without making any guess that could surprise the user
- The user says "go", "ship it", "you have enough", or equivalent
- Three rounds in, further questions would scope-creep rather than clarify
