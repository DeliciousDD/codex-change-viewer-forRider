package com.local.rider.codexviewer.tracking

import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.impl.FileEditorManagerImpl
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotifications
import com.intellij.util.concurrency.AppExecutorUtil
import com.local.rider.codexviewer.diff.ChangeHunk
import com.local.rider.codexviewer.diff.LineDiff
import java.nio.charset.StandardCharsets
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Consumes Hook batches only after Codex reports that the corresponding tool call completed. */
@Service(Service.Level.PROJECT)
class CodexChangeTrackerService(private val project: Project) : Disposable {
    enum class ReviewOperation { MODIFY, CREATE, DELETE, MOVE }

    data class ReviewEntry(
        val key: String,
        val relativePath: String,
        val sourceRelativePath: String?,
        val file: VirtualFile?,
        val beforeText: String,
        val afterText: String,
        val beforeBytes: ByteArray?,
        val operation: ReviewOperation,
    ) {
        val displayPath: String
            get() = if (operation == ReviewOperation.MOVE && sourceRelativePath != null) {
                "$sourceRelativePath → $relativePath"
            } else relativePath
    }

    private data class BatchEntry(
        val relativePath: String,
        val sourceRelativePath: String,
        val operation: ReviewOperation,
        val existed: Boolean,
        val beforeSnapshot: Path?,
    )

    private val lock = Any()
    private val entries = linkedMapOf<String, ReviewEntry>()
    private val listeners = CopyOnWriteArrayList<(VirtualFile?) -> Unit>()
    private val scanner: ScheduledFuture<*>

    init {
        scanner = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
            ::scanAppliedBatches,
            0,
            SCAN_INTERVAL_MILLIS,
            TimeUnit.MILLISECONDS,
        )
    }

    fun snapshot(): List<ReviewEntry> = synchronized(lock) { entries.values.toList() }

    fun changeFor(file: VirtualFile): ReviewEntry? = synchronized(lock) { entries[normalizeAbsoluteKey(file.path)] }

    fun hunks(file: VirtualFile): List<ChangeHunk> = changeFor(file)?.let {
        LineDiff.compute(it.beforeText, currentText(it) ?: it.afterText)
    }.orEmpty()

    fun currentTextFor(entry: ReviewEntry): String = currentText(entry) ?: entry.afterText

    fun clear(file: VirtualFile) = clearByKey(normalizeAbsoluteKey(file.path), file)

    fun clear(entry: ReviewEntry) = clearByKey(entry.key, entry.file)

    fun clearAll() {
        synchronized(lock) { entries.clear() }
        publish(null)
    }

    /** Keep the current contents and finish reviewing this file. */
    fun accept(file: VirtualFile) = clear(file)

    fun accept(entry: ReviewEntry) = clear(entry)

    fun acceptAll() {
        synchronized(lock) { entries.clear() }
        publish(null)
    }

    /** Restore the complete path operation and contents captured before Codex ran. */
    fun discard(file: VirtualFile): Boolean = changeFor(file)?.let(::discard) ?: false

    fun discard(entry: ReviewEntry): Boolean {
        if (!isCapturedAfterState(entry)) return false
        val succeeded = runCatching {
            when (entry.operation) {
                ReviewOperation.CREATE -> deleteTarget(entry, "取消 Codex 新增文件")
                ReviewOperation.DELETE -> restoreMissingTarget(entry, "取消 Codex 删除文件")
                ReviewOperation.MOVE -> restoreMove(entry)
                ReviewOperation.MODIFY -> restoreModifiedFile(entry)
            }
        }.isSuccess
        if (succeeded) clear(entry)
        return succeeded
    }

    fun discardAll() {
        snapshot().forEach(::discard)
        publish(null)
    }

    /** Keep one hunk while retaining the remaining file/path operation for review. */
    fun acceptHunk(file: VirtualFile, key: String): Boolean {
        val entry = changeFor(file) ?: return false
        if (!isCapturedAfterState(entry)) return false
        val hunk = LineDiff.compute(entry.beforeText, entry.afterText).firstOrNull { it.key == key } ?: return false
        val acceptedText = LineDiff.lineSlice(entry.afterText, hunk.newStartLine, hunk.newEndLine)
        val acceptedBefore = LineDiff.replaceLines(entry.beforeText, hunk.oldStartLine, hunk.oldEndLine, acceptedText)
        if (entry.operation == ReviewOperation.CREATE && acceptedBefore == entry.afterText) clear(entry)
        else replaceEntry(entry.copy(beforeText = acceptedBefore))
        return true
    }

    /** Restore one hunk without overwriting edits made in Rider after capture. */
    fun discardHunk(file: VirtualFile, key: String): Boolean {
        val entry = changeFor(file) ?: return false
        if (!isCapturedAfterState(entry)) return false
        val hunk = LineDiff.compute(entry.beforeText, entry.afterText).firstOrNull { it.key == key } ?: return false
        val restoredText = LineDiff.replaceLines(
            entry.afterText,
            hunk.newStartLine,
            hunk.newEndLine,
            hunk.oldLines.joinToString("\n"),
        )
        if (entry.operation == ReviewOperation.CREATE && entry.beforeText.isEmpty() && restoredText.isEmpty()) {
            return discard(entry)
        }
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return false
        replaceDocument(document, restoredText, "取消 Codex 代码块")
        replaceEntry(entry.copy(afterText = restoredText, file = file))
        return true
    }

    fun addListener(listener: (VirtualFile?) -> Unit) {
        listeners += listener
    }

    private fun scanAppliedBatches() {
        if (project.isDisposed) return
        val root = batchesRoot() ?: return
        runCatching {
            if (!Files.isDirectory(root)) return
            Files.list(root).use { directories ->
                directories.filter(Files::isDirectory)
                    .filter { !it.fileName.toString().startsWith(".tmp-") }
                    .sorted()
                    .forEach(::consumeBatch)
            }
        }
    }

    private fun consumeBatch(directory: Path) {
        val manifestPath = directory.resolve(BATCH_MANIFEST_NAME)
        val appliedPath = directory.resolve(BATCH_APPLIED_NAME)
        if (!Files.isRegularFile(manifestPath)) return

        val root = runCatching { JsonParser.parseString(Files.readString(manifestPath)).asJsonObject }.getOrNull() ?: return
        val createdAt = root.get("createdAtUnixMillis")?.asLong ?: return
        val age = System.currentTimeMillis() - createdAt
        if (age !in 0..BATCH_TTL_MILLIS) {
            deleteBatchDirectory(directory)
            return
        }
        if (root.get("schemaVersion")?.asInt != BATCH_SCHEMA_VERSION || !Files.isRegularFile(appliedPath)) return

        val batchEntries = root.getAsJsonArray("entries")?.mapNotNull { element ->
            val item = element.asJsonObject
            val path = normalizeRelativePath(item.get("path")?.asString.orEmpty())
            val sourcePath = normalizeRelativePath(item.get("sourcePath")?.asString ?: path)
            if (!isSafeReviewPath(path) || !isSafeReviewPath(sourcePath)) return@mapNotNull null
            val operation = when (item.get("operation")?.asString) {
                "add" -> ReviewOperation.CREATE
                "delete" -> ReviewOperation.DELETE
                "move" -> ReviewOperation.MOVE
                else -> ReviewOperation.MODIFY
            }
            val existed = item.get("existed")?.asBoolean ?: false
            val beforeFile = item.get("beforeFile")?.takeUnless { it.isJsonNull }?.asString
                ?.let(::normalizeRelativePath)
            val beforeSnapshot = beforeFile?.let { directory.resolve(it).normalize() }
                ?.takeIf { it.startsWith(directory.normalize()) && Files.isRegularFile(it) }
            if (existed && beforeSnapshot == null) return@mapNotNull null
            BatchEntry(path, sourcePath, operation, existed, beforeSnapshot)
        }.orEmpty()

        batchEntries.forEach(::captureEntry)
        deleteBatchDirectory(directory)
    }

    private fun captureEntry(batch: BatchEntry) {
        val target = resolveWorkspacePath(batch.relativePath) ?: return
        val beforeBytes = if (batch.existed) {
            val snapshot = batch.beforeSnapshot ?: return
            if (Files.size(snapshot) > MAX_REVIEWABLE_BYTES) return
            Files.readAllBytes(snapshot)
        } else null
        if (Files.isRegularFile(target) && Files.size(target) > MAX_REVIEWABLE_BYTES) return

        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)
        val fileType = file?.fileType ?: FileTypeManager.getInstance().getFileTypeByFileName(target.fileName.toString())
        if (fileType.isBinary) return
        val charset = file?.charset ?: StandardCharsets.UTF_8
        val beforeText = beforeBytes?.toString(charset)?.normalizeLineEndings().orEmpty()
        val afterText = when {
            Files.isRegularFile(target) -> Files.readAllBytes(target).toString(charset).normalizeLineEndings()
            batch.operation == ReviewOperation.DELETE -> ""
            else -> return
        }
        val key = normalizeAbsoluteKey(target.toString())
        val sourceKey = resolveWorkspacePath(batch.sourceRelativePath)?.let { normalizeAbsoluteKey(it.toString()) }
        val (existing, movedSource) = synchronized(lock) {
            val targetEntry = entries[key]
            val sourceEntry = if (batch.operation == ReviewOperation.MOVE && sourceKey != null && sourceKey != key) {
                entries.remove(sourceKey)
            } else null
            targetEntry to sourceEntry
        }
        val baseline = existing ?: movedSource
        val mergedOperation = when {
            baseline?.operation == ReviewOperation.MOVE -> ReviewOperation.MOVE
            baseline?.beforeBytes == null && baseline != null && Files.exists(target) -> ReviewOperation.CREATE
            baseline?.beforeBytes == null && baseline != null -> ReviewOperation.MODIFY
            baseline != null && !Files.exists(target) -> ReviewOperation.DELETE
            baseline != null && batch.operation == ReviewOperation.MOVE -> ReviewOperation.MOVE
            baseline != null -> ReviewOperation.MODIFY
            else -> batch.operation
        }
        val originalSourcePath = when {
            mergedOperation != ReviewOperation.MOVE -> null
            movedSource?.operation == ReviewOperation.MOVE -> movedSource.sourceRelativePath
            else -> batch.sourceRelativePath
        }
        val captured = ReviewEntry(
            key = key,
            relativePath = batch.relativePath,
            sourceRelativePath = originalSourcePath,
            file = file,
            beforeText = baseline?.beforeText ?: beforeText,
            afterText = afterText,
            beforeBytes = baseline?.beforeBytes ?: beforeBytes,
            operation = mergedOperation,
        )
        replaceEntry(captured)
        file?.let {
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed && it.isValid) FileEditorManager.getInstance(project).openFile(it, true)
            }
        }
    }

    private fun replaceEntry(entry: ReviewEntry) {
        val resolvedFile = entry.file?.takeIf(VirtualFile::isValid)
            ?: LocalFileSystem.getInstance().refreshAndFindFileByPath(entry.key)
        val updated = entry.copy(file = resolvedFile)
        val contentOnlyNoOp = updated.operation == ReviewOperation.MODIFY && updated.beforeText == updated.afterText
        synchronized(lock) {
            if (contentOnlyNoOp) entries.remove(updated.key) else entries[updated.key] = updated
        }
        publish(resolvedFile)
    }

    private fun currentText(entry: ReviewEntry): String? {
        val target = Path.of(entry.key)
        if (!Files.exists(target)) return ""
        if (!Files.isRegularFile(target) || Files.size(target) > MAX_REVIEWABLE_BYTES) return null
        val file = entry.file?.takeIf(VirtualFile::isValid)
            ?: LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)
        val document = file?.let { FileDocumentManager.getInstance().getDocument(it) }
        return (document?.text ?: Files.readAllBytes(target).toString(file?.charset ?: StandardCharsets.UTF_8))
            .normalizeLineEndings()
    }

    private fun isCapturedAfterState(entry: ReviewEntry): Boolean {
        if (currentText(entry) == entry.afterText) return true
        Messages.showWarningDialog(
            project,
            "该文件在 Codex 修改后又发生了变化。为避免覆盖 Rider 中的新编辑，已取消本次回退；请先在差异视图中确认当前内容。",
            "Codex 审阅内容已过期",
        )
        return false
    }

    private fun restoreModifiedFile(entry: ReviewEntry) {
        val file = entry.file?.takeIf(VirtualFile::isValid)
            ?: LocalFileSystem.getInstance().refreshAndFindFileByPath(entry.key)
            ?: error("File no longer exists: ${entry.relativePath}")
        val document = FileDocumentManager.getInstance().getDocument(file) ?: error("Cannot open text document")
        replaceDocument(document, entry.beforeText, "取消 Codex 修改")
    }

    private fun restoreMissingTarget(entry: ReviewEntry, commandName: String) {
        val bytes = entry.beforeBytes ?: entry.beforeText.toByteArray(StandardCharsets.UTF_8)
        CommandProcessor.getInstance().executeCommand(project, {
            val target = Path.of(entry.key)
            Files.createDirectories(target.parent)
            Files.write(target, bytes)
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)
        }, commandName, null)
    }

    private fun restoreMove(entry: ReviewEntry) {
        val sourceRelative = entry.sourceRelativePath ?: error("Move source is missing")
        val source = resolveWorkspacePath(sourceRelative) ?: error("Move source is outside the workspace")
        if (Files.exists(source)) {
            Messages.showWarningDialog(project, "原路径已存在，无法安全撤销移动：$sourceRelative", "无法撤销 Codex 移动")
            error("Move source already exists")
        }
        val bytes = entry.beforeBytes ?: entry.beforeText.toByteArray(StandardCharsets.UTF_8)
        CommandProcessor.getInstance().executeCommand(project, {
            Files.createDirectories(source.parent)
            Files.write(source, bytes)
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source)
        }, "恢复 Codex 移动源文件", null)
        deleteTarget(entry, "取消 Codex 移动")
    }

    private fun deleteTarget(entry: ReviewEntry, commandName: String) {
        val target = Path.of(entry.key)
        val file = entry.file?.takeIf(VirtualFile::isValid)
            ?: LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)
        CommandProcessor.getInstance().executeCommand(project, {
            if (file != null && file.isValid) {
                WriteAction.run<RuntimeException> { file.delete(this) }
            } else {
                Files.deleteIfExists(target)
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)
            }
        }, commandName, null)
    }

    private fun replaceDocument(document: Document, text: String, commandName: String) {
        CommandProcessor.getInstance().executeCommand(project, {
            WriteAction.run<RuntimeException> { document.setText(text) }
        }, commandName, null)
    }

    private fun clearByKey(key: String, file: VirtualFile?) {
        synchronized(lock) { entries.remove(key) }
        publish(file?.takeIf(VirtualFile::isValid))
    }

    private fun resolveWorkspacePath(relativePath: String): Path? {
        val base = project.basePath?.let { Path.of(it) }?.normalize() ?: return null
        val resolved = base.resolve(relativePath).normalize()
        return resolved.takeIf { it.startsWith(base) }
    }

    private fun normalizeRelativePath(path: String): String = path.replace('\\', '/').removePrefix("./")

    private fun normalizeAbsoluteKey(path: String): String {
        val normalized = path.replace('\\', '/')
        return if (File.separatorChar == '\\') normalized.lowercase(Locale.ROOT) else normalized
    }

    private fun isSafeReviewPath(path: String): Boolean = runCatching {
        path.isNotEmpty() && path != ".." && !path.startsWith("../") && !Path.of(path).isAbsolute &&
            path != LEGACY_MARKER_NAME && !path.startsWith("$REVIEW_DIRECTORY_NAME/")
    }.getOrDefault(false)

    private fun batchesRoot(): Path? = project.basePath?.let {
        Path.of(it, REVIEW_DIRECTORY_NAME, BATCHES_DIRECTORY_NAME)
    }

    private fun deleteBatchDirectory(directory: Path) {
        runCatching {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    private fun publish(file: VirtualFile?) {
        if (project.isDisposed) return
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            if (file == null) EditorNotifications.getInstance(project).updateAllNotifications()
            else EditorNotifications.getInstance(project).updateNotifications(file)
            val manager = FileEditorManager.getInstance(project) as? FileEditorManagerImpl
            if (file == null) manager?.openFiles?.forEach(manager::updateFilePresentation)
            else manager?.updateFilePresentation(file)
            listeners.forEach { it(file) }
        }
    }

    override fun dispose() {
        scanner.cancel(false)
    }

    private fun String.normalizeLineEndings(): String = replace("\r\n", "\n").replace('\r', '\n')

    private companion object {
        const val MAX_REVIEWABLE_BYTES = 10L * 1024 * 1024
        const val REVIEW_DIRECTORY_NAME = ".codex-review"
        const val BATCHES_DIRECTORY_NAME = "batches"
        const val BATCH_MANIFEST_NAME = "manifest.json"
        const val BATCH_APPLIED_NAME = "applied.json"
        const val LEGACY_MARKER_NAME = ".codex-review.paths"
        const val BATCH_SCHEMA_VERSION = 3
        const val BATCH_TTL_MILLIS = 5 * 60 * 1000L
        const val SCAN_INTERVAL_MILLIS = 400L
    }
}
