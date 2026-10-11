import os
import logging
import time

from dotenv import load_dotenv

load_dotenv()

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from app.controllers import ClusterController, FaceController
from app.models import APIError, api_error_handler, settings
from app.services import ClusterService, FaceService

request_logger = logging.getLogger("app.requests")
logging.getLogger("uvicorn.access").setLevel(logging.WARNING)


def create_app() -> FastAPI:
    app = FastAPI(title="Face Grouping Server", version="1.0.0")
    face_controller = FaceController(FaceService(settings))
    cluster_controller = ClusterController(ClusterService(settings))

    app.add_exception_handler(APIError, api_error_handler)
    app.include_router(face_controller.router)
    app.include_router(cluster_controller.router)

    @app.middleware("http")
    async def auth_middleware(request: Request, call_next):
        started_at = time.perf_counter()
        if request.url.path in ["/docs", "/openapi.json", "/redoc"]:
            response = await call_next(request)
        else:
            api_key = os.getenv("API_KEY")
            if api_key:
                req_key = request.headers.get("x-api-key")
                if not req_key or req_key != api_key:
                    response = JSONResponse(
                        status_code=401,
                        content={
                            "error": "unauthorized",
                            "detail": "Missing or wrong X-API-Key",
                        },
                    )
                else:
                    response = await call_next(request)
            else:
                response = await call_next(request)

        if response.status_code != 200:
            message = "%s %s returned %s in %.2f ms"
            args = (
                request.method,
                request.url.path,
                response.status_code,
                (time.perf_counter() - started_at) * 1000,
            )
            if response.status_code >= 500:
                request_logger.error(message, *args)
            else:
                request_logger.warning(message, *args)

        return response

    @app.get("/health")
    def health():
        return {
            "status": "ok",
            "detector": settings.detector_name,
            "embedder": settings.embedder_name,
            "embedding_dim": settings.embedding_dim,
        }

    return app


app = create_app()


def main():
    import uvicorn

    uvicorn.run("main:app", host="0.0.0.0", port=int(os.getenv("PORT", "8000")))


if __name__ == "__main__":
    main()
