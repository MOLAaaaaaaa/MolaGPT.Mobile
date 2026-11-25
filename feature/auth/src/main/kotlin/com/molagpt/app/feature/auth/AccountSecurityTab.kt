package com.molagpt.app.feature.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent

fun openAccountSecurityTab(context: Context, url: String) {
    val tab = CustomTabsIntent.Builder().setShowTitle(true).build()
    CustomTabsClient.getPackageName(context, null)?.let(tab.intent::setPackage)
    if (context !is Activity) tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    tab.launchUrl(context, Uri.parse(url))
}
