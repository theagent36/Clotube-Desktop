package com.clotube.desktop.lifecycle

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.clotube.desktop.data.DesktopAppSettings
import java.awt.Frame
import java.awt.Window
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import java.awt.event.WindowStateListener

object AppLifecycleManager {
    var isWindowFocused by mutableStateOf(true)
        private set

    var isWindowMinimized by mutableStateOf(false)
        private set

    val isAppActive: Boolean
        get() = isWindowFocused && !isWindowMinimized

    val isEcoSuspended: Boolean
        get() = DesktopAppSettings.state.backgroundEcoMode && !isAppActive

    fun attachToWindow(window: Window) {
        window.addWindowFocusListener(object : WindowFocusListener {
            override fun windowGainedFocus(e: WindowEvent?) {
                isWindowFocused = true
            }

            override fun windowLostFocus(e: WindowEvent?) {
                isWindowFocused = false
            }
        })

        window.addWindowStateListener(object : WindowStateListener {
            override fun windowStateChanged(e: WindowEvent?) {
                val state = e?.newState ?: 0
                isWindowMinimized = (state and Frame.ICONIFIED) != 0
            }
        })
    }
}
