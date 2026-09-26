package com.mobilegamma.cakesync.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class DriveException(val code: Int, message: String) : IOException(message)

/** Minimal Google Drive v3 REST client: find/create folders and upload files. */
class DriveClient(private val accessToken: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
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

    /** Uploads [bytes] as a new file in [parentId] and returns the Drive file id. */
    suspend fun uploadFile(name: String, mimeType: String, bytes: ByteArray, parentId: String): String =
        withContext(Dispatchers.IO) {
            val meta = JSONObject().put("name", name).put("parents", JSONArray().put(parentId))
            val body: RequestBody = MultipartBody.Builder()
                .setType("multipart/related".toMediaType())
                .addPart(meta.toString().toRequestBody(JSON))
                .addPart(bytes.toRequestBody(mimeType.toMediaType()))
                .build()
            val url = "$UPLOAD_API/files".toHttpUrl().newBuilder()
                .addQueryParameter("uploadType", "multipart")
                .addQueryParameter("fields", "id")
                .build()
            execute(Request.Builder().url(url).post(body)).use { it.jsonOrThrow().getString("id") }
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
