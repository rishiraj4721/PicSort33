package com.Rsq.picsort

import kotlin.math.sqrt

class FaceClusterer(private val threshold: Float = 0.78f) {
    private data class Cluster(
        var center: FloatArray,
        var faceCount: Int,
        val photos: LinkedHashMap<Long, GalleryPhoto>,
    )

    private val clusters = mutableListOf<Cluster>()

    fun add(descriptor: FloatArray, photo: GalleryPhoto) {
        val best = clusters
            .map { cluster -> cluster to cosineSimilarity(descriptor, cluster.center) }
            .maxByOrNull { it.second }

        if (best == null || best.second < threshold) {
            clusters += Cluster(descriptor.copyOf(), 1, linkedMapOf(photo.id to photo))
            return
        }

        val cluster = best.first
        val updated = FloatArray(descriptor.size) { index ->
            (cluster.center[index] * cluster.faceCount + descriptor[index]) / (cluster.faceCount + 1)
        }
        val length = sqrt(updated.sumOf { (it * it).toDouble() }).toFloat().coerceAtLeast(0.001f)
        cluster.center = FloatArray(updated.size) { updated[it] / length }
        cluster.faceCount += 1
        cluster.photos[photo.id] = photo
    }

    fun groups(): List<PersonGroup> = clusters
        .sortedByDescending { it.photos.size }
        .mapIndexed { index, cluster -> PersonGroup(index, cluster.photos.values.toList()) }

    private fun cosineSimilarity(first: FloatArray, second: FloatArray): Float =
        first.indices.sumOf { (first[it] * second[it]).toDouble() }.toFloat()
}