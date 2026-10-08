package com.local.rider.codexviewer.editor

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.impl.EditorTabColorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.local.rider.codexviewer.tracking.CodexChangeTrackerService
import java.awt.Color

/** Marks an editor tab while it still has external Codex changes awaiting a decision. */
class CodexChangeTabColorProvider : EditorTabColorProvider, DumbAware {
    override fun getEditorTabColor(project: Project, file: VirtualFile): Color? =
        if (!project.isDisposed && project.service<CodexChangeTrackerService>().changeFor(file) != null) PENDING_COLOR else null

    private companion object {
        val PENDING_COLOR = Color(42, 142, 86)
    }
}
