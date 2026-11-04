# Le voci gratis di Lectio sul NAS (contratto 16.5): Kyutai Pocket TTS e Supertonic 3, senza internet
# dopo il primo scarico dei modelli (tenuti nel volume /cache). Nessuna porta verso fuori: lo chiama solo
# il container "lectio".
FROM python:3.12-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Kyutai Pocket TTS: il programma ufficiale senza Python (codice MIT, modelli CC-BY 4.0), versione fissa
# e controllata con la sua impronta.
ARG POCKET_URL=https://github.com/kyutai-labs/pocket-tts/releases/download/v4.0.1/pocket-tts-3.3.0-x86_64-unknown-linux-gnu.tar.gz
ARG POCKET_SHA256=9abff0aa138a804e44052952d8c3bdaae247fb267ca8f4b83fe178897d1a0559
RUN curl -fsSL -o /tmp/pocket.tgz "$POCKET_URL" \
    && echo "$POCKET_SHA256  /tmp/pocket.tgz" | sha256sum -c - \
    && mkdir -p /tmp/pocket /opt/pocket-tts \
    && tar xzf /tmp/pocket.tgz -C /tmp/pocket \
    && cp "$(find /tmp/pocket -name pocket-tts -type f | head -1)" /opt/pocket-tts/pocket-tts \
    && chmod +x /opt/pocket-tts/pocket-tts \
    && rm -rf /tmp/pocket /tmp/pocket.tgz

WORKDIR /srv/voci
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt
COPY voci_nas.py ./

ENV HF_HOME=/cache PYTHONUNBUFFERED=1 POCKET_TTS=/opt/pocket-tts/pocket-tts
EXPOSE 8000

HEALTHCHECK --interval=60s --timeout=5s --start-period=20s --retries=3 \
    CMD ["python", "-c", "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://localhost:8000/salute', timeout=4).status == 200 else 1)"]

CMD ["python", "voci_nas.py"]
