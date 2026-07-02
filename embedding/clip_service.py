"""
CLIP embedding microservice (слой 2 «векторизация»).

Отдельный процесс: CLIP в JVM не затащить, поэтому Python + FastAPI.
Картинка/текст -> вектор 512d (модель clip-ViT-B-32, та же, что в clip_check.py).

Эндпоинты:
  GET  /health                         -> статус, режим, размерность
  POST /embed/image  (multipart file)  -> {"vector": [512 float]}
  POST /embed/text   {"text": "..."}   -> {"vector": [512 float]}

Запуск:
  pip install -r requirements.txt
  uvicorn clip_service:app --host 0.0.0.0 --port 8000
  # первый запуск скачает веса (~600 МБ)

Быстрая проверка пайплайна БЕЗ torch/скачивания весов:
  CLIP_FAKE=1 uvicorn clip_service:app --port 8000
  (детерминированный псевдо-вектор по хешу входа — форма та же, 512d)
"""
import hashlib
import io
import os
import random

from fastapi import FastAPI, File, UploadFile
from pydantic import BaseModel

FAKE = 0
DIM = 512

app = FastAPI(title="CLIP embedding service")


class TextIn(BaseModel):
    text: str


class EmbeddingOut(BaseModel):
    vector: list[float]


# Модель грузим лениво: при FAKE=1 torch вообще не импортируется.
_model = None


def model():
    global _model
    if _model is None:
        import torch
        from sentence_transformers import SentenceTransformer
        device = "cuda" if torch.cuda.is_available() else "cpu"
        _model = SentenceTransformer("clip-ViT-B-32", device=device)
    return _model


def fake_vector(seed: bytes) -> list[float]:
    """Детерминированный нормированный вектор из seed — для тестов пайплайна."""
    rnd = random.Random(hashlib.sha256(seed).hexdigest())
    v = [rnd.uniform(-1.0, 1.0) for _ in range(DIM)]
    norm = sum(x * x for x in v) ** 0.5 or 1.0
    return [x / norm for x in v]


@app.get("/health")
def health():
    return {"status": "ok", "fake": FAKE, "dim": DIM}


@app.post("/embed/image", response_model=EmbeddingOut)
async def embed_image(file: UploadFile = File(...)):
    data = await file.read()
    if FAKE:
        return EmbeddingOut(vector=fake_vector(data))
    from PIL import Image
    img = Image.open(io.BytesIO(data)).convert("RGB")  # RGB — CLIP ждёт 3 канала
    vec = model().encode(img)  # numpy.ndarray (512,)
    return EmbeddingOut(vector=vec.tolist())


@app.post("/embed/text", response_model=EmbeddingOut)
def embed_text(body: TextIn):
    if FAKE:
        return EmbeddingOut(vector=fake_vector(body.text.encode("utf-8")))
    vec = model().encode(body.text)
    return EmbeddingOut(vector=vec.tolist())
