package com.Rsq.picsort

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.scale
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import kotlin.math.sqrt

class FaceEmbeddingModel(context: Context) : AutoCloseable {
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private val modelFileName = "vit_tiny_arcface.onnx"

    init {
        try {
            val modelPath = prepareModelFile(context)
            if (modelPath != null) {
                env = OrtEnvironment.getEnvironment()
                session = env?.createSession(modelPath, OrtSession.SessionOptions())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    val isLoaded: Boolean
        get() = session != null

    private fun prepareModelFile(context: Context): String? {
        val cacheDir = context.cacheDir
        val modelFile = File(cacheDir, modelFileName)
        val dataFile = File(cacheDir, "$modelFileName.data")
        val assetManager = context.assets

        try {
            if (!modelFile.exists()) {
                assetManager.open(modelFileName).use { input ->
                    FileOutputStream(modelFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            if (!dataFile.exists()) {
                runCatching {
                    assetManager.open("$modelFileName.data").use { input ->
                        FileOutputStream(dataFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            return null
        }

        return if (modelFile.exists()) modelFile.absolutePath else null
    }

    @Synchronized
    fun getEmbedding(croppedFace: Bitmap): FloatArray? {
        val sess = session ?: return null
        val environment = env ?: return null

        return try {
            val scaled = croppedFace.scale(112, 112, filter = true)
            val pixels = IntArray(112 * 112)
            scaled.getPixels(pixels, 0, 112, 0, 0, 112, 112)

            // NCHW tensor input buffer for shape [1, 3, 112, 112]
            val inputBuffer = FloatBuffer.allocate(1 * 3 * 112 * 112)
            val imageSize = 112 * 112

            for (i in 0 until imageSize) {
                val color = pixels[i]
                val r = ((color shr 16) and 0xFF) / 255.0f
                val g = ((color shr 8) and 0xFF) / 255.0f
                val b = (color and 0xFF) / 255.0f

                // Normalization to [-1.0, 1.0]: (x - 0.5) / 0.5
                inputBuffer.put(i, r * 2.0f - 1.0f)
                inputBuffer.put(imageSize + i, g * 2.0f - 1.0f)
                inputBuffer.put(2 * imageSize + i, b * 2.0f - 1.0f)
            }

            inputBuffer.rewind()

            val inputName = sess.inputNames.iterator().next()
            val inputTensor = OnnxTensor.createTensor(
                environment,
                inputBuffer,
                longArrayOf(1, 3, 112, 112),
            )

            inputTensor.use { tensor ->
                sess.run(mapOf(inputName to tensor)).use { results ->
                    val outputValue = results[0].value
                    val rawArray = when (outputValue) {
                        is Array<*> -> outputValue[0] as? FloatArray
                        is FloatArray -> outputValue
                        else -> null
                    } ?: return null

                    l2Normalize(rawArray)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun l2Normalize(vector: FloatArray): FloatArray {
        var sumSquare = 0.0f
        for (v in vector) {
            sumSquare += v * v
        }
        val norm = sqrt(sumSquare).coerceAtLeast(1e-6f)
        return FloatArray(vector.size) { i -> vector[i] / norm }
    }

    override fun close() {
        try {
            session?.close()
            env?.close()
        } catch (_: Exception) {}
        session = null
        env = null
    }
}
