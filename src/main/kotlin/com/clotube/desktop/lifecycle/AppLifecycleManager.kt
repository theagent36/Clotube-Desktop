package com.clotube.desktop.lifecycle

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.awt.Frame
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener

object AppLifecycleManager {
    var isAppActive by mutableStateOf(true)
        private set

    fun attachToWindow(window: Window) {
        val checkState = {
            val isIconified = if (window is Frame) (window.extendedState and Frame.ICONIFIED) != 0 else false
            isAppActive = window.isVisible && !isIconified && window.isFocused && window.isActive
        }

        window.addWindowFocusListener(object : WindowFocusListener {
            override fun windowGainedFocus(e: WindowEvent?) { checkState() }
            override fun windowLostFocus(e: WindowEvent?) { isAppActive = false }
        })

        window.addWindowListener(object : WindowAdapter() {
            override fun windowActivated(e: WindowEvent?) { checkState() }
            override fun windowDeactivated(e: WindowEvent?) { isAppActive = false }
            override fun windowIconified(e: WindowEvent?) { isAppActive = false }
            override fun windowDeiconified(e: WindowEvent?) { checkState() }
        })

        checkState()
    }
}
