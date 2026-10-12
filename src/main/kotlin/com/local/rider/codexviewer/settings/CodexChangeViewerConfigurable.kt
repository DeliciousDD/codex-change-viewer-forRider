package com.local.rider.codexviewer.settings

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.impl.FileEditorManagerImpl
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.components.service
import com.intellij.ui.ColorPanel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.ui.components.JBTextField
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/** Settings page under Settings | Tools | Codex Change Viewer. */
class CodexChangeViewerConfigurable : Configurable {
    private var colorPanel: ColorPanel? = null
    private var ignoredExtensionsField: JBTextField? = null

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
        val ignored = JBTextField(CodexChangeViewerSettings.getInstance().ignoredFileExtensions.joinToString(", ")).apply {
            emptyText.text = "例如：png, jpg, log"
            columns = 32
            ignoredExtensionsField = this
        }
        return FormBuilder.createFormBuilder()
            .addLabeledComponent("待审阅标签页颜色：", colorRow)
            .addLabeledComponent("忽略文件扩展名：", ignored)
            .addComponent(JBUI.Panels.simplePanel().apply {
                add(javax.swing.JLabel("多个扩展名用逗号或空格分隔，可写 png 或 .png"))
            })
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    override fun isModified(): Boolean {
        val selected = colorPanel?.selectedColor ?: CodexChangeViewerSettings.DEFAULT_PENDING_TAB_COLOR
        val ignored = parseIgnoredExtensions()
        val settings = CodexChangeViewerSettings.getInstance()
        return selected.rgb != settings.pendingTabColor.rgb || ignored != settings.ignoredFileExtensions
    }

    override fun apply() {
        val settings = CodexChangeViewerSettings.getInstance()
        val selected = colorPanel?.selectedColor ?: CodexChangeViewerSettings.DEFAULT_PENDING_TAB_COLOR
        val ignored = parseIgnoredExtensions()
        val colorChanged = settings.pendingTabColor.rgb != selected.rgb
        val ignoredChanged = settings.ignoredFileExtensions != ignored
        settings.pendingTabColor = selected
        settings.ignoredFileExtensions = ignored
        if (colorChanged) refreshOpenTabColors()
        if (ignoredChanged) ProjectManager.getInstance().openProjects.forEach { if (!it.isDisposed) it.service<com.local.rider.codexviewer.tracking.CodexChangeTrackerService>().refreshIgnoredFiles() }
    }

    override fun reset() {
        colorPanel?.selectedColor = CodexChangeViewerSettings.getInstance().pendingTabColor
        ignoredExtensionsField?.text = CodexChangeViewerSettings.getInstance().ignoredFileExtensions.joinToString(", ")
    }

    override fun disposeUIResources() {
        colorPanel = null
        ignoredExtensionsField = null
    }

    private fun parseIgnoredExtensions(): Set<String> = ignoredExtensionsField?.text
        .orEmpty().split(',', ';', ' ', '\n', '\t').mapNotNull { token ->
            val value = token.trim().lowercase().removePrefix("*")
            if (value.isEmpty()) null else if (value.startsWith('.')) value else ".${value}"
        }.filter { it.length > 1 && it.substring(1).all { c -> c.isLetterOrDigit() || c in "_-" } }.toSet()

    private fun refreshOpenTabColors() {
        ProjectManager.getInstance().openProjects.forEach { project ->
            if (project.isDisposed) return@forEach
            val manager = FileEditorManager.getInstance(project) as? FileEditorManagerImpl ?: return@forEach
            manager.openFiles.forEach(manager::updateFilePresentation)
        }
    }
}
