# AI Skills

A collection of skills for AI coding agents (Claude Code, Copilot CLI, Gemini CLI, and others).

## Structure

```
skills/
├── skills/          # Skill definitions (markdown files)
├── references/      # Reference docs, tool mappings, platform guides
└── README.md
```

## Usage

Skills are markdown files with frontmatter that AI agents load on demand. Each skill defines a specialized workflow — TDD, debugging, code review, frontend design, etc.

### Claude Code

Install as a skill directory or reference individual skills via `/skill-name`.

### Other Platforms

See `references/` for platform-specific tool mappings and integration guides.
