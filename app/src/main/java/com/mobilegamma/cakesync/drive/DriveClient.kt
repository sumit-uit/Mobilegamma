package com.mobilegamma.cakesync.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

class DriveException(val code: Int, message: String) : IOException(message)

/** Minimal Google Drive v3 REST client: find/create folders and upload files. */
class DriveClient(private val accessToken: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.MINUTES) // large videos on slow connections
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    /** Returns true if the folder still exists and is not in the trash. */
    suspend fun folderExists(folderId: String): Boolean = withContext(Dispatchers.IO) {
        val url = "$API/files/$folderId".toHttpUrl().newBuilder()
            .addQueryParameter("fields", "id,trashed")
            .build()
        execute(Request.Builder().url(url).get()).use { res ->
            if (res.code == 404) return@withContext false
            val json = res.jsonOrThrow()
            !json.optBoolean("trashed", false)
        }
    }

    suspend fun findFolder(name: String, parentId: String?): String? = withContext(Dispatchers.IO) {
        val escaped = name.replace("\\", "\\\\").replace("'", "\\'")
        val q = buildString {
            append("mimeType = '$FOLDER_MIME' and name = '$escaped' and trashed = false")
            if (parentId != null) append(" and '$parentId' in parents")
        }
        val url = "$API/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("fields", "files(id)")
            .addQueryParameter("spaces", "drive")
            .build()
        execute(Request.Builder().url(url).get()).use { res ->
            val files: JSONArray = res.jsonOrThrow().optJSONArray("files") ?: JSONArray()
            if (files.length() > 0) files.getJSONObject(0).getString("id") else null
        }
    }

    suspend fun createFolder(name: String, parentId: String?): String = withContext(Dispatchers.IO) {
        val meta = JSONObject().put("name", name).put("mimeType", FOLDER_MIME)
        if (parentId != null) meta.put("parents", JSONArray().put(parentId))
        val url = "$API/files".toHttpUrl().newBuilder().addQueryParameter("fields", "id").build()
        val body = meta.toString().toRequestBody(JSON)
        execute(Request.Builder().url(url).post(body)).use { it.jsonOrThrow().getString("id") }
    }

    suspend fun findOrCreateFolder(name: String, parentId: String?): String =
        findFolder(name, parentId) ?: createFolder(name, parentId)

    /**
     * Uploads a file as a new Drive file in [parentId] and returns its id. Uses Drive's
     * resumable upload and streams from [open], so large videos never sit in memory.
     */
    suspend fun uploadFile(
        name: String,
        mimeType: String,
        parentId: String,
        size: Long,
        open: () -> InputStream,
    ): String = withContext(Dispatchers.IO) {
        // 1. Start a resumable session; Drive returns the upload URL in the Location header.
        val meta = JSONObject().put("name", name).put("parents", JSONArray().put(parentId))
        val startUrl = "$UPLOAD_API/files".toHttpUrl().newBuilder()
            .addQueryParameter("uploadType", "resumable")
            .addQueryParameter("fields", "id")
            .build()
        val start = Request.Builder().url(startUrl)
            .header("X-Upload-Content-Type", mimeType)
            .apply { if (size >= 0) header("X-Upload-Content-Length", size.toString()) }
            .post(meta.toString().toRequestBody(JSON))
        val sessionUrl = execute(start).use { res ->
            if (!res.isSuccessful) throw DriveException(res.code, "Drive API error ${res.code}: ${res.body?.string()?.take(300)}")
            res.header("Location") ?: throw IOException("Drive did not return an upload URL")
        }

        // 2. Stream the file body to the session URL.
        val body = object : RequestBody() {
            override fun contentType() = mimeType.toMediaType()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                open().source().use { sink.writeAll(it) }
            }
        }
        execute(Request.Builder().url(sessionUrl).put(body)).use { it.jsonOrThrow().getString("id") }
    }

    private fun execute(builder: Request.Builder): Response =
        http.newCall(builder.header("Authorization", "Bearer $accessToken").build()).execute()

    private fun Response.jsonOrThrow(): JSONObject {
        val text = body?.string().orEmpty()
        if (!isSuccessful) throw DriveException(code, "Drive API error $code: ${text.take(300)}")
        return JSONObject(text)
    }

    private companion object {
        const val API = "https://www.googleapis.com/drive/v3"
        const val UPLOAD_API = "https://www.googleapis.com/upload/drive/v3"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        val JSON = "application/json; charset=UTF-8".toMediaType()
    }
}
