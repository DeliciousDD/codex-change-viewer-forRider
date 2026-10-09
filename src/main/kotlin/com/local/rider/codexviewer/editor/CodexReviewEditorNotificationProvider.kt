package com.local.rider.codexviewer.editor

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationProvider
import com.intellij.util.ui.JBUI
import com.local.rider.codexviewer.diff.ChangeHunk
import com.local.rider.codexviewer.tracking.CodexChangeTrackerService
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.util.function.Function
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/** File-level Apply/Cancel controls above editors with pending Codex changes. */
class CodexReviewEditorNotificationProvider : EditorNotificationProvider, DumbAware {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?> = Function { fileEditor ->
        val tracker = project.service<CodexChangeTrackerService>()
        val entry = tracker.changeFor(file)
        val hunks = tracker.hunks(file)
        if (entry == null) null else JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(5, 10)
            val summary = when (entry.operation) {
                CodexChangeTrackerService.ReviewOperation.CREATE -> "Codex 新增文件，${hunks.size} 个代码块待审阅"
                CodexChangeTrackerService.ReviewOperation.DELETE -> "Codex 删除文件"
                CodexChangeTrackerService.ReviewOperation.MOVE -> "Codex 移动文件，${hunks.size} 个代码块待审阅"
                CodexChangeTrackerService.ReviewOperation.MODIFY -> "Codex 产生 ${hunks.size} 个待审阅代码块"
            }
            add(JLabel(summary), BorderLayout.WEST)
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
                discard.addActionListener { _ ->
                    discard.isEnabled = false
                    accept.isEnabled = false
                    if (!tracker.discard(file)) {
                        discard.isEnabled = true
                        accept.isEnabled = true
                    }
                }
                accept.addActionListener { _ -> discard.isEnabled = false; accept.isEnabled = false; tracker.accept(file) }
                add(discardAll)
                add(acceptAll)
                add(discard)
                add(accept)
                (fileEditor as? TextEditor)?.editor?.takeIf { hunks.isNotEmpty() }?.let { editor ->
                    add(createHunkNavigator(editor, hunks))
                }
            }, BorderLayout.EAST)
        }
    }

    private fun createHunkNavigator(editor: Editor, hunks: List<ChangeHunk>): JComponent {
        var currentIndex = initialHunkIndex(editor, hunks)
        val position = JLabel()
        val previous = navigationButton("↑", "上一个代码块")
        val next = navigationButton("↓", "下一个代码块")

        fun update() {
            position.text = "${currentIndex + 1}/${hunks.size}"
            previous.isEnabled = hunks.size == 1 || currentIndex > 0
            next.isEnabled = hunks.size == 1 || currentIndex < hunks.lastIndex
        }

        previous.addActionListener {
            if (currentIndex > 0) currentIndex--
            navigateToHunk(editor, hunks[currentIndex])
            update()
        }
        next.addActionListener {
            if (currentIndex < hunks.lastIndex) currentIndex++
            navigateToHunk(editor, hunks[currentIndex])
            update()
        }
        update()

        return JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(3), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(6)
            add(previous)
            add(position)
            add(next)
        }
    }

    private fun initialHunkIndex(editor: Editor, hunks: List<ChangeHunk>): Int {
        val caretLine = editor.caretModel.logicalPosition.line
        val containing = hunks.indexOfFirst { caretLine in it.newStartLine until maxOf(it.newEndLine, it.newStartLine + 1) }
        if (containing >= 0) return containing
        return hunks.indexOfFirst { it.newStartLine >= caretLine }.takeIf { it >= 0 } ?: hunks.lastIndex
    }

    private fun navigateToHunk(editor: Editor, hunk: ChangeHunk) {
        val document = editor.document
        val offset = if (hunk.newStartLine >= document.lineCount) {
            document.textLength
        } else {
            document.getLineStartOffset(hunk.newStartLine.coerceAtLeast(0))
        }
        editor.caretModel.moveToOffset(offset)
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        editor.contentComponent.requestFocusInWindow()
    }

    private fun navigationButton(text: String, tooltip: String) = JButton(text).apply {
        toolTipText = tooltip
        isFocusable = false
        isFocusPainted = false
        margin = JBUI.emptyInsets()
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        preferredSize = JBUI.size(28, 22)
        minimumSize = Dimension(preferredSize)
        maximumSize = Dimension(preferredSize)
    }
}
