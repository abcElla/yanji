"""Loopback-only CPU embeddings; keep runtime and caches in YANJI_RAG_HOME."""
import os
from pathlib import Path

RAG_HOME = Path(os.environ.get("YANJI_RAG_HOME", "D:/work/中兴杯/dependence/rag"))
os.environ.setdefault("HF_HOME", str(RAG_HOME / "hf-cache"))
os.environ.setdefault("HF_HUB_DISABLE_TELEMETRY", "1")
from fastembed import TextEmbedding
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

MODEL_NAME = "BAAI/bge-small-zh-v1.5"
CACHE = RAG_HOME / "models"
MODEL = TextEmbedding(model_name=MODEL_NAME, cache_dir=str(CACHE), threads=2)
app = FastAPI(docs_url=None, redoc_url=None)


class EmbeddingRequest(BaseModel):
    model: str
    input: list[str] = Field(min_length=1, max_length=16)


@app.get("/health")
def health():
    return {"status": "ready", "model": MODEL_NAME, "dimensions": 512}


@app.post("/v1/embeddings")
def embeddings(request: EmbeddingRequest):
    if request.model != MODEL_NAME:
        raise HTTPException(400, "Unsupported model")
    if any(not text.strip() or len(text) > 30000 for text in request.input):
        raise HTTPException(400, "Invalid embedding input")
    vectors = list(MODEL.embed(request.input, batch_size=16))
    return {"object": "list", "model": MODEL_NAME, "data": [
        {"object": "embedding", "index": i, "embedding": vector.tolist()}
        for i, vector in enumerate(vectors)
    ]}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="127.0.0.1", port=8092, access_log=False)
