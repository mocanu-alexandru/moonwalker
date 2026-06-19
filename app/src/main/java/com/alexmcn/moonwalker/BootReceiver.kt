package com.alexmcn.moonwalker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Repornește AUTO la boot-ul telefonului, dacă era activ (AutoState). Așa, după un restart de
 * sistem, botul reia singur acoperirea — fără să redeschizi appul. Best-effort: dacă pornirea
 * serviciului foreground din background e blocată, reluarea se face oricum când deschizi appul.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val a = intent.action
        if (a != Intent.ACTION_BOOT_COMPLETED && a != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        if (!AutoState.isActive(ctx)) return
        val poly = AutoState.poly(ctx) ?: return
        val i = Intent(ctx, MockService::class.java).apply {
            putExtra(MockService.EXTRA_TICK_HZ, AutoState.tickHz(ctx))
            putExtra(MockService.EXTRA_ROW_M, 75.0)
            putExtra(MockService.EXTRA_STEP_M, 25.0)
            putExtra(MockService.EXTRA_VERTICAL, false)
            putExtra(MockService.EXTRA_LOOP, false)
            putExtra(MockService.EXTRA_AUTO, true)
            putExtra(MockService.EXTRA_POLY, poly)
        }
        try {
            ContextCompat.startForegroundService(ctx, i)
            Log.i("BootReceiver", "AUTO reluat la boot")
        } catch (e: Exception) {
            Log.w("BootReceiver", "pornire AUTO la boot blocată: ${e.message}")
        }
    }
}
