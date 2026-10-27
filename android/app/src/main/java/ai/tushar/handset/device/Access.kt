package ai.tushar.handset.device

import ai.tushar.handset.Handset
import ai.tushar.handset.tools.ToolFailure
import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings

/**
 * Access beyond the screen, which some tools need: each is off until the user turns it on, at setup or in Settings,
 * and turning it on asks Android for its permission. A tool that needs one says so when it is off.
 */
enum class Access(val title: String, val detail: String, val pref: String) {
    CONTACTS("Contacts", "Find a person's number or email by name", "access_contacts"),
    SMS("Text messages", "Read your SMS, such as a code that just arrived", "read_sms"),
    LOCATION("Location", "Know where the phone is, for directions and places nearby", "access_location"),
    PHOTOS("Photos and videos", "Find your photos and videos and show them to the agent", "access_photos"),
    SCREEN_TIME("Screen time", "See which apps you used today and for how long", "access_screen_time"),
    ;

    /** The runtime permissions Android asks for; screen time is a settings page instead */
    val permissions: List<String>
        get() = when (this) {
            CONTACTS -> listOf(Manifest.permission.READ_CONTACTS)
            SMS -> listOf(Manifest.permission.READ_SMS)
            LOCATION -> listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            PHOTOS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            } else {
                listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            SCREEN_TIME -> emptyList()
        }

    /** Whether Android has given the access. Location counts once the phone may be located while it is used. */
    fun granted(context: Context): Boolean = when (this) {
        SCREEN_TIME -> {
            val ops = context.getSystemService(AppOpsManager::class.java)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
        }
        LOCATION -> context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        else -> permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    /** The settings page for an access Android grants there, not in a prompt */
    fun settingsIntent(context: Context): Intent = when (this) {
        SCREEN_TIME -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    }

    /** Fails with words the agent can pass on when the user has not turned this access on */
    fun require(context: Context) {
        if (!Handset.instance.isAccessOn(this) || !granted(context)) {
            throw ToolFailure("$title access is off. Ask the user to turn on $title in Handset's Settings.")
        }
    }
}
