---
name: local-workspace
description: Inspect and edit files in the current session workspace.
when-to-use: Use when the user asks to read, search, create, or modify workspace files.
allowed-tools: read, write, edit, glob, grep
version: 1
---

# Local workspace

Work only inside the session workspace. Use `read` with line ranges for large
files, `glob`/`grep` to locate content, and `edit` only when the old text is
unique. Shell and terminal execution is separate and may require approval.

After edits, re-read the relevant range and report what changed. Do not claim
success from a tool call alone; inspect its result.
