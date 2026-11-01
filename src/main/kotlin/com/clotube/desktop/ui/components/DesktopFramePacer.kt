package com.clotube.desktop.ui.components

import com.clotube.desktop.data.DesktopAppSettings
import com.clotube.desktop.lifecycle.AppLifecycleManager
import kotlinx.coroutines.delay

object DesktopFramePacer {
    val targetFrameDelayMs: Long
        get() = if (DesktopAppSettings.state.batterySaver60Fps) 16L else 7L

    suspend fun paceLoop(onFrame: (frameStepMs: Long) -> Unit) {
        while (true) {
            if (AppLifecycleManager.isEcoSuspended) {
                delay(250L) // Sleep state: 0% GPU
                continue
            }

            val step = targetFrameDelayMs
            onFrame(step)
            delay(step)
        }
    }
}

