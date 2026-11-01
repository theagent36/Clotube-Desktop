package com.clotube.desktop

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.clotube.desktop.lifecycle.AppLifecycleManager
import com.clotube.desktop.media.LocalStreamServer
import com.clotube.desktop.ui.screens.DesktopBootScreen
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.RECT
import com.sun.jna.platform.win32.WinUser
import uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import java.awt.Color as AwtColor
import java.awt.GraphicsEnvironment
import java.io.File
import javax.swing.SwingUtilities
import javax.swing.UIManager

private const val GWL_STYLE = -16
private const val WS_CAPTION = 0x00C00000
private const val WS_THICKFRAME = 0x00040000
private const val WS_MINIMIZEBOX = 0x00020000
private const val WS_MAXIMIZEBOX = 0x00010000
private const val WS_MAXIMIZE = 0x01000000
private const val SWP_NOZORDER = 0x0004
private const val SWP_FRAMECHANGED = 0x0020
private const val SWP_SHOWWINDOW = 0x0040
private const val SWP_NOMOVE = 0x0002
private const val SWP_NOSIZE = 0x0001
private const val SW_MAXIMIZE = 3
private const val SW_RESTORE = 9

private fun User32.IsZoomed(hwnd: HWND): Boolean {
    val currentStyle = GetWindowLong(hwnd, GWL_STYLE)
    return (currentStyle and WS_MAXIMIZE) != 0
}

/**
 * Probes the active monitor's physical refresh rate in Hz (e.g. 60, 120, 144, 165).
 * Automatically reflects changes if the user switches to battery (60 Hz) or plugs into AC (144/165 Hz).
 */
fun getActiveDisplayRefreshRate(): Int {
    return try {
        val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val rate = ge.defaultScreenDevice.displayMode.refreshRate
        if (rate in 30..360) rate else 60
    } catch (_: Throwable) {
        60
    }
}

fun setupLibVlcEnvironment() {
    val resourcesDir = System.getProperty("compose.application.resources.dir")
    val candidateDirs = listOfNotNull(
        resourcesDir?.let { File(it, "windows/vlc") },
        resourcesDir?.let { File(it, "vlc") },
        File("package-resources/windows/vlc"), // Local development fallback
        File("vlc")
    )

    val bundledVlc = candidateDirs.firstOrNull { dir ->
        dir.exists() && File(dir, "libvlc.dll").exists()
    }

    if (bundledVlc != null) {
        val vlcPath = bundledVlc.absolutePath
        val pluginsPath = File(bundledVlc, "plugins").absolutePath

        NativeLibrary.addSearchPath(RuntimeUtil.getLibVlcLibraryName(), vlcPath)
        NativeLibrary.addSearchPath(RuntimeUtil.getLibVlcCoreLibraryName(), vlcPath)

        System.setProperty("jna.library.path", vlcPath)
        System.setProperty("VLC_PLUGIN_PATH", pluginsPath)
        println("[Clotube] Initialized bundled LibVLC from: $vlcPath")
    } else {
        println("[Clotube] Bundled VLC not detected; running system NativeDiscovery")
        NativeDiscovery().discover()
    }
}

fun main() {
    setupLibVlcEnvironment()

    System.setProperty("java.net.preferIPv4Stack", "true")
    System.setProperty("java.net.preferIPv6Addresses", "false")
    System.setProperty("sun.awt.noerasebackground", "true")
    System.setProperty("compose.interop.blending", "true")
    System.setProperty("skiko.renderApi", "DIRECT3D")

    try {
        val black = AwtColor.BLACK
        UIManager.put("Panel.background", black)
        UIManager.put("Panel.foreground", black)
        UIManager.put("Window.background", black)
        UIManager.put("Viewport.background", black)
        UIManager.put("control", black)
    } catch (_: Throwable) {}

    Runtime.getRuntime().addShutdownHook(Thread({
        try {
            LocalStreamServer.stop()
        } catch (_: Throwable) {}
    }, "Clotube-ShutdownHook"))

    LocalStreamServer.start()

    application {
        val windowState = rememberWindowState(
            placement = WindowPlacement.Maximized,
            width = 1280.dp,
            height = 820.dp
        )
        var isFullscreenActive by remember { mutableStateOf(false) }
        var isBooting by remember { mutableStateOf(true) }

        var wasMaximizedBeforeFullscreen by remember { mutableStateOf(true) }
        var hasEverEnteredFullscreen by remember { mutableStateOf(false) }
        val savedFloatingRect = remember { RECT() }

        Window(
            onCloseRequest = {
                try {
                    LocalStreamServer.stop()
                } catch (_: Throwable) {}
                exitApplication()
                kotlin.system.exitProcess(0)
            },
            state = windowState,
            title = "Clotube",
            icon = painterResource("ic_clotube.png")
        ) {
            LaunchedEffect(window) {
                AppLifecycleManager.attachToWindow(window)
            }

            SideEffect {
                window.background = AwtColor.BLACK
                window.contentPane.background = AwtColor.BLACK
                window.rootPane.background = AwtColor.BLACK
            }

            LaunchedEffect(isFullscreenActive) {
                if (!isFullscreenActive && !hasEverEnteredFullscreen) return@LaunchedEffect

                try {
                    val hwndPtr = Native.getWindowPointer(window)
                    if (hwndPtr != null && hwndPtr != Pointer.NULL) {
                        val hwnd = HWND(hwndPtr)
                        val user32 = User32.INSTANCE

                        if (isFullscreenActive) {
                            hasEverEnteredFullscreen = true
                            wasMaximizedBeforeFullscreen = user32.IsZoomed(hwnd)
                            if (!wasMaximizedBeforeFullscreen) {
                                user32.GetWindowRect(hwnd, savedFloatingRect)
                            }

                            // Strip caption, borders, and MAXIMIZE bit so DWM handles raw full screen
                            val currentStyle = user32.GetWindowLong(hwnd, GWL_STYLE)
                            val borderlessStyle = currentStyle and (WS_CAPTION or WS_THICKFRAME or WS_MINIMIZEBOX or WS_MAXIMIZEBOX or WS_MAXIMIZE).inv()
                            user32.SetWindowLong(hwnd, GWL_STYLE, borderlessStyle)

                            val monitor = user32.MonitorFromWindow(hwnd, WinUser.MONITOR_DEFAULTTONEAREST)
                            val monitorInfo = WinUser.MONITORINFO()
                            user32.GetMonitorInfo(monitor, monitorInfo)
                            val monitorRect: RECT = monitorInfo.rcMonitor

                            user32.SetWindowPos(
                                hwnd,
                                null,
                                monitorRect.left,
                                monitorRect.top,
                                monitorRect.right - monitorRect.left,
                                monitorRect.bottom - monitorRect.top,
                                SWP_NOZORDER or SWP_FRAMECHANGED or SWP_SHOWWINDOW
                            )
                        } else {
                            // 1. Restore standard window styles (clearing any stale MAXIMIZE bit)
                            val currentStyle = user32.GetWindowLong(hwnd, GWL_STYLE)
                            val standardStyle = (currentStyle and WS_MAXIMIZE.inv()) or WS_CAPTION or WS_THICKFRAME or WS_MINIMIZEBOX or WS_MAXIMIZEBOX
                            user32.SetWindowLong(hwnd, GWL_STYLE, standardStyle)

                            // 2. Notify DWM that the frame decorations have changed BEFORE sizing
                            user32.SetWindowPos(
                                hwnd,
                                null,
                                0, 0, 0, 0,
                                SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_FRAMECHANGED
                            )

                            // 3. Size properly to work area
                            if (wasMaximizedBeforeFullscreen) {
                                // Restore to floating then maximize: forces Windows to query rcWork above the taskbar
                                user32.ShowWindow(hwnd, SW_RESTORE)
                                user32.ShowWindow(hwnd, SW_MAXIMIZE)
                            } else {
                                user32.ShowWindow(hwnd, SW_RESTORE)
                                val w = (savedFloatingRect.right - savedFloatingRect.left).coerceAtLeast(1024)
                                val h = (savedFloatingRect.bottom - savedFloatingRect.top).coerceAtLeast(700)
                                user32.SetWindowPos(
                                    hwnd,
                                    null,
                                    savedFloatingRect.left.coerceAtLeast(0),
                                    savedFloatingRect.top.coerceAtLeast(0),
                                    w,
                                    h,
                                    SWP_NOZORDER or SWP_FRAMECHANGED or SWP_SHOWWINDOW
                                )
                            }

                            SwingUtilities.invokeLater {
                                window.revalidate()
                                window.repaint()
                            }
                        }
                    }
                } catch (t: Throwable) {
                    t.printStackTrace()
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                Crossfade(
                    targetState = isBooting,
                    animationSpec = tween(durationMillis = 350)
                ) { booting ->
                    if (booting) {
                        DesktopBootScreen(
                            onBootComplete = { isBooting = false }
                        )
                    } else {
                        App(
                            isFullscreen = isFullscreenActive,
                            onToggleFullscreen = { isFullscreenActive = !isFullscreenActive }
                        )
                    }
                }
            }
        }
    }
}
