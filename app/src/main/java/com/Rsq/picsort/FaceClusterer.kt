package com.Rsq.picsort

import kotlin.math.min

class FaceClusterer(private val threshold: Float = 0.82f) {
    private data class Cluster(
        val exemplars: MutableList<FloatArray>,
        val photos: LinkedHashMap<Long, GalleryPhoto>,
    )

    private val clusters = mutableListOf<Cluster>()

    @Synchronized
    fun add(descriptor: FloatArray, photo: GalleryPhoto) {
        var bestCluster: Cluster? = null
        var bestSimilarity = -1f

        for (cluster in clusters) {
            for (exemplar in cluster.exemplars) {
                val sim = cosineSimilarity(descriptor, exemplar)
                if (sim > bestSimilarity) {
                    bestSimilarity = sim
                    bestCluster = cluster
                }
            }
        }

        if (bestCluster == null || bestSimilarity < threshold) {
            val exemplars = mutableListOf(descriptor.copyOf())
            clusters += Cluster(exemplars, linkedMapOf(photo.id to photo))
            return
        }

        bestCluster.photos[photo.id] = photo

        // Keep distinct facial angles/expressions as additional exemplars
        if (bestCluster.exemplars.size < 6) {
            val minSimToExemplars = bestCluster.exemplars.minOf { cosineSimilarity(descriptor, it) }
            if (minSimToExemplars < 0.94f) {
                bestCluster.exemplars.add(descriptor.copyOf())
            }
        }
    }

    @Synchronized
    fun groups(): List<PersonGroup> = clusters
        .sortedByDescending { it.photos.size }
        .mapIndexed { index, cluster -> PersonGroup(index, cluster.photos.values.toList()) }

    private fun cosineSimilarity(first: FloatArray, second: FloatArray): Float {
        var dot = 0f
        val size = min(first.size, second.size)
        for (i in 0 until size) {
            dot += first[i] * second[i]
        }
        return dot
    }
}