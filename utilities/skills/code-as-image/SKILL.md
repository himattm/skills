---
name: code-as-image
description: Use when the user wants to render a code snippet as a shareable image — for tweets, blog posts, READMEs, slide decks, or anywhere syntax-highlighted code looks better than a copy-paste.
---

Turn a code snippet into a syntax-highlighted image. The default renderer is [ray.so](https://ray.so) — it builds the image from a URL fragment, so the flow is: build the URL, copy it to the clipboard, and optionally screenshot the rendered page to a PNG. [carbon.now.sh](https://carbon.now.sh) works the same way and is a drop-in alternative if the user prefers it.

> macOS-only: this skill uses `pbpaste`, `pbcopy`, and `osascript`. On Linux, swap in `xclip`/`wl-copy` or skip the clipboard steps.

## Inputs

| Parameter | Default | Notes |
|-----------|---------|-------|
| `code` | required | A fenced code block in the user's message, an `@path/to/file` they mention, the clipboard (if they say so), or inline text in their request |
| `theme` | `candy` | See themes below |
| `padding` | `64` | `16`, `32`, `64`, or `128` |
| `darkMode` | `true` | `true` or `false` |
| `background` | `true` | `true` or `false` |
| `language` | auto-detect | See languages below |

If the user provides just the code with no flags, use the defaults. If they want to pick a theme or language but don't know what's available, show a short list from the tables below and let them choose.

## Workflow

### 1. Resolve the code source

- A fenced code block in the user's message → extract the body and language hint
- An `@path/to/file` they mention → read that file
- "Use the clipboard" or similar → `pbpaste`
- Inline code in the request → use it directly

### 2. Detect the language (if not specified)

| Source | Rule |
|--------|------|
| File path | Map by extension (`.py` → `python`, `.ts` → `typescript`, `.kt` → `kotlin`, etc.) |
| Fenced block with language hint | Use the hint |
| Shebang or shell-like start (`gh `, `git `, `npm `, `pnpm `, `bun `, `./gradlew `, `/plugin`) | `bash` |
| Syntax cues | `fun ` + `{` → `kotlin`, `def ` → `python` |
| Otherwise | `auto` |

### 3. Build the URL

```bash
build_ray_so_url() {
  local code="$1" theme="${2:-candy}" padding="${3:-64}" dark="${4:-true}" bg="${5:-true}" lang="${6:-auto}"
  local b64
  b64=$(printf %s "$code" | base64 | tr -d '\n')
  b64="${b64//+/%2B}"; b64="${b64//\//%2F}"; b64="${b64//=/%3D}"
  printf 'https://ray.so/#code=%s&theme=%s&padding=%s&darkMode=%s&background=%s&language=%s\n' \
    "$b64" "$theme" "$padding" "$dark" "$bg" "$lang"
}
```

The `code` fragment is base64 of the literal bytes (newlines included). `+`, `/`, and `=` are percent-encoded so the fragment survives messengers and chat clients that re-encode URLs.

### 4. Print + copy URL to the clipboard

```bash
URL=$(build_ray_so_url "$code" "$theme" "$padding" "$dark" "$bg" "$lang")
printf '%s\n' "$URL"
printf '%s' "$URL" | pbcopy
```

Always print the URL even when copying — useful for logs and for the user to inspect.

### 5. (Optional) Render the PNG

If the user asks for the image (e.g. "with image", "render the png", "as a screenshot"), use the playwright MCP tools (`mcp__plugin_playwright_playwright__*`):

1. `browser_navigate` to the URL
2. `browser_snapshot` once to confirm the code-frame selector for the current ray.so build (look for the rendered code container — historically `[data-element="frame"]` or the wrapper around the syntax-highlighted code)
3. `browser_wait_for` until that element is visible and fonts have settled (a short text-presence check on the rendered code is more reliable than a fixed timeout)
4. `browser_take_screenshot` scoped to that element, saving to `~/Downloads/code-image-<timestamp>.png`
5. Verify the file is non-empty and at least ~10KB — anything smaller suggests a blank or timing-failed render
6. Report the saved absolute path

To copy the PNG to the clipboard on macOS:

```bash
osascript -e "set the clipboard to (read (POSIX file \"$path\") as «class PNGf»)"
```

If the selector has shifted since this skill was written, fall back to a full-page screenshot at a fixed viewport (e.g. `1280x800`) and use the whole image.

## Themes (common)

candy, breeze, crimson, falcon, meadow, midnight, raindrop, sunset, vercel, supabase, bitmap, noir, ice, sand, forest, tailwind

## Languages (common)

auto, bash, shell, javascript, typescript, jsx, tsx, python, ruby, go, rust, kotlin, swift, java, c, cpp, csharp, php, html, css, scss, sql, json, yaml, toml, markdown, dockerfile, plaintext

## Padding

16, 32, 64, 128

## Examples

```bash
# Default theme/padding, language auto-detected from extension
build_ray_so_url "$(cat hello.py)" candy 64 true true python

# Shell snippet, dark vercel theme, no background
build_ray_so_url "$(pbpaste)" vercel 32 true false bash
```

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| Forgetting to strip newlines from `base64` output | macOS `base64` doesn't wrap by default for stdin, but pipe through `tr -d '\n'` to be safe |
| Leaving `+` `/` `=` un-encoded in the fragment | Messengers and chat clients re-encode URLs and can mangle the fragment — always percent-encode |
| Using `echo "$code" | base64` (adds a trailing newline) | Use `printf %s "$code" | base64` |
| Hard-coding a selector for the screenshot | Confirm the selector with `browser_snapshot` on the live page each run; ray.so's DOM can shift between releases |
| Reporting success without checking the PNG | Verify file size > ~10KB before claiming the render worked |
