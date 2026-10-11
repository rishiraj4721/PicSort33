package com.Rsq.picsort

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ServerApiException(
    val statusCode: Int,
    val errorCode: String?,
    override val message: String,
) : Exception(message)

data class HealthResponse(
    val status: String,
    val detector: String,
    val embedder: String,
    val embeddingDim: Int,
)

data class ClusterGroupResult(
    val labels: List<Int>,
    val numClusters: Int,
    val centroids: List<FloatArray>,
)

data class ClusterAssignment(
    val cluster: Int?,
    val similarity: Float,
)

data class AlgoInfo(
    val name: String,
    val description: String,
    val params: List<String>,
)

data class ClusterAlgosResponse(
    val defaultAlgo: String,
    val algos: List<AlgoInfo>,
)

class FaceServerClient {

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()

    private fun normalizeUrl(baseUrl: String): String {
        var clean = baseUrl.trim()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            clean = "http://$clean"
        }
        return clean.removeSuffix("/")
    }

    private fun applyApiKey(builder: Request.Builder, apiKey: String) {
        val key = apiKey.trim()
        if (key.isNotEmpty()) {
            builder.header("X-API-Key", key)
        }
    }

    private fun parseErrorResponse(responseCode: Int, responseBody: String?): ServerApiException {
        if (!responseBody.isNullOrBlank()) {
            try {
                val json = JSONObject(responseBody)
                val errCode = json.optString("error", "unknown_error")
                val detail = json.optString("detail", "HTTP $responseCode error")
                return ServerApiException(responseCode, errCode, detail)
            } catch (_: Exception) {}
        }
        return ServerApiException(responseCode, null, "HTTP $responseCode request failed")
    }

    suspend fun getHealth(baseUrl: String, apiKey: String): HealthResponse = withContext(Dispatchers.IO) {
        val rootUrl = normalizeUrl(baseUrl)
        val request = Request.Builder()
            .url("$rootUrl/health")
            .get()
            .apply { applyApiKey(this, apiKey) }
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val bodyString = response.body?.string()
            if (!response.isSuccessful) {
                throw parseErrorResponse(response.code, bodyString)
            }
            val json = JSONObject(bodyString ?: "{}")
            HealthResponse(
                status = json.optString("status", "ok"),
                detector = json.optString("detector", "unknown"),
                embedder = json.optString("embedder", "unknown"),
                embeddingDim = json.optInt("embedding_dim", 512),
            )
        }
    }

    suspend fun analyzeFace(
        imageBytes: ByteArray,
        baseUrl: String,
        apiKey: String,
        minFaceSize: Int? = null,
        minDetScore: Float? = null,
    ): List<FloatArray> = withContext(Dispatchers.IO) {
        val rootUrl = normalizeUrl(baseUrl)
        val urlBuilder = "$rootUrl/face/analyze".toHttpUrlOrNull()?.newBuilder()
            ?: throw IOException("Invalid base URL: $baseUrl")

        if (minFaceSize != null) {
            urlBuilder.addQueryParameter("min_face_size", minFaceSize.toString())
        }
        if (minDetScore != null) {
            urlBuilder.addQueryParameter("min_det_score", minDetScore.toString())
        }

        val requestBody = imageBytes.toRequestBody("image/jpeg".toMediaType())
        val request = Request.Builder()
            .url(urlBuilder.build())
            .post(requestBody)
            .apply { applyApiKey(this, apiKey) }
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val bodyString = response.body?.string()
            if (!response.isSuccessful) {
                throw parseErrorResponse(response.code, bodyString)
            }

            val json = JSONObject(bodyString ?: "{}")
            val facesArray = json.optJSONArray("faces") ?: JSONArray()
            val embeddings = mutableListOf<FloatArray>()

            for (i in 0 until facesArray.length()) {
                val faceObj = facesArray.getJSONObject(i)
                val embArray = faceObj.getJSONArray("embedding")
                val floatArray = FloatArray(embArray.length())
                for (j in 0 until embArray.length()) {
                    floatArray[j] = embArray.getDouble(j).toFloat()
                }
                embeddings.add(floatArray)
            }
            embeddings
        }
    }

    suspend fun groupClusters(
        embeddings: List<FloatArray>,
        threshold: Float,
        algo: String,
        baseUrl: String,
        apiKey: String,
        minClusterSize: Int = 1,
    ): ClusterGroupResult = withContext(Dispatchers.IO) {
        if (embeddings.isEmpty()) {
            return@withContext ClusterGroupResult(emptyList(), 0, emptyList())
        }

        val rootUrl = normalizeUrl(baseUrl)
        val bodyJson = JSONObject()

        val outerEmbeddingsArray = JSONArray()
        for (vec in embeddings) {
            val innerArray = JSONArray()
            for (v in vec) {
                innerArray.put(v.toDouble())
            }
            outerEmbeddingsArray.put(innerArray)
        }

        bodyJson.put("embeddings", outerEmbeddingsArray)
        bodyJson.put("threshold", threshold.toDouble())
        bodyJson.put("algo", algo)
        bodyJson.put("min_cluster_size", minClusterSize)

        val requestBody = bodyJson.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$rootUrl/cluster/group")
            .post(requestBody)
            .apply { applyApiKey(this, apiKey) }
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val bodyString = response.body?.string()
            if (!response.isSuccessful) {
                throw parseErrorResponse(response.code, bodyString)
            }

            val json = JSONObject(bodyString ?: "{}")
            val labelsArray = json.optJSONArray("labels") ?: JSONArray()
            val labelsList = mutableListOf<Int>()
            for (i in 0 until labelsArray.length()) {
                labelsList.add(labelsArray.getInt(i))
            }

            val numClusters = json.optInt("num_clusters", 0)

            val centroidsArray = json.optJSONArray("centroids") ?: JSONArray()
            val centroidsList = mutableListOf<FloatArray>()
            for (i in 0 until centroidsArray.length()) {
                val centArr = centroidsArray.getJSONArray(i)
                val cVec = FloatArray(centArr.length())
                for (j in 0 until centArr.length()) {
                    cVec[j] = centArr.getDouble(j).toFloat()
                }
                centroidsList.add(cVec)
            }

            ClusterGroupResult(labelsList, numClusters, centroidsList)
        }
    }

    suspend fun assignCluster(
        embeddings: List<FloatArray>,
        centroids: List<FloatArray>,
        threshold: Float,
        baseUrl: String,
        apiKey: String,
    ): List<ClusterAssignment> = withContext(Dispatchers.IO) {
        if (embeddings.isEmpty() || centroids.isEmpty()) {
            return@withContext emptyList()
        }

        val rootUrl = normalizeUrl(baseUrl)
        val bodyJson = JSONObject()

        val embJsonArray = JSONArray()
        for (vec in embeddings) {
            val innerArray = JSONArray()
            for (v in vec) innerArray.put(v.toDouble())
            embJsonArray.put(innerArray)
        }

        val centJsonArray = JSONArray()
        for (vec in centroids) {
            val innerArray = JSONArray()
            for (v in vec) innerArray.put(v.toDouble())
            centJsonArray.put(innerArray)
        }

        bodyJson.put("embeddings", embJsonArray)
        bodyJson.put("centroids", centJsonArray)
        bodyJson.put("threshold", threshold.toDouble())

        val requestBody = bodyJson.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$rootUrl/cluster/assign")
            .post(requestBody)
            .apply { applyApiKey(this, apiKey) }
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val bodyString = response.body?.string()
            if (!response.isSuccessful) {
                throw parseErrorResponse(response.code, bodyString)
            }

            val json = JSONObject(bodyString ?: "{}")
            val assignArray = json.optJSONArray("assignments") ?: JSONArray()
            val result = mutableListOf<ClusterAssignment>()

            for (i in 0 until assignArray.length()) {
                val item = assignArray.getJSONObject(i)
                val clusterVal = if (item.isNull("cluster")) null else item.getInt("cluster")
                val sim = item.optDouble("similarity", 0.0).toFloat()
                result.add(ClusterAssignment(clusterVal, sim))
            }
            result
        }
    }

    suspend fun getClusterAlgos(baseUrl: String, apiKey: String): ClusterAlgosResponse = withContext(Dispatchers.IO) {
        val rootUrl = normalizeUrl(baseUrl)
        val request = Request.Builder()
            .url("$rootUrl/cluster/algos")
            .get()
            .apply { applyApiKey(this, apiKey) }
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val bodyString = response.body?.string()
            if (!response.isSuccessful) {
                throw parseErrorResponse(response.code, bodyString)
            }

            val json = JSONObject(bodyString ?: "{}")
            val defaultAlgo = json.optString("default", "chinese_whispers")
            val algosArray = json.optJSONArray("algos") ?: JSONArray()
            val algoList = mutableListOf<AlgoInfo>()

            for (i in 0 until algosArray.length()) {
                val algoObj = algosArray.getJSONObject(i)
                val name = algoObj.optString("name", "")
                val desc = algoObj.optString("description", "")
                val paramsArray = algoObj.optJSONArray("params") ?: JSONArray()
                val params = mutableListOf<String>()
                for (j in 0 until paramsArray.length()) {
                    params.add(paramsArray.getString(j))
                }
                algoList.add(AlgoInfo(name, desc, params))
            }
            ClusterAlgosResponse(defaultAlgo, algoList)
        }
    }

    suspend fun compareFaces(
        a: FloatArray,
        b: FloatArray,
        baseUrl: String,
        apiKey: String,
    ): Float = withContext(Dispatchers.IO) {
        val rootUrl = normalizeUrl(baseUrl)
        val bodyJson = JSONObject()

        val aArray = JSONArray()
        for (v in a) aArray.put(v.toDouble())

        val bArray = JSONArray()
        for (v in b) bArray.put(v.toDouble())

        bodyJson.put("a", aArray)
        bodyJson.put("b", bArray)

        val requestBody = bodyJson.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$rootUrl/face/compare")
            .post(requestBody)
            .apply { applyApiKey(this, apiKey) }
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val bodyString = response.body?.string()
            if (!response.isSuccessful) {
                throw parseErrorResponse(response.code, bodyString)
            }

            val json = JSONObject(bodyString ?: "{}")
            json.optDouble("similarity", 0.0).toFloat()
        }
    }
}
