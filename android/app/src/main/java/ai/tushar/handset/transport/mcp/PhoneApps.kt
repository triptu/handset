package ai.tushar.handset.transport.mcp

import ai.tushar.handset.Handset
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.security.MessageDigest

/** The SHA-256 of the certificates [packageName] is signed with now, or null when it isn't installed */
fun PackageManager.certificate(packageName: String): String? {
    val signing = runCatching { getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo }.getOrNull() ?: return null
    val signers = if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory.takeLast(1).toTypedArray()
    return signers.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.sorted().joinToString(",")
}

/** Removes the agent of an app on this phone once the app is uninstalled, which revokes its token */
class AppRemovedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_PACKAGE_FULLY_REMOVED) Handset.instance.mcp.agents.forgetGoneApps(context.packageManager::certificate)
    }
}
