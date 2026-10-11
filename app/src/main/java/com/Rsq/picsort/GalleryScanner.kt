package com.Rsq.picsort

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class GalleryScanner(
    private val context: Context,
    private val serverClient: FaceServerClient = FaceServerClient(),
) {
    private val cacheManager = ScanCacheManager(context)

    suspend fun scan(
        folderUri: Uri? = null,
        serverUrl: String,
        apiKey: String,
        threshold: Float = 0.45f,
        clusteringAlgo: String = "chinese_whispers",
        onProgress: (Int, Int) -> Unit,
    ): ScanResult = withContext(Dispatchers.IO) {
        val photos = if (folderUri != null) queryFolderPhotos(folderUri) else queryPhotos()
        if (photos.isEmpty()) return@withContext ScanResult(emptyList(), emptyList())

        val descriptorCache = ConcurrentHashMap(cacheManager.loadDescriptorCache())
        val missingPhotos = photos.filter { !descriptorCache.containsKey(it.uri.toString()) }

        // Fail fast if server is offline when we need to analyze new photos
        if (missingPhotos.isNotEmpty()) {
            try {
                serverClient.getHealth(serverUrl, apiKey)
            } catch (e: Exception) {
                val msg = when (e) {
                    is ServerApiException -> "Server error (${e.statusCode}): ${e.message}"
                    else -> "Server at $serverUrl is offline or unreachable (${e.localizedMessage ?: "Connection refused"}). Check Server Settings."
                }
                throw java.io.IOException(msg, e)
            }
        }

        val cacheUpdated = AtomicBoolean(false)
        val completedCount = AtomicInteger(0)
        val concurrencyLimit = 4
        val semaphore = Semaphore(concurrencyLimit)

        coroutineScope {
            photos.map { photo ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val cacheKey = photo.uri.toString()
                        if (!descriptorCache.containsKey(cacheKey)) {
                            val jpegBytes = loadCompressedJpeg(photo)
                            val descriptors = if (jpegBytes != null) {
                                try {
                                    serverClient.analyzeFace(
                                        imageBytes = jpegBytes,
                                        baseUrl = serverUrl,
                                        apiKey = apiKey,
                                    )
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    emptyList()
                                }
                            } else {
                                emptyList()
                            }
                            descriptorCache[cacheKey] = descriptors
                            cacheUpdated.set(true)
                        }

                        val done = completedCount.incrementAndGet()
                        if (done % 5 == 0 || done == photos.size) {
                            withContext(Dispatchers.Main) { onProgress(done, photos.size) }
                        }
                    }
                }
            }.awaitAll()
        }

        if (cacheUpdated.get()) {
            cacheManager.saveDescriptorCache(descriptorCache)
        }

        val groups = recluster(photos, descriptorCache, serverUrl, apiKey, threshold, clusteringAlgo)
        ScanResult(photos, groups)
    }

    suspend fun recluster(
        photos: List<GalleryPhoto>,
        serverUrl: String,
        apiKey: String,
        threshold: Float,
        clusteringAlgo: String,
    ): List<PersonGroup> = withContext(Dispatchers.IO) {
        val descriptorCache = cacheManager.loadDescriptorCache()
        recluster(photos, descriptorCache, serverUrl, apiKey, threshold, clusteringAlgo)
    }

    private suspend fun recluster(
        photos: List<GalleryPhoto>,
        descriptorCache: Map<String, List<FloatArray>>,
        serverUrl: String,
        apiKey: String,
        threshold: Float,
        clusteringAlgo: String,
    ): List<PersonGroup> = withContext(Dispatchers.IO) {
        val allEmbeddings = mutableListOf<FloatArray>()
        val photoIndices = mutableListOf<GalleryPhoto>()

        for (photo in photos) {
            val descriptors = descriptorCache[photo.uri.toString()] ?: continue
            for (descriptor in descriptors) {
                allEmbeddings.add(descriptor)
                photoIndices.add(photo)
            }
        }

        if (allEmbeddings.isEmpty()) {
            return@withContext emptyList()
        }

        val clusterResult = try {
            serverClient.groupClusters(
                embeddings = allEmbeddings,
                threshold = threshold,
                algo = clusteringAlgo,
                baseUrl = serverUrl,
                apiKey = apiKey,
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext emptyList()
        }

        val groupMap = mutableMapOf<Int, LinkedHashMap<Long, GalleryPhoto>>()
        for (i in clusterResult.labels.indices) {
            val label = clusterResult.labels[i]
            if (label >= 0) {
                val photo = photoIndices[i]
                groupMap.getOrPut(label) { LinkedHashMap() }[photo.id] = photo
            }
        }

        groupMap.entries
            .sortedByDescending { it.value.size }
            .mapIndexed { index, entry ->
                PersonGroup(index, entry.value.values.toList())
            }
    }

    private fun loadCompressedJpeg(photo: GalleryPhoto, maxDimension: Int = 1280): ByteArray? {
        val resolver = context.contentResolver
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(photo.uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
            if (options.outWidth <= 0 || options.outHeight <= 0) return null

            val sampleSize = calculateInSampleSize(options, maxDimension, maxDimension)
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inJustDecodeBounds = false
            }

            val bitmap = resolver.openInputStream(photo.uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return null

            val scaledBitmap = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
                val scale = maxDimension.toFloat() / maxOf(bitmap.width, bitmap.height)
                val newW = (bitmap.width * scale).toInt().coerceAtLeast(1)
                val newH = (bitmap.height * scale).toInt().coerceAtLeast(1)
                val scaled = bitmap.scale(newW, newH, filter = true)
                if (scaled != bitmap) bitmap.recycle()
                scaled
            } else {
                bitmap
            }

            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
            if (scaledBitmap != bitmap) {
                scaledBitmap.recycle()
            }

            outputStream.toByteArray()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun queryPhotos(): List<GalleryPhoto> {
        val result = mutableListOf<GalleryPhoto>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)

        context.contentResolver.query(
            collection,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                result += GalleryPhoto(id, ContentUris.withAppendedId(collection, id))
            }
        }
        return result
    }

    private fun queryFolderPhotos(treeUri: Uri): List<GalleryPhoto> {
        val result = mutableListOf<GalleryPhoto>()
        try {
            val rootDocId = if (DocumentsContract.isTreeUri(treeUri)) {
                DocumentsContract.getTreeDocumentId(treeUri)
            } else {
                DocumentsContract.getDocumentId(treeUri)
            }
            traverseDirectory(treeUri, rootDocId, result)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }

    private fun traverseDirectory(treeUri: Uri, parentDocId: String, result: MutableList<GalleryPhoto>) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val docId = cursor.getString(idIndex)
                val mimeType = cursor.getString(mimeIndex)
                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    traverseDirectory(treeUri, docId, result)
                } else if (mimeType != null && mimeType.startsWith("image/")) {
                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    result.add(GalleryPhoto(docId.hashCode().toLong(), fileUri))
                }
            }
        }
    }
}
