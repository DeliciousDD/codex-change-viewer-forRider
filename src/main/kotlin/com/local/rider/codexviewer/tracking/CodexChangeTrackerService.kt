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
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.ui.EditorNotifications
import com.intellij.util.concurrency.AppExecutorUtil
import com.local.rider.codexviewer.diff.ChangeHunk
import com.local.rider.codexviewer.diff.LineDiff
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Keeps the first pre-edit snapshot only for paths pre-authorized by a Codex Hook batch. The Hook
 * writes immutable manifests and before-images before Codex edits a file, so Rider does not depend
 * on winning a race with the external file-system refresh. A short-lived legacy marker is still
 * accepted for compatibility with older clients.
 */
@Service(Service.Level.PROJECT)
class CodexChangeTrackerService(private val project: Project) : Disposable {
    data class ReviewEntry(
        val file: VirtualFile,
        val beforeText: String,
        val afterText: String,
        val wasCreated: Boolean = false,
    )

    private data class AuthorizedPath(
        val path: String,
        val existed: Boolean,
        val beforeSnapshot: Path?,
    )

    private data class ReviewBatch(
        val id: String,
        val paths: Map<String, AuthorizedPath>,
        val createdAtMillis: Long,
        val directory: Path? = null,
        val legacyMarker: Path? = null,
    )

    private data class CapturedChange(
        val file: VirtualFile,
        val beforeText: String,
        val batches: List<ReviewBatch>,
        val wasCreated: Boolean = false,
    )

    private data class PendingCreatedChange(
        val event: VFileCreateEvent,
        val batches: List<ReviewBatch>,
    )

    private val lock = Any()
    private val entries = linkedMapOf<String, ReviewEntry>()
    private val listeners = CopyOnWriteArrayList<(VirtualFile?) -> Unit>()
    private val observedBatchPaths = mutableMapOf<String, MutableSet<String>>()

    private val fileListener = object : AsyncFileListener {
        override fun prepareChange(events: List<VFileEvent>): AsyncFileListener.ChangeApplier? {
            if (project.isDisposed) return null
            val batches = readCurrentBatches()
            if (batches.isEmpty()) return null

            val captured = buildList {
                events.filterIsInstance<VFileContentChangeEvent>()
                    .filter { it.isFromRefresh }
                    .forEach { event ->
                        val file = event.file
                        val matching = matchingBatches(file, batches)
                        if (matching.isNotEmpty() && isReviewable(file)) {
                            readAuthorizedBeforeText(file, matching)?.let {
                                add(CapturedChange(file, it, matching))
                            }
                        }
                    }
                events.filterIsInstance<VFileMoveEvent>()
                    .filter { it.isFromRefresh }
                    .forEach { event ->
                        val file = event.file
                        val targetPath = relativePath(event.newParent)?.let {
                            normalizeRelativePath("$it/${file.name}")
                        } ?: return@forEach
                        val matching = matchingBatches(targetPath, batches)
                        if (matching.isNotEmpty() && isReviewable(file)) {
                            readAuthorizedBeforeText(file, matching, targetPath)?.let {
                                add(CapturedChange(file, it, matching))
                            }
                        }
                    }
                events.filterIsInstance<VFilePropertyChangeEvent>()
                    .filter { it.isFromRefresh && it.propertyName == VirtualFile.PROP_NAME }
                    .forEach { event ->
                        val file = event.file
                        val parentPath = file.parent?.let(::relativePath) ?: return@forEach
                        val targetPath = normalizeRelativePath("$parentPath/${event.newValue}")
                        val matching = matchingBatches(targetPath, batches)
                        if (matching.isNotEmpty() && isReviewable(file)) {
                            readAuthorizedBeforeText(file, matching, targetPath)?.let {
                                add(CapturedChange(file, it, matching))
                            }
                        }
                    }
            }
            val pendingCreated = events.filterIsInstance<VFileCreateEvent>()
                .asSequence()
                .filter { it.isFromRefresh && !it.isDirectory }
                .mapNotNull { event ->
                    val path = relativePath(event.path) ?: return@mapNotNull null
                    matchingBatches(path, batches).takeIf(List<*>::isNotEmpty)?.let {
                        PendingCreatedChange(event, it)
                    }
                }
                .toList()
            if (captured.isEmpty() && pendingCreated.isEmpty()) return null

            return object : AsyncFileListener.ChangeApplier {
                override fun afterVfsChange() {
                    captured.forEach(::captureCurrentText)
                    pendingCreated.forEach(::captureCreatedFile)
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
        if (entry.wasCreated && entry.beforeText.isEmpty()) {
            val deleted = runCatching {
                CommandProcessor.getInstance().executeCommand(project, {
                    WriteAction.run<RuntimeException> { file.delete(this) }
                }, "取消 Codex 新增文件", null)
            }.isSuccess
            if (deleted) {
                synchronized(lock) { entries.remove(file.path) }
                publish(null)
            }
            return
        }
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
            entry.wasCreated,
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
        if (entry.wasCreated && entry.beforeText.isEmpty() && restoredText.isEmpty()) {
            discard(file)
            return
        }
        FileDocumentManager.getInstance().getDocument(file)?.let {
            replaceDocument(it, restoredText, "取消 Codex 代码块")
        } ?: return
        replaceEntry(file, entry.beforeText, restoredText, entry.wasCreated)
    }

    fun addListener(listener: (VirtualFile?) -> Unit) {
        listeners += listener
    }

    private fun captureCurrentText(captured: CapturedChange) {
        AppExecutorUtil.getAppExecutorService().execute {
            val afterText = readText(captured.file) ?: return@execute
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || !captured.file.isValid) return@invokeLater
                val existing = changeFor(captured.file)
                val beforeText = existing?.beforeText ?: captured.beforeText
                replaceEntry(captured.file, beforeText, afterText, existing?.wasCreated ?: captured.wasCreated)
                FileEditorManager.getInstance(project).openFile(captured.file, true)
                captured.batches.forEach { markBatchPathObserved(it, captured.file) }
            }
        }
    }

    private fun captureCreatedFile(pending: PendingCreatedChange) {
        val event = pending.event
        val file = event.file ?: event.parent.findChild(event.childName) ?: return
        if (!isReviewable(file)) return
        val beforeText = readAuthorizedBeforeText(file, pending.batches) ?: ""
        val path = relativePath(file)
        val wasCreated = path != null && pending.batches
            .asSequence()
            .mapNotNull { it.paths[path] }
            .firstOrNull()
            ?.existed == false
        captureCurrentText(CapturedChange(file, beforeText, pending.batches, wasCreated))
    }

    private fun readCurrentBatches(): List<ReviewBatch> {
        val currentTime = System.currentTimeMillis()
        val batches = mutableListOf<ReviewBatch>()
        batchesRoot()?.let { root ->
            runCatching {
                if (Files.isDirectory(root)) {
                    Files.list(root).use { directories ->
                        directories.filter(Files::isDirectory).forEach { directory ->
                            readBatch(directory, currentTime)?.let(batches::add)
                        }
                    }
                }
            }
        }
        readLegacyBatch(currentTime)?.let(batches::add)
        return batches.sortedBy(ReviewBatch::createdAtMillis)
    }

    private fun readBatch(directory: Path, currentTime: Long): ReviewBatch? = runCatching {
        if (directory.fileName.toString().startsWith(".tmp-")) return null
        val manifestPath = directory.resolve(BATCH_MANIFEST_NAME)
        if (!Files.isRegularFile(manifestPath)) return null
        val root = JsonParser.parseString(Files.readString(manifestPath)).asJsonObject
        if (root.get("schemaVersion")?.asInt != BATCH_SCHEMA_VERSION) return null
        val batchId = root.get("batchId")?.asString?.takeIf(String::isNotBlank) ?: return null
        val createdAt = root.get("createdAtUnixMillis")?.asLong ?: return null
        val age = currentTime - createdAt
        if (age !in 0..BATCH_TTL_MILLIS) {
            deleteBatchDirectory(directory)
            return null
        }

        val paths = linkedMapOf<String, AuthorizedPath>()
        root.getAsJsonArray("entries")?.forEach { element ->
            val entry = element.asJsonObject
            val path = normalizeRelativePath(entry.get("path")?.asString.orEmpty())
            if (!isSafeReviewPath(path)) return@forEach
            val existed = entry.get("existed")?.asBoolean ?: false
            val beforeFile = entry.get("beforeFile")
                ?.takeUnless { it.isJsonNull }
                ?.asString
                ?.let(::normalizeRelativePath)
            val beforeSnapshot = beforeFile?.let { relative ->
                directory.resolve(relative).normalize().takeIf { it.startsWith(directory.normalize()) }
            }
            if (!existed || beforeSnapshot?.let(Files::isRegularFile) == true) {
                paths[path] = AuthorizedPath(path, existed, beforeSnapshot)
            }
        }
        if (paths.isEmpty()) null else ReviewBatch(batchId, paths, createdAt, directory = directory)
    }.getOrNull()

    private fun readLegacyBatch(currentTime: Long): ReviewBatch? = runCatching {
        val marker = markerPath() ?: return null
        if (!Files.isRegularFile(marker)) return null
        val modifiedAt = Files.getLastModifiedTime(marker).toMillis()
        if (currentTime - modifiedAt !in 0..LEGACY_BATCH_TTL_MILLIS) return null
        val paths = Files.readAllLines(marker)
            .asSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .map(::normalizeRelativePath)
            .filter(::isSafeReviewPath)
            .associateWith { AuthorizedPath(it, existed = true, beforeSnapshot = null) }
        if (paths.isEmpty()) null else ReviewBatch(
            id = "legacy-$modifiedAt",
            paths = paths,
            createdAtMillis = modifiedAt,
            legacyMarker = marker,
        )
    }.getOrNull()

    private fun matchingBatches(file: VirtualFile, batches: List<ReviewBatch>): List<ReviewBatch> {
        val path = relativePath(file) ?: return emptyList()
        return matchingBatches(path, batches)
    }

    private fun matchingBatches(path: String, batches: List<ReviewBatch>): List<ReviewBatch> =
        batches.filter { path in it.paths }

    private fun readAuthorizedBeforeText(
        file: VirtualFile,
        batches: List<ReviewBatch>,
        authorizedPath: String? = relativePath(file),
    ): String? {
        val path = authorizedPath ?: return null
        batches.forEach { batch ->
            val authorized = batch.paths[path] ?: return@forEach
            if (!authorized.existed) return ""
            val snapshot = authorized.beforeSnapshot ?: return@forEach
            val text = runCatching {
                if (Files.size(snapshot) > MAX_REVIEWABLE_BYTES) return@runCatching null
                String(Files.readAllBytes(snapshot), file.charset).normalizeLineEndings()
            }.getOrNull()
            if (text != null) return text
        }
        return readText(file)
    }

    private fun relativePath(file: VirtualFile): String? = relativePath(file.path)

    private fun relativePath(filePath: String): String? =
        project.basePath?.let { base ->
            val basePath = Path.of(base).normalize()
            runCatching { normalizeRelativePath(basePath.relativize(Path.of(filePath).normalize()).toString()) }.getOrNull()
        }

    private fun normalizeRelativePath(path: String): String = path.replace('\\', '/').removePrefix("./")

    private fun isSafeReviewPath(path: String): Boolean =
        path.isNotEmpty() && path != ".." && !path.startsWith("../") &&
            path != BATCH_MARKER_NAME && !path.startsWith("$REVIEW_DIRECTORY_NAME/")

    private fun markBatchPathObserved(batch: ReviewBatch, file: VirtualFile) {
        val path = relativePath(file) ?: return
        val complete = synchronized(lock) {
            val observed = observedBatchPaths.getOrPut(batch.id) { mutableSetOf() }
            observed += path
            observed.containsAll(batch.paths.keys)
        }
        if (!complete) return
        batch.directory?.let(::deleteBatchDirectory)
        batch.legacyMarker?.let { marker ->
            runCatching {
                if (Files.isRegularFile(marker) && Files.getLastModifiedTime(marker).toMillis() == batch.createdAtMillis) {
                    Files.deleteIfExists(marker)
                }
            }
        }
        synchronized(lock) { observedBatchPaths.remove(batch.id) }
    }

    private fun markerPath(): Path? = project.basePath?.let { Path.of(it, BATCH_MARKER_NAME) }

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

    private fun replaceDocument(document: Document, text: String, commandName: String) {
        CommandProcessor.getInstance().executeCommand(project, {
            WriteAction.run<RuntimeException> { document.setText(text) }
        }, commandName, null)
    }

    private fun replaceEntry(file: VirtualFile, beforeText: String, afterText: String, wasCreated: Boolean = false) {
        synchronized(lock) {
            if (beforeText == afterText) entries.remove(file.path)
            else entries[file.path] = ReviewEntry(file, beforeText, afterText, wasCreated)
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
        VfsUtilCore.loadText(file).normalizeLineEndings()
    }.getOrNull()

    private fun String.normalizeLineEndings(): String = replace("\r\n", "\n").replace('\r', '\n')

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
        const val REVIEW_DIRECTORY_NAME = ".codex-review"
        const val BATCHES_DIRECTORY_NAME = "batches"
        const val BATCH_MANIFEST_NAME = "manifest.json"
        const val BATCH_SCHEMA_VERSION = 2
        const val LEGACY_BATCH_TTL_MILLIS = 2 * 60 * 1000L
        const val BATCH_TTL_MILLIS = 5 * 60 * 1000L
    }
}
