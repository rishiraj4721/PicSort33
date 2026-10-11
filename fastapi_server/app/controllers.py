from typing import Optional

from fastapi import APIRouter, Query, Request

from app.models import ClusterAssignReq, ClusterGroupReq, CompareReq
from app.services import ClusterService, FaceService


class FaceController:
    def __init__(self, service: FaceService):
        self.service = service
        self.router = APIRouter()
        self.router.add_api_route(
            "/face/analyze",
            self.analyze,
            methods=["POST"],
        )
        self.router.add_api_route(
            "/face/compare",
            self.compare,
            methods=["POST"],
        )

    async def analyze(
        self,
        request: Request,
        min_face_size: Optional[int] = Query(None),
        min_det_score: Optional[float] = Query(None),
    ):
        data = await request.body()
        return await self.service.analyze(
            request,
            data,
            min_face_size=min_face_size,
            min_det_score=min_det_score,
        )

    def compare(self, request: CompareReq):
        return self.service.compare(request)


class ClusterController:
    def __init__(self, service: ClusterService):
        self.service = service
        self.router = APIRouter()
        self.router.add_api_route(
            "/cluster/algos",
            self.list_algorithms,
            methods=["GET"],
        )
        self.router.add_api_route(
            "/cluster/group",
            self.group,
            methods=["POST"],
        )
        self.router.add_api_route(
            "/cluster/assign",
            self.assign,
            methods=["POST"],
        )

    def list_algorithms(self):
        return self.service.list_algorithms()

    def group(self, request: ClusterGroupReq):
        return self.service.group(request)

    def assign(self, request: ClusterAssignReq):
        return self.service.assign(request)
