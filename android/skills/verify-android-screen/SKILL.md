---
name: verify-android-screen
description: Use when verifying an Android UI state requires a real screenshot — WebView content, animations, visual fidelity (color, font, image content, alignment), or finding an element by appearance when you don't know its resource id. For everything else, prefer `verify-android-layout` first — JSON is cheaper and more precise. Triggers whenever you'd otherwise read a PNG from `android screen capture` in the main thread.
---

# Verify Android UI via Screenshot

## When to reach for this skill

Use a screenshot only when the JSON layout tree can't answer the question:

- **WebView content** — web markup doesn't appear in `android layout`
- **Animations or transitions** — `layout` may fail or return partial state mid-frame
- **Visual fidelity** — colors, fonts, image content, spacing, alignment polish
- **Finding by appearance** — locating an element when you don't know its `resourceId` or `text`

**For everything else, use `verify-android-layout` first.** A JSON dump is strictly cheaper than a vision-token screenshot for "did the element appear?" / "is the input focused?" / "did the text update?" style questions.

## Why delegate

A single screenshot is a large image payload. Reading it in the main thread burns tokens fast — across an iteration loop, inline reads pollute the conversation and balloon context. **Always delegate to a sub-agent.** It reads the image, returns a short text answer, and the bytes never enter the main thread.

## Workflow

1. **Capture** to a tmp path:
   ```bash
   android screen capture -o /tmp/<descriptive-name>.png
   ```
   Add `--device <serial>` if multiple devices are connected.

2. **Spawn a sub-agent** (`general-purpose` or `Explore`) with **`model: "sonnet"`** and a self-contained prompt that includes:
   - The exact file path to read
   - Specific, concrete validation criteria — what should be on screen, what shouldn't, where to look
   - The expected return format ("YES/NO + one sentence", "under 40 words")

3. **Act on the text answer.** Do NOT Read the screenshot yourself.

## Annotated screenshots — finding elements by appearance

When you need to interact with an element that doesn't show up in `android layout` (or you can't identify it by `resourceId`):

```bash
android screen capture --annotate -o /tmp/annotated.png
```

This overlays numbered labels and bounding boxes on every UI element. Have the sub-agent identify the label number for the element you want, then resolve to coordinates:

```bash
adb shell input tap $(android screen resolve --screen /tmp/annotated.png --string "tap #34")
```

The chained form lets you tap a numbered annotation in a single command.

## Example sub-agent prompts

> Read `/tmp/reader-after-hold.png`. Verify: (a) a single large word is centered in the upper third with a red ORP letter, (b) the bottom inline-context strip is visible, (c) no code block is shown — we expect a paused image break with caption "Pipeline diagram". Answer in under 40 words: did all three pass? If not, which failed and what's actually visible?

> Read `/tmp/annotated.png`. Find the "Sign in" button — return only its label number (e.g. `#7`). If multiple candidates, pick the most prominent. One token answer.

> Read `/tmp/webview-state.png`. The page should show a logged-in user header with avatar in the top-right and a "Welcome back" greeting. Under 30 words: present or not, and what's actually shown?

## Why Sonnet, not Opus

The task is narrow: read one image, check 2–3 criteria, return a sentence. Sonnet is multimodal and much cheaper than Opus for this. Haiku also works for very simple criteria. **Always pass `model: "sonnet"`** when spawning the verification sub-agent — never let it default to Opus.

## Common mistakes

| Mistake | Fix |
|---------|-----|
| Reaching for a screenshot when JSON would answer | Try `verify-android-layout` first; screenshots are the fallback |
| Reading the screenshot inline "just to check quickly" | The bytes are massive even for a quick peek. Always delegate. |
| Vague criteria ("does it look right?") | Spell out what should/shouldn't be on screen and where |
| No return-format cap | Agents return long descriptions by default. Specify "under N words" or "YES/NO + one sentence" |
| Letting the sub-agent default to Opus | Pass `model: "sonnet"` explicitly every time |
| Forwarding the screenshot back to the main thread | Defeats the purpose. Sub-agent returns text only. |
