# Lectio — server (FastAPI + SQLite)

Il "regista" delle audio-lezioni: legge il materiale, chiede il testo della lezione a Mistral,
la voce a Mistral (Voxtral) o ad Azure (voce gratis), monta l'MP3 con ffmpeg e tiene i conti.
Fonte di verità delle API: [../docs/contratto-api.md](../docs/contratto-api.md) (v1.2, sezione 15 compresa).
Server 1.0.1: in più `server_id` in salute e abbinamento, `lectio-pc.zip.sig` scaricabile.

## Come è fatto

| File | Cosa fa |
|---|---|
| `app/main.py` | crea l'app, avvia i due giri in sottofondo |
| `app/spesa.py` | **l'unico punto** che controlla il tetto prima di ogni tentativo di chiamata a pagamento: scrive una riga di prenotazione con la stima PRIMA della chiamata (se il database non scrive, la chiamata non parte) e la aggiorna con il costo vero; i ritenti partono solo se il fornitore di sicuro non ha lavorato, altrimenti la stima resta nel conto |
| `app/prezzi.json` | prezzi in dollari e cambio in euro |
| `app/lezione.py` | la preparazione: lettura → piano → scrittura → voce → montaggio (contratto 9.1) |
| `app/prompt.py` | i prompt (lezione, non podcast; materiale racchiuso come dato) |
| `app/finto.py` | il generatore di testo finto della modalità prova |
| `app/testo_ai.py`, `app/ocr.py`, `app/voci.py` | i fornitori (Mistral chat, Mistral OCR, Voxtral, Azure, espeak-ng), sostituibili |
| `app/audio.py` | pezzi di voce e montaggio con ffmpeg |
| `app/lavoro.py` | il lavoro in sottofondo: una lezione alla volta, poi foto da leggere e schede da aggiornare |
| `app/scheda.py` | la scheda del prof (le parti scritte da Andrea e `note_mie` non si perdono) |
| `app/ripasso.py` | risposte scritte, correttore e Da ripassare (contratto 15.2) |
| `app/lettura.py` | lettura di txt, md, pdf, docx, odt, rtf (gratis, sul NAS) |
| `app/origine.py` | da casa o da internet: i tentativi di abbinamento si contano a parte |
| `app/limiti.py` | limite al corpo delle richieste, contato mentre arriva (20 MB i file, 2 MB il resto) |
| `app/routes/` | le API |

Dati sul volume `/data`: `lectio.db`, `materiale/<materia>/<id>.<estensione>`, `lezioni/<id>/`
(i passi della preparazione e `lezione.mp3`). Un `riprova` riparte dalla fase fallita
senza ripagare i pezzi già fatti.

## Modalità prova

Senza `LECTIO_MISTRAL_KEY` il server parte da solo in modalità prova: testo da un generatore
finto che usa davvero il materiale, voce robotica di espeak-ng, costi 0. Serve a provare tutto
(app comprese) prima di avere le chiavi. Le foto in prova non si leggono davvero.

## Avvio in locale

1. `python -m venv C:\Users\andre\lectio-studio\venv-server` (fuori da OneDrive)
2. `C:\Users\andre\lectio-studio\venv-server\Scripts\python.exe -m pip install -r requirements-dev.txt`
3. dalla cartella `server/`: `...\python.exe -m uvicorn app.main:create_app --factory --port 8000`
   → http://127.0.0.1:8000/api/salute
4. Abbinare un'app: `...\python.exe -m app.abbina` stampa un codice di 8 cifre (15 minuti, una volta sola).
   Sul NAS: `docker exec lectio python -m app.abbina`.

Senza ffmpeg sul PC le lezioni si fermano al montaggio con una frase chiara; senza espeak-ng
la voce di prova non parte. Nel container ci sono entrambi.

## Variabili d'ambiente (`server/.env`, mai nel repo)

| Variabile | A cosa serve |
|---|---|
| `LECTIO_MISTRAL_KEY` | testo, voce espressiva, lettura delle foto. Senza: modalità prova |
| `LECTIO_AZURE_KEY`, `LECTIO_AZURE_REGIONE` | voce gratis (piano F0, 500.000 caratteri al mese). Senza: niente voce gratis |
| `TS_AUTHKEY` | Tailscale Funnel (indirizzo https pubblico): sta in `tailscale/.env`, non qui |
| `LECTIO_INDIRIZZO_FUORI_CASA` | facoltativa: l'indirizzo https per usare Lectio fuori casa (per esempio `https://lectio.<rete>.ts.net`); il server lo passa alle app in `/api/salute` (con token) e all'abbinamento come `indirizzo_fuori_casa` |
| `LECTIO_TAILSCALE_HOST` | nome del container di Tailscale (base `lectio-tailscale`): le richieste che arrivano da lui sono "da internet" |
| `LECTIO_MODELLO_PIANO` | piano, scheda, scelta degli appunti, correttore (base `mistral-small-latest`) |
| `LECTIO_MODELLO_SCRITTURA` | scrittura della lezione (base `mistral-medium-latest`) |
| `LECTIO_MODELLO_OCR` | lettura di foto e PDF scansionati (base `mistral-ocr-latest`) |
| `LECTIO_MODELLO_VOCE`, `LECTIO_VOCE_MISTRAL` | voce espressiva (base `voxtral-mini-tts-3-0`, `voice_482` = Lorenzo) |
| `LECTIO_DATI`, `LECTIO_DB`, `LECTIO_APK_DIR` | cartelle (nel container `/data`, `/data/lectio.db`, `/apk`) |
| `LECTIO_SOTTOFONDO` | `0` spegne il lavoro in sottofondo (solo per prove) |

Un modello cambiato in `.env` che non è in `prezzi.json` blocca le chiamate a pagamento con una
frase chiara (e `/api/salute` lo dice in `mancano`): senza prezzo non c'è tetto che tenga. Se
Mistral cambia i prezzi, o si passa ad Azure S0, si aggiorna `app/prezzi.json`.

Quando arriva la chiave Mistral, all'avvio il server rifà quello che aveva fatto il generatore
finto: rilegge le foto lette in prova (con stima e tetto, l'OCR solo se serve davvero) e rifà
da zero le schede del prof scritte in prova (restano le parti scritte da Andrea e le sue note).

## Sul NAS

Cartella `/volume1/docker/Lectio-NAS` con dentro `docker-compose.yml`, `server/`, `tailscale/`,
`imposta-chiave.sh`. Due file di chiavi separati, mai nel repo: `server/.env` (Mistral, Azure:
li vede solo `lectio`) e `tailscale/.env` (solo `TS_AUTHKEY`: lo vede solo `lectio-tailscale`).
La prima volta `touch server/.env tailscale/.env`, poi:

    docker compose -p lectio up -d --build
    ssh -t "<utente>"@<ip-del-nas> "bash /volume1/docker/Lectio-NAS/imposta-chiave.sh"

Porta di casa 8500 (dietro c'è sempre il token). La chiave di Tailscale (scelta 4 dello script)
va in `tailscale/.env` e fa ripartire `lectio-tailscale` (profilo `tailscale`, immagine fissata a
`tailscale/tailscale:v1.102.5`); le altre vanno in `server/.env` e fanno ripartire `lectio`. Lo
script sposta da solo una `TS_AUTHKEY` rimasta in `server/.env` dalle installazioni di prima.
APK, zip del PC e la sua firma vanno in `server/apk/` (`lectio.apk`, `lectio-pc.zip`,
`lectio-pc.zip.sig`); versioni e nota in `app/versioni.json` (letto a ogni richiesta).

## Test

Dalla cartella `server/`: `C:\Users\andre\lectio-studio\venv-server\Scripts\python.exe -m pytest -q`.
Nessuna rete: Mistral e Azure sono server finti (`tests/finti.py`), la voce e il montaggio hanno
sostituti in Python. I test con ffmpeg o espeak-ng veri si saltano se mancano; nel container:

    docker exec lectio sh -c "pip install -q -r requirements-dev.txt && python -m pytest -q"
