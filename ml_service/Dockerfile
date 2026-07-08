FROM python:3.11-slim

WORKDIR /app

RUN apt-get update \
  && apt-get install -y --no-install-recommends libgomp1 \
  && rm -rf /var/lib/apt/lists/*

COPY ml_service/requirements.txt ./ml_service/requirements.txt
RUN pip install --no-cache-dir -r ./ml_service/requirements.txt

COPY scripts ./scripts
COPY ml_service ./ml_service
COPY data_sources/modeling_v2/firesky_v2_model.joblib ./data_sources/modeling_v2/firesky_v2_model.joblib

ENV FIRESKY_MODEL_PATH=/app/data_sources/modeling_v2/firesky_v2_model.joblib
ENV PORT=8080

CMD ["sh", "-c", "uvicorn ml_service.app:app --host 0.0.0.0 --port ${PORT:-8080}"]
