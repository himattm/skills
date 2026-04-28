---
layout: page
title: Install
icon: fas fa-download
order: 0
permalink: /
---

The `himattm` Claude Code plugin marketplace — Matt Mckenna's personal skill bundles for Android/Compose development, pull request review workflows, and social media writing.

## Plugins

- **[android](./categories/android/)** — Android/Compose development: project scaffolding, edge-to-edge insets, the `android` CLI, and screenshot/UI verification.
- **[review](./categories/review/)** — Pull request review workflows: addressing Gemini feedback, iterative review cycles, and batch PR validation.
- **[social-media](./categories/social-media/)** — Social media writing skills: identify and remove common AI writing tells from prose before posting.

## Install

In Claude Code:

```bash
/plugin marketplace add https://github.com/himattm/skills
/plugin install android@himattm
/plugin install review@himattm
/plugin install social-media@himattm
```

The marketplace manifest is refreshed at startup, so version bumps to either plugin auto-deploy on the next Claude Code launch — no manual `/plugin update` needed.

## Source

Repo: <https://github.com/himattm/skills>
