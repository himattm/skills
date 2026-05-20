---
name: android-snapshot-diff
description: Use to prove (or disprove) that a state actually changed between two points — "did the dialog dismiss?", "did this navigation actually leak memory?", "did my fix do anything?". Capture a snapshot bundle (layout JSON, dumpsys meminfo, getprop, screenshot) at point A, perform the action, capture at point B, diff. The empirical answer to "did anything change?" — useful when the agent suspects its own fix is a no-op.
---

# Android Snapshot Diff — Before / After State Capture

## Why diff snapshots

Most "this isn't working" symptoms boil down to one question: **did anything actually change?** The agent's edit might not have hit the right code path. The fix might be a no-op. The dialog might be invisible but still in the hierarchy. The leak might be 2 KB or 200 KB.

A diff between two snapshots gives a categorical answer where staring at the screen does not.

## When to use

- "Did the dialog dismiss?" — layout-A vs layout-B
- "Is this leaking?" — meminfo-A vs meminfo-B after navigating away/back N times; same delta each cycle = leak
- "Did the fix change anything?" — full snapshot diff; if both snapshots are identical, the fix is a no-op
- "Did rotation preserve state?" — snapshot before rotation, rotate, snapshot after, compare relevant fields
- "Did this property actually flip?" — `getprop` before/after a `setprop`

## When NOT to use

- You already know what you expect to see — use `verify-android-layout` directly with explicit criteria
- The bug is purely visual fidelity (color, font, image) — use `verify-android-screen`
- The bug is a crash — use `android-probe-logging` and `adb logcat`

## Pre-flight: detect what your project supports

```bash
# 1. adb device authorized
adb devices                              # expect "<id>  device"

# 2. The `android` CLI is installed (used by the layout / screen channels)
which android && android --version

# 3. Package name (needed for dumpsys meminfo, am send-trim-memory)
adb shell pm list packages | grep -i <fragment>
```

**No `android` CLI installed?** See the `android-cli` skill for installation; it's a one-time setup. The `dumpsys meminfo` / `getprop` / screenshot channels still work via raw `adb`, but the layout JSON channel requires the CLI's bridge.

**Layout channel needs an Accessibility window.** `android layout` reads via `uiautomator`, which sometimes returns empty when no Activity is in the foreground or the screen is locked. Wake the device (`adb shell input keyevent KEYCODE_WAKEUP`) and ensure your app is in the foreground before capturing.

**Diff size sanity.** The snapshot bundle for a busy screen can be 100+ KB JSON. Diffs over 30 KB should always be delegated to a Sonnet sub-agent rather than read inline; the JSON tree's verbosity makes inline reading expensive. Pass the file path, not the content.

**Same device for A and B.** Memory categories vary across OEMs and device states (recent dexopt, recent reboot). Don't compare a snapshot from a Pixel emulator to one from a physical Galaxy — same device, same emulator instance, ideally same boot.

## What goes in a snapshot bundle

Pick the channels relevant to your question. Don't capture all four every time — diffing noise wastes tokens.

| Channel | Captures | Command | Best for |
|---------|----------|---------|----------|
| **Layout JSON** | On-screen UI tree, text, ids, bounds, state | `android layout --pretty -o /tmp/snap-A-layout.json` | "Did the UI change?" |
| **`dumpsys meminfo`** | App memory usage by category (Native, Dalvik, Graphics, etc.) | `adb shell dumpsys meminfo <pkg> > /tmp/snap-A-mem.txt` | Leaks |
| **`getprop`** | All system properties | `adb shell getprop > /tmp/snap-A-props.txt` | Verifying `setprop` flips landed |
| **Screenshot** | Pixel-exact UI | `android screen capture -o /tmp/snap-A.png` | Visual fidelity comparisons |

## Workflow

### 1. Capture point A — the baseline

Pick the channels you need:

```bash
android layout --pretty -o /tmp/snap-A-layout.json
adb shell dumpsys meminfo com.example.app > /tmp/snap-A-mem.txt
```

Use a consistent label (`A` / `B`, or a descriptive pair like `pre-rotation` / `post-rotation`) so the file pairs are obvious.

### 2. Perform the action

A single, deterministic action — tap, navigation, rotation, fix-deploy. If you do five things between snapshots, you can't attribute the diff to any one of them.

For leak hunting, repeat the action N times (10 is usually enough):

```bash
for i in {1..10}; do
    adb shell input tap 500 1200          # open detail
    sleep 1
    adb shell input keyevent KEYCODE_BACK  # back to list
    sleep 1
done
```

### 3. Capture point B

Same channels, same labels but `B`:

```bash
android layout --pretty -o /tmp/snap-B-layout.json
adb shell dumpsys meminfo com.example.app > /tmp/snap-B-mem.txt
```

### 4. Diff and delegate

For layout, use the JSON tree diff:

```bash
android layout --diff --pretty -o /tmp/snap-AB-layout-diff.json
```

(`--diff` returns only elements that changed since the last `android layout` call — see `verify-android-layout` for the full JSON shape.)

For meminfo and getprop, plain `diff`:

```bash
diff /tmp/snap-A-mem.txt /tmp/snap-B-mem.txt > /tmp/snap-AB-mem-diff.txt
diff /tmp/snap-A-props.txt /tmp/snap-B-props.txt > /tmp/snap-AB-props-diff.txt
```

### 5. Delegate parsing

Diffs are usually short, but meminfo diffs across many channels are wide. Spawn a Sonnet sub-agent with a self-contained prompt:

> Read `/tmp/snap-AB-mem-diff.txt`. Compare TOTAL PSS, Native Heap, Dalvik Heap, and Graphics between A and B. Return each as `A → B (delta)`. Flag any category that grew by more than 1 MB. Under 50 words. `model: "sonnet"`.

> Read `/tmp/snap-AB-layout-diff.json`. Confirm: (a) no element with text="Confirm dialog" remains, (b) the list (`resourceId` containing `recycler_view`) is back in focus. Answer YES/NO + one sentence. `model: "sonnet"`.

For screenshots, see `verify-android-screen` for the delegation pattern.

## Reading meminfo for leaks

The headline number is **TOTAL PSS** at the bottom of the report. For a true leak hunt, run the action 10 times and watch the trend across multiple snapshots — single A→B can be noise.

| Pattern | Likely meaning |
|---------|----------------|
| TOTAL PSS grows ~equally on each cycle | Real leak — typically activity, view, or listener retention |
| TOTAL PSS grows once, then plateaus | Cold cache fill, not a leak |
| Native Heap grows; Dalvik flat | Native (NDK / image decoder) leak, or Bitmap pool growth |
| Dalvik grows; Native flat | JVM-side leak — static refs, escaping `inner class` references |
| Graphics grows | Bitmap retention; check `LeakCanary` if available |

If suspicion remains, escalate: force GC between captures (`adb shell am send-trim-memory <pkg> COMPLETE`) and re-snapshot — leaks survive GC, caches don't.

## Cleanup gate

Snapshots are scratch:

```bash
rm /tmp/snap-*-layout.json /tmp/snap-*-mem.txt /tmp/snap-*-props.txt /tmp/snap-*.png /tmp/snap-AB-*.txt /tmp/snap-AB-*.json
```

No source is touched, so the cleanup gate is light. But: if you used a custom label (e.g. `/tmp/login-pre.json`), grep for the label so you don't leave stale files for the next investigation.

## Common mistakes

| Mistake | Fix |
|---------|-----|
| Capturing all four channels every time | Pick the channels relevant to the question; diffing noise burns tokens |
| Two actions between snapshots | One action per A→B pair, or you can't attribute the diff |
| Single A→B for leak detection | Single delta is noise; do 10 cycles and look at the trend |
| Reading the meminfo diff inline | Delegate to a Sonnet sub-agent with category-specific criteria |
| Skipping `android layout --diff` and re-dumping the full tree each time | `--diff` returns only what changed and is dramatically smaller |
| Comparing snapshots from different devices | Heap categories differ across OEMs; same emulator/device for A and B |
| Not forcing GC before a leak snapshot | Trim memory (`am send-trim-memory <pkg> COMPLETE`) so caches collapse and leaks stand out |
| Leaving `/tmp/snap-*` files behind | Cleanup gate: `rm` the snapshot bundle when done |
