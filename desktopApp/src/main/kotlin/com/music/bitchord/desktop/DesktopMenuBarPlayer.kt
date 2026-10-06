package com.music.bitchord.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Toolkit
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

/**
 * The menu bar's own little player on macOS: the artwork, the track, the playhead and the transport
 * controls, opened from the menu bar icon the way every Mac music app does it, where Linux and
 * Windows get a plain menu.
 */
internal object DesktopMenuBarPlayer {

    /** Whether the menu bar icon opens this rather than a menu. */
    val enabled: Boolean get() = DesktopPlatform.isMac

    private val _anchor = MutableStateFlow<Point?>(null)

    /** Where on screen the icon was clicked, while the popover is open. */
    val anchor: StateFlow<Point?> = _anchor.asStateFlow()

    private val _playback = MutableStateFlow<StateFlow<DesktopPlaybackState>?>(null)
    private var engine: DesktopPlaybackEngine? = null

    /** When the popover last closed by losing focus — see [toggle]. */
    @Volatile
    private var dismissedAt = 0L

    fun attach(playbackEngine: DesktopPlaybackEngine) {
        engine = playbackEngine
        _playback.value = playbackEngine.state
    }

    /**
     * Opens the popover under [at], or closes it.
     *
     * Clicking the icon while the popover is open takes focus from it first, which has already
     * closed it by the time the click arrives; reopening it then would make the icon unable to close
     * what it opened.
     */
    fun toggle(at: Point) {
        if (_anchor.value != null) {
            dismiss()
        } else if (System.currentTimeMillis() - dismissedAt > REOPEN_GUARD_MS) {
            _anchor.value = at
        }
    }

    fun dismiss() {
        if (_anchor.value == null) return
        dismissedAt = System.currentTimeMillis()
        _anchor.value = null
    }

    internal fun seekTo(positionMs: Long) {
        engine?.seekTo(positionMs)
    }

    internal val playback: StateFlow<StateFlow<DesktopPlaybackState>?> = _playback.asStateFlow()

    private const val REOPEN_GUARD_MS = 300L
}

/** The popover itself, alongside the main window in the application's composition. */
@Composable
internal fun DesktopMenuBarPopover() {
    if (!DesktopMenuBarPlayer.enabled) return
    val anchor by DesktopMenuBarPlayer.anchor.collectAsState()
    val at = anchor ?: return
    val state = rememberWindowState(
        position = popoverPosition(at),
        size = DpSize(POPOVER_WIDTH.dp, POPOVER_HEIGHT.dp),
    )
    Window(
        onCloseRequest = DesktopMenuBarPlayer::dismiss,
        state = state,
        title = "BitChord",
        undecorated = true,
        transparent = true,
        resizable = false,
        alwaysOnTop = true,
    ) {
        val popover = window
        LaunchedEffect(popover) {
            popover.rootPane.putClientProperty("Window.shadow", true)
            popover.addWindowFocusListener(
                object : WindowAdapter() {
                    override fun windowLostFocus(event: WindowEvent) = DesktopMenuBarPlayer.dismiss()
                },
            )
            popover.toFront()
            popover.requestFocus()
        }
        PopoverContent()
    }
}

/** Centred under the icon, just below the menu bar, and kept on the screen it was clicked on. */
private fun popoverPosition(at: Point): WindowPosition {
    val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        .map { it.defaultConfiguration }
        .firstOrNull { it.bounds.contains(at) }
        ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
    val bounds = screen.bounds
    val menuBar = Toolkit.getDefaultToolkit().getScreenInsets(screen).top
    val x = (at.x - POPOVER_WIDTH / 2)
        .coerceIn(bounds.x + EDGE_MARGIN, bounds.x + bounds.width - POPOVER_WIDTH - EDGE_MARGIN)
    return WindowPosition(x.dp, (bounds.y + menuBar + EDGE_MARGIN).dp)
}

@Composable
private fun PopoverContent() {
    val source by DesktopMenuBarPlayer.playback.collectAsState()
    val playback = source?.collectAsState()?.value ?: DesktopPlaybackState()
    val song = playback.song
    val palette = rememberDesktopArtworkPalette(song?.thumbnailUrl)
    val surface = Color(0xFF17171C)
    Column(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    listOf(lerp(surface, palette.primary, 0.45f), lerp(surface, palette.secondary, 0.12f)),
                ),
            )
            .border(0.5.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .clickable { DesktopTrayMenu.openPlayer(); DesktopMenuBarPlayer.dismiss() },
            contentAlignment = Alignment.Center,
        ) {
            if (song != null) {
                DesktopArtwork(song.thumbnailUrl, Modifier.fillMaxSize(), px = ART_PX)
            } else {
                Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.06f)))
                Icon(Icons.Rounded.MusicNote, null, Modifier.size(64.dp), tint = Color.White.copy(alpha = 0.35f))
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            song?.title ?: DesktopStrings["d_nothing_playing", "Nothing playing"],
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            song?.artist ?: DesktopStrings["d_pick_something", "Pick something in BitChord"],
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(12.dp))
        Playhead(playback)
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Control(Icons.Rounded.SkipPrevious, DesktopStrings["widget_previous", "Previous"], 30, song != null) {
                DesktopTrayMenu.previous()
            }
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (song != null) 1f else 0.35f))
                    .clickable(enabled = song != null) { DesktopTrayMenu.playPause() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (playback.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (playback.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(30.dp),
                    tint = surface,
                )
            }
            Control(Icons.Rounded.SkipNext, DesktopStrings["widget_next", "Next"], 30, song != null) {
                DesktopTrayMenu.next()
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            FooterAction(DesktopStrings["widget_open_bitchord", "Open BitChord"]) {
                DesktopTrayMenu.openPlayer()
                DesktopMenuBarPlayer.dismiss()
            }
            FooterAction(DesktopStrings["d_quit_bitchord", "Quit BitChord"]) { DesktopTrayMenu.quit() }
        }
    }
}

/** The bar and its two times; clicking the bar seeks. */
@Composable
private fun Playhead(playback: DesktopPlaybackState) {
    // The engine reports the playhead when it moves rather than on a clock, so it is carried
    // forward here between reports while the track plays.
    val position by produceState(playback.positionMs, playback) {
        while (true) {
            val elapsed = if (playback.isPlaying && !playback.awaitingAudio && playback.positionSampledAtNanos > 0) {
                (System.nanoTime() - playback.positionSampledAtNanos) / 1_000_000
            } else {
                0L
            }
            value = (playback.positionMs + elapsed).coerceIn(0L, playback.durationMs.coerceAtLeast(0L))
            delay(250)
        }
    }
    val duration = playback.durationMs
    val fraction = if (duration > 0) position.toFloat() / duration else 0f
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(14.dp)
            .pointerInput(duration) {
                detectTapGestures { offset ->
                    if (duration > 0) {
                        DesktopMenuBarPlayer.seekTo((offset.x / size.width * duration).toLong())
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)))
        Box(
            Modifier
                .width(maxWidth * fraction.coerceIn(0f, 1f))
                .height(4.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(clock(position), color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
        Text(
            if (duration > 0) "-" + clock(duration - position) else "",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun Control(icon: ImageVector, label: String, sizeDp: Int, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size((sizeDp + 14).dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            modifier = Modifier.size(sizeDp.dp),
            tint = Color.White.copy(alpha = if (enabled) 1f else 0.35f),
        )
    }
}

@Composable
private fun FooterAction(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Text(label, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
    }
}

private fun clock(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private const val POPOVER_WIDTH = 300
private const val POPOVER_HEIGHT = 496
private const val EDGE_MARGIN = 6
private const val ART_PX = 600
