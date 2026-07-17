FROM python:3.11-slim
WORKDIR /srv
RUN pip install --no-cache-dir torch --index-url https://download.pytorch.org/whl/cpu
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY clip_service.py .
EXPOSE 8000
CMD ["uvicorn", "clip_service:app", "--host", "0.0.0.0", "--port", "8000"]