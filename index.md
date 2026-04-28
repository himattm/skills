---
title: Home
nav_order: 1
layout: default
permalink: /
---

# himattm skills

A Claude Code plugin marketplace publishing personal skill bundles for Android/Compose development and pull request review workflows.

## Plugins

- **[android](./plugins/android/)** — Android/Compose development: project scaffolding, edge-to-edge insets, the `android` CLI, and screenshot/UI verification.
- **[review](./plugins/review/)** — Pull request review workflows: addressing Gemini feedback, iterative review cycles, and batch PR validation.

## Install

In Claude Code:

```
/plugin marketplace add https://github.com/himattm/skills
/plugin install android@himattm
/plugin install review@himattm
```

The marketplace manifest is refreshed at startup, so version bumps to either plugin auto-deploy on the next Claude Code launch — no manual `/plugin update` needed.

## Source

Repo: <https://github.com/himattm/skills>
