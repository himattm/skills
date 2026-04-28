---
title: About
icon: fas fa-info-circle
order: 4
---

The `himattm` Claude Code plugin marketplace — Matt Mckenna's personal skill bundles for Android/Compose development and pull request review workflows.

## Plugins

- **[android](/skills/categories/android/)** — Android/Compose development: project scaffolding, edge-to-edge insets, the `android` CLI, and screenshot/UI verification.
- **[review](/skills/categories/review/)** — Pull request review workflows: addressing Gemini feedback, iterative review cycles, and batch PR validation.

## Install

In Claude Code:

<pre><code>/plugin marketplace add https://github.com/himattm/skills
/plugin install android@himattm
/plugin install review@himattm
</code></pre>

The marketplace manifest is refreshed at startup, so version bumps to either plugin auto-deploy on the next Claude Code launch — no manual `/plugin update` needed.

## Source

Repo: <https://github.com/himattm/skills>
