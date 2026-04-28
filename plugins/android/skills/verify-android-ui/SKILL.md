---
name: verify-android-ui
description: Use when about to verify a UI state on an Android emulator or device — confirming a screen rendered correctly, an image actually loaded, a layout matches expectations, or a fix is observed in the running app. Triggers whenever you'd otherwise read a screenshot file or large `android layout` JSON dump in the main thread.
---

# Verify Android UI

## Overview

Visual verification — screenshots, large layout dumps — burns main-context tokens fast. A single screenshot is a large image payload; across an iteration loop, inline reads pollute the conversation and balloon context. **Delegate to a sub-agent.** It reads the image, returns a short text answer, and the bytes never enter the main thread.

## When to use

- Confirming a screen rendered correctly on the emulator
- Verifying an image actually loaded
- Checking layout matches expectations
- Observing a fix in the running app
- Parsing a large `android layout` JSON dump (more than ~50 lines)

## When NOT to use

- A `dumpsys` line of text or a small log snippet — read it directly
- A short layout dump you already have open from a prior tool call

## Workflow

1. **Capture** the screenshot to a tmp path:
   ```bash
   android screen capture -o /tmp/<descriptive-name>.png
   ```
   Add `--device <serial>` if multiple devices/emulators are connected.

2. **Spawn a sub-agent** (`general-purpose` or `Explore`) with **`model: "sonnet"`** and a self-contained prompt that includes:
   - The exact file path to read
   - Specific, concrete validation criteria — what should be on screen, what shouldn't, where to look
   - The expected return format (e.g., "YES/NO + one sentence", "under 40 words")

3. **Act on the text answer.** Do NOT Read the screenshot yourself.

## Example sub-agent prompt

> Read `/tmp/reader-after-hold.png`. Verify: (a) a single large word is centered in the upper third with a red ORP letter, (b) the bottom inline-context strip is visible, (c) no code block is shown — we expect a paused image break with caption "Pipeline diagram". Answer in under 40 words: did all three pass? If not, which failed and what's actually visible?

## Why Sonnet, not Opus

The task is narrow: read one image, check 2–3 criteria, return a sentence. Sonnet is multimodal and much cheaper than Opus for this. Haiku also works if the criteria are very simple. **Always pass `model: "sonnet"`** when spawning the verification sub-agent — never let it default to Opus.

## Layout dumps

The same rule applies to `android layout` JSON dumps. When the dump is more than ~50 lines, give the sub-agent the file path, concrete criteria, and a short return format. Don't parse it inline.

## Common mistakes

| Mistake | Fix |
|---------|-----|
| Reading the screenshot inline "just to check quickly" | The bytes are massive even for a quick peek. Always delegate. |
| Vague criteria ("does it look right?") | Spell out what should/shouldn't be on screen and where. |
| No return-format cap | Agents return long descriptions by default. Specify "under N words" or "YES/NO + one sentence". |
| Letting the sub-agent default to Opus | Pass `model: "sonnet"` explicitly every time. |
| Forwarding the screenshot back to the main thread | Defeats the purpose. Sub-agent returns text only. |
