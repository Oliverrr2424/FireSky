FROM python:3.11-slim

WORKDIR /app

RUN apt-get update \
  && apt-get install -y --no-install-recommends libgomp1 \
  && rm -rf /var/lib/apt/lists/*

COPY requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -r ./requirements.txt

COPY scripts ./scripts
COPY ml_service ./ml_service
COPY model/firesky_v2_model.joblib ./model/firesky_v2_model.joblib

ENV FIRESKY_MODEL_PATH=/app/model/firesky_v2_model.joblib
ENV PORT=8080

CMD ["sh", "-c", "uvicorn ml_service.app:app --host 0.0.0.0 --port ${PORT:-8080}"]
