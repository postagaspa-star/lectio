# Lectio sul NAS: un volume su /data (database, materiale, lezioni) e uno su /apk (file da scaricare).
FROM python:3.14-slim

# ffmpeg monta l'audio delle lezioni; espeak-ng è la voce di prova (modalità prova, senza chiavi).
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg espeak-ng \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /srv/lectio

COPY requirements.txt requirements-dev.txt pytest.ini ./
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app
# I test viaggiano con l'immagine (pochi KB): dentro il container ci sono ffmpeg ed espeak-ng.
# Per farli girare: docker exec lectio sh -c "pip install -q -r requirements-dev.txt && python -m pytest -q"
COPY tests ./tests

# Le cartelle dei volumi devono esistere prima del mount: un volume vergine parte senza errori.
RUN mkdir -p /data /apk

ENV LECTIO_DATI=/data \
    LECTIO_DB=/data/lectio.db \
    LECTIO_APK_DIR=/apk \
    PYTHONUNBUFFERED=1

EXPOSE 8000

# python invece di curl: l'immagine slim non ha curl.
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD ["python", "-c", "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://localhost:8000/api/salute', timeout=4).status == 200 else 1)"]

CMD ["uvicorn", "app.main:create_app", "--factory", "--host", "0.0.0.0", "--port", "8000"]
