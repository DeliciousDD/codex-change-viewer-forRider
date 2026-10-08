package com.local.rider.codexviewer.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.ComponentInlayAlignment
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.InlayProperties
import com.intellij.openapi.editor.addComponentInlay
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorTextField
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.local.rider.codexviewer.diff.ChangeHunk
import com.local.rider.codexviewer.tracking.CodexChangeTrackerService
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.util.IdentityHashMap
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel

/** Shows pending Codex changes inline in a normal Rider editor. */
@Service(Service.Level.PROJECT)
class CodexInlineReviewManager(private val project: Project) : Disposable {
    private val tracker = project.service<CodexChangeTrackerService>()
    private val decorations = IdentityHashMap<Editor, EditorDecoration>()
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val pendingRefreshes = mutableMapOf<VirtualFile?, Runnable>()

    init {
        tracker.addListener(::scheduleRefresh)
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: com.intellij.openapi.fileEditor.FileEditorManager, file: VirtualFile) = scheduleRefresh(file)
            },
        )
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                if (event.editor.project != project) return
                FileDocumentManager.getInstance().getFile(event.editor.document)?.let(::scheduleRefresh)
            }

            override fun editorReleased(event: EditorFactoryEvent) {
                decorations.remove(event.editor)?.let(Disposer::dispose)
            }
        }, this)
    }

    private fun scheduleRefresh(file: VirtualFile?) {
        if (file != null && pendingRefreshes.containsKey(null)) return
        if (file == null) {
            pendingRefreshes.values.forEach(alarm::cancelRequest)
            pendingRefreshes.clear()
        } else pendingRefreshes.remove(file)?.let(alarm::cancelRequest)

        lateinit var request: Runnable
        request = Runnable {
            pendingRefreshes.remove(file, request)
            if (project.isDisposed) return@Runnable
            if (file == null) refreshAllEditors() else refreshFile(file)
        }
        pendingRefreshes[file] = request
        alarm.addRequest(request, 60)
    }

    private fun refreshAllEditors() = EditorFactory.getInstance().allEditors
        .filter { it.project == project }
        .forEach(::refreshEditor)

    private fun refreshFile(file: VirtualFile) {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        EditorFactory.getInstance().getEditors(document, project).forEach(::refreshEditor)
    }

    private fun refreshEditor(editor: Editor) {
        decorations.remove(editor)?.let(Disposer::dispose)
        if (editor.isDisposed) return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val hunks = tracker.hunks(file)
        if (hunks.isNotEmpty()) decorations[editor] = EditorDecoration(editor, file, hunks, tracker)
    }

    override fun dispose() {
        decorations.values.toList().forEach(Disposer::dispose)
        decorations.clear()
    }
}

private class EditorDecoration(
    private val editor: Editor,
    private val file: VirtualFile,
    hunks: List<ChangeHunk>,
    private val tracker: CodexChangeTrackerService,
) : Disposable {
    private val highlighters = mutableListOf<RangeHighlighter>()
    private val inlays = mutableListOf<Inlay<*>>()

    init { hunks.forEachIndexed(::addHunk) }

    private fun addHunk(index: Int, hunk: ChangeHunk) {
        if (hunk.newStartLine < hunk.newEndLine && editor.document.lineCount > 0) {
            val first = hunk.newStartLine.coerceAtMost(editor.document.lineCount - 1)
            val last = (hunk.newEndLine - 1).coerceAtMost(editor.document.lineCount - 1)
            highlighters += editor.markupModel.addRangeHighlighter(
                editor.document.getLineStartOffset(first), editor.document.getLineEndOffset(last),
                HighlighterLayer.ADDITIONAL_SYNTAX,
                TextAttributes(null, blend(editor.colorsScheme.defaultBackground, ADDED_TINT, 0.28f), null, null, Font.PLAIN),
                HighlighterTargetArea.LINES_IN_RANGE,
            )
        }
        val atEnd = hunk.newStartLine >= editor.document.lineCount
        val offset = if (atEnd) editor.document.textLength else editor.document.getLineStartOffset(hunk.newStartLine.coerceAtLeast(0))
        val properties = InlayProperties().relatesToPrecedingText(true).showAbove(!atEnd)
        editor.addComponentInlay(offset, properties, createHunkComponent(index, hunk), ComponentInlayAlignment.FIT_VIEWPORT_WIDTH)?.let(inlays::add)
    }

    private fun createHunkComponent(index: Int, hunk: ChangeHunk): JPanel {
        val background = blend(editor.colorsScheme.defaultBackground, REMOVED_TINT, 0.28f)
        val panel = JPanel(BorderLayout()).apply { isOpaque = true; this.background = background; border = JBUI.Borders.empty() }
        if (hunk.oldLines.isEmpty()) {
            panel.add(JLabel("+ 新增代码块").apply { border = JBUI.Borders.emptyLeft(3) }, BorderLayout.CENTER)
        } else {
            val oldCode = EditorTextField(EditorFactory.getInstance().createDocument(hunk.oldLines.joinToString("\n")), editor.project, file.fileType, true, false).apply {
                setViewer(true)
                setOneLineMode(false)
                setDisposedWith(this@EditorDecoration)
                this.background = background
                border = BorderFactory.createEmptyBorder()
                preferredSize = Dimension(1, this@EditorDecoration.editor.lineHeight * hunk.oldLines.size)
                addSettingsProvider { embedded ->
                    embedded.colorsScheme = this@EditorDecoration.editor.colorsScheme
                    embedded.backgroundColor = background
                    embedded.setHorizontalScrollbarVisible(false)
                    embedded.setVerticalScrollbarVisible(false)
                    embedded.settings.apply { isLineNumbersShown = false; isLineMarkerAreaShown = false; isFoldingOutlineShown = false; isRightMarginShown = false; isAdditionalPageAtBottom = false; additionalLinesCount = 0; additionalColumnsCount = 0 }
                }
            }
            panel.add(oldCode, BorderLayout.CENTER)
        }
        val height = editor.lineHeight.coerceAtMost(22)
        val discard = CompactColoredButton("取消", DISCARD_COLOR, 46, height)
        val accept = CompactColoredButton("应用", ACCEPT_COLOR, 46, height)
        discard.addActionListener { _ -> discard.isEnabled = false; accept.isEnabled = false; tracker.discardHunk(file, hunk.key) }
        accept.addActionListener { _ -> discard.isEnabled = false; accept.isEnabled = false; tracker.acceptHunk(file, hunk.key) }
        panel.add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply { isOpaque = false; add(discard); add(accept) }, BorderLayout.EAST)
        return panel
    }

    private fun blend(base: Color, tint: Color, amount: Float) = Color(
        (base.red * (1f - amount) + tint.red * amount).toInt().coerceIn(0, 255),
        (base.green * (1f - amount) + tint.green * amount).toInt().coerceIn(0, 255),
        (base.blue * (1f - amount) + tint.blue * amount).toInt().coerceIn(0, 255),
    )

    override fun dispose() {
        highlighters.forEach(RangeHighlighter::dispose)
        inlays.forEach(Inlay<*>::dispose)
    }

    private companion object {
        val ADDED_TINT = Color(62, 123, 73)
        val REMOVED_TINT = Color(153, 45, 53)
        val ACCEPT_COLOR = Color(42, 142, 86)
        val DISCARD_COLOR = Color(200, 67, 73)
    }
}
