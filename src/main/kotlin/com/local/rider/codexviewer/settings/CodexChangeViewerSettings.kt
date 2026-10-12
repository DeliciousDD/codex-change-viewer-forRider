package com.local.rider.codexviewer.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import java.awt.Color

/** Application-wide visual settings persisted by Rider. */
@Service(Service.Level.APP)
@State(name = "CodexChangeViewerSettings", storages = [Storage("codexChangeViewer.xml")])
class CodexChangeViewerSettings : PersistentStateComponent<CodexChangeViewerSettings.SettingsState> {
    data class SettingsState(
        var pendingTabColorRgb: Int = DEFAULT_PENDING_TAB_COLOR.rgb,
        var ignoredFileExtensions: MutableList<String> = mutableListOf(),
    )

    private var settingsState = SettingsState()

    var pendingTabColor: Color
        get() = Color(settingsState.pendingTabColorRgb, true)
        set(value) {
            settingsState.pendingTabColorRgb = value.rgb
        }

    var ignoredFileExtensions: Set<String>
        get() = settingsState.ignoredFileExtensions.mapNotNull(::normalizeExtension).toSet()
        set(value) {
            settingsState.ignoredFileExtensions = value.mapNotNull(::normalizeExtension).distinct().sorted().toMutableList()
        }

    fun isIgnoredFileName(fileName: String): Boolean {
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0 || dot == fileName.lastIndex) return false
        return normalizeExtension(fileName.substring(dot)) in ignoredFileExtensions
    }

    override fun getState(): SettingsState = settingsState

    override fun loadState(state: SettingsState) {
        settingsState = state
    }

    companion object {
        val DEFAULT_PENDING_TAB_COLOR = Color(42, 142, 86)

        fun getInstance(): CodexChangeViewerSettings = service()

        private fun normalizeExtension(value: String): String? {
            val trimmed = value.trim().lowercase().removePrefix("*")
            if (trimmed.isEmpty()) return null
            val extension = if (trimmed.startsWith('.')) trimmed else ".${trimmed}"
            return extension.takeIf { it.length > 1 && it.substring(1).all { c -> c.isLetterOrDigit() || c in "_-" } }
        }
    }
}
