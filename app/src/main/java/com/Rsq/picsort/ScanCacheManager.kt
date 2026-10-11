package com.Rsq.picsort

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ScanCacheManager(private val context: Context) {

    private fun getCacheFile(sourceKey: String): File {
        val safeKey = sourceKey.hashCode().toString()
        return File(context.filesDir, "scan_cache_v3_$safeKey.json")
    }

    private fun getDescriptorCacheFile(): File {
        return File(context.cacheDir, "descriptors_cache_v3.json")
    }

    fun loadScanResult(sourceKey: String): ScanResult? {
        val file = getCacheFile(sourceKey)
        if (!file.exists()) return null

        return try {
            val jsonStr = file.readText()
            val root = JSONObject(jsonStr)

            val photosArray = root.getJSONArray("photos")
            val photosMap = mutableMapOf<Long, GalleryPhoto>()
            val photosList = mutableListOf<GalleryPhoto>()

            for (i in 0 until photosArray.length()) {
                val item = photosArray.getJSONObject(i)
                val id = item.getLong("id")
                val uri = Uri.parse(item.getString("uri"))
                val photo = GalleryPhoto(id, uri)
                photosMap[id] = photo
                photosList.add(photo)
            }

            val groupsArray = root.getJSONArray("groups")
            val groupsList = mutableListOf<PersonGroup>()

            for (i in 0 until groupsArray.length()) {
                val groupObj = groupsArray.getJSONObject(i)
                val groupId = groupObj.getInt("id")
                val idsArray = groupObj.getJSONArray("photoIds")
                val groupPhotos = mutableListOf<GalleryPhoto>()

                for (j in 0 until idsArray.length()) {
                    val pId = idsArray.getLong(j)
                    photosMap[pId]?.let { groupPhotos.add(it) }
                }

                if (groupPhotos.isNotEmpty()) {
                    groupsList.add(PersonGroup(groupId, groupPhotos))
                }
            }

            ScanResult(photosList, groupsList)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun saveScanResult(sourceKey: String, result: ScanResult) {
        try {
            val root = JSONObject()

            val photosArray = JSONArray()
            for (photo in result.photos) {
                val item = JSONObject()
                item.put("id", photo.id)
                item.put("uri", photo.uri.toString())
                photosArray.put(item)
            }
            root.put("photos", photosArray)

            val groupsArray = JSONArray()
            for (group in result.groups) {
                val groupObj = JSONObject()
                groupObj.put("id", group.id)
                val idsArray = JSONArray()
                for (photo in group.photos) {
                    idsArray.put(photo.id)
                }
                groupObj.put("photoIds", idsArray)
                groupsArray.put(groupObj)
            }
            root.put("groups", groupsArray)

            val file = getCacheFile(sourceKey)
            file.writeText(root.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadDescriptorCache(): MutableMap<String, List<FloatArray>> {
        val map = mutableMapOf<String, List<FloatArray>>()
        val file = getDescriptorCacheFile()
        if (!file.exists()) return map

        try {
            val jsonStr = file.readText()
            val root = JSONObject(jsonStr)
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val descriptorsArray = root.getJSONArray(key)
                val descriptorList = mutableListOf<FloatArray>()
                for (i in 0 until descriptorsArray.length()) {
                    val floatsArray = descriptorsArray.getJSONArray(i)
                    val floats = FloatArray(floatsArray.length())
                    for (j in 0 until floatsArray.length()) {
                        floats[j] = floatsArray.getDouble(j).toFloat()
                    }
                    descriptorList.add(floats)
                }
                map[key] = descriptorList
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return map
    }

    fun saveDescriptorCache(cacheMap: Map<String, List<FloatArray>>) {
        try {
            val root = JSONObject()
            for ((key, descriptors) in cacheMap) {
                val descriptorsArray = JSONArray()
                for (descriptor in descriptors) {
                    val floatsArray = JSONArray()
                    for (value in descriptor) {
                        floatsArray.put(value.toDouble())
                    }
                    descriptorsArray.put(floatsArray)
                }
                root.put(key, descriptorsArray)
            }
            val file = getDescriptorCacheFile()
            file.writeText(root.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun clearAllCache() {
        try {
            context.filesDir.listFiles()?.forEach { file ->
                if (file.name.startsWith("scan_cache")) {
                    file.delete()
                }
            }
            getDescriptorCacheFile().delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}