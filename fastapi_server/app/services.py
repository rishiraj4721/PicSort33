import os
from typing import Optional

import cv2
import numpy as np
from fastapi import Request

from app.models import APIError, ClusterAssignReq, ClusterGroupReq, CompareReq, Settings
from app.repositories import ClusterRepository, FaceAnalysisRepository


class FaceService:
    def __init__(
        self,
        settings: Settings,
        face_repository: Optional[FaceAnalysisRepository] = None,
    ):
        self.settings = settings
        self.face_repository = face_repository or FaceAnalysisRepository()

    async def analyze(
        self,
        request: Request,
        data: bytes,
        min_face_size: Optional[int] = None,
        min_det_score: Optional[float] = None,
    ) -> dict:
        content_type = (
            request.headers.get("content-type", "")
            .split(";")[0]
            .strip()
            .lower()
        )
        if content_type not in ["image/jpeg", "image/png"]:
            raise APIError(
                415,
                "unsupported_media_type",
                "Content type must be image/jpeg or image/png",
            )
        if len(data) > self.settings.max_image_size:
            raise APIError(
                413,
                "image_too_large",
                f"Image size exceeds limit of {self.settings.max_image_size} bytes",
            )

        image = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR)
        if image is None:
            raise APIError(400, "invalid_image", "Image bytes could not be decoded")

        current_min_face_size = (
            min_face_size
            if min_face_size is not None
            else int(os.getenv("MIN_FACE_SIZE", str(self.settings.min_face_size)))
        )
        current_min_det_score = (
            min_det_score
            if min_det_score is not None
            else float(os.getenv("MIN_DET_SCORE", str(self.settings.min_det_score)))
        )
        height, width = image.shape[:2]

        try:
            raw_faces = self.face_repository.get_analyzer().get(image)
        except APIError:
            raise
        except Exception as exc:
            raise APIError(500, "inference_failed", str(exc)) from exc

        faces = []
        for face in raw_faces:
            score = float(face.det_score)
            if score < current_min_det_score:
                continue

            bbox = [float(value) for value in face.bbox]
            face_width = bbox[2] - bbox[0]
            face_height = bbox[3] - bbox[1]
            face_size = max(face_width, face_height)
            if face_size < current_min_face_size:
                continue

            landmarks = []
            if hasattr(face, "kps") and face.kps is not None:
                landmarks = [
                    [float(point[0]), float(point[1])] for point in face.kps
                ]

            quality = {"size": int(round(face_size))}
            if hasattr(face, "pose") and face.pose is not None:
                try:
                    quality["pose_yaw"] = round(float(face.pose[0]), 2)
                except Exception:
                    pass

            x1 = max(0, int(bbox[0]))
            y1 = max(0, int(bbox[1]))
            x2 = min(width, int(bbox[2]))
            y2 = min(height, int(bbox[3]))
            if x2 > x1 and y2 > y1:
                crop = image[y1:y2, x1:x2]
                if crop.size > 0:
                    gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
                    quality["blur"] = round(
                        float(cv2.Laplacian(gray, cv2.CV_64F).var()),
                        2,
                    )

            embedding = (
                face.normed_embedding.tolist()
                if hasattr(face, "normed_embedding")
                else []
            )
            faces.append(
                {
                    "bbox": bbox,
                    "score": round(score, 4),
                    "landmarks": landmarks,
                    "quality": quality,
                    "embedding": embedding,
                }
            )

        return {
            "image": {"width": width, "height": height},
            "faces": faces,
        }

    def compare(self, request: CompareReq) -> dict:
        embedding_dim = int(
            os.getenv("EMBEDDING_DIM", str(self.settings.embedding_dim))
        )
        if len(request.a) != embedding_dim or len(request.b) != embedding_dim:
            raise APIError(
                400,
                "dimension_mismatch",
                f"Vector length differs from embedding_dim ({embedding_dim})",
            )

        vector_a = np.asarray(request.a, dtype=np.float32)
        vector_b = np.asarray(request.b, dtype=np.float32)
        norm_a = np.linalg.norm(vector_a)
        norm_b = np.linalg.norm(vector_b)
        if norm_a > 0 and norm_b > 0:
            similarity = float(
                np.dot(vector_a, vector_b) / (norm_a * norm_b)
            )
        else:
            similarity = 0.0
        similarity = max(-1.0, min(1.0, similarity))
        return {"similarity": round(similarity, 2)}


class ClusterService:
    def __init__(self, settings: Settings):
        self.settings = settings

    def list_algorithms(self) -> dict:
        return {
            "default": os.getenv("CLUSTER_ALGO", self.settings.cluster_algo),
            "algos": [
                {
                    "name": "chinese_whispers",
                    "description": "Graph-based; links embeddings above the similarity threshold",
                    "params": ["threshold"],
                },
                {
                    "name": "dbscan",
                    "description": "Density-based on cosine distance; can mark noise as -1",
                    "params": ["threshold", "min_cluster_size"],
                },
            ],
        }

    def group(self, request: ClusterGroupReq) -> dict:
        embedding_dim = int(
            os.getenv("EMBEDDING_DIM", str(self.settings.embedding_dim))
        )
        threshold = (
            request.threshold
            if request.threshold is not None
            else float(
                os.getenv(
                    "CLUSTER_THRESHOLD",
                    str(self.settings.cluster_threshold),
                )
            )
        )
        algorithm = (
            request.algo
            if request.algo is not None
            else os.getenv("CLUSTER_ALGO", self.settings.cluster_algo)
        )
        return ClusterRepository.group(
            request.embeddings,
            threshold,
            algorithm,
            request.min_cluster_size,
            embedding_dim,
        )

    def assign(self, request: ClusterAssignReq) -> dict:
        embedding_dim = int(
            os.getenv("EMBEDDING_DIM", str(self.settings.embedding_dim))
        )
        threshold = (
            request.threshold
            if request.threshold is not None
            else float(
                os.getenv(
                    "CLUSTER_THRESHOLD",
                    str(self.settings.cluster_threshold),
                )
            )
        )
        return ClusterRepository.assign(
            request.embeddings,
            request.centroids,
            threshold,
            embedding_dim,
        )
