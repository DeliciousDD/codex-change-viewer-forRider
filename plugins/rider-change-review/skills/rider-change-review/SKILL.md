---
name: rider-change-review
description: Use when modifying a project that is open in JetBrains Rider with Codex Change Viewer, so Codex edits can be reviewed inline or in Rider's native Diff.
---

# Codex Change Viewer

Use this workflow whenever the user asks to make code changes that they want to
inspect in Rider, or asks to prepare, summarize, or troubleshoot Codex edits
for Codex Change Viewer.

## What this integrates

This skill intentionally contains no file-type classification, ignore-rule
logic, settings pages, or ignore menus. The installed Codex Change Viewer Rider
plugin watches only paths explicitly authorized by Codex, retains the first
pre-change snapshot, lists changed files, opens Rider's native Diff viewer,
and displays the same change blocks inline in the editor.

For `apply_patch` edits, the plugin's trusted `PreToolUse` hook extracts the
patched paths and writes a short-lived marker immediately before the patch.
For other edit mechanisms, Codex must write that marker itself before changing
source files. Because normal third-party writes do not have that marker, they
are not added to the Rider review queue.

## Workflow

1. Before changing files, identify the exact source/configuration files needed
   for the request. Avoid writing generated output, dependency directories,
   IDE metadata, build artifacts, caches, and lock files unless the user
   explicitly asks; the Rider plugin may intentionally ignore those paths.
2. For `apply_patch` edits, rely on the trusted bundled hook to write or replace
   `.codex-review.paths` in the session's project root immediately before the
   patch. The hook includes one exact project-relative path per patched file.
   Do not create a second marker for the same patch.
3. For edits made through a shell command, generator, or another mechanism,
   create or replace `.codex-review.paths` in a separate workspace write before
   changing source files. Include a comment header and one exact
   project-relative path per line. Do not list the marker itself or use globs.
4. Make only the requested edits in the listed project files. Prefer focused,
   coherent patches: smaller logical edits create clearer Rider diffs.
   Preserve existing line endings and avoid unrelated formatting rewrites.
5. Verify the implementation proportionately (tests, build, lint, or targeted
   inspection), but do not use a formatter or generator that would rewrite
   unrelated files without the user's approval.
6. Inspect the final changed-file list and diff. Report the files and their
   purpose in the final response, then tell the user to switch to the same
   project in Rider. Codex Change Viewer will refresh the files, color their tabs
   green, and make them available from the **Codex Changes** tool window.
7. If no eligible files changed, say so plainly. Do not claim that a Rider
   review is available when the work only changed files outside the opened
   project, binary files, or files over the viewer's size limit.

## Rider viewing guidance

Tell the user to ensure the same project is open in Rider. After Rider refreshes
the filesystem, it automatically opens each captured changed file (the last
one becomes active); review the inline red/green
code blocks. Each block has **应用** and **取消**; the editor header applies or
cancels the whole file and also provides **应用全部文件** / **取消全部文件** for
the current Codex review batch. The **Codex Changes** tool window also opens Rider's
built-in Diff, with the first pre-change snapshot on the left and current file
content on the right. **不再显示** only removes an entry from that list.

## Limits and troubleshooting

- This is coordination through shared workspace files, not a live remote-control
  connection to a running Rider instance.
- The automatic marker hook runs only after the user has reviewed and trusted
  its current definition. It covers `apply_patch`; use the explicit marker
  workflow for shell-based or generated file changes.
- The marker is a trusted coordination protocol, not operating-system process
  attribution. Do not create or modify `.codex-review.paths` outside a Codex
  change batch.
- Rider only tracks eligible external text-file updates in its current project;
  it excludes binary and oversized content.
- Codex must edit the same directory Rider has opened. If Rider points at a
  different checkout/worktree, no review item will appear.
- If a change does not appear, ask the user to refresh/synchronize the project
  in Rider and verify the path is inside the opened project and below 4 MiB.
