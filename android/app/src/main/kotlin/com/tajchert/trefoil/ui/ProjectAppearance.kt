package com.tajchert.trefoil.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * A project's chosen look: one glyph and one colour, both stored per project id. Absent means the tile keeps its
 * deterministic initial-and-palette look, so existing projects are unchanged until the user picks something.
 */
internal data class ProjectAppearance(val icon: ProjectIcon? = null, val color: ProjectColor? = null)

/**
 * The glyph set offered for projects. Stroke icons drawn on the same 24x24 grid as [TfIcons], so they read as one family
 * with the rest of the app. The id is the stable storage key: never rename one, add a new entry instead.
 */
internal enum class ProjectIcon(val id: String, val paths: List<String>) {
    Folder("folder", listOf("M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")),
    Home("home", listOf("M3 10l9-7 9 7v10a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z")),
    Terminal("terminal", listOf("M5 8l4 4-4 4M12 16h7")),
    Branch("branch", listOf("M6 6v12M6 12h6a4 4 0 0 0 4-4V6", "M4 4h4v4H4zM16 4h4v4h-4zM4 16h4v4H4z")),
    Bolt("bolt", listOf("M13 2L4 14h7l-1 8 9-12h-7z")),
    Star("star", listOf("M12 3l2.9 5.9 6.5.9-4.7 4.6 1.1 6.5L12 17.8 6.2 20.9l1.1-6.5L2.6 9.8l6.5-.9z")),
    Shield("shield", listOf("M12 3L5 6v5c0 4.5 3 8.5 7 10 4-1.5 7-5.5 7-10V6z")),
    Globe("globe", listOf("M21 12a9 9 0 1 1-18 0a9 9 0 0 1 18 0z", "M3 12h18M12 3c1.8 1.6 3 5 3 9s-1.2 7.4-3 9c-1.8-1.6-3-5-3-9s1.2-7.4 3-9z")),
    Cube("cube", listOf("M12 3l8 4.5v9L12 21l-8-4.5v-9z", "M4 7.5l8 4.5 8-4.5M12 21v-9")),
    Server("server", listOf("M4 5h16v5H4zM4 14h16v5H4z", "M8 7.5h.01M8 16.5h.01")),
    Database("database", listOf("M4 6c0-2 16-2 16 0v12c0 2-16 2-16 0z", "M4 6c0 2 16 2 16 0M4 12c0 2 16 2 16 0")),
    Cloud("cloud", listOf("M7 18a4 4 0 0 1 0-8a6 6 0 0 1 11.5 1.5A3.5 3.5 0 0 1 18 18z")),
    Game("game", listOf("M6 7h12a4 4 0 0 1 4 4v3a3 3 0 0 1-3 3c-2 0-3-2-5-2h-4c-2 0-3 2-5 2a3 3 0 0 1-3-3v-3a4 4 0 0 1 4-4z", "M7 12h4M9 10v4M15.5 11h.01M17.5 13h.01")),
    Music("music", listOf("M9 18V5l11-2v13", "M9 18a3 3 0 1 1-6 0a3 3 0 0 1 6 0zM20 16a3 3 0 1 1-6 0a3 3 0 0 1 6 0z")),
    Camera("camera", listOf("M3 8a2 2 0 0 1 2-2h2l2-2h6l2 2h2a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z", "M16 13a4 4 0 1 1-8 0a4 4 0 0 1 8 0z")),
    Map("map", listOf("M3 6l6-3 6 3 6-3v15l-6 3-6-3-6 3z", "M9 3v15M15 6v15")),
    Book("book", listOf("M4 4h9a3 3 0 0 1 3 3v13H7a3 3 0 0 1-3-3z", "M20 4h-2v16h2z")),
    Chart("chart", listOf("M4 20V10M10 20V4M16 20v-7M22 20H2")),
    Wrench("wrench", listOf("M14.7 6.3a4 4 0 0 0 5 5l-9.6 9.6a2.1 2.1 0 0 1-3-3z")),
    Rocket("rocket", listOf("M12 3c3 1.5 5 4.5 5 8l-3 3H10l-3-3c0-3.5 2-6.5 5-8z", "M7 14l-2 5 5-2M17 14l2 5-5-2")),
    Robot("robot", listOf("M6 8h12a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z", "M12 3v3M9 13h.01M15 13h.01M9 17h6")),
    Sparkle("sparkle", listOf("M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z")),
    Palette("palette", listOf("M12 3a9 9 0 1 0 0 18c1.5 0 2-1 2-2s-.5-2-2-2h-1a3 3 0 0 1 0-6h4a3 3 0 0 0 3-3c0-3-3-5-6-5z", "M8 9h.01M12 7h.01M16 9h.01")),
    File("file", listOf("M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z", "M14 3v5h5")),
    Image("image", listOf("M4 5h16v14H4z", "M4 15l4-4 3 3 3-3 6 6", "M10 9a1.6 1.6 0 1 1-3.2 0a1.6 1.6 0 0 1 3.2 0z")),
    Megaphone("megaphone", listOf("M3 11v2a1 1 0 0 0 1 1h3l8 5V5L7 10H4a1 1 0 0 0-1 1z", "M18 9a3 3 0 0 1 0 6")),
    Flask("flask", listOf("M9 3h6M10 3v5L4 19a2 2 0 0 0 1.8 3h12.4A2 2 0 0 0 20 19L14 8V3")),
    Puzzle("puzzle", listOf("M4 8h3a2 2 0 1 1 4 0h3v3a2 2 0 1 0 0 4v3h-3a2 2 0 1 0-4 0H4z")),
    Mail("mail", listOf("M3 6h18v12H3z", "M3 7l9 6 9-6")),
    Phone("phone", listOf("M6 3h3l2 5-2.5 1.5a11 11 0 0 0 6 6L16 13l5 2v3a2 2 0 0 1-2 2A16 16 0 0 1 4 5a2 2 0 0 1 2-2z")),
    Key("key", listOf("M15 3a6 6 0 0 0-5.6 8.1L3 17.5V21h3.5l6.4-6.4A6 6 0 1 0 15 3z", "M17 7h.01")),
    Tag("tag", listOf("M3 12V4a1 1 0 0 1 1-1h8l9 9-9 9z", "M7.5 7.5h.01")),
    ;

    val index: Int get() = ordinal

    fun vector(): ImageVector = vectors[index]

    companion object {
        /** Built once and shared: the picker draws every glyph at the same time. */
        private val vectors: List<ImageVector> by lazy {
            entries.map { icon -> ImageVector.Builder("project.${icon.id}", 24.dp, 24.dp, 24f, 24f).apply {
                icon.paths.forEach { d ->
                    addPath(PathParser().parsePathString(d).toNodes(), stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
                        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = null)
                }
            }.build() }
        }

        fun byId(id: String?): ProjectIcon? = id?.let { stored -> entries.firstOrNull { it.id == stored } }
    }
}

/**
 * The colours a project can be given. Each is one hue with a light and a dark variant that keep the same identity, so a
 * project looks the same in both brightnesses. Stored by [id], never by ordinal.
 */
internal enum class ProjectColor(
    val id: String,
    val light: Long,
    val dark: Long,
    val lightInk: Long,
    val darkInk: Long,
) {
    Amber("amber", 0xFFFBEDCB, 0xFF3A2D10, 0xFF7A5200, 0xFFF3C35E),
    Pink("pink", 0xFFFADFEC, 0xFF3A1B2B, 0xFF992A60, 0xFFF49CC6),
    Teal("teal", 0xFFD6F2EE, 0xFF0F332E, 0xFF0B6A5E, 0xFF6ED8C7),
    Blue("blue", 0xFFDFE7FB, 0xFF17264B, 0xFF2A4FA6, 0xFF94B6FF),
    Violet("violet", 0xFFEBE6FC, 0xFF2A2342, 0xFF5A3EB5, 0xFFBBA8FF),
    Slate("slate", 0xFFE8ECEA, 0xFF262B2B, 0xFF3E4745, 0xFFC9D0CE),
    Green("green", 0xFFDCF0E0, 0xFF14311C, 0xFF1D6A33, 0xFF8EDB9B),
    Orange("orange", 0xFFF6E6D3, 0xFF33240F, 0xFF7E4F1C, 0xFFEDB57E),
    Red("red", 0xFFFADEDC, 0xFF3A1A18, 0xFFA8332B, 0xFFF0A099),
    Cyan("cyan", 0xFFD9F0F7, 0xFF0F2C35, 0xFF17606F, 0xFF7FD1E8),
    Magenta("magenta", 0xFFF6E0FA, 0xFF33143A, 0xFF6E2478, 0xFFE0A3FF),
    Olive("olive", 0xFFEDF0D8, 0xFF2B3016, 0xFF5C6320, 0xFFC6D178),
    ;

    fun background(dark: Boolean): Color = Color(if (dark) this.dark else this.light)

    companion object {
        fun byId(id: String?): ProjectColor? = id?.let { stored -> entries.firstOrNull { it.id == stored } }
    }
}
