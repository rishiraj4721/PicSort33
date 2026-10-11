
# Face Grouping Server API

Oct 11, 2026 · @Man

## Overview

This is a stateless HTTP API that detects faces in photos, returns embeddings, and groups them by person. The server stores no photos, embeddings or settings: every request carries its own input and the data is discarded when the response is sent.

Base URL: `http://<host>:8000`

Run the application with `python main.py` or `uvicorn main:app --host 0.0.0.0 --port 8000`.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/health` | Liveness check and model info |
| POST | `/face/analyze` | Detect, align and embed every face in one image |
| POST | `/face/compare` | Cosine similarity of two face embeddings |
| GET | `/cluster/algos` | List supported clustering algorithms |
| POST | `/cluster/group` | Cluster a set of embeddings into people |
| POST | `/cluster/assign` | Assign new embeddings to known cluster centroids |

## Conventions

- **Images** are sent as a raw request body with `Content-Type: image/jpeg` or `image/png`, not multipart. This keeps photo data in memory and off the server disk.
- **Coordinates** are in pixels of the image you sent. If the app downsampled before upload, scale them back up by the same factor.
- **Embeddings** are L2-normalized float arrays, so cosine similarity is a plain dot product.
- **JSON endpoints** use `Content-Type: application/json`.
- **Auth:** send a shared secret in the `X-API-Key` header on every request (recommended even on a LAN).
- **Errors** use the shape `{"error": "<code>", "detail": "<message>"}` with the HTTP status listed under Status and error codes.
- **Docs:** interactive Swagger UI is served at `/docs` and the OpenAPI spec at `/openapi.json`.

## System

### GET /health

Returns liveness and the loaded model info. No request body.

Response `200`:

```json
{
  "status": "ok",
  "detector": "yunet",
  "embedder": "arcface_r50",
  "embedding_dim": 512
}
```

## Face endpoints

### POST /face/analyze

Detects, aligns and embeds every face in one image.

**Request:** raw image bytes with `Content-Type: image/jpeg` or `image/png`.

| Query param | Type | Default | Meaning |
| --- | --- | --- | --- |
| `min_face_size` | int (px) | 60 | Drop faces smaller than this |
| `min_det_score` | float | 0.6 | Drop detections below this confidence |

Query params override the server defaults for this call only.

Response `200`:

```json
{
  "image": { "width": 1024, "height": 768 },
  "faces": [
    {
      "bbox": [x1, y1, x2, y2],
      "score": 0.98,
      "landmarks": [[x, y], [x, y], [x, y], [x, y], [x, y]],
      "quality": { "blur": 0.82, "size": 143, "pose_yaw": -12.4 },
      "embedding": [0.012, -0.044, "... 512 floats"]
    }
  ]
}
```

- Landmark order: left eye, right eye, nose, left mouth corner, right mouth corner.
- `quality` is for client-side filtering, for example keeping blurry faces out of cluster centroids. Fields may be omitted if the model cannot provide them.
- No faces found returns `200` with `"faces": []`.
- Errors: `400 invalid_image`, `413 image_too_large`, `415 unsupported_media_type`.

### POST /face/compare

Returns the cosine similarity between two face embeddings. Useful for choosing thresholds from known same-person and different-person pairs.

Request:

```json
{ "a": [0.012, -0.044, "..."], "b": [0.031, 0.002, "..."] }
```

Response `200`:

```json
{ "similarity": 0.63 }
```

Errors: `400 dimension_mismatch`.

## Cluster endpoints

### POST /cluster/group

Groups the embeddings you send into clusters, one per person. The server keeps nothing.

Request:

```json
{
  "embeddings": [[...], [...]],
  "threshold": 0.45,
  "algo": "chinese_whispers",
  "min_cluster_size": 1
}
```

| Field | Type | Default | Meaning |
| --- | --- | --- | --- |
| `embeddings` | float\[\]\[\] | required | Vectors from `/face/analyze` |
| `threshold` | float | 0.45 | Cosine similarity cutoff; higher is stricter |
| `algo` | string | `chinese_whispers` | `chinese_whispers` or `dbscan` |
| `min_cluster_size` | int | 1 | Smaller clusters are labeled `-1` (`dbscan` only) |

Response `200`:

```json
{
  "labels": [0, 0, 1, 2, 1, -1],
  "num_clusters": 3,
  "centroids": [[...], [...], [...]]
}
```

- `labels[i]` is the cluster of `embeddings[i]`. `-1` means noise, which only occurs with `dbscan` and `min_cluster_size` above 1.
- `centroids[k]` is the normalized mean of cluster `k`. Store these in the app for incremental assignment.
- Errors: `400 empty_input`, `400 dimension_mismatch`, `422` for malformed JSON.

### POST /cluster/assign

Assigns new embeddings to the nearest known centroid, or reports no match. Use it for photos that arrive after the initial grouping.

Request:

```json
{
  "embeddings": [[...], [...]],
  "centroids": [[...], [...]],
  "threshold": 0.45
}
```

Response `200`:

```json
{
  "assignments": [
    { "cluster": 1, "similarity": 0.71 },
    { "cluster": null, "similarity": 0.32 }
  ]
}
```

`cluster: null` means no centroid passed the threshold, so the app should start a new cluster.

Errors: `400 empty_input`, `400 dimension_mismatch`, `422` for malformed JSON.

### GET /cluster/algos

Lists the clustering algorithms the server supports, so the app can offer them without hardcoding names. No request body.

Response `200`:

```json
{
  "default": "chinese_whispers",
  "algos": [
    {
      "name": "chinese_whispers",
      "description": "Graph-based; links embeddings above the similarity threshold",
      "params": ["threshold"]
    },
    {
      "name": "dbscan",
      "description": "Density-based on cosine distance; can mark noise as -1",
      "params": ["threshold", "min_cluster_size"]
    }
  ]
}
```

- `default` is the algorithm used when `algo` is omitted from `/cluster/group`.
- `params` lists which `/cluster/group` fields the algorithm uses.

## Status and error codes

| HTTP | Error code | Meaning |
| --- | --- | --- |
| 200 | none | Success |
| 400 | `invalid_image` | Image bytes could not be decoded |
| 400 | `empty_input` | Empty embeddings list |
| 400 | `dimension_mismatch` | Vector length differs from `embedding_dim` |
| 401 | `unauthorized` | Missing or wrong `X-API-Key` |
| 413 | `image_too_large` | Image body over the limit (suggested 10 MB) |
| 415 | `unsupported_media_type` | Content type is not `image/jpeg` or `image/png` |
| 422 | none | JSON body failed validation |
| 500 | `inference_failed` | Model error while processing |

## Defaults, tuning and privacy

The server has no runtime config endpoint. Tunable values are per-request parameters, with startup defaults read from environment variables, so tuning never requires an app rebuild.

| Setting | Env variable | Default | Per-request override |
| --- | --- | --- | --- |
| Minimum face size | `MIN_FACE_SIZE` | 60 px | `min_face_size` on `/face/analyze` |
| Minimum detection score | `MIN_DET_SCORE` | 0.6 | `min_det_score` on `/face/analyze` |
| Cluster threshold | `CLUSTER_THRESHOLD` | 0.45 | `threshold` on `/cluster/group` and `/cluster/assign` |
| Cluster algorithm | `CLUSTER_ALGO` | `chinese_whispers` | `algo` on `/cluster/group` |
| Detector input size | none (startup constant) | 640 × 640 | not overridable |

To change a default, edit the value and restart the server. With `uvicorn --reload` this takes a second or two.

**Privacy and implementation notes**

- Read the image with `await request.body()` and decode it with `cv2.imdecode`. Do not use multipart `UploadFile`, which can spill to a temporary file on disk above 1 MB.
- Never write photos, crops or embeddings to disk, and do not add middleware that logs request bodies.
- Face embeddings are biometric data. Store them only in the app's private storage, and offer users a way to delete them.
- Keep the server on a trusted network or behind HTTPS with the `X-API-Key` check. Do not expose it to the internet without both.
- Re-tune thresholds after moving the pipeline on-device, since a different embedding model will produce a different similarity distribution.
