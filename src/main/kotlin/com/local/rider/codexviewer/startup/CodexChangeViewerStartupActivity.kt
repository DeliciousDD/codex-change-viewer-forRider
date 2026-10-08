package com.local.rider.codexviewer.startup

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.local.rider.codexviewer.tracking.CodexChangeTrackerService
import com.local.rider.codexviewer.editor.CodexInlineReviewManager

/** Starts external-change observation before the tool window is first opened. */
class CodexChangeViewerStartupActivity : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        project.service<CodexChangeTrackerService>()
        project.service<CodexInlineReviewManager>()
    }
}
