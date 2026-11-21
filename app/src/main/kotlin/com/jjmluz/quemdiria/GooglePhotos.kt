package com.jjmluz.quemdiria

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset

/** A person Google Photos recognised and the family named, as Photos TV lists them. */
data class PhotosPerson(val key: String, val name: String, val count: Int)

/** One of a person's photos: when it was taken, as UTC millis and its time zone's offset. */
data class PhotosItem(val key: String, val time: Long, val offset: Long, val video: Boolean) {
    /** The year where it was taken. */
    val year: Int get() = Instant.ofEpochMilli(time + offset).atZone(ZoneOffset.UTC).year

    /** The day where it was taken, as days since 1 January 1970. */
    val day: Long get() = Math.floorDiv(time + offset, 86_400_000L)
}

/**
 * Google Photos, through the Photos TV app on the same TV (its `GameProvider`): the People there, their photos
 * and places, and the pictures. This app never signs in to Google: Photos TV does, and the TV asks the person
 * in front of it once before this app may see them ([PERMISSION]).
 */
class GooglePhotos(private val context: Context) {
    /** Photos TV is installed with its games provider (a version that has one). */
    val installed: Boolean
        get() = context.packageManager.resolveContentProvider(AUTHORITY, 0) != null

    val allowed: Boolean
        get() = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    suspend fun people(): List<PhotosPerson> {
        val json = JSONArray(call("people"))
        return (0 until json.length()).map { json.getJSONObject(it) }
            .map { PhotosPerson(it.getString("key"), it.getString("name"), it.optInt("count")) }
    }

    /** [person]'s photos and videos, newest first, from the first [pages] pages Google Photos sends. */
    suspend fun photos(person: String, pages: Int = 3): List<PhotosItem> {
        val json = JSONArray(call("photos", person, pages))
        return (0 until json.length()).map { json.getJSONObject(it) }
            .map { PhotosItem(it.getString("key"), it.getLong("time"), it.optLong("offset"), it.optBoolean("video")) }
    }

    /** The places Google Photos names for [photo], most specific first ("Ribeira, Porto"), or null. */
    suspend fun place(photo: String): String? =
        JSONObject(call("place", photo)).let { if (it.isNull("place")) null else it.getString("place") }

    /** A person's face, square, [px] wide; only for people [people] has listed since Photos TV started. */
    suspend fun face(person: String, px: Int): ByteArray? = read("face/${Uri.encode(person)}?px=$px")

    /** A photo, fitting [width] × [height]; only for photos [photos] has listed since Photos TV started. */
    suspend fun picture(photo: String, width: Int, height: Int): ByteArray? =
        read("photo/${Uri.encode(photo)}?w=$width&h=$height")

    private suspend fun call(method: String, arg: String? = null, pages: Int? = null): String = withContext(Dispatchers.IO) {
        val extras = pages?.let { android.os.Bundle().apply { putInt("pages", it) } }
        val result = try {
            context.contentResolver.call(Uri.parse("content://$AUTHORITY"), method, arg, extras)
        } catch (e: Exception) {
            // Not installed, refused (no permission), or Photos TV crashed.
            throw IOException("Photos TV: ${e.message}", e)
        } ?: throw IOException("Photos TV didn't answer")
        result.getString("error")?.let { throw if (it == "not_signed_in") NotSignedIn() else IOException("Photos TV: $it") }
        result.getString("json") ?: throw IOException("Photos TV: empty answer")
    }

    private suspend fun read(path: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(Uri.parse("content://$AUTHORITY/$path"))?.use { it.readBytes() }
        } catch (e: Exception) {
            Log.w("QuemDiria", "Photos TV picture $path: ${e.message}")
            null
        }
    }

    /** Photos TV is installed but nobody has signed in to Google Photos there. */
    class NotSignedIn : IOException("Not signed in to Photos TV")

    companion object {
        const val AUTHORITY = "com.jjmluz.photostv.games"
        const val PERMISSION = "com.jjmluz.photostv.permission.GAMES"
    }
}
