package com.local.rider.codexviewer.editor

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationProvider
import com.intellij.util.ui.JBUI
import com.local.rider.codexviewer.tracking.CodexChangeTrackerService
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.util.function.Function
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/** File-level Apply/Cancel controls above editors with pending Codex changes. */
class CodexReviewEditorNotificationProvider : EditorNotificationProvider, DumbAware {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?> = Function {
        val tracker = project.service<CodexChangeTrackerService>()
        val count = tracker.hunks(file).size
        if (count == 0) null else JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(5, 10)
            add(JLabel("Codex 产生 $count 个待审阅代码块"), BorderLayout.WEST)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
                isOpaque = false
                val discardAll = CompactColoredButton("取消全部文件", Color(128, 54, 60), 96)
                val acceptAll = CompactColoredButton("应用全部文件", Color(23, 104, 82), 96)
                val discard = CompactColoredButton("取消此文件", Color(200, 67, 73), 80)
                val accept = CompactColoredButton("应用此文件", Color(42, 142, 86), 80)
                fun disableAll() {
                    discardAll.isEnabled = false
                    acceptAll.isEnabled = false
                    discard.isEnabled = false
                    accept.isEnabled = false
                }
                discardAll.addActionListener { _ -> disableAll(); tracker.discardAll() }
                acceptAll.addActionListener { _ -> disableAll(); tracker.acceptAll() }
                discard.addActionListener { _ -> discard.isEnabled = false; accept.isEnabled = false; tracker.discard(file) }
                accept.addActionListener { _ -> discard.isEnabled = false; accept.isEnabled = false; tracker.accept(file) }
                add(discardAll)
                add(acceptAll)
                add(discard)
                add(accept)
            }, BorderLayout.EAST)
        }
    }
}
