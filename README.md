# AI Skills

Matt McKenna's personal skill bundles, packaged natively for **Claude Code**, **Codex CLI**, and **OpenCode**.

## Structure

```
.
├── .claude-plugin/
│   └── marketplace.json   # Claude Code marketplace manifest
├── .agents/
│   └── plugins/
│       └── marketplace.json   # Codex CLI marketplace manifest
├── .opencode/
│   ├── opencode.json
│   └── skills/            # symlinks → plugins/*/skills/*
├── plugins/
│   ├── android/           # Android/Compose development
│   │   ├── .claude-plugin/plugin.json
│   │   ├── .codex-plugin/plugin.json   # symlink
│   │   └── skills/
│   ├── review/            # Pull request review workflows
│   │   ├── .claude-plugin/plugin.json
│   │   ├── .codex-plugin/plugin.json   # symlink
│   │   └── skills/
│   └── utilities/         # Utility skills
│       ├── .claude-plugin/plugin.json
│       ├── .codex-plugin/plugin.json   # symlink
│       └── skills/
└── README.md
```

The skill content lives once under `plugins/<plugin>/skills/<skill>/SKILL.md`. Codex's `.codex-plugin/plugin.json` symlinks to the Claude Code manifest (the schemas overlap), and OpenCode's `.opencode/skills/*` are symlinks into `plugins/*/skills/*`.

## Plugins

| Plugin | Skills |
|--------|--------|
| `android@himattm` | `android-cli`, `edge-to-edge`, `new-android-app`, `verify-android-ui` |
| `review@himattm` | `address-gemini-review`, `review-cycle`, `validate-merge-prs` |
| `utilities@himattm` | `code-as-image` |

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
for s in plugins/*/skills/*/; do
  ln -sfn "$(realpath "$s")" "$HOME/.claude/skills/$(basename "$s")"
done
```

The symlinks and the plugin install can coexist on a development machine. On consumer machines, prefer the plugin install alone — it's the only path with auto-update.

### Codex CLI

```
codex marketplace add himattm/skills
```

Then run `codex`, type `/plugins`, and install `android`, `review`, `utilities` from the browser. Codex resolves the marketplace manifest at `.agents/plugins/marketplace.json` and reads each plugin's `.codex-plugin/plugin.json` (symlinked to the Claude Code manifest — the schemas overlap on the fields we use).

### OpenCode

OpenCode auto-discovers `SKILL.md` from `~/.config/opencode/skills/`, so installation is a one-time clone + symlink:

```bash
git clone https://github.com/himattm/skills ~/.local/share/himattm-skills
mkdir -p ~/.config/opencode/skills
for s in ~/.local/share/himattm-skills/.opencode/skills/*/; do
  ln -sfn "$(realpath "$s")" "$HOME/.config/opencode/skills/$(basename "$s")"
done
```

To pick up new skills later, `git pull` in `~/.local/share/himattm-skills` and re-run the loop. Inside the cloned repo itself (when used as a project), OpenCode reads `.opencode/skills/` directly — no extra setup.

## Publishing changes

To push an update that auto-installs on every Claude Code consumer machine at next startup:

1. Edit the relevant skill (or add a new one) under `plugins/<plugin>/skills/`.
2. Bump the `version` field in that plugin's `.claude-plugin/plugin.json` (e.g. `0.1.0` → `0.1.1`). Use semver: patch for fixes, minor for new skills, major for breaking changes. The Codex `.codex-plugin/plugin.json` is a symlink, so it follows automatically.
3. Commit and push to `main`.

Claude Code refreshes the marketplace manifest at startup; consumers see the version bump and pull the new content automatically. No `/plugin update` invocation needed. Codex and OpenCode users update by re-running their respective install commands (or `git pull` for OpenCode).

To add a brand-new plugin:

1. Create `plugins/<new-plugin>/.claude-plugin/plugin.json` with `name`, `version: "0.1.0"`, and `description`.
2. Symlink the Codex manifest: `ln -s ../.claude-plugin/plugin.json plugins/<new-plugin>/.codex-plugin/plugin.json`.
3. Add `plugins/<new-plugin>/skills/<skill-name>/SKILL.md` for each skill.
4. Symlink each skill into `.opencode/skills/`: `ln -s ../../plugins/<new-plugin>/skills/<skill-name> .opencode/skills/<skill-name>`.
5. Add an entry for the plugin in `.claude-plugin/marketplace.json` and `.agents/plugins/marketplace.json` under `plugins[]`.
6. Push. Existing Claude Code consumers can install with `/plugin install <new-plugin>@himattm`; Codex consumers run `/plugins` again to see it; OpenCode consumers re-run the symlink loop.
