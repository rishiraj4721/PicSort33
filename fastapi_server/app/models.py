import os
from dataclasses import dataclass
from typing import Optional

from fastapi import Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel


@dataclass(frozen=True)
class Settings:
    min_face_size: int = int(os.getenv("MIN_FACE_SIZE", "60"))
    min_det_score: float = float(os.getenv("MIN_DET_SCORE", "0.6"))
    cluster_threshold: float = float(os.getenv("CLUSTER_THRESHOLD", "0.45"))
    cluster_algo: str = os.getenv("CLUSTER_ALGO", "chinese_whispers")
    max_image_size: int = int(os.getenv("MAX_IMAGE_SIZE", str(10 * 1024 * 1024)))
    embedding_dim: int = int(os.getenv("EMBEDDING_DIM", "512"))
    detector_name: str = os.getenv("DETECTOR", "yunet")
    embedder_name: str = os.getenv("EMBEDDER", "arcface_r50")


settings = Settings()


class APIError(Exception):
    def __init__(self, status_code: int, error: str, detail: str):
        self.status_code = status_code
        self.error = error
        self.detail = detail


class CompareReq(BaseModel):
    a: list[float]
    b: list[float]


class ClusterGroupReq(BaseModel):
    embeddings: list[list[float]]
    threshold: Optional[float] = None
    algo: Optional[str] = None
    min_cluster_size: int = 1


class ClusterAssignReq(BaseModel):
    embeddings: list[list[float]]
    centroids: list[list[float]]
    threshold: Optional[float] = None


async def api_error_handler(request: Request, exc: APIError):
    return JSONResponse(
        status_code=exc.status_code,
        content={"error": exc.error, "detail": exc.detail},
    )
