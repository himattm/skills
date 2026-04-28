---
layout: page
title: Install
icon: fas fa-download
order: 0
permalink: /
---

The `himattm` Claude Code plugin marketplace — Matt Mckenna's personal skill bundles for Android/Compose development and pull request review workflows.

## Plugins

- **[android](./categories/android/)** — Android/Compose development: project scaffolding, edge-to-edge insets, the `android` CLI, and screenshot/UI verification.
- **[review](./categories/review/)** — Pull request review workflows: addressing Gemini feedback, iterative review cycles, and batch PR validation.

## Install

In Claude Code:

```bash
/plugin marketplace add https://github.com/himattm/skills
/plugin install android@himattm
/plugin install review@himattm
```

The marketplace manifest is refreshed at startup, so version bumps to either plugin auto-deploy on the next Claude Code launch — no manual `/plugin update` needed.

## Source

Repo: <https://github.com/himattm/skills>
