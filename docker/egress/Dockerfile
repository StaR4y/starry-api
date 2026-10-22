FROM python:3.12-slim-bookworm
WORKDIR /app
COPY docker/egress/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY docker/egress/bridge.py .
USER 65534:65534
CMD ["python", "bridge.py"]
