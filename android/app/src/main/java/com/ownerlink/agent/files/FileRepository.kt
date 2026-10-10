package com.ownerlink.agent.files

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Build
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.ownerlink.agent.security.SecureStore
import com.ownerlink.agent.util.Permissions
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.InputStream
import com.ownerlink.agent.affiliate.UserDataFile

class FileRepository(private val context: Context, private val store: SecureStore) {
    fun allowedRoots(): List<File> {
        if (!Permissions.hasAllFiles(context)) return emptyList()
        val roots = linkedSetOf<File>()
        roots += Environment.getExternalStorageDirectory().canonicalFile
        if (Build.VERSION.SDK_INT >= 30) {
            context.getSystemService(StorageManager::class.java).storageVolumes.forEach { volume ->
                volume.directory?.canonicalFile?.let(roots::add)
            }
        }
        context.getExternalFilesDirs(null).filterNotNull().forEach { appDir ->
            var cursor: File? = appDir.canonicalFile
            while (cursor != null && cursor.name != "Android") cursor = cursor.parentFile
            cursor?.parentFile?.canonicalFile?.let(roots::add)
        }
        return roots.filter { it.isDirectory && it.canRead() }
    }

    fun list(folderId: String?): JsonObject {
        if (folderId.isNullOrBlank()) {
            val items = ArrayList<JsonObject>()
            if (Permissions.hasAllFiles()) {
                val local = UserDataFile(context).file
                if (local.isFile) items.add(fileItem("user_data.txt", FileIds.encodeAppPrivate("user_data.txt"), local.length(), "text/plain", local.lastModified()))
                val primary = Environment.getExternalStorageDirectory().canonicalFile
                allowedRoots().forEach { root ->
                    val name = if (root == primary) "Internal Storage" else "Storage ${root.name}"
                    items.add(folderItem(name, FileIds.encodePath(root.path), root.lastModified()))
                }
            }
            for (tree in store.safTrees) {
                val treeUri = Uri.parse(tree)
                if (!hasTreeGrant(tree)) continue
                val doc = DocumentFile.fromTreeUri(context, treeUri)?.takeIf { it.exists() && it.canRead() } ?: continue
                val docId = DocumentsContract.getTreeDocumentId(treeUri)
                items.add(
                    folderItem(
                        doc.name ?: "Selected folder",
                        FileIds.encodeSaf(tree, docId),
                        null,
                    ),
                )
            }
            if (items.isEmpty()) {
                return buildJsonObject {
                    put("ok", false)
                    put("code", "PERMISSION_MISSING")
                    put("message", "All Files access is required on this phone, or select a folder using Android's folder picker.")
                }
            }
            return buildJsonObject { put("items", JsonArray(items)) }
        }
        return when (val ref = FileIds.decode(folderId)) {
            is FileRef.Path -> listPath(ref.canonical)
            is FileRef.Saf -> listSaf(ref)
            is FileRef.Gallery -> buildJsonObject {
                put("ok", false)
                put("code", "INVALID_PATH")
                put("message", "Not a folder")
            }
            is FileRef.AppPrivate -> buildJsonObject {
                put("ok", false); put("code", "INVALID_PATH"); put("message", "Not a folder")
            }
        }
    }

    private fun listPath(canonical: String): JsonObject {
        FileIds.assertAllowedPath(canonical, allowedRoots())
        val dir = File(canonical)
        if (!dir.isDirectory) {
            return buildJsonObject {
                put("ok", false)
                put("code", "FILE_NOT_FOUND")
                put("message", "Folder not found")
            }
        }
        val children = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?: return buildJsonObject {
                put("ok", false)
                put("code", "ACCESS_DENIED")
                put("message", "Android could not read this folder. Check storage permission; other apps' private folders are protected.")
            }
        val items = children.map { child ->
            val id = FileIds.encodePath(child.canonicalPath)
            if (child.isDirectory) folderItem(child.name, id, child.lastModified())
            else fileItem(child.name, id, child.length(), mimeOf(child.name), child.lastModified())
        }
        return buildJsonObject { put("items", JsonArray(items)) }
    }

    private fun listSaf(ref: FileRef.Saf): JsonObject {
        requireTreeGrant(ref.treeUri)
        val tree = Uri.parse(ref.treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, ref.documentId)
        val items = ArrayList<JsonObject>()
        context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null,
            null,
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val docId = c.getString(0)
                val name = c.getString(1) ?: "item"
                val mime = c.getString(2)
                val size = if (c.isNull(3)) 0L else c.getLong(3)
                val modified = if (c.isNull(4)) null else c.getLong(4)
                val id = FileIds.encodeSaf(ref.treeUri, docId)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    items.add(folderItem(name, id, modified))
                } else {
                    items.add(fileItem(name, id, size, mime, modified))
                }
            }
        } ?: return buildJsonObject {
            put("ok", false)
            put("code", "FILE_NOT_FOUND")
            put("message", "Folder not found")
        }
        items.sortWith(compareBy({ it["directory"].toString() == "false" }, { it["name"].toString().lowercase() }))
        return buildJsonObject { put("items", JsonArray(items)) }
    }

    fun open(ref: FileRef): Triple<InputStream, String, Long?> {
        return when (ref) {
            is FileRef.Path -> {
                FileIds.assertAllowedPath(ref.canonical, allowedRoots())
                val file = File(ref.canonical)
                require(file.isFile) { "file not found" }
                Triple(file.inputStream(), file.name, file.length())
            }
            is FileRef.Saf -> {
                requireTreeGrant(ref.treeUri)
                val uri = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(ref.treeUri), ref.documentId)
                val document = DocumentFile.fromSingleUri(context, uri)
                val name = document?.name ?: "download"
                val stream = context.contentResolver.openInputStream(uri) ?: error("file not found")
                Triple(stream, name, document?.takeIf { it.isFile }?.length())
            }
            is FileRef.Gallery -> error("use gallery repository")
            is FileRef.AppPrivate -> {
                if (!Permissions.hasAllFiles()) error("permission missing")
                val file = if (ref.logicalName == "user_data.txt") UserDataFile(context).file.canonicalFile else {
                    if (!ref.logicalName.startsWith("proofs/") || ref.logicalName.contains("..")) error("path not allowed")
                    val root = File(context.filesDir, "Affiliate/proofs").canonicalFile
                    File(root, ref.logicalName.removePrefix("proofs/")).canonicalFile.also { FileIds.assertAllowedPath(it.path, listOf(root)) }
                }
                require(file.isFile) { "file not found" }
                Triple(file.inputStream(), if (ref.logicalName == "user_data.txt") "user_data.txt" else file.name.substringAfter('_'), file.length())
            }
        }
    }

    fun mimeOf(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }

    private fun hasTreeGrant(tree: String): Boolean = tree in store.safTrees &&
        context.contentResolver.persistedUriPermissions.any { it.uri.toString() == tree && it.isReadPermission }

    private fun requireTreeGrant(tree: String) {
        check(hasTreeGrant(tree)) { "Folder permission expired. Select this folder again on the phone." }
    }

    private fun folderItem(name: String, id: String, modified: Long?) = buildJsonObject {
        put("id", id)
        put("name", name)
        put("directory", true)
        if (modified != null) put("modified", modified)
    }

    private fun fileItem(name: String, id: String, size: Long, mime: String?, modified: Long?) = buildJsonObject {
        put("id", id)
        put("name", name)
        put("directory", false)
        put("size", size)
        if (mime != null) put("mime", mime)
        if (modified != null) put("modified", modified)
    }
}
