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
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class GalleryScanner(private val context: Context) {
    private val cacheManager = ScanCacheManager(context)

    suspend fun scan(
        folderUri: Uri? = null,
        threshold: Float = 0.82f,
        onProgress: (Int, Int) -> Unit,
    ): ScanResult = withContext(Dispatchers.IO) {
        val photos = if (folderUri != null) queryFolderPhotos(folderUri) else queryPhotos()
        if (photos.isEmpty()) return@withContext ScanResult(emptyList(), emptyList())

        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .build()

        val concurrencyLimit = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val detectorPool = ConcurrentLinkedQueue<FaceDetector>()

        fun acquireDetector(): FaceDetector = detectorPool.poll() ?: FaceDetection.getClient(options)
        fun releaseDetector(detector: FaceDetector) { detectorPool.offer(detector) }

        val clusterer = FaceClusterer(threshold)
        val descriptorCache = ConcurrentHashMap(cacheManager.loadDescriptorCache())
        val cacheUpdated = AtomicBoolean(false)
        val completedCount = AtomicInteger(0)
        val semaphore = Semaphore(concurrencyLimit)

        try {
            coroutineScope {
                photos.map { photo ->
                    async(Dispatchers.Default) {
                        semaphore.withPermit {
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
                                    val detector = acquireDetector()
                                    val faces: List<Face>? = try {
                                        detector.process(InputImage.fromBitmap(bitmap, 0)).await()
                                    } catch (_: Exception) {
                                        null
                                    } finally {
                                        releaseDetector(detector)
                                    }

                                    faces?.forEach { face ->
                                        faceDescriptor(bitmap, face)?.let { descriptor ->
                                            clusterer.add(descriptor, photo)
                                            newDescriptors.add(descriptor)
                                        }
                                    }
                                }
                                descriptorCache[cacheKey] = newDescriptors
                                cacheUpdated.set(true)
                            }

                            val done = completedCount.incrementAndGet()
                            if (done % 10 == 0 || done == photos.size) {
                                withContext(Dispatchers.Main) { onProgress(done, photos.size) }
                            }
                        }
                    }
                }.awaitAll()
            }

            if (cacheUpdated.get()) {
                cacheManager.saveDescriptorCache(descriptorCache)
            }
        } finally {
            while (true) {
                val d = detectorPool.poll() ?: break
                try {
                    d.close()
                } catch (_: Exception) {}
            }
        }

        ScanResult(photos, clusterer.groups())
    }

    private fun loadThumbnail(photo: GalleryPhoto): Bitmap? {
        val resolver = context.contentResolver
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.loadThumbnail(photo.uri, Size(320, 320), null)
            } else {
                decodeSampledBitmapFromUri(resolver, photo.uri, 320, 320)
            }
        } catch (_: Exception) {
            decodeSampledBitmapFromUri(resolver, photo.uri, 320, 320)
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

    private fun faceDescriptor(bitmap: Bitmap, face: Face): FloatArray? {
        val bounds = face.boundingBox
        val paddingX = (bounds.width() * 0.15f).toInt()
        val paddingY = (bounds.height() * 0.15f).toInt()
        val left = max(0, bounds.left - paddingX)
        val top = max(0, bounds.top - paddingY)
        val right = min(bitmap.width, bounds.right + paddingX)
        val bottom = min(bitmap.height, bounds.bottom + paddingY)
        if (right - left < 16 || bottom - top < 16) return null

        val geoFeatures = extractGeometricFeatures(face, bounds)

        val faceCrop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        val scaled = faceCrop.scale(32, 32, filter = true)
        val pixels = IntArray(1024)
        scaled.getPixels(pixels, 0, 32, 0, 0, 32, 32)

        val gray = FloatArray(1024) { index ->
            val color = pixels[index]
            (0.299f * ((color shr 16) and 0xff) +
                0.587f * ((color shr 8) and 0xff) +
                0.114f * (color and 0xff)) / 255f
        }

        val hog = FloatArray(8 * 8 * 8)
        for (y in 1 until 31) {
            for (x in 1 until 31) {
                val dx = gray[y * 32 + x + 1] - gray[y * 32 + x - 1]
                val dy = gray[(y + 1) * 32 + x] - gray[(y - 1) * 32 + x]
                val magnitude = sqrt(dx * dx + dy * dy)
                val angle = (atan2(dy, dx) + PI.toFloat()) % PI.toFloat()
                val bin = (angle / PI.toFloat() * 8).toInt().coerceIn(0, 7)
                val cell = ((y / 4) * 8 + x / 4) * 8 + bin
                hog[cell] += magnitude
            }
        }

        val rawDescriptor = geoFeatures + hog
        val length = sqrt(rawDescriptor.sumOf { (it * it).toDouble() }).toFloat().coerceAtLeast(0.001f)
        return FloatArray(rawDescriptor.size) { rawDescriptor[it] / length }
    }

    private fun extractGeometricFeatures(face: Face, bounds: Rect): FloatArray {
        val geo = FloatArray(16)
        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position
        val mouthLeft = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
        val mouthRight = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position

        if (leftEye != null && rightEye != null && nose != null && mouthLeft != null && mouthRight != null) {
            val eyeDist = distance(leftEye.x, leftEye.y, rightEye.x, rightEye.y).coerceAtLeast(1f)
            val eyeCenterX = (leftEye.x + rightEye.x) / 2f
            val eyeCenterY = (leftEye.y + rightEye.y) / 2f
            val mouthCenterX = (mouthLeft.x + mouthRight.x) / 2f
            val mouthCenterY = (mouthLeft.y + mouthRight.y) / 2f

            geo[0] = distance(eyeCenterX, eyeCenterY, nose.x, nose.y) / eyeDist
            geo[1] = distance(nose.x, nose.y, mouthCenterX, mouthCenterY) / eyeDist
            geo[2] = distance(mouthLeft.x, mouthLeft.y, mouthRight.x, mouthRight.y) / eyeDist
            geo[3] = distance(leftEye.x, leftEye.y, nose.x, nose.y) / eyeDist
            geo[4] = distance(rightEye.x, rightEye.y, nose.x, nose.y) / eyeDist
            geo[5] = distance(leftEye.x, leftEye.y, mouthLeft.x, mouthLeft.y) / eyeDist
            geo[6] = distance(rightEye.x, rightEye.y, mouthRight.x, mouthRight.y) / eyeDist
            geo[7] = bounds.width().toFloat() / bounds.height().toFloat()
        }
        return geo
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return sqrt(dx * dx + dy * dy)
    }
}