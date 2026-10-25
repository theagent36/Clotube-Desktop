package com.clotube.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.clotube.desktop.media.LocalStreamServer
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.RECT
import com.sun.jna.platform.win32.WinUser
import java.awt.Color as AwtColor
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

fun main() = application {
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

    val windowState = rememberWindowState(placement = WindowPlacement.Maximized, width = 1280.dp, height = 820.dp)
    var isFullscreenActive by remember { mutableStateOf(false) }

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
        SideEffect {
            window.background = AwtColor.BLACK
            window.contentPane.background = AwtColor.BLACK
            window.rootPane.background = AwtColor.BLACK
        }

        // Win32 Borderless Fullscreen Engine (Preserves Maximized State & Prevents Window Shrinking)
        LaunchedEffect(isFullscreenActive) {
            if (!isFullscreenActive && !hasEverEnteredFullscreen) {
                return@LaunchedEffect
            }

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

                        val currentStyle = user32.GetWindowLong(hwnd, GWL_STYLE)
                        val borderlessStyle = currentStyle and (WS_CAPTION or WS_THICKFRAME or WS_MINIMIZEBOX or WS_MAXIMIZEBOX).inv()
                        user32.SetWindowLong(hwnd, GWL_STYLE, borderlessStyle)

                        val monitor = user32.MonitorFromWindow(hwnd, WinUser.MONITOR_DEFAULTTONEAREST)
                        val monitorInfo = WinUser.MONITORINFO()
                        user32.GetMonitorInfo(monitor, monitorInfo)
                        val monitorRect: RECT = monitorInfo.rcMonitor

                        val width = monitorRect.right - monitorRect.left
                        val height = monitorRect.bottom - monitorRect.top

                        user32.SetWindowPos(
                            hwnd,
                            null,
                            monitorRect.left,
                            monitorRect.top,
                            width,
                            height,
                            SWP_NOZORDER or SWP_FRAMECHANGED or SWP_SHOWWINDOW
                        )
                    } else {
                        val currentStyle = user32.GetWindowLong(hwnd, GWL_STYLE)
                        val standardStyle = currentStyle or WS_CAPTION or WS_THICKFRAME or WS_MINIMIZEBOX or WS_MAXIMIZEBOX
                        user32.SetWindowLong(hwnd, GWL_STYLE, standardStyle)

                        if (wasMaximizedBeforeFullscreen) {
                            user32.ShowWindow(hwnd, SW_MAXIMIZE)
                            user32.SetWindowPos(
                                hwnd,
                                null,
                                0, 0, 0, 0,
                                SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_FRAMECHANGED or SWP_SHOWWINDOW
                            )
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
                            window.validate()
                            window.repaint()
                        }
                    }
                }
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }

        App(
            isFullscreen = isFullscreenActive,
            onToggleFullscreen = { isFullscreenActive = !isFullscreenActive }
        )
    }
}
