package com.music.bitchord.desktop

import java.nio.file.Path

/** Which desktop this is running on. */
internal object DesktopPlatform {

    private val name: String = System.getProperty("os.name").orEmpty()

    val isWindows: Boolean = name.startsWith("Windows", ignoreCase = true)

    val isLinux: Boolean = name.contains("linux", ignoreCase = true)

    val isMac: Boolean = name.startsWith("Mac", ignoreCase = true)

    /** Whether the application draws the window's own frame instead of the system drawing it. */
    val drawsOwnWindowFrame: Boolean = isWindows

    /**
     * This app's own directory of one kind: `~/Library/<macFolder>/BitChord` on macOS, and
     * `$<xdgVariable>/bitchord` — or [xdgDefault] under $HOME when it is unset — everywhere else.
     */
    fun userDirectory(xdgVariable: String, xdgDefault: String, macFolder: String): Path {
        val home = System.getProperty("user.home")
        if (isMac) return Path.of(home, "Library", macFolder, "BitChord")
        val base = System.getenv(xdgVariable)?.takeIf(String::isNotBlank) ?: "$home/$xdgDefault"
        return Path.of(base, "bitchord")
    }
}
