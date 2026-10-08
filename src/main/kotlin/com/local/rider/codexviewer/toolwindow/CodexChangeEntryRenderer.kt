package com.local.rider.codexviewer.toolwindow

import com.intellij.ui.SimpleListCellRenderer
import com.local.rider.codexviewer.tracking.CodexChangeTrackerService

class CodexChangeEntryRenderer : SimpleListCellRenderer<CodexChangeTrackerService.ReviewEntry>() {
    override fun customize(
        list: javax.swing.JList<out CodexChangeTrackerService.ReviewEntry>,
        value: CodexChangeTrackerService.ReviewEntry?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        text = value?.file?.presentableUrl ?: ""
    }
}
