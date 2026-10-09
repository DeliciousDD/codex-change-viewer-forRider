package com.local.rider.codexviewer.toolwindow

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import com.local.rider.codexviewer.tracking.CodexChangeTrackerService
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.DefaultListModel
import javax.swing.JLabel
import javax.swing.JPanel

/** A deliberately small UI: select an externally changed file and use Rider's native diff. */
class CodexChangesToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val tracker = project.service<CodexChangeTrackerService>()
        val model = DefaultListModel<CodexChangeTrackerService.ReviewEntry>()
        val list = JBList(model).apply {
            cellRenderer = CodexChangeEntryRenderer()
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent) {
                    if (event.clickCount == 2) selectedValue?.let { showDiff(project, it) }
                }
            })
        }
        val count = JLabel()

        fun refresh() {
            val selectedPath = list.selectedValue?.key
            model.clear()
            tracker.snapshot().forEach(model::addElement)
            list.selectedIndex = (0 until model.size).firstOrNull {
                model.getElementAt(it).key == selectedPath
            } ?: -1
            count.text = "${model.size} 个待审阅 Codex 修改"
        }

        val openDiff = JButton("查看差异").apply {
            addActionListener { list.selectedValue?.let { showDiff(project, it) } }
        }
        val discardSelected = JButton("取消修改").apply {
            addActionListener { _ ->
                list.selectedValue?.let(tracker::discard)
            }
        }
        val acceptSelected = JButton("应用修改").apply {
            addActionListener { _ ->
                list.selectedValue?.let(tracker::accept)
            }
        }
        val clearAll = JButton("清空列表").apply {
            addActionListener { _ -> tracker.clearAll() }
        }
        list.addListSelectionListener {
            val hasSelection = list.selectedValue != null
            openDiff.isEnabled = hasSelection
            discardSelected.isEnabled = hasSelection
            acceptSelected.isEnabled = hasSelection
        }

        val panel = JPanel(BorderLayout(JBUI.scale(6), JBUI.scale(6))).apply {
            border = JBUI.Borders.empty(8)
            add(count, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(list), BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                add(openDiff)
                add(discardSelected)
                add(acceptSelected)
                add(clearAll)
            }, BorderLayout.SOUTH)
        }
        tracker.addListener {
            ApplicationManager.getApplication().invokeLater { if (!project.isDisposed) refresh() }
        }
        refresh()
        toolWindow.contentManager.addContent(
            com.intellij.ui.content.ContentFactory.getInstance().createContent(panel, "", false),
        )
    }

    private fun showDiff(project: Project, entry: CodexChangeTrackerService.ReviewEntry) {
        val factory = DiffContentFactory.getInstance()
        val fileType = entry.file?.fileType ?: PlainTextFileType.INSTANCE
        DiffManager.getInstance().showDiff(
            project,
            SimpleDiffRequest(
                "Codex 修改：${entry.displayPath}",
                factory.create(entry.beforeText, fileType),
                factory.create(project.service<CodexChangeTrackerService>().currentTextFor(entry), fileType),
                "修改前",
                "当前内容",
            ),
        )
    }
}
