package ai.tushar.handset.context

import android.content.SharedPreferences

/**
 * Apps whose screens are never collected: system UI, launchers, keyboards, password managers, payments, settings and
 * files, and the apps the user adds in Settings
 */
object ExcludedApps {
    private const val CHOSEN = "excluded_apps"
    private var preferences: SharedPreferences? = null

    /** The apps the user added */
    @Volatile
    var chosen: Set<String> = emptySet()
        private set

    fun load(preferences: SharedPreferences) {
        this.preferences = preferences
        chosen = preferences.getStringSet(CHOSEN, emptySet()).orEmpty().toSet()
    }

    fun choose(packageName: String, excluded: Boolean) {
        chosen = if (excluded) chosen + packageName else chosen - packageName
        preferences?.edit()?.putStringSet(CHOSEN, chosen)?.apply()
    }

    /** Whether the app is on the built-in list, which the user can't change */
    fun isBuiltIn(packageName: String): Boolean = packageName in EXACT || KEYBOARD_PREFIXES.any { packageName.startsWith(it) }

    private val SYSTEM = setOf(
        "com.android.systemui",
        "com.google.android.apps.nexuslauncher",
        "com.sec.android.app.launcher",
        "com.huawei.android.launcher",
        "com.miui.home",
        "com.oppo.launcher",
        "com.vivo.launcher",
        "com.samsung.android.app.tips",
        "com.android.launcher3",
        "android",
        "com.google.android.gms",
    )

    private val KEYBOARD_PREFIXES = setOf(
        "com.google.android.inputmethod.",
        "com.touchtype.swiftkey.",
        "com.emoji.keyboard.",
        "io.github.halftouch.",
        "com.microsoft.office.word.",
        "com.samsung.android.honeyboard",
    )

    private val PASSWORD_MANAGERS = setOf(
        "com.lastpass.lpandroid",
        "com.lastpass.ilastpass",
        "com.agilebits.onepassword",
        "com.onepassword.android",
        "io.keepass.android",
        "com.dashlane",
        "com.roboform.passwordmanager",
        "com.samsung.android.app.password.manager",
        "com.google.android.apps.authenticator2",
        "com.authy.authy",
        "com.microsoft.authenticator",
        "org.fedorahosted.freeotp",
        "com.x8bit.bitwarden",
    )

    private val PAYMENTS = setOf(
        "com.google.android.apps.walletnfcrel",
        "com.samsung.android.spay",
        "com.samsung.android.spaymini",
        "com.paypal.android.p2pmobile",
        "com.venmo",
        "com.squareup.cash",
        "com.zellepay.zelle",
        "com.phonepe.app",
        "com.google.android.apps.nbu.paisa.user",
    )

    private val SETTINGS_AND_FILES = setOf(
        "com.android.settings",
        "com.samsung.android.app.settings",
        "com.google.android.apps.nbu.files",
        "com.sec.android.app.myfiles",
        "com.samsung.android.app.myfiles",
        "com.android.documentsui",
    )

    private val EXACT = SYSTEM + PASSWORD_MANAGERS + PAYMENTS + SETTINGS_AND_FILES

    fun isExcluded(packageName: String): Boolean = isBuiltIn(packageName) || packageName in chosen
}
