---
layout: page
title: Install
icon: fas fa-download
order: 0
permalink: /
---

The `himattm` Claude Code plugin marketplace — Matt Mckenna's personal skill bundles for Android/Compose development, pull request review workflows, and handy utilities.

## Plugins

- **[android](./categories/android/)** — Android/Compose development: project scaffolding, the `android` CLI, and screenshot/UI verification.
- **[review](./categories/review/)** — Pull request review workflows: addressing Gemini feedback, iterative review cycles, and batch PR validation.
- **[utilities](./categories/utilities/)** — Utility skills: render code snippets as shareable images (code-as-image).

## Install

In Claude Code:

```bash
/plugin marketplace add https://github.com/himattm/skills
/plugin install android@himattm
/plugin install review@himattm
/plugin install utilities@himattm
```

The marketplace manifest is refreshed at startup, so version bumps to either plugin auto-deploy on the next Claude Code launch — no manual `/plugin update` needed.

## Source

Repo: <https://github.com/himattm/skills>
