# AI Skills

The `himattm` Claude Code plugin marketplace — Matt McKenna's personal skill bundles.

## Structure

```
.
├── .claude-plugin/
│   └── marketplace.json   # marketplace manifest (name: "himattm")
├── android/               # Android/Compose development
│   ├── .claude-plugin/plugin.json
│   └── skills/
├── review/                # Pull request review workflows
│   ├── .claude-plugin/plugin.json
│   └── skills/
├── utilities/             # Utility skills + slash commands
│   ├── .claude-plugin/plugin.json
│   ├── commands/          # slash commands (/checkpoint, /resume)
│   └── skills/
└── README.md
```

The Jekyll source for the published site (https://himattm.github.io/skills/) lives on the [`gh-pages-src`](https://github.com/himattm/skills/tree/gh-pages-src) branch. The built site lives on the orphan [`gh-pages`](https://github.com/himattm/skills/tree/gh-pages) branch.

## Plugins

| Plugin | Skills |
|--------|--------|
| `android@himattm` | `android-cli`, `new-android-app`, `verify-android-layout`, `verify-android-screen`, `android-probe-logging`, `android-reproduce-as-test`, `android-strictmode-probe`, `android-snapshot-diff`, `android-regression-diff-scan`, `android-crash-repro-loop`, `android-trace-sections`, `android-runtime-flag-probe`, `android-coroutine-trace`, `android-perfetto-capture`, `android-perfetto-analyze` |
| `review@himattm` | `address-gemini-review`, `review-cycle`, `validate-merge-prs` |
| `utilities@himattm` | `code-as-image`, `interrogation`, `/checkpoint` + `/resume` |

## Usage

### Claude Code

**Install via the marketplace** (auto-updates at startup):

```
/plugin marketplace add https://github.com/himattm/skills
/plugin install android@himattm
/plugin install review@himattm
/plugin install utilities@himattm
```

Claude Code refreshes the marketplace manifest at startup; new skills and version bumps in either plugin's `plugin.json` ship to every machine without manual `/plugin update` calls.

**Local development shortcut** (edits show up immediately, no commit needed):

```bash
mkdir -p ~/.claude/skills
for s in {android,review,utilities}/skills/*/; do
  ln -sfn "$(realpath "$s")" "$HOME/.claude/skills/$(basename "$s")"
done
```

The symlinks and the plugin install can coexist on a development machine. On consumer machines, prefer the plugin install alone — it's the only path with auto-update.

## Publishing changes

To push an update that auto-installs on every consumer machine at next Claude Code startup:

1. Edit the relevant skill (or add a new one) under `<plugin>/skills/`.
2. Bump the `version` field in that plugin's `.claude-plugin/plugin.json` (e.g. `0.1.0` → `0.1.1`). Use semver: patch for fixes, minor for new skills, major for breaking changes.
3. Commit and push to `main`.

Claude Code refreshes the marketplace manifest at startup; consumers see the version bump and pull the new content automatically. No `/plugin update` invocation needed.

To add a brand-new plugin:

1. Create `<new-plugin>/.claude-plugin/plugin.json` with `name`, `version: "0.1.0"`, and `description`.
2. Add `<new-plugin>/skills/<skill-name>/SKILL.md` for each skill.
3. Add an entry for the plugin in `.claude-plugin/marketplace.json` under `plugins[]` with `"source": "./<new-plugin>"`.
4. Push. Existing consumers can install it with `/plugin install <new-plugin>@himattm`.
