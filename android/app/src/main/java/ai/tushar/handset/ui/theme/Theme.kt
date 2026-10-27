package ai.tushar.handset.ui.theme

import ai.tushar.handset.R
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Every colour, size, spacing and duration the screens use. A slightly cool neutral with one muted ink-blue accent,
 * for links, the selected tab, Send and the selected date. Light and dark follow the system.
 */
@Immutable
data class AppColors(
    val background: Color,
    /** The detail pane beside the list on a wide screen */
    val pane: Color,
    /** Fields and the track of an off switch */
    val surface: Color,
    /** Sheets and dialogs */
    val raised: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val press: Color,
    /** Thin rules between parts of a page */
    val rule: Color,
    val accent: Color,
    val onAccent: Color,
    /** An on switch: the thumb is always the lighter part */
    val switchOn: Color,
    val switchOnThumb: Color,
    val live: Color,
    val danger: Color,
    val onDanger: Color,
    val scrim: Color,
    val tints: AppTints,
    val isDark: Boolean,
)

/** A person's initial: the one round, tinted shape, so people stand apart from apps */
@Immutable
data class AppTints(
    val avatar: Color,
    val onAvatar: Color,
)

val LightColors = AppColors(
    background = Color(0xFFF2F3F4),
    pane = Color(0xFFE9EBEE),
    surface = Color(0xFFE6E8EB),
    raised = Color(0xFFFFFFFF),
    text = Color(0xFF1A1C1F),
    text2 = Color(0xFF565B62),
    text3 = Color(0xFF60666D),
    press = Color(0x121A1C1F),
    rule = Color(0xFFD9DCE0),
    accent = Color(0xFF34558A),
    onAccent = Color(0xFFFFFFFF),
    switchOn = Color(0xFF34558A),
    switchOnThumb = Color(0xFFFFFFFF),
    live = Color(0xFF2F8A4C),
    danger = Color(0xFFA8402F),
    onDanger = Color(0xFFFFFFFF),
    scrim = Color(0x52141210),
    tints = AppTints(
        avatar = Color(0xFFE1E5EB),
        onAvatar = Color(0xFF3D4A5E),
    ),
    isDark = false,
)

val DarkColors = AppColors(
    background = Color(0xFF17191C),
    pane = Color(0xFF1F2226),
    surface = Color(0xFF23262A),
    raised = Color(0xFF2A2D32),
    text = Color(0xFFE7E9EC),
    text2 = Color(0xFFA3A8AF),
    text3 = Color(0xFF8F959C),
    press = Color(0x14E7E9EC),
    rule = Color(0xFF33373C),
    accent = Color(0xFFA9C1EA),
    onAccent = Color(0xFF13243F),
    switchOn = Color(0xFF4A6B9E),
    switchOnThumb = Color(0xFFF2F5FA),
    live = Color(0xFF6BC287),
    danger = Color(0xFFE8907F),
    onDanger = Color(0xFF2A120C),
    scrim = Color(0x80000000),
    tints = AppTints(
        avatar = Color(0xFF2E3540),
        onAvatar = Color(0xFFC3CFE0),
    ),
    isDark = true,
)

private val Sans = FontFamily(
    Font(R.font.google_sans_flex_regular, FontWeight.Normal),
    Font(R.font.google_sans_flex_medium, FontWeight.Medium),
    Font(R.font.google_sans_flex_semibold, FontWeight.SemiBold),
)

private fun sans(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = Sans, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight)

/** The type scale */
object AppType {
    /** A page's title, such as Settings or an agent's name */
    val title = sans(20, 26, FontWeight.SemiBold)

    /** The day in the Days header, and a sheet's month */
    val heading = sans(16, 22, FontWeight.SemiBold)

    /** A moment's page title, such as the article you read */
    val pageTitle = sans(22, 29, FontWeight.SemiBold)
    val sheetTitle = sans(20, 26, FontWeight.SemiBold)
    val rowTitle = sans(16, 22, FontWeight.Medium)
    val rowText = sans(14, 20)
    /** A place and its span, lighter than the moments under it */
    val place = sans(13, 18, FontWeight.Medium).copy(fontFeatureSettings = "tnum")
    /** What was read in a moment, a step under a row's title */
    val body = sans(15, 24)
    val bodySmall = sans(15, 22)
    val label = sans(13, 18, FontWeight.Medium)
    val meta = sans(13, 18)
    val button = sans(15, 20, FontWeight.SemiBold)
    val avatar = sans(15, 20, FontWeight.SemiBold)
    val tab = sans(12, 16, FontWeight.Medium)
    val tabSelected = sans(12, 16, FontWeight.SemiBold)
    val railTab = sans(11, 14, FontWeight.Medium)
    val railTabSelected = sans(11, 14, FontWeight.SemiBold)
    val calendarDay = sans(15, 20).copy(fontFeatureSettings = "tnum")

    /** A row's time, such as 9:25 am, with digits that line up */
    val time = sans(13, 22).copy(fontFeatureSettings = "tnum")
}

/** Sizes and spacing in dp */
object AppSize {
    val pageX = 20.dp
    val pageMaxWidth = 600.dp
    val headerHeight = 56.dp
    val touch = 48.dp
    val icon = 24.dp
    val iconSmall = 20.dp
    val iconPlace = 16.dp
    val rowMinHeight = 60.dp
    val rowSingleMinHeight = 56.dp
    val rowPadX = 12.dp
    val rowPadY = 10.dp
    val rowRadius = 14.dp
    val rowGap = 14.dp
    val lead = 36.dp
    val avatar = 36.dp
    val sectionTop = 18.dp
    val placeTop = 12.dp
    val placeHeight = 32.dp
    val actingHeight = 48.dp
    val navHeight = 64.dp
    val railWidth = 88.dp
    val railItem = 72.dp
    val listWidth = 380.dp
    val paneRadius = 24.dp
    val sheetRadius = 28.dp
    val dialogWidth = 520.dp
    val calendarCell = 48.dp
    val field = 48.dp
    val switchWidth = 52.dp
    val switchHeight = 32.dp
    val switchThumb = 24.dp
    val switchThumbOff = 16.dp

    /** Screens at least this wide show a rail and a detail pane beside the list */
    val wideFrom = 600.dp
}

/** Durations in ms. Things come in fast and leave a little slower. */
object AppMotion {
    const val fast = 150
    const val slow = 220
}

val LocalAppColors = staticCompositionLocalOf { LightColors }

/** The colour tokens, as `AppTheme.colors.text` */
object AppTheme {
    val colors: AppColors
        @Composable @ReadOnlyComposable get() = LocalAppColors.current
}

@Composable
fun HandsetTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    val base = if (dark) darkColorScheme() else lightColorScheme()
    // Material pieces the app still uses (the text cursor, selection handles, ripples) take these
    val scheme = base.copy(
        primary = colors.accent,
        onPrimary = colors.onAccent,
        background = colors.background,
        onBackground = colors.text,
        surface = colors.background,
        onSurface = colors.text,
        surfaceContainerLow = colors.raised,
        surfaceVariant = colors.surface,
        onSurfaceVariant = colors.text2,
        error = colors.danger,
    )
    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
