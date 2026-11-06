package com.example.advancedtunnel.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * Walks the ContextWrapper chain that Compose hosts the activity in until the owning
 * Activity is found, so federated auth flows that require an activity can obtain one
 * from LocalContext.current. Returns null when no activity backs the context.
 */
fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
