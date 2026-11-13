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
import com.clotube.desktop.data.DesktopAppSettings
import com.clotube.desktop.lifecycle.AppLifecycleManager
import com.clotube.desktop.media.LocalStreamServer
import com.clotube.desktop.ui.components.DesktopUpdateOverlay
import com.clotube.desktop.ui.screens.DesktopBootScreen
import com.clotube.desktop.update.DesktopUpdateManager
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.RECT
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
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

private fun applyWin32ImmersiveDarkMode(window: java.awt.Window) {
    try {
        val hwndPtr = Native.getWindowPointer(window) ?: return
        if (hwndPtr == Pointer.NULL) return

        val dwmapi = NativeLibrary.getInstance("dwmapi")
        val dwmSetWindowAttribute = dwmapi.getFunction("DwmSetWindowAttribute")

        // 1. DWMWA_USE_IMMERSIVE_DARK_MODE (20 on Win10 20H1+ and Win11; 19 on earlier Win10)
        val darkModeEnabled = IntByReference(1)
        val res = dwmSetWindowAttribute.invoke(
            Int::class.java,
            arrayOf(hwndPtr, 20, darkModeEnabled.pointer, 4)
        ) as Int

        if (res != 0) {
            dwmSetWindowAttribute.invoke(
                Int::class.java,
                arrayOf(hwndPtr, 19, darkModeEnabled.pointer, 4)
            )
        }

        // 2. Windows 11 DWMWA_CAPTION_COLOR = 35 (ColorRef: 0x00BBGGRR -> #07090E is 0x000E0907)
        val captionColor = IntByReference(0x000E0907)
        dwmSetWindowAttribute.invoke(
            Int::class.java,
            arrayOf(hwndPtr, 35, captionColor.pointer, 4)
        )

        // 3. Windows 11 DWMWA_TEXT_COLOR = 36 (White: 0x00FFFFFF)
        val textColor = IntByReference(0x00FFFFFF)
        dwmSetWindowAttribute.invoke(
            Int::class.java,
            arrayOf(hwndPtr, 36, textColor.pointer, 4)
        )
    } catch (_: Throwable) {
        // Safe fallback on unsupported OS versions
    }
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

    // 1. Network & DNS Stabilization (Fixes UnknownHostException: www.googleapis.com and api.tvmaze.com)
    System.setProperty("java.net.useSystemProxies", "false")
    System.setProperty("java.net.preferIPv4Stack", "false")
    System.setProperty("java.net.preferIPv6Addresses", "false")
    java.security.Security.setProperty("networkaddress.cache.ttl", "30")
    java.security.Security.setProperty("networkaddress.cache.negative.ttl", "5")

    // 2. Hardware Presentation Flags
    System.setProperty("sun.awt.noerasebackground", "true")
    System.setProperty("sun.awt.erasebackgroundonresize", "false")
    System.setProperty("compose.interop.blending", "false")
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
        val initialPlacement = if (DesktopAppSettings.state.launchMaximized) {
            WindowPlacement.Maximized
        } else {
            WindowPlacement.Floating
        }

        val windowState = rememberWindowState(
            placement = initialPlacement,
            width = 1280.dp,
            height = 820.dp
        )
        var isFullscreenActive by remember { mutableStateOf(false) }
        var isBooting by remember { mutableStateOf(true) }

        var wasMaximizedBeforeFullscreen by remember { mutableStateOf(DesktopAppSettings.state.launchMaximized) }
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
                applyWin32ImmersiveDarkMode(window)
            }

            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(2500L)
                DesktopUpdateManager.checkForUpdatesOnStartup()
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

                            applyWin32ImmersiveDarkMode(window)

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

                DesktopUpdateOverlay()
            }
        }
    }
}
