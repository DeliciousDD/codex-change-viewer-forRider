#!/usr/bin/env python3
"""Create the Rider review marker before a Codex apply_patch tool call."""

from __future__ import annotations

import json
import os
import re
import sys
from pathlib import Path


PATCH_FILE = re.compile(r"^\*\*\* (?:Add|Update|Delete) File: (.+?)\s*$", re.MULTILINE)
MARKER_NAME = ".codex-review.paths"


def relative_patch_paths(command: str, workspace: Path) -> list[str]:
    paths: list[str] = []
    for raw_path in PATCH_FILE.findall(command):
        candidate = Path(raw_path.strip())
        try:
            relative = candidate.resolve().relative_to(workspace).as_posix() if candidate.is_absolute() else candidate
        except (OSError, ValueError):
            continue
        normalized = Path(relative)
        if normalized.is_absolute() or ".." in normalized.parts:
            continue
        path = normalized.as_posix().removeprefix("./")
        if path and path != MARKER_NAME and path not in paths:
            paths.append(path)
    return paths


def main() -> None:
    try:
        event = json.load(sys.stdin)
        if event.get("tool_name") != "apply_patch":
            return
        command = event.get("tool_input", {}).get("command")
        cwd = event.get("cwd")
        if not isinstance(command, str) or not isinstance(cwd, str):
            return
        workspace = Path(cwd).resolve()
        paths = relative_patch_paths(command, workspace)
        if not paths:
            return
        marker = workspace / MARKER_NAME
        temporary = marker.with_name(f"{MARKER_NAME}.tmp")
        temporary.write_text(
            "# Authorized Codex apply_patch batch for Codex Change Viewer\n" + "\n".join(paths) + "\n",
            encoding="utf-8",
        )
        os.replace(temporary, marker)
    except Exception:
        # Review authorization must not interfere with the user's requested edit.
        return


if __name__ == "__main__":
    main()
