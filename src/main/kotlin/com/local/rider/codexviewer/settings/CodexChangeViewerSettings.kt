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
    )

    private var settingsState = SettingsState()

    var pendingTabColor: Color
        get() = Color(settingsState.pendingTabColorRgb, true)
        set(value) {
            settingsState.pendingTabColorRgb = value.rgb
        }

    override fun getState(): SettingsState = settingsState

    override fun loadState(state: SettingsState) {
        settingsState = state
    }

    companion object {
        val DEFAULT_PENDING_TAB_COLOR = Color(42, 142, 86)

        fun getInstance(): CodexChangeViewerSettings = service()
    }
}
