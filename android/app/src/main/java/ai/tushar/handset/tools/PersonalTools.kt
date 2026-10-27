package ai.tushar.handset.tools

import ai.tushar.handset.device.Access
import ai.tushar.handset.device.NotificationWatcher
import android.annotation.SuppressLint
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.util.Base64
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume

/**
 * Tools that read the user's own things through Android's APIs: contacts, location, photos and videos, screen time,
 * and what's playing. Each needs an [Access] the user turns on, except media control, which comes with notification
 * access.
 */
class PersonalTools(private val context: Context) {
    private val resolver get() = context.contentResolver

    val all: List<PhoneTool> = listOf(
        PhoneTool(
            slug = "find_contact",
            concurrent = true,
            description = "Find people in the phone's contacts by name or number, with their numbers and emails. Use the number in " +
                "a tel:, sms: or wa.me link. Needs Contacts access.",
            inputSchema = objectSchema(
                "query" to prop("string", "A name, part of one, or part of a number"),
                "limit" to prop("integer", "How many people to return; 5 by default, 20 at most"),
                required = listOf("query"),
            ),
        ) { args ->
            Access.CONTACTS.require(context)
            ToolOutput(findContacts(args.requireString("query"), (args.int("limit") ?: 5).coerceIn(1, 20)))
        },
        PhoneTool(
            slug = "get_location",
            concurrent = true,
            description = "Where the phone is: coordinates and accuracy, the address, the Wi-Fi network's name, and a Maps link. " +
                "Use it before directions or a search for places nearby. Needs Location access.",
            inputSchema = objectSchema(),
        ) {
            Access.LOCATION.require(context)
            ToolOutput(location())
        },
        PhoneTool(
            slug = "list_media",
            concurrent = true,
            description = "List photos, videos or screenshots on the phone, newest first, each with an id for view_media and " +
                "share_media. Needs Photos and videos access.",
            inputSchema = objectSchema(
                "kind" to prop("string", "What to list; photos by default", enum = listOf("photos", "screenshots", "videos", "all")),
                "since_minutes" to prop("integer", "Only those from the last this many minutes"),
                "name" to prop("string", "Part of the file name"),
                "limit" to prop("integer", "How many; 10 by default, 50 at most"),
            ),
        ) { args ->
            Access.PHOTOS.require(context)
            ToolOutput(listMedia(args))
        },
        PhoneTool(
            slug = "view_media",
            concurrent = true,
            description = "See a photo or a video's frame from list_media, as an image. Needs Photos and videos access.",
            inputSchema = objectSchema("id" to prop("string", "An id from list_media, such as photo:1234"), required = listOf("id")),
        ) { args ->
            Access.PHOTOS.require(context)
            val item = mediaUri(args.requireString("id"))
            ToolOutput("${args.requireString("id")}, as an image", listOf(ToolOutput.Image(preview(item), "image/jpeg")))
        },
        PhoneTool(
            slug = "share_media",
            concurrent = true,
            description = "Get photos from list_media as images, sharper than view_media, so they can be read closely or " +
                "attached elsewhere. Up to $MAX_SHARE at a time, each at most $SHARE_SIDE px on its long side. A video comes as one " +
                "frame. Needs Photos and videos access.",
            inputSchema = objectSchema(
                "ids" to stringListProp("Ids from list_media"),
                required = listOf("ids"),
            ),
        ) { args ->
            Access.PHOTOS.require(context)
            val ids = args.strings("ids")
            if (ids.isEmpty()) throw ToolFailure("Give at least one id")
            if (ids.size > MAX_SHARE) throw ToolFailure("Give at most $MAX_SHARE ids; ask for the rest in another call")
            val images = ids.map { ToolOutput.Image(preview(mediaUri(it), SHARE_SIDE, SHARE_QUALITY), "image/jpeg") }
            ToolOutput("${ids.joinToString()}, as ${if (ids.size == 1) "an image" else "images, in that order"}", images)
        },
        PhoneTool(
            slug = "screen_time",
            concurrent = true,
            description = "Which apps the phone was used in, and for how long, today or yesterday, or the most recent apps in order. " +
                "Needs Screen time access.",
            inputSchema = objectSchema(
                "day" to prop("string", "Which day; today by default", enum = listOf("today", "yesterday")),
                "app" to prop("string", "Only this app, by name or package"),
                "recent" to prop("boolean", "List the last apps opened, with times, instead of totals"),
            ),
        ) { args ->
            Access.SCREEN_TIME.require(context)
            ToolOutput(if (args.boolean("recent") == true) recentApps() else screenTime(args.string("day") == "yesterday", args.string("app")))
        },
        PhoneTool(
            slug = "now_playing",
            concurrent = true,
            description = "What's playing, in any app, and control it: play, pause, next or previous. With no action, it says what's " +
                "playing. Needs notification access.",
            inputSchema = objectSchema(
                "action" to prop("string", "What to do", enum = listOf("status", "play", "pause", "toggle", "next", "previous")),
            ),
        ) { args -> ToolOutput(media(args.string("action") ?: "status")) },
    )

    // Contacts

    private fun findContacts(query: String, limit: Int): String {
        val ids = LinkedHashSet<Long>()
        val digits = query.filter { it.isDigit() }
        val byName = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(query))
        resolver.query(byName, arrayOf(ContactsContract.Contacts._ID), null, null, null)?.use { cursor ->
            while (ids.size < limit && cursor.moveToNext()) ids.add(cursor.getLong(0))
        }
        // Part of a number, in any format: the normalized number holds only digits and a leading +
        if (ids.size < limit && digits.length >= MIN_NUMBER_DIGITS) {
            resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID),
                "${ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER} LIKE ? OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?",
                arrayOf("%$digits%", "%$query%"),
                null,
            )?.use { cursor ->
                while (ids.size < limit && cursor.moveToNext()) ids.add(cursor.getLong(0))
            }
        }
        if (ids.isEmpty()) return "No contact matches \"$query\""
        return ids.joinToString("\n\n") { id -> describeContact(id) }
    }

    private fun describeContact(id: Long): String {
        var name = ""
        val phones = ArrayList<String>()
        val emails = ArrayList<String>()
        val projection = arrayOf(
            ContactsContract.Data.MIMETYPE,
            ContactsContract.Data.DISPLAY_NAME,
            ContactsContract.Data.DATA1,
            ContactsContract.Data.DATA2,
            ContactsContract.Data.DATA3,
        )
        resolver.query(ContactsContract.Data.CONTENT_URI, projection, "${ContactsContract.Data.CONTACT_ID} = ?", arrayOf(id.toString()), null)?.use { cursor ->
            while (cursor.moveToNext()) {
                name = cursor.getString(1) ?: name
                val value = cursor.getString(2) ?: continue
                when (cursor.getString(0)) {
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> {
                        val type = ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.resources, cursor.getInt(3), cursor.getString(4))
                        if (phones.none { phone -> phone.filter(Char::isDigit) == value.filter(Char::isDigit) }) phones.add("$value ($type)")
                    }
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> if (value !in emails) emails.add(value)
                }
            }
        }
        return buildString {
            append(name.ifEmpty { "(no name)" })
            phones.forEach { append("\nphone: ").append(it) }
            emails.forEach { append("\nemail: ").append(it) }
            if (phones.isEmpty() && emails.isEmpty()) append("\n(no number or email)")
        }
    }

    // Location

    @SuppressLint("MissingPermission")
    private suspend fun location(): String {
        val manager = context.getSystemService(LocationManager::class.java)
        if (!manager.isLocationEnabled) throw ToolFailure("Location is off on the phone. Ask the user to turn it on in Quick Settings.")
        val provider = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && manager.hasProvider(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> LocationManager.GPS_PROVIDER
        }
        // A provider the app may not read, as GPS with coarse access only, throws rather than answers null
        fun lastKnown() = manager.getProviders(true).mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.elapsedRealtimeNanos }
        // A fix from the last few minutes answers at once; otherwise the phone is asked for a new one
        val recent = lastKnown()?.takeIf { ageMs(it) < RECENT_FIX_MS }
        val fix = recent ?: withTimeoutOrNull(FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val cancel = CancellationSignal()
                continuation.invokeOnCancellation { cancel.cancel() }
                manager.getCurrentLocation(provider, cancel, context.mainExecutor) { continuation.resume(it) }
            }
        } ?: lastKnown()
            ?: throw ToolFailure("The phone could not find its location just now. Try again in a moment.")
        val address = address(fix)
        val wifi = wifiName()
        return buildString {
            append("Location: %.6f, %.6f (within %d m), from %s ago".format(fix.latitude, fix.longitude, fix.accuracy.toInt(), age(fix)))
            address?.let { append("\nAddress: ").append(it) }
            wifi?.let { append("\nWi-Fi: ").append(it) }
            append("\nMaps: https://www.google.com/maps/search/?api=1&query=%.6f,%.6f".format(fix.latitude, fix.longitude))
        }
    }

    private fun ageMs(location: Location): Long = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000

    private fun age(location: Location): String {
        val seconds = ageMs(location) / 1000
        return if (seconds < 90) "$seconds s" else "${seconds / 60} min"
    }

    private suspend fun address(location: Location): String? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context)
        val found = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            withTimeoutOrNull(GEOCODE_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    geocoder.getFromLocation(location.latitude, location.longitude, 1) { continuation.resume(it.firstOrNull()) }
                }
            }
        } else {
            @Suppress("DEPRECATION")
            withContext(Dispatchers.IO) { runCatching { geocoder.getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull() }.getOrNull() }
        }
        return found?.getAddressLine(0)
    }

    @Suppress("DEPRECATION")
    private fun wifiName(): String? {
        val ssid = runCatching { context.getSystemService(WifiManager::class.java).connectionInfo?.ssid }.getOrNull() ?: return null
        return ssid.trim('"').takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    }

    // Photos and videos

    private class Media(val id: String, val uri: Uri, val name: String, val takenMs: Long, val size: Long, val width: Int, val height: Int, val durationMs: Long?)

    private fun listMedia(args: ToolArgs): String {
        val kind = args.string("kind") ?: "photos"
        val limit = (args.int("limit") ?: 10).coerceIn(1, 50)
        val since = args.int("since_minutes")?.let { System.currentTimeMillis() / 1000 - it * 60L }
        val name = args.string("name")
        val items = ArrayList<Media>()
        if (kind != "videos") items += queryMedia(photo = true, since, name, screenshotsOnly = kind == "screenshots", limit)
        if (kind == "videos" || kind == "all") items += queryMedia(photo = false, since, name, screenshotsOnly = false, limit)
        val shown = items.sortedByDescending { it.takenMs }.take(limit)
        if (shown.isEmpty()) return "Nothing matches"
        val time = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm").withZone(ZoneId.systemDefault())
        return shown.joinToString("\n") { item ->
            val duration = item.durationMs?.let { " · ${it / 1000} s" }.orEmpty()
            "${item.id} · ${time.format(Instant.ofEpochMilli(item.takenMs))} · ${item.name} · ${item.width}x${item.height}$duration · ${item.size / 1024} KB"
        }
    }

    private fun queryMedia(photo: Boolean, sinceSeconds: Long?, name: String?, screenshotsOnly: Boolean, limit: Int): List<Media> {
        val collection = if (photo) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.DATE_TAKEN)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.WIDTH)
            add(MediaStore.MediaColumns.HEIGHT)
            if (!photo) add(MediaStore.Video.VideoColumns.DURATION)
        }.toTypedArray()
        val where = ArrayList<String>()
        val values = ArrayList<String>()
        sinceSeconds?.let { where.add("${MediaStore.MediaColumns.DATE_ADDED} >= ?"); values.add(it.toString()) }
        name?.let { where.add("${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"); values.add("%$it%") }
        if (screenshotsOnly) { where.add("${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"); values.add("%Screenshots%") }
        val items = ArrayList<Media>()
        resolver.query(collection, projection, where.joinToString(" AND ").ifEmpty { null }, values.toTypedArray(), "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cursor ->
            while (items.size < limit && cursor.moveToNext()) {
                val rowId = cursor.getLong(0)
                val taken = cursor.getLong(2).takeIf { it > 0 } ?: (cursor.getLong(3) * 1000)
                items.add(
                    Media(
                        id = "${if (photo) "photo" else "video"}:$rowId",
                        uri = ContentUris.withAppendedId(collection, rowId),
                        name = cursor.getString(1).orEmpty(),
                        takenMs = taken,
                        size = cursor.getLong(4),
                        width = cursor.getInt(5),
                        height = cursor.getInt(6),
                        durationMs = if (photo) null else cursor.getLong(7),
                    ),
                )
            }
        }
        return items
    }

    private fun mediaUri(id: String): Uri {
        val (kind, number) = id.split(":").takeIf { it.size == 2 } ?: throw ToolFailure("Use an id from list_media, such as photo:1234")
        val rowId = number.toLongOrNull() ?: throw ToolFailure("Use an id from list_media, such as photo:1234")
        val collection = when (kind) {
            "photo" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else -> throw ToolFailure("Use an id from list_media, such as photo:1234")
        }
        return ContentUris.withAppendedId(collection, rowId)
    }

    /** A JPEG of a photo, or of a video's frame, no larger than [side] on its long side */
    private suspend fun preview(uri: Uri, side: Int = PREVIEW_SIDE, quality: Int = JPEG_QUALITY): String = withContext(Dispatchers.IO) {
        val bitmap = runCatching {
            if (uri.toString().contains("/video/")) {
                resolver.loadThumbnail(uri, Size(side, side), null)
            } else {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                    val scale = side.toFloat() / maxOf(info.size.width, info.size.height)
                    if (scale < 1f) decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }
        }.getOrElse { throw ToolFailure("That item could not be opened: ${it.message}") }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
        Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    // Screen time

    /** Time in each app, from Android's record of apps coming to the front and leaving, as Digital Wellbeing counts it */
    private fun screenTime(yesterday: Boolean, app: String?): String {
        val zone = ZoneId.systemDefault()
        val day = LocalDate.now(zone).minusDays(if (yesterday) 1 else 0)
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = minOf(System.currentTimeMillis(), day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        val totals = foregroundTimes(start, end)
        val launcher = context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
        val counted = totals.filterKeys { it != launcher && it != context.packageName && it != SYSTEM_UI }.filterValues { it >= MIN_USE_MS }
        val label = if (yesterday) "Yesterday" else "Today"
        if (app != null) {
            val match = counted.entries.firstOrNull { (pkg, _) -> pkg.equals(app, ignoreCase = true) || appLabel(pkg).equals(app, ignoreCase = true) }
                ?: counted.entries.firstOrNull { (pkg, _) -> appLabel(pkg).contains(app, ignoreCase = true) }
            return match?.let { "$label: ${appLabel(it.key)} for ${duration(it.value)}" } ?: "$label: no time in $app"
        }
        if (counted.isEmpty()) return "$label: no app use recorded"
        val lines = counted.entries.sortedByDescending { it.value }.take(TOP_APPS).joinToString("\n") { "${appLabel(it.key)}: ${duration(it.value)}" }
        return "$label: ${duration(counted.values.sum())} in apps\n$lines"
    }

    /**
     * Each activity's time in front, paired per activity, since an app's next screen resumes before its last one stops,
     * and summed per app. Events from before [start] are read too, so a session that began before midnight counts from it.
     */
    private fun foregroundTimes(start: Long, end: Long): Map<String, Long> {
        val events = context.getSystemService(UsageStatsManager::class.java).queryEvents(start - LOOKBACK_MS, end)
        val since = HashMap<Pair<String, String?>, Long>()
        val seen = HashSet<Pair<String, String?>>()
        val totals = HashMap<String, Long>()
        fun close(key: Pair<String, String?>, at: Long) {
            val from = since.remove(key) ?: return
            val counted = minOf(at, end) - maxOf(from, start)
            if (counted > 0) totals[key.first] = (totals[key.first] ?: 0L) + counted
        }
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val key = event.packageName to event.className
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> since.putIfAbsent(key, event.timeStamp)
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                    // A screen that leaves before any event of it was read was in front when the reading began
                    if (key !in since && key !in seen) since[key] = start - LOOKBACK_MS
                    close(key, event.timeStamp)
                }
                // An app left in front while the screen is off or locked isn't being used
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.KEYGUARD_SHOWN, UsageEvents.Event.DEVICE_SHUTDOWN ->
                    since.keys.toList().forEach { close(it, event.timeStamp) }
            }
            seen.add(key)
        }
        // An app still in front counts until now
        since.keys.toList().forEach { close(it, end) }
        return totals
    }

    private fun recentApps(): String {
        val end = System.currentTimeMillis()
        val events = context.getSystemService(UsageStatsManager::class.java).queryEvents(end - RECENT_WINDOW_MS, end)
        val opened = ArrayList<Pair<String, Long>>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED && opened.lastOrNull()?.first != event.packageName) {
                opened.add(event.packageName to event.timeStamp)
            }
        }
        val time = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
        val shown = opened.filter { it.first != context.packageName && it.first != SYSTEM_UI }.takeLast(RECENT_APPS).reversed()
        if (shown.isEmpty()) return "No apps opened in the last 3 hours"
        return "Opened, newest first:\n" + shown.joinToString("\n") { (pkg, at) -> "${time.format(Instant.ofEpochMilli(at))} ${appLabel(pkg)} ($pkg)" }
    }

    private fun appLabel(packageName: String): String = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    private fun duration(ms: Long): String {
        val minutes = ms / 60_000
        return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
    }

    // Media

    private suspend fun media(action: String): String {
        val watcher = NotificationWatcher.require()
        val sessions = context.getSystemService(MediaSessionManager::class.java)
            .getActiveSessions(ComponentName(watcher, NotificationWatcher::class.java))
        // The first session is the one Android last played, which is the one the user means
        val controller = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: sessions.firstOrNull()
            ?: return "Nothing is playing, and no app has media to play"
        val controls = controller.transportControls
        val before = controller.playbackState?.state
        when (action) {
            "status" -> return describe(controller)
            "play" -> controls.play()
            "pause" -> controls.pause()
            "toggle" -> if (before == PlaybackState.STATE_PLAYING) controls.pause() else controls.play()
            "next" -> controls.skipToNext()
            "previous" -> controls.skipToPrevious()
            else -> throw ToolFailure("Unknown action $action")
        }
        // The app reports its new state a moment later
        val title = controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val deadline = SystemClock.uptimeMillis() + MEDIA_SETTLE_MS
        while (SystemClock.uptimeMillis() < deadline &&
            controller.playbackState?.state == before && controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) == title
        ) {
            delay(POLL_MS)
        }
        return describe(controller)
    }

    private fun describe(controller: MediaController): String {
        val metadata = controller.metadata
        val state = when (controller.playbackState?.state) {
            PlaybackState.STATE_PLAYING -> "playing"
            PlaybackState.STATE_PAUSED -> "paused"
            PlaybackState.STATE_BUFFERING -> "loading"
            PlaybackState.STATE_STOPPED -> "stopped"
            else -> "idle"
        }
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val length = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 }
        val position = controller.playbackState?.position?.takeIf { it >= 0 }
        val where = if (length != null && position != null) " · ${position / 1000 / 60}:%02d of ${length / 1000 / 60}:%02d".format(position / 1000 % 60, length / 1000 % 60) else ""
        return "${appLabel(controller.packageName)}: $state · ${title ?: "(no title)"}${artist?.let { " · $it" }.orEmpty()}$where"
    }

    private companion object {
        const val MIN_NUMBER_DIGITS = 4
        const val RECENT_FIX_MS = 2 * 60_000L
        const val FIX_TIMEOUT_MS = 6_000L
        const val GEOCODE_TIMEOUT_MS = 2_000L
        const val PREVIEW_SIDE = 1280
        const val JPEG_QUALITY = 75
        // Models read images at about 1,600 px on the long side at most, so a larger one only costs bytes. Four photos of
        // this size come to about 2 MB of JPEG.
        const val MAX_SHARE = 4
        const val SHARE_SIDE = 1600
        const val SHARE_QUALITY = 85
        const val MIN_USE_MS = 30_000L
        /** How far before the day to read, so a session already running at midnight counts from midnight */
        const val LOOKBACK_MS = 24 * 60 * 60_000L
        const val TOP_APPS = 15
        const val RECENT_WINDOW_MS = 3 * 60 * 60_000L
        const val RECENT_APPS = 15
        const val MEDIA_SETTLE_MS = 800L
        const val POLL_MS = 25L
        const val SYSTEM_UI = "com.android.systemui"
    }
}
