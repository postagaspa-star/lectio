# Lectio — contratto delle API (v1.3)

Fonte di verità tra server, app Android e programma PC. Si cambia QUI prima che nel codice.
Le novità della v1.1 (risposte scritte alle domande, materiale solo nella materia) sono nella sezione 15 e
valgono sopra il testo precedente dove lo contraddicono.
Repo pubblico: negli esempi e nei test solo dati finti (Prof. Rossi, Prof. Bianchi, `@esempio.it`).

## 0. Cos'è Lectio

Audio-lezioni per la scuola (non podcast) tarate sulle verifiche del prof. Un solo utente (Andrea). Il server,
sul NAS di casa (Docker, processore N100, niente scheda video), fa da **regista**: legge i file, chiede il testo
della lezione a Mistral, chiede la voce a Mistral (Voxtral) o ad Azure (voce gratis), monta l'MP3 con ffmpeg,
tiene i conti. Le app (telefono e PC) sono finestre sullo stesso server.

### Regole che nessun pezzo può aggirare

- **Tetto dei soldi.** Nessuna chiamata a pagamento parte se la spesa del mese + la stima supera il tetto
  (`impostazioni.tetto_eur`, predefinito 2,00 €). Il controllo sta in UN punto del server (`spesa.py`), prima
  di ogni chiamata a pagamento, non solo alla partenza della lezione.
- **Il contenuto dei file è dato, mai istruzione.** Nei prompt va racchiuso e dichiarato come testo da usare.
- **Le note di Andrea nella scheda del prof (`note_mie`) l'AI non le tocca mai.**
- **Ogni esito si vede**: errori in italiano semplice nel campo `errore`; una lezione fallita resta con lo
  stato `errore` e la frase, mai sparita.
- **Modalità prova** automatica quando manca la chiave Mistral: testo da modello finto, voce di prova (espeak-ng,
  robotica, dichiarata), costi 0. Serve a provare tutto prima di avere le chiavi.

## 1. Convenzioni

- Base `http(s)://<server>/api`. JSON UTF-8. Date ISO 8601 con fuso; date senza ora `YYYY-MM-DD`.
- Autenticazione `Authorization: Bearer <token>` su tutto tranne `GET /api/versione`, `GET /api/salute`
  (ridotta senza token), `POST /api/abbina`, `GET /scarica/<file>`.
- Errori: status HTTP + `{"errore": "frase", "codice": "slug"}`. Codici: `non_autorizzato` 401,
  `non_trovato` 404, `non_valido` 422, `conflitto` 409, `troppo_grande` 413, `troppi_tentativi` 429,
  `tetto_raggiunto` 402, `fornitore_non_disponibile` 503, `errore_interno` 500 (sempre JSON).
- Id interi. Soldi in euro con due decimali come numero (`0.25`); il server arrotonda per eccesso al centesimo
  nelle stime.
- Testi per l'utente: italiano semplice, zero gergo.

## 2. Abbinamento dei dispositivi (come Sol)

Codice di 8 cifre (15 minuti, monouso) con `docker exec lectio python -m app.abbina`.
`POST /api/abbina` `{"codice":"48213377","nome":"Telefono di Andrea","tipo":"android"}` (`android`|`pc`) →
`{"token":"<64>","dispositivo_id":1}`. 401 se sbagliato/scaduto; più di 10 errori in 15 minuti → 429 e tutti i
codici aperti annullati. `GET /api/dispositivi`, `DELETE /api/dispositivi/{id}`.

## 3. Salute, versioni, aggiornamenti

`GET /api/versione` → `{"server":"1.0.0","android":{"codice":1,"nome":"1.0.0","sha256":"…"},"pc":{"codice":1,"nome":"1.0.0","sha256":"…"},"nota":"…"}`
(da `server/app/versioni.json`). File: `GET /scarica/lectio.apk`, `GET /scarica/lectio-pc.zip` (dalla cartella
montata `/apk`).

`GET /api/salute` (con token; senza: `{"ok":true,"versione":"1.0.0"}`):
```json
{"ok": true, "versione": "1.0.0", "modalita_prova": false,
 "testo": {"stato": "pronto", "fornitore": "mistral"},
 "voce_espressiva": {"stato": "pronta", "fornitore": "mistral"},
 "voce_gratis": {"stato": "pronta", "fornitore": "azure"},
 "mancano": [], "coda": {"lezioni_in_preparazione": 1}}
```
`stato`: `pronto/pronta` | `prova` | `non_disponibile`. `mancano`: frasi tipo "la chiave Mistral".

## 4. Materie

`Materia`: `{"id":1,"nome":"Storia","prof":"Rossi","colore":"ambra","conteggi":{"verifiche":4,"interrogazioni":1,"appunti":3,"lezioni":2},"centratura":{"previste_uscite":10,"uscite_totali":16,"percentuale":63}}`
(`centratura` null se nessun esito). Colori: `ambra rosa petrolio viola blu verde arancio oliva`.

- `GET /api/materie` → `[Materia]` (ordine alfabetico).
- `POST /api/materie` `{"nome","prof","colore"}` → `Materia` (nome unico, 1-40 caratteri; colore libero o il
  primo non usato se manca).
- `PATCH /api/materie/{id}` (campi facoltativi), `DELETE /api/materie/{id}` con `{"conferma":true}` (cancella
  materiale, scheda, eventi, lezioni e i loro audio).

## 5. Scheda del prof

`GET /api/materie/{id}/scheda` →
```json
{"materia_id": 1, "aggiornata_il": "…", "basata_su": {"verifiche": 4, "interrogazioni": 1, "esiti": 2},
 "argomenti": [{"nome": "Cause e conseguenze", "volte": 4, "esempio": "Perché cadde la Repubblica?"}],
 "tipi_domanda": [{"tipo": "aperta", "quota": 0.6}, {"tipo": "definizione", "quota": 0.25}, {"tipo": "date", "quota": 0.15}],
 "cosa_corregge": ["Vuole le date precise"], "cosa_premia": ["I collegamenti tra fatti"],
 "lunghezza_risposte": "5-8 righe per le domande aperte", "consigli": ["Parti dalla definizione"],
 "note_mie": "", "stato": "pronta"}
```
`stato`: `vuota` (nessuna verifica ancora) | `in_aggiornamento` | `pronta` | `errore` (+`errore`).
`tipi_domanda.tipo` tra `aperta definizione date esercizio vero_falso scelta_multipla collegamento traduzione altro`.
- `PUT /api/materie/{id}/scheda` (tutto tranne i campi calcolati): le modifiche di Andrea si tengono; un
  aggiornamento AI successivo riceve la scheda attuale e la migliora senza cancellare ciò che lui ha scritto o
  corretto; `note_mie` non entra mai nell'output dell'AI (si rimette uguale).
- Si aggiorna da sola (in sottofondo, ~1 centesimo) quando arriva materiale di tipo `verifica` o
  `interrogazione` o un esito; `POST /api/materie/{id}/scheda/aggiorna` la rifà a mano.

## 6. Materiale

`Materiale`: `{"id":7,"materia_id":1,"tipo":"verifica","titolo":"Verifica 12 ottobre","data":"2026-10-12","origine":"file","nome_file":"verifica.pdf","caratteri":5321,"stato":"pronto","errore":null,"costo_eur":0.0,"creato_il":"…"}`
`tipo`: `verifica` (corretta, di solito dal testo di Gemini) | `interrogazione` | `appunti` | `libro` | `altro`.
`origine`: `file` | `foto` | `scritto`. `stato`: `in_lettura` | `pronto` | `errore`.

- `POST /api/materie/{id}/materiale` multipart: `file` (uno) + `tipo`, `titolo`, `data` facoltativi; oppure
  JSON `{"tipo","titolo","data","testo"}` per appunti/interrogazioni scritti. Formati: `.txt .md .pdf .docx
  .odt .rtf` e immagini `.jpg .jpeg .png .webp` (le app convertono HEIC in JPEG). Massimo 20 MB a file e
  200.000 caratteri letti. PDF con testo: letto gratis; PDF scansionato o immagine: OCR Mistral a pagamento
  (stima prima: se sfora il tetto → 402). Titolo predefinito: dal file (per i testi di Gemini, la riga
  `MATERIA/PROF/DATA/TIPO` se c'è).
- `GET /api/materie/{id}/materiale` → `[Materiale]` (più recenti prima).
- `GET /api/materiale/{id}` → `Materiale` + `"testo"` (quello che il server ha letto).
- `DELETE /api/materiale/{id}`.
- `POST /api/materiale/{id}/riprova` (se `errore`).

## 7. Verifiche e interrogazioni in arrivo (eventi)

`Evento`: `{"id":3,"materia_id":1,"tipo":"scritta","argomento":"Impero romano","data":"2026-10-30","esito":null,"lezione_id":12}`
(`tipo` `scritta` | `orale`; `lezione_id` = l'ultima lezione fatta per questo evento).
- `GET /api/eventi?da=YYYY-MM-DD` (predefinito: oggi meno 30 giorni), `POST /api/materie/{id}/eventi`,
  `PATCH /api/eventi/{id}`, `DELETE /api/eventi/{id}`.
- `POST /api/eventi/{id}/esito`
  `{"domande_uscite":[31,33,36],"altre_domande":"Chi era Seiano?\nCos'era il pretorio?","voto":"7"}` →
  `{"previste_uscite":3,"totale_vere":5,"percentuale":60}`. Le `domande_uscite` sono id di domande della
  lezione dell'evento; `totale_vere` = uscite + righe non vuote di `altre_domande`. Fa partire l'aggiornamento
  della scheda.

## 8. Voci, stime e spesa

`GET /api/voci` →
```json
[{"id": "mistral:voice_482", "nome": "Lorenzo", "tipo": "espressiva", "fornitore": "mistral", "euro_al_minuto": 0.0126, "disponibile": true},
 {"id": "azure:it-IT-IsabellaMultilingualNeural", "nome": "Isabella", "tipo": "gratis", "fornitore": "azure", "euro_al_minuto": 0, "disponibile": true}]
```
In modalità prova c'è anche `{"id":"prova:espeak","nome":"Voce di prova","tipo":"prova",…}`. Le voci Azure
italiane si leggono dall'elenco ufficiale di Azure (filtro `it-IT`) e si tengono in memoria per un giorno;
predefinita la prima "Multilingual" femminile, altrimenti `it-IT-IsabellaNeural`.

`POST /api/lezioni/stima` `{"materia_id":1,"durata_min":20,"voce":"mistral:voice_482"}` →
```json
{"caratteri_stimati": 18300, "costo_testo_eur": 0.02, "costo_voce_eur": 0.23, "costo_eur": 0.25,
 "spesa_mese_eur": 0.80, "tetto_eur": 2.00, "dopo_eur": 1.05, "consentita": true,
 "motivo": null, "voce_gratis_caratteri_rimasti": 412000, "si_azzera_il": "2026-11-01"}
```
`consentita=false` + `motivo` ("Sforerebbe il tetto di 2 €: usa la voce gratis") quando `dopo_eur > tetto`.
Stima: parole = durata × 145; caratteri = parole × 6,3; voce Mistral = caratteri/1000 × prezzo; testo =
stima dei token di entrata/uscita × prezzi. Prezzi e cambio in `server/app/prezzi.json` (dollari) con
`cambio_usd_eur` (predefinito 0,86); il costo vero si calcola dai numeri che il fornitore restituisce.

`GET /api/spesa` → `{"mese":"2026-10","speso_eur":0.80,"tetto_eur":2.00,"percentuale":40,"si_azzera_il":"2026-11-01","voce_gratis":{"caratteri_usati":88000,"caratteri_mese":500000},"voci":[{"quando","cosa","fornitore","quantita","unita","costo_eur","lezione_id"}]}`
(`voci` = ultime 50 spese). Il mese è quello di Europe/Rome.

## 9. Lezioni

`Lezione` (in elenco):
```json
{"id": 12, "materia_id": 1, "evento_id": 3, "argomento": "L'Impero romano da Augusto a Nerone",
 "durata_min": 20, "durata_s": 1187, "voce": "mistral:voice_482", "stato": "pronta",
 "avanzamento": {"fase": "voce", "fatto": 3, "totale": 7, "frase": "Registro la voce · 3 di 7"},
 "costo_eur": 0.24, "creata_il": "…", "pronta_il": "…", "errore": null, "nota": null,
 "ascolto": {"posizione_s": 462, "finita": false, "aggiornato_il": "…"},
 "audio": {"byte": 14234112, "sha256": "…"}}
```
`stato`: `in_coda` | `in_preparazione` | `pronta` | `errore` | `annullata`.
`avanzamento.fase`: `lettura` | `piano` | `scrittura` | `voce` | `montaggio` (null quando pronta).
`nota`: per esempio "Gli appunti bastavano per circa 12 minuti: ho aggiunto esempi e domande."

- `POST /api/lezioni` `{"materia_id":1,"evento_id":3,"argomento":"…","materiali":[7,8,9],"durata_min":20,"voce":"mistral:voice_482"}`
  → 201 `Lezione` (`in_coda`). `durata_min` tra 15 e 60 (le app offrono 15/20/30/45). 402
  `tetto_raggiunto` se la stima non è consentita. `materiali` vuoto = tutti quelli della materia. Una lezione
  alla volta in preparazione; le altre aspettano in coda.
- `GET /api/lezioni?materia_id=` → `[Lezione]` (più recenti prima).
- `GET /api/lezioni/{id}` → `Lezione` + `"capitoli"`, `"domande"`, `"testo"`:
```json
{"capitoli": [{"n": 1, "titolo": "Augusto e il principato", "inizio_s": 0, "fine_s": 301}],
 "domande": [{"id": 31, "tipo": "blocco", "n": 1, "di": 6, "testo": "Perché Augusto non si fece mai chiamare re?",
              "risposta": "Perché a Roma la parola re ricordava la tirannia…",
              "inizio_s": 280.4, "fine_domanda_s": 286.9, "inizio_risposta_s": 289.4, "fine_s": 301.0}],
 "testo": [{"capitolo": 1, "paragrafi": ["…", "…"]}]}
```
  `domande.tipo`: `blocco` (la domanda a fine blocco) | `interrogazione` (quelle finali). `n`/`di` contano
  tutte le domande della lezione. Nel file audio dopo ogni domanda ci sono 2,5 s di silenzio
  (`fine_domanda_s` → `inizio_risposta_s`).
- `GET /api/lezioni/{id}/audio` → `audio/mpeg` (MP3 mono 64 kbps, 24 kHz), con `Range`, `ETag`.
- `PUT /api/lezioni/{id}/ascolto` `{"posizione_s":462,"finita":false}` (vince il più recente: il server
  ignora un aggiornamento con `aggiornato_il` del dispositivo più vecchio di quello salvato).
- `POST /api/lezioni/{id}/annulla` (se in coda/in preparazione; le spese già fatte restano nel conto),
  `POST /api/lezioni/{id}/riprova` (se `errore`: riparte dalla fase fallita, senza ripagare le parti fatte),
  `DELETE /api/lezioni/{id}`.

### 9.1 Com'è fatta una lezione (il server la costruisce così)

1. **Lettura**: testo dei materiali scelti + scheda del prof + argomento.
2. **Piano** (modello piccolo): titolo, blocchi, domande. Blocchi = max(3, round(durata/5)); ogni blocco 4-6
   minuti chiuso da UNA domanda di blocco con risposta breve. Domande di interrogazione finali =
   3 + round(durata/10) (15→4, 20→5, 30→6, 45→8), nello stile della scheda (tipi e argomenti che tornano).
   Il piano dice anche `minuti_di_materiale`: se sono meno della durata, i minuti mancanti si riempiono con
   esempi ed esercizi **sullo stesso argomento**, mai chiacchiere o argomenti nuovi, e si scrive la `nota`.
3. **Scrittura** (modello migliore), un blocco alla volta, ~145 parole al minuto:
   apertura (~45 s: cosa vediamo oggi e perché a questo prof interessa), blocchi, riepilogo (~1 min),
   interrogazione, "tre errori da non fare con il prof Rossi" (~45 s), chiusura breve.
   Regole di scrittura: seconda persona, italiano parlato semplice, frasi corte (≤ 20 parole), un esempio
   concreto per concetto, ogni blocco parte con un aggancio (una domanda o un'immagine), "occhio: questo lo
   chiede quasi sempre" solo se la scheda lo dice davvero, niente invenzioni oltre materiale e conoscenze
   sicure di livello liceo, niente elenchi letti come elenchi, niente "come detto prima". Le domande sono
   scritte come le farebbe il prof.
   Per la voce espressiva il testo porta segni di emozione e ritmo di Voxtral all'inizio dei paragrafi
   (al massimo uno per paragrafo): `[emotion=warm]` apertura, `[emotion=curious]` agganci e domande,
   `[emotion=confident]` spiegazioni, `[emotion=calm][pace=slow]` definizioni e date, `[emotion=determined]`
   interrogazione. Per Azure e per la voce di prova i segni si tolgono (`[pace=slow]` → `<prosody rate="-10%">`).
4. **Voce**: pezzi da ≤ 1.500 caratteri (Voxtral fa al massimo ~2 minuti per richiesta), tagliati a fine frase;
   domande e risposte sono pezzi a parte. Fino a 3 richieste in parallelo; ritenta 3 volte con attesa
   crescente; il tetto si ricontrolla prima di ogni pezzo. I pezzi si salvano: un `riprova` non li ripaga.
5. **Montaggio** (ffmpeg): unisce i pezzi, mette 0,6 s tra paragrafi, 1,2 s tra capitoli, 2,5 s dopo ogni
   domanda, normalizza il volume, MP3 mono 64 kbps 24 kHz; calcola tempi di capitoli e domande dalle durate vere.

Modelli predefiniti (cambiabili in `.env`): piano e scheda `mistral-small-latest`, scrittura
`mistral-medium-latest`, OCR `mistral-ocr-latest`, voce `voxtral-mini-tts-3-0` con `voice_id` `voice_482`
(Lorenzo, unica voce italiana preimpostata). Chiamata voce Mistral: `POST https://api.mistral.ai/v1/audio/speech`
`{"model","input","voice_id","response_format":"mp3"}` → JSON con `audio_data` in base64. Azure:
`POST https://<regione>.tts.speech.microsoft.com/cognitiveservices/v1` con SSML,
`X-Microsoft-OutputFormat: audio-24khz-48kbitrate-mono-mp3`.

## 10. Oggi

`GET /api/oggi` →
```json
{"oggi": "2026-10-28",
 "prossima": {"evento": {…Evento}, "materia": {"id":1,"nome":"Storia","colore":"ambra"}, "lezione": {…Lezione o null}, "tra_giorni": 2},
 "riprendi": {…Lezione con posizione, o null},
 "da_raccontare": [{"evento": {…}, "materia": {…}}],
 "piu_avanti": [{"evento": {…}, "materia": {…}, "tra_giorni": 6}],
 "spesa": {"speso_eur": 0.80, "tetto_eur": 2.00, "percentuale": 40, "si_azzera_il": "2026-11-01", "avviso": false},
 "modalita_prova": false}
```
`prossima` = l'evento futuro (o di oggi) più vicino; `riprendi` = l'ultima lezione ascoltata non finita;
`da_raccontare` = eventi passati (fino a 30 giorni) senza esito con una lezione; `piu_avanti` = i 4 eventi
seguenti; `avviso` = spesa ≥ 80% del tetto.

## 11. Novità (per le notifiche)

`GET /api/novita?dopo_id=0` → `[{"id":51,"tipo":"lezione_pronta","testo":"La lezione di Storia è pronta","lezione_id":12,"quando":"…"}]`
`tipo`: `lezione_pronta` | `lezione_errore` | `scheda_aggiornata` | `materiale_letto` | `materiale_errore` |
`tetto_vicino`. Le app chiedono ogni 20 s mentre una lezione è in preparazione, altrimenti all'apertura.

## 12. Testo per Gemini

`GET /api/gemini` → `{"testo":"…","aggiornato_il":"…"}` dal file `server/app/testo_gemini.txt`. Le app ne
tengono una copia per quando non c'è rete. Testo attuale:

```
Trascrivi questa verifica in un file di testo, senza inventare niente. Usa sempre questo schema:
MATERIA: … | PROF: … | DATA: … | TIPO: scritta/orale | VOTO: …
Per ogni domanda:
## Domanda N (punti: …)
TESTO: la domanda parola per parola
MIA RISPOSTA: quello che ho scritto io
CORREZIONI DEL PROF: tutto quello che ha segnato o scritto, anche a penna o a margine, indicando a quale punto si riferisce
PUNTI PRESI: …
Alla fine: COMMENTO DEL PROF: …
Se una parte non si legge scrivi [illeggibile], non indovinare.
```

## 13. Impostazioni

`GET /api/impostazioni` / `PATCH /api/impostazioni` →
`{"tetto_eur":2.00,"voce_predefinita":"mistral:voice_482","durata_predefinita":20,"fermati_alle_domande":true,"velocita":1.0,"nome":"Andrea"}`
`tetto_eur` 0,50-10,00. Le app possono tenere in locale tema e velocità, ma leggono qui i valori comuni.

## 14. Chiavi e ambiente (`server/.env`, mai nel repo)

`LECTIO_MISTRAL_KEY`, `LECTIO_AZURE_KEY`, `LECTIO_AZURE_REGIONE` (es. `westeurope`), `TS_AUTHKEY`;
facoltativi: `LECTIO_MODELLO_PIANO`, `LECTIO_MODELLO_SCRITTURA`, `LECTIO_MODELLO_OCR`, `LECTIO_MODELLO_VOCE`,
`LECTIO_VOCE_MISTRAL` (predefinito `voice_482`). Le scrive Andrea con
`ssh -t "<utente>"@<ip-del-nas> "bash /volume1/docker/Lectio-NAS/imposta-chiave.sh"` (domanda nascosta,
riavvio da solo). Senza Mistral → modalità prova. Senza Azure → niente voce gratis (la voce espressiva resta).

## 15. Novità della v1.1 (richieste di Andrea del 27/10)

### 15.1 Il materiale sta nella materia, mai nella lezione
Verifiche, interrogazioni, appunti e libro si caricano SOLO nella materia (`POST /api/materie/{id}/materiale`
o dalla condivisione). Una lezione usa da sola: tutte le verifiche e interrogazioni della materia (attraverso la
scheda del prof e come esempi di domande) e tutti gli appunti/libro della materia. `POST /api/lezioni` accetta
ancora `materiali` per restringere gli appunti, ma le app lo mandano solo se Andrea tocca "Cambia"; di base
non lo mandano. Se il testo degli appunti supera ~60.000 caratteri, il passo **piano** sceglie prima i pezzi
che riguardano l'argomento (modello piccolo sui titoli e sui primi paragrafi) e la scrittura riceve solo quelli.

### 15.2 Risposta scritta a ogni domanda
A ogni domanda (di blocco e di interrogazione) Andrea scrive la sua risposta (o la detta col microfono della
tastiera); il server la corregge.

`POST /api/lezioni/{id}/domande/{domanda_id}/risposta` `{"testo":"Perché non si fidava del senato","saltata":false}` →
```json
{"id": 88, "domanda_id": 32, "esito": "parziale", "punteggio": 0.5,
 "commento": "Giusto: non si fidava del senato. Manca chi governava Roma al posto suo.",
 "mancava": ["il ruolo di Seiano"], "risposta_attesa": "Perché non si fidava del senato e temeva le congiure: lasciò Roma a Seiano…",
 "costo_eur": 0.0, "quando": "…"}
```
- `esito`: `giusta` | `parziale` | `sbagliata` | `saltata` | `non_corretta`. `testo` 1-2000 caratteri (vuoto
  solo con `saltata: true` → esito `saltata`, nessuna chiamata a pagamento).
- Correttore: modello piccolo (`LECTIO_MODELLO_PIANO`) con domanda, risposta attesa, testo del capitolo e
  "cosa corregge / cosa premia" della scheda; JSON validato; giudica il contenuto, non l'ortografia; generoso
  ma onesto; `commento` in seconda persona, al massimo 2 frasi, prima cosa giusta poi cosa manca; `mancava`
  0-3 voci brevi. Costo registrato nella spesa (circa 0,03 centesimi a risposta). Se il tetto è finito:
  esito `non_corretta`, `commento` "Hai finito i soldi del mese: confronta tu con la risposta qui sotto",
  nessun 402 (il flusso dell'ascolto non si blocca). Modalità prova: correttore finto che confronta le parole
  importanti.
- Si può rispondere di nuovo alla stessa domanda: vale l'ultima risposta.
- `GET /api/lezioni/{id}`: ogni domanda ha anche `"mia_risposta": {"testo","esito","commento","mancava"}` o `null`.
- **Da ripassare**: `GET /api/materie/{id}/da_ripassare` → `[{"domanda_id","lezione_id","lezione":"…","testo","esito","commento","quando"}]`
  = domande con ultima risposta `parziale`, `sbagliata` o `saltata` (più recenti prima, max 50). `Materia`
  ha in più `"da_ripassare": 3`. Il passo **piano** di una nuova lezione della stessa materia riceve i punti da
  ripassare legati all'argomento e li riprende (una domanda nuova su ciascuno, se c'entrano).
- **Impostazioni**: nuovo `"risposte_scritte": true` (predefinito). Se `false` le app tornano al vecchio
  "Senti la risposta" senza campo. `fermati_alle_domande` resta: se è `false` l'audio non si ferma mai (in
  quel caso niente campo).
- Senza rete: le app tengono la risposta in coda, mostrano subito la risposta attesa (dal dettaglio della
  lezione scaricata) e mandano la risposta appena c'è rete.

### 15.4 Domande saltabili o obbligatorie, scelto per ogni lezione (v1.2)
- `POST /api/lezioni` accetta `"domande_saltabili": false|true` (se manca: `impostazioni.domande_saltabili`,
  predefinito `false`). La `Lezione` lo riporta e si può cambiare dopo con `PATCH /api/lezioni/{id}`
  `{"domande_saltabili": true}`.
- **Saltabili** (`true`): pulsante **Salta** normale; il play di auricolari/schermata di blocco alla domanda
  vale come Salta.
- **Obbligatorie** (`false`): l'audio aspetta finché non arriva la risposta; il play di auricolari/schermata di
  blocco NON salta (ripete la domanda); resta comunque un pulsante **Salta comunque** più piccolo che chiede
  conferma ("Saltarla? Finisce in Da ripassare"). Il server accetta `saltata: true` in entrambi i casi: è una
  regola delle app, non del server.
- `GET/PATCH /api/impostazioni`: nuovo `"domande_saltabili": false`.

### 15.5 Dopo le revisioni (v1.3)
- `GET /api/salute` (anche senza token) e `POST /api/abbina` restituiscono `"server_id"`: 32 caratteri
  esadecimali fissi per questo server. Le app lo salvano all'abbinamento e, su un indirizzo `http`, mandano il
  token solo dopo aver verificato che `server_id` coincide (altrimenti: come senza rete).
- `POST /api/lezioni/stima` accetta `materiali` come `POST /api/lezioni`. `materiali: []` esplicito → 422
  "Scegli almeno un appunto o una verifica" in stima e in creazione; il predefinito "tutti" vale solo se il
  campo manca.
- `mancano` in salute: frasi semplici, senza nomi di programmi.
- `GET /api/salute` con token e `POST /api/abbina` restituiscono `"indirizzo_fuori_casa"` (stringa o null) da
  `LECTIO_INDIRIZZO_FUORI_CASA` in `server/.env`. Le app lo adottano se l'utente non ne ha scritto uno a mano:
  nessun indirizzo di rete è scritto nel codice.
- **`materiali` (quando c'è) è l'elenco COMPLETO del materiale da usare**, verifiche e interrogazioni comprese
  (vale sopra la 15.1). Le app che lo mandano (solo dopo "Cambia") includono tutte le verifiche e
  interrogazioni `pronto` della materia più gli appunti/libro lasciati accesi. Solo materiale `pronto`.
- Stima: `spesa_mese_eur` comprende il lavoro già in corso (lezioni in coda/in preparazione, OCR, schede);
  la parte in corso è anche in `impegnato_eur`. Le righe di `GET /api/spesa` hanno `stato`.
- `Materiale` ha `avviso` (null o frase, es. "Ho letto solo le prime 70 pagine: il resto supera il limite").
- Modello senza prezzo nel listino → 503 `fornitore_non_disponibile` con frase semplice su stima e creazione.
- `GET /scarica/lectio-pc.zip.sig` (firma ECDSA P-256 in base64 dello zip del PC) e in `GET /api/versione`
  `pc.firma: true|false`. Il programma PC installa un aggiornamento solo con firma valida.
- Aggiornamenti da soli (1.0.1). PC: 20 s dopo l'avvio e poi ogni 12 ore legge `/api/versione`; una versione
  nuova si scarica, si controlla (impronta, dimensione, firma) e si scompatta in `%LOCALAPPDATA%\Lectio\aggiornamento`;
  lo zip contiene `Lectio\versione.json` (`codice`, `nome`, coperto dalla firma) e si accetta solo con `codice` più
  alto del programma. Si installa quando Lectio si chiude (o al primo avvio dopo, e poi si riapre). Android: il
  controllo delle 12 ore e quello un minuto dopo che si lascia l'app installano senza chiedere, mai con l'app sullo
  schermo o una lezione che suona (in quel caso si scarica soltanto).

### 15.3 Materie: creazione e modifica
Già in sezione 4 (`POST/PATCH/DELETE /api/materie`). In più: `prof` facoltativo (0-40 caratteri), `nome`
unico senza distinguere maiuscole; 409 `conflitto` "Esiste già una materia con questo nome".
