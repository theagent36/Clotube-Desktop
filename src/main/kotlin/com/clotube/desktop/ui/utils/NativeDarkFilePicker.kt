package com.clotube.desktop.ui.utils

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import java.io.File
import javax.swing.SwingUtilities
import kotlin.concurrent.thread

object NativeDarkFilePicker {

    fun pickJsonFile(
        title: String = "Select Google Cloud client_secret_*.json",
        onFileSelected: (File) -> Unit
    ) {
        pickFile(
            title = title,
            filters = listOf(
                "JSON Files (*.json)" to "*.json",
                "All Files (*.*)" to "*.*"
            )
        ) { filePath ->
            onFileSelected(File(filePath))
        }
    }

    fun pickFile(
        title: String,
        filters: List<Pair<String, String>>,
        onFileSelected: (String) -> Unit
    ) {
        thread(isDaemon = true, name = "Clotube-NativeDarkFilePicker") {
            var selectedPath: String? = null
            try {
                selectedPath = showNativeWindowsDarkDialog(title, filters)
            } catch (t: Throwable) {
                System.err.println("[NativeDarkFilePicker] Error showing native dialog: ${t.message}")
            }

            if (!selectedPath.isNullOrBlank()) {
                SwingUtilities.invokeLater {
                    onFileSelected(selectedPath)
                }
            }
        }
    }

    private fun showNativeWindowsDarkDialog(
        title: String,
        filters: List<Pair<String, String>>
    ): String? {
        val ole32 = NativeLibrary.getInstance("ole32")
        val coInitializeEx = ole32.getFunction("CoInitializeEx")
        val coCreateInstance = ole32.getFunction("CoCreateInstance")
        val coTaskMemFree = ole32.getFunction("CoTaskMemFree")
        val coUninitialize = ole32.getFunction("CoUninitialize")

        // 1. Initialize Single-Threaded Apartment for Windows Shell COM UI
        coInitializeEx.invoke(Int::class.java, arrayOf(Pointer.NULL, 2 /* COINIT_APARTMENTTHREADED */))

        try {
            // CLSID_FileOpenDialog = {DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7}
            val clsid = parseGuid("DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7")
            // IID_IFileOpenDialog = {D57C7288-D4AD-4768-BE02-9D969532D960}
            val iid = parseGuid("D57C7288-D4AD-4768-BE02-9D969532D960")

            val pDialogMem = Memory(Native.POINTER_SIZE.toLong())
            val hrCreate = coCreateInstance.invoke(
                Int::class.java,
                arrayOf(clsid, Pointer.NULL, 1 /* CLSCTX_INPROC_SERVER */, iid, pDialogMem)
            ) as Int

            if (hrCreate != 0) return null
            val pDialog = pDialogMem.getPointer(0) ?: return null

            try {
                // 2. Set Dialog Title (IFileDialog::SetTitle = Index 17)
                val titleMem = Memory(((title.length + 1) * 2).toLong()).apply { setWideString(0, title) }
                callVTable(pDialog, 17, pDialog, titleMem)

                // 3. Configure Filter Specs (IFileDialog::SetFileTypes = Index 4)
                if (filters.isNotEmpty()) {
                    val filterCount = filters.size
                    val filterSpecMem = Memory((filterCount * 2 * Native.POINTER_SIZE).toLong())

                    for (i in filters.indices) {
                        val (label, spec) = filters[i]
                        val mLabel = Memory(((label.length + 1) * 2).toLong()).apply { setWideString(0, label) }
                        val mSpec = Memory(((spec.length + 1) * 2).toLong()).apply { setWideString(0, spec) }

                        filterSpecMem.setPointer((i * 2L) * Native.POINTER_SIZE, mLabel)
                        filterSpecMem.setPointer((i * 2L + 1) * Native.POINTER_SIZE, mSpec)
                    }

                    callVTable(pDialog, 4, pDialog, filterCount, filterSpecMem)
                    // SetFileTypeIndex = Index 5 (1-based index)
                    callVTable(pDialog, 5, pDialog, 1)
                }

                // 4. Set Options: FOS_FORCEFILESYSTEM (0x40) | FOS_FILEMUSTEXIST (0x1000)
                val optMem = Memory(4)
                callVTable(pDialog, 10, pDialog, optMem) // GetOptions = Index 10
                val currentOpt = optMem.getInt(0)
                callVTable(pDialog, 9, pDialog, currentOpt or 0x00000040 or 0x00001000) // SetOptions = Index 9

                // 5. Show Dialog with Clotube's active dark HWND (IModalWindow::Show = Index 3)
                val hwnd = User32.INSTANCE.GetActiveWindow()
                val hwndPtr = hwnd?.pointer ?: Pointer.NULL
                val hrShow = callVTable(pDialog, 3, pDialog, hwndPtr)

                // Dialog cancelled or closed by user
                if (hrShow != 0) return null

                // 6. Get Result (IFileDialog::GetResult = Index 20)
                val ppsiMem = Memory(Native.POINTER_SIZE.toLong())
                val hrResult = callVTable(pDialog, 20, pDialog, ppsiMem)
                if (hrResult != 0) return null
                val ppsi = ppsiMem.getPointer(0) ?: return null

                try {
                    val ppszPathMem = Memory(Native.POINTER_SIZE.toLong())
                    // IShellItem::GetDisplayName = Index 5 (SIGDN_FILESYSPATH = 0x80058000)
                    val hrPath = callVTable(ppsi, 5, ppsi, 0x80058000.toInt(), ppszPathMem)
                    if (hrPath == 0) {
                        val pathPtr = ppszPathMem.getPointer(0)
                        if (pathPtr != null) {
                            val path = pathPtr.getWideString(0)
                            coTaskMemFree.invoke(arrayOf(pathPtr))
                            return path
                        }
                    }
                } finally {
                    // IShellItem::Release = Index 2
                    callVTable(ppsi, 2, ppsi)
                }
            } finally {
                // IFileOpenDialog::Release = Index 2
                callVTable(pDialog, 2, pDialog)
            }
        } finally {
            coUninitialize.invoke(emptyArray())
        }
        return null
    }

    private fun callVTable(pInstance: Pointer, methodIndex: Int, vararg args: Any?): Int {
        val vtable = pInstance.getPointer(0)
        val methodPtr = vtable.getPointer(methodIndex.toLong() * Native.POINTER_SIZE)
        val function = Function.getFunction(methodPtr)
        val result = function.invoke(Int::class.java, args)
        return (result as? Number)?.toInt() ?: 0
    }

    private fun parseGuid(guidStr: String): Memory {
        val clean = guidStr.replace("{", "").replace("}", "").replace("-", "")
        val mem = Memory(16)
        val d1 = clean.substring(0, 8).toLong(16).toInt()
        val d2 = clean.substring(8, 12).toInt(16).toShort()
        val d3 = clean.substring(12, 16).toInt(16).toShort()
        mem.setInt(0, d1)
        mem.setShort(4, d2)
        mem.setShort(6, d3)
        for (i in 0 until 8) {
            val b = clean.substring(16 + i * 2, 18 + i * 2).toInt(16).toByte()
            mem.setByte(8L + i, b)
        }
        return mem
    }
}

