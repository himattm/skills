---
name: verify-android-layout
description: Use FIRST when verifying any UI state on an Android emulator or device — checking that elements rendered, text appears, state is correct, or a fix landed. Reads the structured JSON tree from `android layout`, which is faster and cheaper than a screenshot for almost everything except WebViews, animations, and purely visual checks (color, font, image content).
---

# Verify Android UI via Layout JSON

## Why this is the default

`android layout` returns the entire on-screen UI as a structured JSON tree. For most verification — "did the button appear?", "is the input focused?", "did the count increment?", "is the error visible?" — JSON is **strictly better than a screenshot**:

- Cheaper: text tokens, not vision tokens
- Precise: exact resource ids and bounds, no fuzzy reading
- Diffable: `--diff` returns only what changed since the last call
- Greppable: a sub-agent can answer "is element X present" in one pass

**Default to this skill. Reach for `verify-android-screen` only when JSON can't answer the question** (WebView content, animations, visual fidelity, image content).

## When to use

- Confirming a screen rendered (specific text, resource ids, or controls present)
- Verifying input state (`focused`, `checked`, `selected`)
- Checking interactions are available (`clickable`, `scrollable`, etc.)
- Observing a fix in the running app (element appears/disappears, text updates)
- Iterating in a tight loop where you've already seen the screen once
- Driving input — taps, swipes, text entry — using element coordinates

## When NOT to use

- WebView content — won't appear in the layout tree
- Animations in flight — `layout` may fail or return partial state
- Visual-only checks — colors, fonts, image content, alignment polish
- Locating an element by visual appearance when you don't know its id

For those, use `verify-android-screen`.

## The JSON shape

Each element in the layout tree may include:

| Property | Meaning |
|----------|---------|
| `text` | Literal text the element contains |
| `resourceId` | The Android resource id used to refer to the element |
| `contentDesc` | Accessibility description |
| `class` | Android view class (e.g. `android.widget.Button`) |
| `interactions` | What the user can do: `checkable`, `clickable`, `focusable`, `scrollable`, `long-clickable`, `password` |
| `state` | Current state: `checked`, `focused`, `selected` |
| `bounds` | Bounding rectangle as `[minX,minY][maxX,maxY]` |
| `center` | Center point as `[x,y]` |
| `off-screen` | True if in the hierarchy but not currently visible — may need a scroll |

Example:

```json
{
  "key": -248568265,
  "class": "android.widget.Button",
  "text": "Submit",
  "bounds": "[138,9][167,38]",
  "center": "[152,23]",
  "interactions": ["clickable", "focusable"]
}
```

## Workflow

### First look — full layout

```bash
android layout --pretty -o /tmp/layout.json
```

If the file is under ~50 lines, read it inline. Otherwise, **delegate to a sub-agent** (see below).

### Iteration — diff only

After the first call, use `--diff` to get only the elements that changed:

```bash
android layout --diff --pretty -o /tmp/layout-diff.json
```

This is the single biggest context saver. A calculator key press should return a one-element diff, not the whole tree.

### Delegating large dumps

When the dump is >50 lines (most real screens), spawn a sub-agent with `model: "sonnet"` and a self-contained prompt:

- Exact file path to read
- Specific criteria — what should be present, what shouldn't, which `resourceId` or `text` to find
- Expected return format ("YES/NO + one sentence", "under 40 words", or "return the `center` of the element with text='Submit'")

Do NOT read the dump in the main thread.

## Example sub-agent prompts

> Read `/tmp/layout.json`. Find an element with `text="Sign in"`. Return its `center` coordinate as `[x,y]`, or "NOT FOUND" if absent. Under 20 words.

> Read `/tmp/layout-diff.json`. Verify the readout element (`resourceId` containing `display`) now shows `text="42"`. Answer YES/NO + one sentence on what it actually shows.

> Read `/tmp/layout.json`. Confirm: (a) an EditText with `state` containing `focused`, (b) a Button with `text="Submit"` and `interactions` containing `clickable`. Under 40 words: did both pass? If not, what's actually there?

## Driving input from layout coordinates

Once you have a `center` or `bounds`, drive `adb shell input` directly.

**Tap** the center of an element:

```bash
adb shell input tap 152 23
```

**Swipe / scroll** a scrollable element. The 5th argument is duration in ms — keep it generous (500ms+) so the gesture is interpreted as a scroll, not a fling:

```bash
adb shell input swipe 250 400 250 100 500
```

**Type into an input.** Always confirm `state` contains `focused` before typing — if it isn't, tap the element first:

```bash
adb shell input text "hello%sworld"
```

(Use `%s` for spaces in `input text`.)

## Interaction rules

1. **Text inputs must be focused before typing.** Check `state` contains `focused`; if not, `adb shell input tap` the element first, then re-dump and verify focus.
2. **If an element has `scrollable` in its `interactions`,** try scrolling it when looking for an off-screen element. `off-screen: true` on a target is a strong signal you need to scroll its container.
3. **Scroll slowly.** A short-duration swipe is interpreted as a fling and overshoots. Use 500ms+ for predictable scrolling.
4. **Content takes time to load.** If a `layout` call is missing expected information after an action, wait a couple of seconds and call `layout --diff` to see what arrived.

## Recovery — when `layout` fails

`android layout` can fail on WebViews or mid-animation. Two fallbacks:

1. Wait a moment and retry with `--diff` (animation may finish)
2. Switch to `verify-android-screen` with `--annotate` to find elements visually, then resolve to coordinates

## Common mistakes

| Mistake | Fix |
|---------|-----|
| Reaching for a screenshot first | JSON is the default; screenshots are the fallback |
| Reading a 500-line layout dump inline | Always delegate dumps >50 lines to a Sonnet sub-agent |
| Not using `--diff` in iteration loops | The full tree on every step is wasted context — `--diff` gives you only what changed |
| Typing into an unfocused input | Always verify `state` contains `focused` first; tap to focus if not |
| Fast swipes that fling instead of scroll | Use a duration of 500ms+ on `adb shell input swipe` |
| Vague sub-agent criteria ("does it look right?") | Name the `resourceId`, `text`, or `state` to check, and cap the response length |
| Letting the sub-agent default to Opus | Always pass `model: "sonnet"` — the task is narrow text parsing |
