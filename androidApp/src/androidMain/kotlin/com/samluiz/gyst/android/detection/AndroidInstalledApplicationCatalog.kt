package com.samluiz.gyst.android.detection

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

data class InstalledApplicationDescriptor(
    val packageName: String,
    val displayName: String,
)

fun interface InstalledApplicationSource {
    fun listLaunchableApplications(): List<InstalledApplicationDescriptor>
}

/**
 * Lists launchable applications visible through the narrowly scoped manifest query. It deliberately
 * does not request QUERY_ALL_PACKAGES. Callers are responsible for running this off the main thread.
 */
class AndroidInstalledApplicationCatalog(
    context: Context,
) : InstalledApplicationSource {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager

    override fun listLaunchableApplications(): List<InstalledApplicationDescriptor> {
        val intent =
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
        val resolved =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(intent, 0)
            }

        return resolved
            .asSequence()
            .mapNotNull { info ->
                val packageName = info.activityInfo?.packageName?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                if (packageName == appContext.packageName) return@mapNotNull null
                val displayName = info.loadLabel(packageManager).toString().trim()
                InstalledApplicationDescriptor(
                    packageName = packageName,
                    displayName = displayName.ifBlank { packageName },
                )
            }.distinctBy(InstalledApplicationDescriptor::packageName)
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, InstalledApplicationDescriptor::displayName))
            .toList()
    }
}
