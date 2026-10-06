package com.music.bitchord.desktop

import java.awt.EventQueue
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/** The tray icon on the platforms that have no StatusNotifierItem. */
internal object DesktopAwtTray {

    private val lock = Any()
    private var icon: TrayIcon? = null
    private var image: BufferedImage? = null
    private var tooltip: String = APP_NAME

    /** Whether this platform has a tray AWT can reach. */
    fun isSupported(): Boolean = runCatching { SystemTray.isSupported() }.getOrDefault(false)

    /** The logo, rasterised. */
    fun setIcon(rendered: BufferedImage?) {
        if (rendered == null) return
        synchronized(lock) {
            image = rendered
            icon?.image = rendered
        }
    }

    fun start(onActivate: () -> Unit) {
        if (!isSupported()) return
        EventQueue.invokeLater {
            synchronized(lock) {
                if (icon != null) return@invokeLater
                val tray = runCatching { SystemTray.getSystemTray() }.getOrNull() ?: return@invokeLater
                val art = image ?: blank(tray.trayIconSize.width.coerceAtLeast(16))
                // On macOS the icon opens the menu bar player rather than a menu — see
                // [DesktopMenuBarPlayer].
                val menu = if (DesktopMenuBarPlayer.enabled) null else DesktopTrayMenu.awtPopup()
                val created = TrayIcon(art, tooltip, menu).apply {
                    isImageAutoSize = true
                    if (DesktopMenuBarPlayer.enabled) {
                        addMouseListener(
                            object : MouseAdapter() {
                                override fun mousePressed(event: MouseEvent) {
                                    val at = event.locationOnScreen
                                    EventQueue.invokeLater { DesktopMenuBarPlayer.toggle(at) }
                                }
                            },
                        )
                    }
                    // Windows opens the menu on right-click itself; the left click is the shortcut
                    // back to the window, which is what every media player there does.
                    addActionListener { EventQueue.invokeLater(onActivate) }
                }
                runCatching { tray.add(created) }
                    .onSuccess { icon = created }
                    .onFailure { DesktopTrackLog.log("tray unavailable: ${it.message}") }
            }
        }
    }

    fun stop() {
        EventQueue.invokeLater {
            synchronized(lock) {
                val current = icon ?: return@invokeLater
                runCatching { SystemTray.getSystemTray().remove(current) }
                icon = null
            }
        }
    }

    /**
     * Re-labels the icon, and rebuilds the menu because one of its entries is Play or Pause
     * depending on this.
     */
    fun update(title: String?, isPlaying: Boolean) {
        val text = title?.let { "$APP_NAME — $it" } ?: APP_NAME
        EventQueue.invokeLater {
            synchronized(lock) {
                tooltip = text
                val current = icon ?: return@invokeLater
                current.toolTip = text
                if (!DesktopMenuBarPlayer.enabled) current.popupMenu = DesktopTrayMenu.awtPopup()
            }
        }
    }

    /** PNG bytes to an image AWT can hand the tray. */
    fun decode(png: ByteArray): BufferedImage? =
        runCatching { ImageIO.read(ByteArrayInputStream(png)) }.getOrNull()

    /** A placeholder while the logo is still being rasterised. */
    private fun blank(size: Int) = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)

    private const val APP_NAME = "BitChord"
}
