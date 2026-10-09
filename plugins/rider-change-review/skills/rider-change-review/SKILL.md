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
plugin watches only paths explicitly authorized and confirmed by Codex, retains
the first pre-change snapshot, lists changed files, opens Rider's native Diff
viewer, and displays the same change blocks inline in the editor. Added,
deleted, and moved files are first-class review operations.

For `apply_patch` edits, separately installed user `PreToolUse` and `PostToolUse`
Hooks create a v3 batch under `.codex-review/batches/`. PreToolUse records exact
paths, operations, source paths for moves, and byte-for-byte snapshots.
PostToolUse confirms the matching tool-call ID only after the tool completes;
Rider ignores unconfirmed batches. Separate directories and tool-call IDs make
concurrent edits safe. Run `scripts/install-user-hook.ps1` once and use `/hooks`
once to trust the definitions. Later launches load the user-level Hook
automatically; reinstall only when the packaged Hook changes or a different
user/Codex home is used.

## Workflow

1. Before changing files, identify the exact source/configuration files needed
   for the request. Avoid writing generated output, dependency directories,
   IDE metadata, build artifacts, caches, and lock files unless the user
   explicitly asks; the Rider plugin may intentionally ignore those paths.
2. For `apply_patch` edits, rely on the user Hook installed by
   `scripts/install-user-hook.ps1`. It creates one v3 batch with exact
   project-relative paths and before snapshots, then confirms it after success.
   Do not create a second batch or marker when the Hook is active.
3. Prefer `apply_patch` for every reviewable write. Shell generators and other
   write mechanisms do not provide the before/after lifecycle needed for safe,
   automatic Codex-only attribution and therefore are not added manually.
4. Make only the requested edits in the listed project files. Prefer focused,
   coherent patches: smaller logical edits create clearer Rider diffs.
   Preserve existing line endings and avoid unrelated formatting rewrites.
5. Verify the implementation proportionately (tests, build, lint, or targeted
   inspection), but do not use a formatter or generator that would rewrite
   unrelated files without the user's approval.
6. Inspect the final changed-file list and diff. Report the files and their
   purpose in the final response, then tell the user to switch to the same
   project in Rider. Codex Change Viewer will refresh the files, color their tabs
   with the configured pending-review color, and make them available from the
   **Codex Changes** tool window.
7. If no eligible files changed, say so plainly. Do not claim that a Rider
   review is available when the work only changed files outside the opened
   project, binary files, or files over the viewer's size limit.

## Rider viewing guidance

Tell the user to ensure the same project is open in Rider. After Rider refreshes
the filesystem, it automatically opens each captured changed file (the last
one becomes active); review the inline red/green
code blocks. Each block has **应用** and **取消**; the editor header applies or
cancels the whole file and also provides **应用全部文件** / **取消全部文件** for
the current Codex review batch. It also shows the current/total change-block
count with previous/next controls that move the caret and center the selected
block. The pending tab color is configurable under **Settings | Tools | Codex
Change Viewer** and refreshes open tabs immediately. The **Codex Changes** tool window also opens Rider's
built-in Diff, with the first pre-change snapshot on the left and current file
content on the right. Deleted files remain actionable in this tool window.

## Limits and troubleshooting

- This is coordination through shared workspace files, not a live remote-control
  connection to a running Rider instance.
- The automatic Hook runs only after `scripts/install-user-hook.ps1` has
  installed it. Merely enabling the Codex plugin is insufficient on current
  desktop builds. The installer preserves other user Hooks and runs an isolated
  end-to-end self-test. It uses Windows PowerShell, does not require Python, and
  copies a standalone uninstaller beside the installed Hook.
- The v3 batch is trusted coordination data, not operating-system process
  attribution. Do not create or modify `.codex-review/` outside a Codex change
  batch. Rider consumes only PostToolUse-confirmed batches and expires abandoned
  ones after five minutes, preventing failed patches from authorizing a later
  unrelated write.
- The Hook covers `apply_patch` directly, including nested calls from code mode.
  Shell-based or generated file writes are intentionally not attributed.
- Rider only tracks eligible external text-file updates in its current project;
  it excludes binary and oversized content.
- Codex must edit the same directory Rider has opened. If Rider points at a
  different checkout/worktree, no review item will appear.
- If a change does not appear, ask the user to refresh/synchronize the project,
  verify that the same checkout is open in Rider, inspect
  `.codex-review/last-hook-error.txt`, and verify the file is below 10 MiB.
