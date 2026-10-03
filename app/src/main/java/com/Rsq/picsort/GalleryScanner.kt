package com.Rsq.picsort

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Size
import androidx.core.graphics.scale
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class GalleryScanner(private val context: Context) {
    private val cacheManager = ScanCacheManager(context)

    suspend fun scan(
        folderUri: Uri? = null,
        threshold: Float = 0.78f,
        onProgress: (Int, Int) -> Unit,
    ): ScanResult = withContext(Dispatchers.IO) {
        val photos = if (folderUri != null) queryFolderPhotos(folderUri) else queryPhotos()
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .build()
        val detector = FaceDetection.getClient(options)
        val clusterer = FaceClusterer(threshold)

        val descriptorCache = cacheManager.loadDescriptorCache()
        var cacheUpdated = false

        try {
            photos.forEachIndexed { index, photo ->
                val cacheKey = photo.uri.toString()
                val cachedDescriptors = descriptorCache[cacheKey]

                if (cachedDescriptors != null) {
                    cachedDescriptors.forEach { descriptor ->
                        clusterer.add(descriptor, photo)
                    }
                } else {
                    val bitmap: Bitmap? = runCatching<Bitmap?> {
                        loadThumbnail(photo)
                    }.getOrNull()

                    val newDescriptors = mutableListOf<FloatArray>()
                    if (bitmap != null) {
                        val faces: List<Face>? = runCatching<List<Face>> {
                            detector.process(InputImage.fromBitmap(bitmap, 0)).await()
                        }.getOrNull()
                        faces?.forEach { face ->
                            faceDescriptor(bitmap, face.boundingBox)?.let { descriptor ->
                                clusterer.add(descriptor, photo)
                                newDescriptors.add(descriptor)
                            }
                        }
                    }
                    descriptorCache[cacheKey] = newDescriptors
                    cacheUpdated = true
                }

                if ((index + 1) % 10 == 0 || index + 1 == photos.size) {
                    withContext(Dispatchers.Main) { onProgress(index + 1, photos.size) }
                }
            }

            if (cacheUpdated) {
                cacheManager.saveDescriptorCache(descriptorCache)
            }
        } finally {
            detector.close()
        }

        ScanResult(photos, clusterer.groups())
    }

    private fun loadThumbnail(photo: GalleryPhoto): Bitmap? {
        val resolver = context.contentResolver
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.loadThumbnail(photo.uri, Size(512, 512), null)
            } else {
                decodeSampledBitmapFromUri(resolver, photo.uri, 512, 512)
            }
        } catch (_: Exception) {
            decodeSampledBitmapFromUri(resolver, photo.uri, 512, 512)
        }
    }

    private fun decodeSampledBitmapFromUri(resolver: ContentResolver, uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
            if (options.outWidth <= 0 || options.outHeight <= 0) return null
            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        } catch (_: Exception) {
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

    private fun faceDescriptor(bitmap: Bitmap, bounds: Rect): FloatArray? {
        val paddingX = (bounds.width() * 0.16f).toInt()
        val paddingY = (bounds.height() * 0.16f).toInt()
        val left = max(0, bounds.left - paddingX)
        val top = max(0, bounds.top - paddingY)
        val right = min(bitmap.width, bounds.right + paddingX)
        val bottom = min(bitmap.height, bounds.bottom + paddingY)
        if (right - left < 12 || bottom - top < 12) return null

        val face = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        val normalized = face.scale(16, 16, filter = true)
        val pixels = IntArray(256)
        normalized.getPixels(pixels, 0, 16, 0, 0, 16, 16)
        val gray = FloatArray(256) { index ->
            val color = pixels[index]
            (0.299f * ((color shr 16) and 0xff) +
                0.587f * ((color shr 8) and 0xff) +
                0.114f * (color and 0xff)) / 255f
        }

        val mean = gray.average().toFloat()
        val deviation = sqrt(gray.sumOf { ((it - mean) * (it - mean)).toDouble() } / gray.size)
            .toFloat()
            .coerceAtLeast(0.04f)
        val intensity = FloatArray(gray.size) { (gray[it] - mean) / deviation }
        val histograms = FloatArray(4 * 4 * 8)
        for (y in 1 until 15) {
            for (x in 1 until 15) {
                val dx = gray[y * 16 + x + 1] - gray[y * 16 + x - 1]
                val dy = gray[(y + 1) * 16 + x] - gray[(y - 1) * 16 + x]
                val magnitude = sqrt(dx * dx + dy * dy)
                val angle = (atan2(dy, dx) + PI.toFloat()) % PI.toFloat()
                val bin = (angle / PI.toFloat() * 8).toInt().coerceIn(0, 7)
                val cell = ((y / 4) * 4 + x / 4) * 8 + bin
                histograms[cell] += magnitude
            }
        }

        val descriptor = intensity + histograms
        val length = sqrt(descriptor.sumOf { (it * it).toDouble() }).toFloat().coerceAtLeast(0.001f)
        return FloatArray(descriptor.size) { descriptor[it] / length }
    }
}