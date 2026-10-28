# AI agents

Coding agents write better sqlx4k code when they know its conventions: the Gradle plugin, `@Table` entities,
`@Repository` interfaces, and the rules the build enforces on every `@Query`. sqlx4k gives them two ways to learn them.

## Agent skill

sqlx4k ships an [Agent Skill](https://agentskills.io/): a short guide to using the library, with reference files the
agent reads only when a task needs them. It uses the open `SKILL.md` format, which Claude Code, Codex, Cursor, GitHub
Copilot, Gemini CLI and many other agents support.

Install it with the [skills](https://github.com/vercel-labs/skills) command-line tool, which detects your agents and
asks which ones to install it for:

```shell
npx skills add smyrgeorge/sqlx4k
```

Add `-g` to install it for all your projects instead of the current one.

Or copy the [skills/sqlx4k](https://github.com/smyrgeorge/sqlx4k/tree/main/skills/sqlx4k) folder of the repository
into your agent's skills directory, e.g. `.claude/skills/` (Claude Code); see your agent's documentation for its
directory.

The agent then uses the skill on its own when it works on code that uses sqlx4k. The skill is updated with every
release: reinstall it when you upgrade sqlx4k.

## llms.txt

For agents that read documentation from the web, the site publishes:

- [llms.txt](https://smyrgeorge.github.io/sqlx4k/llms.txt): an index of the documentation pages, following the
  [llms.txt](https://llmstxt.org/) convention;
- [llms-full.txt](https://smyrgeorge.github.io/sqlx4k/llms-full.txt): the whole documentation in a single Markdown
  file;
- every page as Markdown, at its address followed by `index.md` (e.g.
  [guide/errors/index.md](https://smyrgeorge.github.io/sqlx4k/guide/errors/index.md)).
