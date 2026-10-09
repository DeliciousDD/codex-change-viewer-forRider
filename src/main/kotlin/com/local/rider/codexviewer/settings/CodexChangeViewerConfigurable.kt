package com.local.rider.codexviewer.settings

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.impl.FileEditorManagerImpl
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.ColorPanel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/** Settings page under Settings | Tools | Codex Change Viewer. */
class CodexChangeViewerConfigurable : Configurable {
    private var colorPanel: ColorPanel? = null

    override fun getDisplayName(): String = "Codex Change Viewer"

    override fun createComponent(): JComponent {
        val picker = ColorPanel().also {
            it.selectedColor = CodexChangeViewerSettings.getInstance().pendingTabColor
            colorPanel = it
        }
        val reset = JButton("恢复默认").apply {
            addActionListener { picker.selectedColor = CodexChangeViewerSettings.DEFAULT_PENDING_TAB_COLOR }
        }
        val colorRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            add(picker)
            add(reset)
        }
        return FormBuilder.createFormBuilder()
            .addLabeledComponent("待审阅标签页颜色：", colorRow)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    override fun isModified(): Boolean {
        val selected = colorPanel?.selectedColor ?: CodexChangeViewerSettings.DEFAULT_PENDING_TAB_COLOR
        return selected.rgb != CodexChangeViewerSettings.getInstance().pendingTabColor.rgb
    }

    override fun apply() {
        val settings = CodexChangeViewerSettings.getInstance()
        val selected = colorPanel?.selectedColor ?: CodexChangeViewerSettings.DEFAULT_PENDING_TAB_COLOR
        if (settings.pendingTabColor.rgb == selected.rgb) return
        settings.pendingTabColor = selected
        refreshOpenTabColors()
    }

    override fun reset() {
        colorPanel?.selectedColor = CodexChangeViewerSettings.getInstance().pendingTabColor
    }

    override fun disposeUIResources() {
        colorPanel = null
    }

    private fun refreshOpenTabColors() {
        ProjectManager.getInstance().openProjects.forEach { project ->
            if (project.isDisposed) return@forEach
            val manager = FileEditorManager.getInstance(project) as? FileEditorManagerImpl ?: return@forEach
            manager.openFiles.forEach(manager::updateFilePresentation)
        }
    }
}
