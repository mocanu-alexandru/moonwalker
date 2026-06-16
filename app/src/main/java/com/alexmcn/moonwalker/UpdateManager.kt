package com.alexmcn.moonwalker

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Verifică ultima versiune pe GitHub Releases și instalează APK-ul dacă e mai nou.
 * Repo: mocanu-alexandru/moonwalker — trebuie să fie public SAU cu releases publice.
 * Flow: "Verifică update" → API pe thread separat → comparare versionName
 *       → DownloadManager → FileProvider intent pentru instalare.
 */
object UpdateManager {

    private const val RELEASES_URL =
        "https://api.github.com/repos/mocanu-alexandru/moonwalker/releases/latest"

    /** silent=true = verificare la pornire; nu arată toast dacă ești deja la zi */
    fun checkAndInstall(activity: Activity, silent: Boolean = false) {
        if (!silent) Toast.makeText(activity, "Verifică update…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val release = fetchLatestRelease()
                if (release == null) {
                    if (!silent) activity.runOnUiThread {
                        Toast.makeText(activity,
                            "Nu s-a putut contacta GitHub", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }

                val remoteTag = release.optString("tag_name", "").trimStart('v')
                val currentVersion = activity.packageManager
                    .getPackageInfo(activity.packageName, 0).versionName ?: "0"

                if (!isNewer(remoteTag, currentVersion)) {
                    if (!silent) activity.runOnUiThread {
                        Toast.makeText(activity,
                            "Ești pe ultima versiune ($currentVersion)", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }

                val assets = release.optJSONArray("assets")
                var apkUrl: String? = null
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.getString("name").endsWith(".apk")) {
                            apkUrl = a.getString("browser_download_url"); break
                        }
                    }
                }

                if (apkUrl == null) {
                    if (!silent) activity.runOnUiThread {
                        Toast.makeText(activity,
                            "Release $remoteTag nu are APK atașat", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }

                val finalUrl = apkUrl
                activity.runOnUiThread {
                    Toast.makeText(activity, "Update v$remoteTag disponibil, se descarcă…",
                        Toast.LENGTH_SHORT).show()
                    downloadAndInstall(activity, finalUrl, "moonwalker-$remoteTag.apk")
                }

            } catch (_: Exception) {
                if (!silent) activity.runOnUiThread {
                    Toast.makeText(activity, "Eroare update: verifică internetul",
                        Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun fetchLatestRelease(): JSONObject? {
        val conn = URL(RELEASES_URL).openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        return try {
            if (conn.responseCode == 200)
                JSONObject(conn.inputStream.bufferedReader().readText())
            else null
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadAndInstall(activity: Activity, url: String, fileName: String) {
        val destFile = File(
            activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)

        val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(
            DownloadManager.Request(Uri.parse(url)).apply {
                setTitle("Moonwalker update")
                setDescription(fileName)
                setDestinationInExternalFilesDir(
                    activity, Environment.DIRECTORY_DOWNLOADS, fileName)
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setMimeType("application/vnd.android.package-archive")
            }
        )

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != downloadId)
                    return
                activity.unregisterReceiver(this)

                val cursor = dm.query(DownloadManager.Query().setFilterById(downloadId))
                var success = false
                if (cursor.moveToFirst()) {
                    val col = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    if (col >= 0 && cursor.getInt(col) == DownloadManager.STATUS_SUCCESSFUL)
                        success = true
                }
                cursor.close()

                if (success) installApk(activity, destFile)
                else Toast.makeText(activity, "Descărcarea a eșuat", Toast.LENGTH_LONG).show()
            }
        }
        activity.registerReceiver(
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            Context.RECEIVER_NOT_EXPORTED
        )
    }

    private fun installApk(activity: Activity, apkFile: File) {
        val apkUri = FileProvider.getUriForFile(
            activity, "${activity.packageName}.fileprovider", apkFile)
        activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun isNewer(remote: String, current: String): Boolean {
        fun parts(v: String) = v.split(".").map { it.toIntOrNull() ?: 0 }
        val r = parts(remote); val c = parts(current)
        for (i in 0 until maxOf(r.size, c.size)) {
            val diff = (r.getOrElse(i) { 0 }) - (c.getOrElse(i) { 0 })
            if (diff != 0) return diff > 0
        }
        return false
    }
}
