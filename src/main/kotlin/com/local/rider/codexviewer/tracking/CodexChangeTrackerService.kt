package com.local.rider.codexviewer.tracking

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.impl.FileEditorManagerImpl
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.EditorNotifications
import com.intellij.util.concurrency.AppExecutorUtil
import com.local.rider.codexviewer.diff.ChangeHunk
import com.local.rider.codexviewer.diff.LineDiff
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Keeps the first pre-refresh snapshot only for paths pre-authorized by a short-lived Codex batch
 * marker. This prevents unrelated external tools from entering the review queue.
 */
@Service(Service.Level.PROJECT)
class CodexChangeTrackerService(private val project: Project) : Disposable {
    data class ReviewEntry(
        val file: VirtualFile,
        val beforeText: String,
        val afterText: String,
    )

    private data class ReviewBatch(val paths: Set<String>, val modifiedAtMillis: Long)
    private data class CapturedChange(
        val file: VirtualFile,
        val beforeText: String,
        val batch: ReviewBatch,
    )

    private val lock = Any()
    private val entries = linkedMapOf<String, ReviewEntry>()
    private val listeners = CopyOnWriteArrayList<(VirtualFile?) -> Unit>()
    private val observedBatchPaths = mutableMapOf<Long, MutableSet<String>>()

    private val fileListener = object : AsyncFileListener {
        override fun prepareChange(events: List<VFileEvent>): AsyncFileListener.ChangeApplier? {
            if (project.isDisposed) return null
            val batch = readCurrentBatch() ?: return null

            val captured = buildList {
                events.filterIsInstance<VFileContentChangeEvent>()
                    .filter { it.isFromRefresh }
                    .forEach { event ->
                        val file = event.file
                        if (isAuthorized(file, batch) && isReviewable(file)) {
                            readText(file)?.let { add(CapturedChange(file, it, batch)) }
                        }
                    }
                events.filterIsInstance<VFileCreateEvent>()
                    .filter { it.isFromRefresh && !it.isDirectory }
                    .forEach { event ->
                        event.file?.let { file ->
                            if (isAuthorized(file, batch) && isReviewable(file)) {
                                add(CapturedChange(file, "", batch))
                            }
                        }
                    }
            }
            if (captured.isEmpty()) return null

            return object : AsyncFileListener.ChangeApplier {
                override fun afterVfsChange() {
                    captured.forEach(::captureCurrentText)
                }
            }
        }
    }

    init {
        VirtualFileManager.getInstance().addAsyncFileListener(fileListener, this)
    }

    fun snapshot(): List<ReviewEntry> = synchronized(lock) { entries.values.toList() }

    fun changeFor(file: VirtualFile): ReviewEntry? = synchronized(lock) { entries[file.path] }

    fun hunks(file: VirtualFile): List<ChangeHunk> = changeFor(file)?.let {
        LineDiff.compute(it.beforeText, it.afterText)
    }.orEmpty()

    fun clear(file: VirtualFile) {
        synchronized(lock) { entries.remove(file.path) }
        publish(file)
    }

    fun clearAll() {
        synchronized(lock) { entries.clear() }
        publish(null)
    }

    /** Keep the current contents and finish reviewing this file. */
    fun accept(file: VirtualFile) = clear(file)

    /** Keep every pending Codex-authorized file in the current review batch. */
    fun acceptAll() {
        snapshot().map { it.file }.forEach(::accept)
        publish(null)
    }

    /** Restore the whole file to the snapshot that preceded the first external update. */
    fun discard(file: VirtualFile) {
        val entry = changeFor(file) ?: return
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        replaceDocument(document, entry.beforeText, "取消 Codex 修改")
        clear(file)
    }

    /** Restore every pending Codex-authorized file to its first captured snapshot. */
    fun discardAll() {
        snapshot().map { it.file }.forEach(::discard)
        publish(null)
    }

    /** Keep one hunk while retaining the remaining hunks for review. */
    fun acceptHunk(file: VirtualFile, key: String) {
        val entry = changeFor(file) ?: return
        val hunk = hunks(file).firstOrNull { it.key == key } ?: return
        val acceptedText = LineDiff.lineSlice(entry.afterText, hunk.newStartLine, hunk.newEndLine)
        replaceEntry(
            file,
            LineDiff.replaceLines(entry.beforeText, hunk.oldStartLine, hunk.oldEndLine, acceptedText),
            entry.afterText,
        )
    }

    /** Restore one hunk and leave other external changes visible. */
    fun discardHunk(file: VirtualFile, key: String) {
        val entry = changeFor(file) ?: return
        val hunk = hunks(file).firstOrNull { it.key == key } ?: return
        val restoredText = LineDiff.replaceLines(
            entry.afterText,
            hunk.newStartLine,
            hunk.newEndLine,
            hunk.oldLines.joinToString("\n"),
        )
        FileDocumentManager.getInstance().getDocument(file)?.let {
            replaceDocument(it, restoredText, "取消 Codex 代码块")
        } ?: return
        replaceEntry(file, entry.beforeText, restoredText)
    }

    fun addListener(listener: (VirtualFile?) -> Unit) {
        listeners += listener
    }

    private fun captureCurrentText(captured: CapturedChange) {
        AppExecutorUtil.getAppExecutorService().execute {
            val afterText = readText(captured.file) ?: return@execute
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || !captured.file.isValid) return@invokeLater
                val beforeText = changeFor(captured.file)?.beforeText ?: captured.beforeText
                replaceEntry(captured.file, beforeText, afterText)
                FileEditorManager.getInstance(project).openFile(captured.file, true)
                markBatchPathObserved(captured.batch, captured.file)
            }
        }
    }

    private fun readCurrentBatch(): ReviewBatch? = runCatching {
        val marker = markerPath() ?: return null
        if (!Files.isRegularFile(marker)) return null
        val modifiedAt = Files.getLastModifiedTime(marker).toMillis()
        if (System.currentTimeMillis() - modifiedAt !in 0..BATCH_TTL_MILLIS) return null
        val paths = Files.readAllLines(marker)
            .asSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .map(::normalizeRelativePath)
            .filter { it.isNotEmpty() && !it.startsWith("../") && it != BATCH_MARKER_NAME }
            .toSet()
        if (paths.isEmpty()) null else ReviewBatch(paths, modifiedAt)
    }.getOrNull()

    private fun isAuthorized(file: VirtualFile, batch: ReviewBatch): Boolean =
        relativePath(file)?.let(batch.paths::contains) == true

    private fun relativePath(file: VirtualFile): String? =
        project.basePath?.let { base ->
            val basePath = Path.of(base).normalize()
            runCatching { normalizeRelativePath(basePath.relativize(Path.of(file.path).normalize()).toString()) }.getOrNull()
        }

    private fun normalizeRelativePath(path: String): String = path.replace('\\', '/').removePrefix("./")

    private fun markBatchPathObserved(batch: ReviewBatch, file: VirtualFile) {
        val path = relativePath(file) ?: return
        val complete = synchronized(lock) {
            val observed = observedBatchPaths.getOrPut(batch.modifiedAtMillis) { mutableSetOf() }
            observed += path
            observed.containsAll(batch.paths)
        }
        if (!complete) return
        runCatching {
            val marker = markerPath() ?: return@runCatching
            if (Files.isRegularFile(marker) && Files.getLastModifiedTime(marker).toMillis() == batch.modifiedAtMillis) {
                Files.deleteIfExists(marker)
            }
        }
        synchronized(lock) { observedBatchPaths.remove(batch.modifiedAtMillis) }
    }

    private fun markerPath(): Path? = project.basePath?.let { Path.of(it, BATCH_MARKER_NAME) }

    private fun replaceDocument(document: Document, text: String, commandName: String) {
        CommandProcessor.getInstance().executeCommand(project, {
            WriteAction.run<RuntimeException> { document.setText(text) }
        }, commandName, null)
    }

    private fun replaceEntry(file: VirtualFile, beforeText: String, afterText: String) {
        synchronized(lock) {
            if (beforeText == afterText) entries.remove(file.path)
            else entries[file.path] = ReviewEntry(file, beforeText, afterText)
        }
        publish(file)
    }

    private fun isReviewable(file: VirtualFile): Boolean =
        if (project.isDisposed || !file.isValid || file.isDirectory || !file.isInLocalFileSystem ||
            file.fileType.isBinary || file.length > MAX_REVIEWABLE_BYTES
        ) {
            false
        } else {
            val application = ApplicationManager.getApplication()
            if (application.isReadAccessAllowed) isProjectContent(file)
            else ReadAction.computeBlocking<Boolean, RuntimeException> { isProjectContent(file) }
        }

    private fun isProjectContent(file: VirtualFile): Boolean =
        ProjectFileIndex.getInstance(project).isInContent(file)

    private fun readText(file: VirtualFile): String? = runCatching {
        VfsUtilCore.loadText(file).replace("\r\n", "\n").replace('\r', '\n')
    }.getOrNull()

    private fun publish(file: VirtualFile?) {
        if (project.isDisposed) return
        if (file == null) EditorNotifications.getInstance(project).updateAllNotifications()
        else EditorNotifications.getInstance(project).updateNotifications(file)
        val manager = FileEditorManager.getInstance(project) as? FileEditorManagerImpl
        if (file == null) manager?.openFiles?.forEach(manager::updateFilePresentation)
        else manager?.updateFilePresentation(file)
        listeners.forEach { it(file) }
    }

    override fun dispose() = Unit

    private companion object {
        const val MAX_REVIEWABLE_BYTES = 4L * 1024 * 1024
        const val BATCH_MARKER_NAME = ".codex-review.paths"
        const val BATCH_TTL_MILLIS = 2 * 60 * 1000L
    }
}
