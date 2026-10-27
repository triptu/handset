package ai.tushar.handset.ui

import ai.tushar.handset.R
import ai.tushar.handset.ui.theme.AppTheme
import ai.tushar.handset.ui.theme.AppSize
import ai.tushar.handset.ui.theme.AppType
import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.drawable.toBitmap

/** A generic shape for the apps people use most, never the app's own logo */
private val APP_GLYPHS: Map<String, Int> = mapOf(
    "com.whatsapp" to R.drawable.ph_chat_circle,
    "com.Slack" to R.drawable.ph_hash,
    "com.android.chrome" to R.drawable.ph_globe,
    "in.swiggy.android" to R.drawable.ph_bowl_food,
    "com.ubercab" to R.drawable.ph_car,
    "com.google.android.gm" to R.drawable.ph_envelope_simple,
    "com.spotify.music" to R.drawable.ph_music_notes,
    "com.amazon.kindle" to R.drawable.ph_book_open,
    "com.anthropic.claude" to R.drawable.ph_asterisk,
    "com.google.android.apps.docs.editors.docs" to R.drawable.ph_file_text,
    "com.google.android.apps.tachyon" to R.drawable.ph_video_camera,
    "com.google.android.apps.maps" to R.drawable.ph_map_trifold,
    "com.strava" to R.drawable.ph_map_pin,
)

/** An app as a grey outline glyph, one size and one colour for every app; circles are for people */
@Composable
fun AppGlyph(packageName: String, size: Dp = AppSize.icon) {
    PhIcon(APP_GLYPHS[packageName] ?: R.drawable.ph_squares_four, tint = AppTheme.colors.text2, size = size)
}

/** An app's own launcher icon, for picking apps, where recognising it matters most */
@Composable
fun LauncherIcon(packageName: String, size: Dp = AppSize.icon) {
    val context = LocalContext.current
    val preview = LocalInspectionMode.current
    val icon: Drawable? = remember(packageName) { if (preview) null else runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull() }
    if (icon != null) {
        val bitmap = remember(icon) { icon.toBitmap().asImageBitmap() }
        Image(bitmap, contentDescription = null, modifier = Modifier.size(size))
    } else {
        AppGlyph(packageName, size)
    }
}

/** An agent's glyph, from the name the user gave it */
@DrawableRes
fun agentGlyph(name: String): Int {
    val lower = name.lowercase()
    return when {
        "claude code" in lower || "terminal" in lower -> R.drawable.ph_terminal_window
        "claude" in lower -> R.drawable.ph_asterisk
        "codex" in lower -> R.drawable.ph_code
        "chatgpt" in lower || "openai" in lower -> R.drawable.ph_sparkle
        else -> R.drawable.ph_plugs
    }
}

@Composable
fun AgentGlyph(name: String, size: Dp = AppSize.icon) {
    PhIcon(agentGlyph(name), tint = AppTheme.colors.text2, size = size)
}

/** A place's glyph, from its name */
@DrawableRes
fun placeGlyph(label: String): Int {
    val lower = label.lowercase()
    return when {
        "home" in lower -> R.drawable.ph_house
        "office" in lower || "work" in lower -> R.drawable.ph_briefcase
        "coffee" in lower || "cafe" in lower || "café" in lower -> R.drawable.ph_coffee
        "park" in lower || "lake" in lower || "garden" in lower -> R.drawable.ph_tree
        else -> R.drawable.ph_map_pin
    }
}

/** A person's first letter in a round tinted disc, for people without a photo */
@Composable
fun Initial(name: String, size: Dp = AppSize.avatar) {
    val colors = AppTheme.colors
    Box(Modifier.size(size).background(colors.tints.avatar, CircleShape), contentAlignment = Alignment.Center) {
        Text(name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?", style = AppType.avatar, color = colors.tints.onAvatar)
    }
}

/** A group's people glyph in the same disc */
@Composable
fun GroupAvatar(size: Dp = AppSize.avatar) {
    val colors = AppTheme.colors
    Box(Modifier.size(size).background(colors.tints.avatar, CircleShape), contentAlignment = Alignment.Center) {
        PhIcon(R.drawable.ph_users, tint = colors.tints.onAvatar, size = size / 2)
    }
}
