import os
import random

import numpy as np

from app.models import APIError


class FaceAnalysisRepository:
    def __init__(self):
        self._analyzer = None

    def get_analyzer(self):
        if self._analyzer is None:
            try:
                from insightface.app import FaceAnalysis

                analyzer = FaceAnalysis(
                    name=os.getenv("INSIGHTFACE_MODEL", "buffalo_s"),
                    providers=["CUDAExecutionProvider"],
                )
                analyzer.prepare(ctx_id=0, det_size=(640, 640))
            except Exception as exc:
                raise APIError(
                    500,
                    "inference_failed",
                    f"Failed to load FaceAnalysis model: {exc}",
                ) from exc
            self._analyzer = analyzer
        return self._analyzer


class ClusterRepository:
    @staticmethod
    def run_chinese_whispers(
        similarity_matrix: np.ndarray,
        threshold: float,
        max_iter: int = 20,
    ) -> list[int]:
        count = similarity_matrix.shape[0]
        if count == 0:
            return []

        adjacency: list[list[tuple[int, float]]] = [[] for _ in range(count)]
        for i in range(count):
            for j in range(i + 1, count):
                weight = float(similarity_matrix[i, j])
                if weight >= threshold:
                    adjacency[i].append((j, weight))
                    adjacency[j].append((i, weight))

        labels = list(range(count))
        rng = random.Random(42)
        nodes = list(range(count))

        for _ in range(max_iter):
            changed = False
            rng.shuffle(nodes)
            for node in nodes:
                if not adjacency[node]:
                    continue
                class_weights: dict[int, float] = {}
                for neighbor, weight in adjacency[node]:
                    label = labels[neighbor]
                    class_weights[label] = class_weights.get(label, 0.0) + weight
                best_label = max(
                    class_weights.items(),
                    key=lambda item: (item[1], -item[0]),
                )[0]
                if labels[node] != best_label:
                    labels[node] = best_label
                    changed = True
            if not changed:
                break

        return labels

    @staticmethod
    def group(
        embeddings: list[list[float]],
        threshold: float,
        algorithm: str,
        min_cluster_size: int,
        embedding_dim: int,
    ) -> dict:
        if not embeddings:
            raise APIError(400, "empty_input", "Empty embeddings list")
        for embedding in embeddings:
            if len(embedding) != embedding_dim:
                raise APIError(
                    400,
                    "dimension_mismatch",
                    f"Vector length differs from embedding_dim ({embedding_dim})",
                )

        vectors = np.asarray(embeddings, dtype=np.float32)
        norms = np.linalg.norm(vectors, axis=1, keepdims=True)
        norms[norms == 0] = 1.0
        normalized = vectors / norms

        if algorithm == "chinese_whispers":
            similarity_matrix = np.dot(normalized, normalized.T)
            raw_labels = ClusterRepository.run_chinese_whispers(
                similarity_matrix,
                threshold=threshold,
            )
        elif algorithm == "dbscan":
            from sklearn.cluster import DBSCAN

            eps = max(1e-6, float(1.0 - threshold))
            clustering = DBSCAN(
                eps=eps,
                min_samples=min_cluster_size,
                metric="cosine",
            )
            raw_labels = clustering.fit_predict(normalized).tolist()
        else:
            raise APIError(
                400,
                "invalid_algo",
                f"Unsupported algorithm '{algorithm}'. Supported: chinese_whispers, dbscan",
            )

        label_map: dict[int, int] = {}
        next_label = 0
        labels: list[int] = []
        for label in raw_labels:
            if label == -1:
                labels.append(-1)
            else:
                if label not in label_map:
                    label_map[label] = next_label
                    next_label += 1
                labels.append(label_map[label])

        centroids: list[list[float]] = []
        for cluster_id in range(next_label):
            cluster_indices = [
                index for index, label in enumerate(labels) if label == cluster_id
            ]
            mean_vector = np.mean(normalized[cluster_indices], axis=0)
            mean_norm = np.linalg.norm(mean_vector)
            if mean_norm > 0:
                mean_vector = mean_vector / mean_norm
            centroids.append(mean_vector.tolist())

        return {
            "labels": labels,
            "num_clusters": next_label,
            "centroids": centroids,
        }

    @staticmethod
    def assign(
        embeddings: list[list[float]],
        centroids: list[list[float]],
        threshold: float,
        embedding_dim: int,
    ) -> dict:
        if not embeddings:
            raise APIError(400, "empty_input", "Empty embeddings list")
        for embedding in embeddings:
            if len(embedding) != embedding_dim:
                raise APIError(
                    400,
                    "dimension_mismatch",
                    f"Vector length differs from embedding_dim ({embedding_dim})",
                )
        for centroid in centroids:
            if len(centroid) != embedding_dim:
                raise APIError(
                    400,
                    "dimension_mismatch",
                    f"Vector length differs from embedding_dim ({embedding_dim})",
                )

        if not centroids:
            return {
                "assignments": [
                    {"cluster": None, "similarity": 0.0} for _ in embeddings
                ]
            }

        vectors = np.asarray(embeddings, dtype=np.float32)
        vector_norms = np.linalg.norm(vectors, axis=1, keepdims=True)
        vector_norms[vector_norms == 0] = 1.0
        normalized_vectors = vectors / vector_norms

        centroid_vectors = np.asarray(centroids, dtype=np.float32)
        centroid_norms = np.linalg.norm(centroid_vectors, axis=1, keepdims=True)
        centroid_norms[centroid_norms == 0] = 1.0
        normalized_centroids = centroid_vectors / centroid_norms
        similarity_matrix = np.dot(normalized_vectors, normalized_centroids.T)

        assignments = []
        for index in range(len(embeddings)):
            row = similarity_matrix[index]
            best_cluster = int(np.argmax(row))
            best_similarity = float(row[best_cluster])
            best_similarity = max(-1.0, min(1.0, best_similarity))
            rounded_similarity = round(best_similarity, 2)
            if best_similarity >= threshold:
                assignments.append(
                    {"cluster": best_cluster, "similarity": rounded_similarity}
                )
            else:
                assignments.append(
                    {"cluster": None, "similarity": rounded_similarity}
                )

        return {"assignments": assignments}
