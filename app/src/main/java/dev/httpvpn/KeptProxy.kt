package dev.httpvpn

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Список профилей в «Загрузках». Файл не входит в каталог приложения,
 * поэтому удаление через настройки его не стирает. Пароль сюда не пишется.
 */
object KeptProxy {
    const val FILE_NAME = "simple-http-proxy.txt"

    fun missingLegacyPermissions(context: Context): Array<String> {
        if (Build.VERSION.SDK_INT >= 29) return emptyArray()
        return arrayOf(
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ).filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
    }

    fun write(context: Context, snapshot: KeptProfiles.Snapshot) {
        if (!KeptProfiles.roundTrips(snapshot)) {
            Log.w(TAG, "Список прокси не записан: файл не совпал бы при чтении")
            return
        }
        val body = KeptProfiles.format(snapshot).toByteArray(Charsets.UTF_8)
        try {
            if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
                writePublicFile(body)
                return
            }
            if (Build.VERSION.SDK_INT >= 29) writeMediaStore(context, body) else writeLegacy(context, body)
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось записать список прокси: ${e.javaClass.simpleName}")
        }
    }

    fun needsAllFilesAccess(): Boolean {
        return Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()
    }

    fun read(context: Context): KeptProfiles.Snapshot? {
        return try {
            val text = if (Build.VERSION.SDK_INT >= 29) {
                readMediaStore(context) ?: readPublicFile()
            } else {
                readLegacy(context)
            }
            text?.let(KeptProfiles::parse)
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось прочитать список прокси: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun writeMediaStore(context: Context, body: ByteArray) {
        val resolver = context.contentResolver
        val existing = findOwned(context)
        val uri = existing ?: insertPending(context) ?: return
        try {
            resolver.openOutputStream(uri, "wt")?.use { it.write(body) }
        } finally {
            if (existing == null) {
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            }
        }
    }

    private fun insertPending(context: Context): android.net.Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return context.contentResolver.insert(collection, values)
    }

    private fun readMediaStore(context: Context): String? {
        val uri = findOwned(context) ?: return null
        return context.contentResolver.openInputStream(uri)?.use(KeptProfiles::readAtMost)
    }

    private fun findOwned(context: Context): android.net.Uri? {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME}=?",
            arrayOf(FILE_NAME),
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            return ContentUris.withAppendedId(collection, cursor.getLong(0))
        }
        return null
    }

    private fun writeLegacy(context: Context, body: ByteArray) {
        if (missingLegacyPermissions(context).isNotEmpty()) return
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists() && !dir.mkdirs()) return
        File(dir, FILE_NAME).writeBytes(body)
    }

    private fun readPublicFile(): String? {
        val dirs = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            File("/storage/emulated/0/Download"),
            File("/storage/emulated/0/Downloads"),
        )
        val seen = HashSet<String>()
        for (dir in dirs) {
            if (!seen.add(dir.absolutePath)) continue
            try {
                val file = File(dir, FILE_NAME)
                if (!file.isFile) continue
                return file.inputStream().use(KeptProfiles::readAtMost)
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun writePublicFile(body: ByteArray) {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists() && !dir.mkdirs()) return
        File(dir, FILE_NAME).writeBytes(body)
    }

    private fun readLegacy(context: Context): String? {
        if (missingLegacyPermissions(context).isNotEmpty()) return null
        val file = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            FILE_NAME,
        )
        if (!file.isFile) return null
        return file.inputStream().use(KeptProfiles::readAtMost)
    }

    private const val TAG = "HttpVpn"
}
