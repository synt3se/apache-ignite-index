FROM python:3.11-slim
WORKDIR /app

# git нужен для pip install git+...CLIP.git
RUN apt-get update && apt-get install -y --no-install-recommends git \
 && rm -rf /var/lib/apt/lists/*

# CPU-сборка torch (иначе качается CUDA-версия на гигабайты)
RUN pip install --no-cache-dir torch torchvision --index-url https://download.pytorch.org/whl/cpu

# остальное + сам CLIP
RUN pip install --no-cache-dir fastapi uvicorn ftfy regex pillow \
    git+https://github.com/openai/CLIP.git

COPY embedding/clip_service.py .
EXPOSE 8000
CMD ["uvicorn","clip_service:app","--host","0.0.0.0","--port","8000"]