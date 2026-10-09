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
and displays the same change blocks inline in the editor. Added files use an
empty before-image and are reviewed like modified files.

For `apply_patch` edits, the separately installed user `PreToolUse` Hook extracts
the patched paths and atomically creates an immutable v2 batch under
`.codex-review/batches/` immediately before the patch. Each batch contains a
manifest and byte-for-byte before snapshots for existing files. Separate batch
directories make concurrent Codex edits safe, and the snapshots remove the race
between the Codex write and Rider's external-file refresh. Current Codex desktop
builds do not automatically activate Hooks bundled only in a plugin, so the
repository's `scripts/install-user-hook.ps1` must be run once. Ordinary external
writers do not create these batches and are not added to the Rider review queue.
The installer registers a user-level Hook under the current Codex home, so later
Codex launches load it automatically; reinstall only when the packaged Hook is
updated, removed, or used from a different user/Codex home.

## Workflow

1. Before changing files, identify the exact source/configuration files needed
   for the request. Avoid writing generated output, dependency directories,
   IDE metadata, build artifacts, caches, and lock files unless the user
   explicitly asks; the Rider plugin may intentionally ignore those paths.
2. For `apply_patch` edits, rely on the user Hook installed by
   `scripts/install-user-hook.ps1`. It creates one v2 batch with exact
   project-relative paths and before snapshots. Do not create a second batch or
   legacy marker when the Hook is known to be active.
3. For edits made through a shell command, generator, or another mechanism,
   `apply_patch` is preferred because only it has automatic Codex attribution.
   If such a write is unavoidable, create or replace the legacy
   `.codex-review.paths` file in a separate workspace write before changing the
   files. Include a comment header and one exact project-relative path per line.
   Do not list the marker itself or use globs. This compatibility path does not
   have the v2 protocol's durable before snapshots.
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
the current Codex review batch. It also shows the current/total change-block
count with previous/next controls that move the caret and center the selected
block. The **Codex Changes** tool window also opens Rider's
built-in Diff, with the first pre-change snapshot on the left and current file
content on the right. **不再显示** only removes an entry from that list.

## Limits and troubleshooting

- This is coordination through shared workspace files, not a live remote-control
  connection to a running Rider instance.
- The automatic Hook runs only after `scripts/install-user-hook.ps1` has
  installed it. Merely enabling the Codex plugin is insufficient on current
  desktop builds. The installer preserves other user Hooks and runs an isolated
  end-to-end self-test. It uses Windows PowerShell, does not require Python, and
  copies a standalone uninstaller beside the installed Hook.
- The v2 batch is trusted coordination data, not operating-system process
  attribution. Do not create or modify `.codex-review/` outside a Codex change
  batch. Rider removes fully observed batches and expires abandoned ones after
  five minutes, which also prevents a failed Codex patch from authorizing a
  later unrelated write indefinitely.
- The Hook covers `apply_patch` directly and an `exec` call that wraps
  `tools.apply_patch`; use the explicit legacy marker only for shell-based or
  generated file changes.
- Rider only tracks eligible external text-file updates in its current project;
  it excludes binary and oversized content.
- Codex must edit the same directory Rider has opened. If Rider points at a
  different checkout/worktree, no review item will appear.
- If a change does not appear, ask the user to refresh/synchronize the project,
  verify that the same checkout is open in Rider, inspect
  `.codex-review/last-hook-error.txt`, and verify the file is below 4 MiB.
