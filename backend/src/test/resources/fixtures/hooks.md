# Automate actions with hooks

Hooks are shell commands that run automatically at points in Claude Code's lifecycle.

## How hooks work

A hook fires on an event such as `PreToolUse` or `PostToolUse`. The command receives JSON on stdin and can block the tool call by exiting with code 2.

## Configure hooks

Add hooks to `.claude/settings.json` under the `hooks` key, with a matcher such as `Edit|Write`.
