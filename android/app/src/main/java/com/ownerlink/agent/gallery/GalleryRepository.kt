package com.ownerlink.agent.gallery

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import com.ownerlink.agent.files.FileIds
import com.ownerlink.agent.util.Permissions
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream

class GalleryRepository(private val context: Context) {
    fun listVideos(cursor: String?, limit: Int): JsonObject {
        if (!Permissions.hasMedia(context)) return buildJsonObject {
            put("ok", false); put("code", "PERMISSION_MISSING")
            put("message", "Video permission is not granted on the phone.")
        }
        val items = ArrayList<JsonObject>()
        val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.MIME_TYPE, MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.DURATION,
        )
        val after = cursor?.toLongOrNull()
        val selection = after?.let { "${MediaStore.Video.Media._ID} < ?" }
        val args = after?.let { arrayOf(it.toString()) }
        context.contentResolver.query(uri, projection, selection, args, "${MediaStore.Video.Media._ID} DESC")?.use { c ->
            while (c.moveToNext() && items.size < limit.coerceIn(1, 100)) {
                val id = c.getLong(0)
                items += buildJsonObject {
                    put("id", FileIds.encodeGallery(id)); put("name", c.getString(1) ?: "video")
                    put("mime", c.getString(2) ?: "video/*"); put("size", c.getLong(3))
                    put("width", c.getInt(4)); put("height", c.getInt(5))
                    put("taken_at", c.getLong(6)); put("duration_ms", c.getLong(7)); put("is_video", true)
                }
            }
        }
        val next = items.lastOrNull()?.get("id")?.jsonPrimitive?.content?.let {
            (FileIds.decode(it) as com.ownerlink.agent.files.FileRef.Gallery).mediaId.toString()
        }
        return buildJsonObject {
            put("items", JsonArray(items)); put("has_more", items.size >= limit.coerceIn(1, 100))
            if (items.size >= limit.coerceIn(1, 100) && next != null) put("cursor", next)
        }
    }

    fun list(cursor: String?, limit: Int, album: String? = null): JsonObject {
        if (!Permissions.hasMedia(context)) return buildJsonObject {
            put("ok", false); put("code", "PERMISSION_MISSING")
            put("message", "Allow shared media access on the phone to browse its albums.")
        }
        val uri = MediaStore.Files.getContentUri("external")
        val mediaSelection = "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=?)"
        val mediaArgs = arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(), MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString())
        val albums = linkedMapOf<String, AlbumCount>()
        if (cursor == null) {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.Files.FileColumns.MEDIA_TYPE), mediaSelection, mediaArgs, null)?.use { c ->
                while (c.moveToNext()) {
                    val path = c.getString(0).orEmpty()
                    val count = albums.getOrPut(path) { AlbumCount() }
                    if (c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE) count.images++ else count.videos++
                }
            } ?: error("Android could not read the media index")
        }
        val selection = StringBuilder(mediaSelection)
        val args = mediaArgs.toMutableList()
        if (album != null) {
            if (album.isEmpty()) selection.append(" AND (${MediaStore.MediaColumns.RELATIVE_PATH} IS NULL OR ${MediaStore.MediaColumns.RELATIVE_PATH}='')")
            else { selection.append(" AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"); args.add(album) }
        }
        cursor?.toLongOrNull()?.let { selection.append(" AND ${MediaStore.Files.FileColumns._ID} < ?"); args.add(it.toString()) }
        val projection = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE, MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.MediaColumns.RELATIVE_PATH)
        val items = ArrayList<JsonObject>()
        val pageSize = limit.coerceIn(1, 200)
        var hasMore = false
        var lastId: Long? = null
        context.contentResolver.query(uri, projection, selection.toString(), args.toTypedArray(), "${MediaStore.Files.FileColumns._ID} DESC")?.use { c ->
            while (c.moveToNext()) {
                if (items.size == pageSize) { hasMore = true; break }
                val id = c.getLong(0)
                lastId = id
                items.add(buildJsonObject {
                    put("id", FileIds.encodeGallery(id)); put("name", c.getString(1) ?: "media")
                    put("mime", c.getString(2).orEmpty()); put("size", c.getLong(3))
                    put("is_video", c.getInt(4) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)
                    put("album", c.getString(5).orEmpty())
                })
            }
        } ?: error("Android could not read this album")
        return buildJsonObject {
            put("items", JsonArray(items)); put("has_more", hasMore)
            if (hasMore && lastId != null) put("cursor", lastId.toString())
            if (cursor == null) {
                put("total_images", albums.values.sumOf { it.images })
                put("total_videos", albums.values.sumOf { it.videos })
                put("albums", JsonArray(albums.entries.sortedBy { it.key.lowercase() }.map { (path, count) ->
                    buildJsonObject {
                        put("id", path); put("path", path)
                        put("name", path.trimEnd('/').substringAfterLast('/').ifEmpty { "Other" })
                        put("images", count.images); put("videos", count.videos)
                        put("count", count.images + count.videos)
                    }
                }))
            }
        }
    }

    private data class AlbumCount(var images: Long = 0, var videos: Long = 0)

    fun thumbnailJpeg(mediaId: Long): ByteArray {
        val uri = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), mediaId)
        val bitmap = if (Build.VERSION.SDK_INT >= 29) {
            context.contentResolver.loadThumbnail(uri, Size(256, 256), null)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Thumbnails.getThumbnail(
                context.contentResolver,
                mediaId,
                MediaStore.Images.Thumbnails.MINI_KIND,
                null,
            )
        } ?: error("thumbnail unavailable")
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)
        return out.toByteArray()
    }

    fun openOriginal(mediaId: Long) =
        context.contentResolver.openInputStream(
            ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), mediaId),
        ) ?: error("media not found")

    fun displayName(mediaId: Long): Pair<String, String?> {
        val uri = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), mediaId)
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.MIME_TYPE, MediaStore.Files.FileColumns.SIZE),
            null,
            null,
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                return (c.getString(0) ?: "media") to c.getString(1)
            }
        }
        return "media" to null
    }

    fun sizeOf(mediaId: Long): Long? {
        val uri = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), mediaId)
        context.contentResolver.query(uri, arrayOf(MediaStore.Files.FileColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        return null
    }
}

