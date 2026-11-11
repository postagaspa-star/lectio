# Lectio — contratto delle API (v1.6)

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
`mistral-medium-latest` (ora Medium 3.5: 1,50/7,50 $ per milione, contati giusti dalla 1.3.2; `mistral-large-4` costa
meno a token ma ragiona sempre prima di rispondere, provato l'11/11/2026: il ragionamento si paga e taglia le risposte,
il server gli chiede il livello basso e gli lascia spazio, ma non conviene), OCR `mistral-ocr-3`
(metà prezzo di `mistral-ocr-latest`, che ora è OCR 4.1), voce `voxtral-mini-tts-3-0` con `voice_id` `voice_482`
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
`tetto_vicino` | `materiale_in_cartella` (v1.6, sezione 27.1). Le app chiedono ogni 20 s mentre una lezione è in preparazione, altrimenti all'apertura.

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

## 16. Nuova lezione e materiale (v1.4, richieste di Andrea del 4/11)

Prototipo approvato: `docs/prototipo/NuovaLezionePc.dc.html`, `NuovaLezioneTel.dc.html`, `MaterialePc.dc.html`.

### 16.1 Tipo di lezione e indicazioni
`POST /api/lezioni` e `POST /api/lezioni/stima` accettano in più:
- `"tipo"`: `spiegazione` (predefinito) | `ripasso` | `interrogazione`;
- `"indicazioni"`: testo libero di Andrea, 0-600 caratteri (spazi in più tolti). Oltre 600 → 422.

La `Lezione` riporta `tipo`, `indicazioni` e `materiali` (elenco degli id scelti; `[]` = tutto il materiale,
solo per le lezioni vecchie). Com'è fatta (la stessa regola nel server, `misure.struttura`, e nelle app per il
riepilogo; `arrotonda` = per eccesso a metà, come `Math.round` sui positivi):

| tipo | parti | domande dopo ogni parte | domande alla fine | riepilogo nelle app |
|---|---|---|---|---|
| `spiegazione` | `max(3, arrotonda(durata/5))` | sì, una | `{15:4, 20:5, 30:6, 45:8}` | "6 parti, una domanda dopo ognuna e 5 alla fine" |
| `ripasso` | `max(2, arrotonda(durata/7.5))` | no | `arrotonda(finali_spiegazione × 1,6)` (15→6, 20→8, 30→10, 45→13) | "I punti chiave in breve e 8 domande alla fine" |
| `interrogazione` | gruppi di domande, `max(2, arrotonda(domande/5))` | — | `arrotonda(durata × 0,6)` domande in tutto (15→9, 20→12, 30→18, 45→27) | "12 domande come le fa il prof, con la risposta giusta dopo ognuna" |

- **Spiegazione**: come prima (parti con una domanda alla fine di ognuna, riepilogo, interrogazione, tre
  errori da non fare).
- **Ripasso**: parti brevi e fitte con i punti chiave (niente aggancio lungo, niente domanda per parte), poi le
  domande alla fine con la risposta completa, poi i tre errori.
- **Interrogazione**: niente spiegazione. Ogni capitolo è un gruppo di domande su una parte dell'argomento;
  dopo ogni domanda la risposta che il prof vuole sentire. Alla fine i tre errori. Tutte le domande sono di tipo
  `interrogazione` (le app si fermano come sempre e chiedono la risposta scritta).
- **Indicazioni**: vanno nei prompt dentro `<indicazioni>…</indicazioni>`: decidono su cosa insistere, che
  esempi fare, quanto difficili le domande; non possono cambiare le regole fisse (fatti veri, numero di parti e
  domande, durata, formato). Se mancano, la lezione segue il materiale e la scheda del prof.
- Il costo stimato cambia poco con il tipo (dipende soprattutto dalla durata e dal materiale). `POST /api/lezioni/stima`
  restituisce in più `"struttura": {"tipo", "parti", "domanda_per_parte", "finali", "domande"}`.

### 16.2 Materiale scelto per la lezione
Le app nuove mandano SEMPRE `materiali` con l'elenco completo dei materiali spuntati (appunti, libro, verifiche,
interrogazioni). Solo materiale `pronto`; `[]` → 422 "Scegli almeno un appunto o una verifica".

`GET /api/materie/{id}/suggeriti?argomento=<testo>` → materiale che parla dell'argomento:
```json
{"argomento": "Mirone e il Discobolo", "parole": ["mirone", "discobolo"], "suggeriti": [10, 11],
 "punti": {"10": 4, "11": 4, "9": 1}}
```
- Solo materiale `pronto`, appunti e verifiche insieme. Parole dell'argomento di almeno 4 lettere, senza accenti
  e senza parole vuote ("della", "come"…), confrontate per radice (scultura/sculture). Punti per ogni parola:
  2 se è nel titolo, 1 se è nel testo almeno due volte. Le parole che stanno in più del 60% del materiale della
  materia (con almeno 4 materiali) non contano, a meno che siano tutte così. Suggerito = punti ≥ 2 (≥ 1 se
  l'argomento ha una parola sola). Argomento vuoto → `suggeriti: []`.
- Nelle app: finché Andrea non tocca una casella, le caselle seguono i suggeriti ("Sull'argomento", chiesti di
  nuovo 400 ms dopo l'ultima lettera scritta). "Tutti" spunta tutto il materiale pronto, "Nessuno" toglie tutto.

### 16.3 Titolo e tipo di un materiale
`PATCH /api/materiale/{id}` `{"titolo": "Mirone e il Discobolo", "tipo": "appunti"}` (campi facoltativi) →
`Materiale`. `titolo` 1-120 caratteri (spazi in più tolti); `tipo` come in sezione 6. Se cambia il tipo da o
verso `verifica`/`interrogazione`, la scheda del prof si rifà (`in_aggiornamento`). 404 se non c'è.

`Materiale` ha in più `"titolo_da": "lectio" | "file" | "utente"`:
- `file`: titolo preso dal nome del file o predefinito ("Foto 4 novembre"); Lectio lo riscriverà;
- `lectio`: titolo scritto da Lectio leggendo il materiale (le app scrivono "titolo scritto da Lectio");
- `utente`: scelto da Andrea (scritto al caricamento o cambiato con PATCH). Lectio non lo tocca più.

Titoli scritti da Lectio: quando un materiale con `titolo_da: file` è `pronto` e ha almeno 200 caratteri, il
server chiede al modello piccolo (`LECTIO_MODELLO_PIANO`) un titolo di 2-7 parole che dica di cosa parla
(circa 0,01 centesimi a materiale, nel conto come "Titolo di …"). In modalità prova i titoli restano quelli del
file: Lectio li riscrive quando arriva la chiave. Se il tetto è finito o la risposta non va, resta quello del file. Al caricamento, un `titolo` uguale al nome del file (senza estensione, `_`/`-` come spazi) vale come
`file`: le app nuove non mandano più il titolo se Andrea non l'ha scritto.

### 16.4 Il caricamento nelle app
"Che cos'è" in due famiglie: **Da studiare** (`appunti`, `libro`: Lectio lo spiega e ci fa le domande) e
**Già corretta dal prof** (`verifica`, `interrogazione`: Lectio impara come chiede e corregge). Foto e file
partono da `appunti`; i testi incollati da Gemini col modello di verifica restano riconosciuti dal server.

### 16.5 Voci gratis sul NAS (v1.4, scelta di Andrea del 4/11)
Tre voci senza costi e senza internet, servite dal container `lectio-voci` (nessuna porta verso fuori; il
server lo raggiunge con `LECTIO_VOCI_URL`, per esempio `http://lectio-voci:8000`):

| id | nome | motore | minuti di lavoro per minuto di lezione |
|----|------|--------|-----------------------------------------|
| `nas:giovanni` | Giovanni | Kyutai Pocket TTS, modello italiano | 0,6 |
| `nas:giovanni_grande` | Giovanni grande | Kyutai Pocket TTS, modello italiano da 24 strati | 1,3 |
| `nas:supertonic` | Supertonic | Supertonic 3, voce maschile M1 | 1,1 |

In `GET /api/voci` hanno `"tipo": "gratis"`, `"fornitore": "nas"`, `"euro_al_minuto": 0`, `"disponibile"`
(il servizio risponde?) e in più `"minuti_per_minuto"`. Con le voci del NAS accese, il posto "Isabella non
ancora attiva" di Azure non compare più. La voce predefinita resta quella delle impostazioni (Lorenzo);
`voce_predefinita` accetta anche gli id `nas:…`. Senza voce scelta né predefinita usabile: Lorenzo, poi
`nas:giovanni`, poi la voce di prova, poi Azure.

`POST /api/lezioni/stima` restituisce anche `"minuti_preparazione"`: arrotondato di 2 + durata × minuti per
minuto (Lorenzo 0,1; Azure 0,15; prova 0,05). Le app lo mostrano come "pronta in circa N min" sulle voci che
hanno `minuti_per_minuto`. Le voci del NAS non toccano i caratteri gratis di Azure e non costano niente.

Se il servizio non risponde: `stima` e `POST /api/lezioni` con una voce `nas:…` danno 422 `campo: "voce"`
("Le voci gratis del NAS adesso non rispondono…"); `GET /api/salute` dà `"voce_gratis": {"stato":
"non_disponibile", "fornitore": "nas"}`. Un pezzo non riuscito si ritenta da solo (2, 4, 8 secondi); se non va
ancora, la lezione va in errore con la frase del servizio e "Tocca Riprova".

Servizio interno: `GET /salute` → `{"ok": true, "motori": [...]}`; `POST /voce` `{"motore": "giovanni",
"testo": "..."}` → `audio/wav` (al massimo 4000 caratteri, i segni `[emotion=…]`/`[pace=…]` si tolgono);
errori `{"errore": "frase"}` con 400 o 500. Una voce alla volta. Licenze: Kyutai Pocket TTS (codice MIT,
modelli CC-BY 4.0), Supertonic 3 (codice MIT, modello OpenRAIL-M).

## 17. Memoria sui documenti (v1.5, richieste di Andrea del 10/11)

### 17.1 Le regole
Vale per lezioni, schemi, "Non ho capito", ripasso lampo, verifica simulata, scheda del prof.
- **Solo i documenti.** Il testo si basa solo su quello che c'è nel materiale della materia. Niente fatti aggiunti dal
  modello, neanche se veri. Se manca, non si dice: una lezione o uno schema vengono più corti e la `nota` lo spiega.
- **Ordine di priorità** per qualsiasi cosa: 1 pagine del libro (`libro`), 2 verifiche (`verifica`), 3 interrogazioni
  (`interrogazione`), 4 appunti (`appunti`, `altro`). Se due documenti dicono cose diverse vale il numero più basso.
  Delle verifiche e interrogazioni contano le domande e le correzioni del prof, non le risposte scritte dallo studente.
- **Dove sta.** Sul NAS: ogni materiale letto si divide in pezzi (tabella `pezzi`) e si indicizza in sottofondo. Il
  modello riceve solo i pezzi trovati per quella parte. Senza chiave Mistral o a tetto finito si cerca per parole.
- **Controllo.** Dopo ogni parte scritta, un secondo passaggio confronta ogni frase con i pezzi usati. Quello che non
  c'è si fa riscrivere una volta; se resta, la frase si toglie.
- **Linguaggio.** Schemi e lezioni devono sembrare un normale ripasso fatto da una persona. Il server rifiuta le
  formule tipiche delle AI (`stile.py`) e toglie i segni che le tradiscono (trattino lungo, emoji).

### 17.2 Fonti
Ogni parte scritta porta gli id dei pezzi usati (`"fonti": ["B3", "V1"]`). L'oggetto che li spiega è
`{"materiale_id": 12, "titolo": "Libro, capitolo 7", "tipo": "libro", "pagina": 42, "etichetta": "Libro, pag. 42"}`
(`pagina` può essere `null`). Le app mostrano `etichetta`. Per verifiche e interrogazioni è "Verifica del 12/10".

## 18. Schemi
`POST /api/schemi/stima` `{"materia_id": 7, "argomento": "La scultura classica", "materiali": [1, 2], "forma": "auto",
"livello": "completo", "indicazioni": ""}` → `{"costo_eur": 0.08, "spesa_mese_eur": 0.21, "impegnato_eur": 0, "tetto_eur": 2,
"dopo_eur": 0.29, "consentita": true, "motivo": null, "si_azzera_il": "2026-12-01", "minuti_preparazione": 4, "immagini": 3}`.
`forma`: `auto` | `capitoli` | `schede`. `livello`: `essenziale` | `completo` | `approfondito`. `materiali` come in 16.2
(lista non vuota di materiali `pronto`). `indicazioni` come in 16.1.

`POST /api/schemi` (gli stessi campi più `evento_id` facoltativo) → 201 `Schema` in `in_coda`. Tetto superato: come per le
lezioni. `GET /api/schemi?materia_id=7` → lista di `Schema` senza `contenuto`, dal più recente. `GET /api/schemi/{id}` →
`Schema`. `POST /api/schemi/{id}/annulla`, `POST /api/schemi/{id}/riprova` (riparte da dove si era fermato),
`DELETE /api/schemi/{id}`. `GET /api/schemi/{id}/pdf` → `application/pdf`.

`Schema`: `{"id": 3, "materia_id": 7, "evento_id": null, "argomento": "…", "titolo": "…", "forma": "capitoli", "livello":
"completo", "indicazioni": "", "materiali": [1, 2], "stato": "in_preparazione", "fase": "scrittura", "fatto": 2, "totale": 8,
"frase": "Scrivo la parte 2 di 8", "errore": null, "nota": null, "stima_eur": 0.08, "creato_il": "…", "pronto_il": null,
"carte": 12, "contenuto": null}`. `stato`: `in_coda` | `in_preparazione` | `pronto` | `errore` | `annullato`. Si chiede ogni 3-5 s
finché è in lavorazione. Una novità `schema_pronto` o `schema_errore` avvisa quando finisce.

`contenuto`:
```
{"forma": "capitoli",
 "sezioni": [{"n": 1, "titolo": "…", "campi": [{"nome": "Autore", "valore": "Mirone"}],
   "blocchi": [
     {"tipo": "paragrafo", "titolo": "Descrizione", "testo": "Il **Discobolo** …", "fonti": ["B1"]},
     {"tipo": "punti", "titolo": null, "voci": ["…"], "fonti": ["B1"]},
     {"tipo": "passaggi", "titolo": null, "voci": ["causa", "effetto", "effetto dopo"], "fonti": ["B2"]},
     {"tipo": "definizione", "termine": "chiasmo", "testo": "…", "fonti": ["B2"]},
     {"tipo": "tabella", "titolo": "Date", "intestazione": ["Anno", "Evento"], "righe": [["1122", "…"]], "fonti": []},
     {"tipo": "prof", "testo": "Chiesto nella verifica del 12/10.", "fonti": ["V1"]},
     {"tipo": "immagine", "n": 1, "didascalia": "Discobolo di Mirone", "credito": "Foto di X, CC BY-SA 4.0, Wikimedia Commons"}
   ]}],
 "fonti": {"B1": {…Fonte}},
 "copertura": {"concetti": 42, "coperti": 41, "mancanti": ["…"]},
 "avvisi": ["Per la parte 4 non ho trovato una foto giusta."]}
```
`testo` e `voci` possono avere `**grassetto**` e nient'altro. Le formule sono già in caratteri normali (x², √2, (x+3)/(x−1)).
`campi` c'è solo nella forma `schede` (altrimenti `[]`). Tipi sconosciuti: le app li saltano.

Come si prepara (dalla 1.3.1, `fase` = `lettura`, `mappa`, `piano`, `scrittura`, `copertura`, `immagini`, `chiusura`; le app
mostrano solo `frase`): le pagine uguali (la stessa pagina fotografata due volte) si leggono una volta; il modello piccolo legge
TUTTO il materiale scelto, a gruppi di 40.000 caratteri, e segna i punti da sapere; i punti sulla stessa cosa (lo stesso
soggetto prima dei due punti, per esempio "Canossa (1077): …" da due documenti) diventano un argomento solo; il piano mette ogni
argomento in una sola parte, nell'ordine del tempo quando ci sono date, con parti di peso simile e senza parti di avanzi (una
parte da uno o due punti in uno schema lungo si unisce a quella con gli stessi documenti); poi il modello rilegge il piano e
scrive solo le correzioni (punto da spostare, parte da unire a un'altra, parte da mettere dopo un'altra), che fa il codice, così
nessun punto si perde; le parole di una parte crescono con i suoi punti. Il controllo
finale confronta ogni punto con il testo (prima le parole, poi il modello piccolo per i dubbi) e quello che manca si scrive in
fondo alla sua parte. `copertura.mancanti` sono i punti che i documenti nominano senza dire abbastanza per spiegarli.

Immagini: `GET /api/schemi/{id}/immagini/{n}` → JPEG. `POST /api/schemi/{id}/immagini/{n}/cambia` → `Schema` (ne cerca un'altra
coerente; 404 se non ce ne sono altre). `DELETE /api/schemi/{id}/immagini/{n}` → `Schema`. Le immagini sono poche: 2 in `essenziale`,
3 in `completo`, 5 in `approfondito`; di più solo se le indicazioni lo chiedono (fino a 40). Ognuna è controllata guardandola:
deve mostrare davvero quello che dice la didascalia, che è solo il nome di quello che si vede. Ogni immagine mostra una cosa
diversa. Le foto vengono da Wikimedia Commons; la richiesta si presenta con il nome di Lectio e il link pubblico del progetto.
Se non se ne trova una sicura, lo dice `avvisi`.

"Ascolta questa parte": l'app apre Nuova lezione con `argomento` = titolo della sezione e gli stessi `materiali` dello schema.

## 19. "Non ho capito"
`POST /api/chiarimenti` `{"materia_id": 7, "schema_id": 3, "sezione": 2, "passaggio": "testo del paragrafo", "domanda": "cosa vuol dire chiasmo?"}`
oppure `{"materia_id": 7, "lezione_id": 5, "posizione_s": 312.4, "domanda": ""}` → 200 (fino a 40 secondi di attesa)
`{"id": 9, "passaggio": "…", "domanda": "…", "risposta": "…", "trovato": true, "fonti": [{…Fonte}], "costo_eur": 0.002, "quando": "…"}`.
`domanda` è facoltativa (al massimo 300 caratteri). `trovato: false` = nei documenti non c'è; `risposta` lo dice e dice cosa manca.
Tetto finito: come le lezioni. `GET /api/chiarimenti?materia_id=7` → gli ultimi 50.

## 20. Ripasso lampo
`GET /api/lampo?materia_id=7&limite=12` (`materia_id` facoltativo) → `{"carte": [{"id": 4, "materia_id": 7, "materia": "Storia dell'Arte",
"colore": "rosa", "domanda": "…", "risposta": "…", "fonti": [{…Fonte}], "scatola": 2}], "da_fare": 8, "domani": 3}`.
`POST /api/lampo/{id}` `{"esito": "sapevo" | "quasi" | "non_sapevo"}` → `{"scatola": 3, "prossima": "2026-11-14", "da_fare": 7}`.
`DELETE /api/lampo/{id}` toglie una carta. Le carte nascono da sole: dagli schemi pronti, dalle risposte sbagliate o a metà nelle
lezioni, dalle domande sbagliate nella verifica simulata. Costo: niente per ripassare. Scatole: 1, 2, 4, 9, 21 giorni; con una
verifica vicina di quella materia la carta torna prima della verifica.
`GET /api/oggi` ha in più `"lampo": {"da_fare": 8, "minuti": 3}`. `Materia.conteggi` ha in più `"schemi"`, `"lampo"`, `"simulate"`.

## 21. Verifica simulata
`POST /api/simulate/stima` `{"materia_id": 7, "argomento": "…", "materiali": [1, 2], "minuti": 50, "indicazioni": ""}` → stessi campi della stima
degli schemi (senza `immagini`). `POST /api/simulate` (più `evento_id`) → 201 `Simulata`. `GET /api/simulate?materia_id=7` (lista di `Simulata` senza `domande` e `risposte`; `esito` ha solo `punti`, `punti_totali`, `voto_stimato`, `soglia`, `da_rifare`),
`GET /api/simulate/{id}`, `POST /api/simulate/{id}/annulla`, `POST /api/simulate/{id}/riprova`, `DELETE /api/simulate/{id}`,
`GET /api/simulate/{id}/pdf` → `application/pdf` per farla su carta.

`Simulata`: `{"id": 2, "materia_id": 7, "evento_id": null, "argomento": "…", "titolo": "…", "minuti": 50, "materiali": [1, 2], "indicazioni": "",
"stato": "pronta", "fase": null, "fatto": 0, "totale": 0, "frase": null, "errore": null, "nota": null, "stima_eur": 0.03, "creata_il": "…",
"iniziata_il": null, "consegnata_il": null, "domande": [ … ], "risposte": {"1": "testo"}, "esito": null}`.
`stato`: `in_coda` | `in_preparazione` | `pronta` | `in_corso` | `in_correzione` | `corretta` | `errore` | `annullata`.
`domande`: `[{"n": 1, "tipo": "aperta", "testo": "…", "punti": 10, "sezione": null}]`. Finché non è `corretta` il server non manda le risposte attese.

`PATCH /api/simulate/{id}` `{"risposte": {"1": "…"}, "iniziata": true}` salva quello che c'è (va chiamato spesso). `POST /api/simulate/{id}/consegna` → `Simulata` in
`in_correzione`; si chiede ogni 3-5 s. Corretta, `esito` è `{"punti": 62, "punti_totali": 100, "voto_stimato": 6.5, "soglia": 58, "domande": [{"n": 1, "esito": "giusta" |
"parziale" | "sbagliata" | "non_corretta", "punti": 8, "commento": "…", "mancava": ["…"], "risposta_attesa": "…", "fonti": [{…Fonte}]}], "da_rifare": 3}`.
`non_corretta` = l'esercizio non ha una soluzione nei documenti: si mostra "confronta tu", e i suoi punti non contano né in `punti` né in `punti_totali`. Gli esercizi si ripropongono solo se sono nei documenti, con gli stessi numeri.
Il formato (tipi di domande, punti, durata) viene dalle verifiche vere della materia; senza verifiche caricate la `nota` lo dice.

## 22. Colori delle materie
Sono 16: `ambra`, `rosa`, `petrolio`, `viola`, `blu`, `verde`, `arancio`, `oliva`, `giada`, `senape`, `orchidea`, `prugna`, `cacao`, `lavagna`, `salvia`, `lavanda`. I valori sono in `docs/aspetto.md`. `POST` e `PATCH` delle materie accettano solo questi; un nome che un'app non conosce diventa ambra.

## 23. Scheda del prof: numeri contati
`argomenti[].volte`, `argomenti[].esempio` e `tipi_domanda` non li scrive più il modello: li conta il server dalle domande vere, una per una, copiate dai documenti.
Una domanda vale "definizione" solo se chiede davvero di definire qualcosa. `cosa_corregge`, `cosa_premia` e `consigli` restano solo se hanno una prova copiata dai documenti.

## 24. File con il nome sbagliato
Un file `.pdf` che dentro è testo (per esempio Markdown) si legge come testo. Vale lo stesso per i file di testo con un'altra estensione ammessa.

## 25. Firma
Ogni PDF creato da Lectio ha in fondo a ogni pagina, in bianco, la firma scritta nel file `firma.txt` della cartella dati del server; gli audio la portano nei dati del file. I PDF li fa il server. Se un'app crea un altro file da salvare o esportare, ci mette la stessa scritta allo stesso modo.

## 26. Riassunti scritti
Un riassunto si fa a partire da uno schema `pronto`, in una di tre forme: `punti`, `discorsivo`, `misto` (la via di mezzo). Usa solo quello che c'è nello schema e passa dallo stesso controllo degli altri testi (17.1).

`GET /api/schemi/{id}` e le altre risposte con il `contenuto` hanno in più `"riassunti": {"stima_eur": 0.01, "fatti": [{"id": 4, "forma": "misto", "stato": "pronto"}]}` (`null` se lo schema non è pronto). Nella lista degli schemi è `null`.

`POST /api/schemi/{id}/riassunti` `{"forma": "misto"}` → 201 `Riassunto` in `in_coda`. Uno per forma: se quella forma c'è già la restituisce così com'è; se si era fermata per un errore riparte. Schema non pronto: 409. Tetto superato: come per le lezioni.
`GET /api/schemi/{id}/riassunti` → lista di `Riassunto`. `GET /api/riassunti/{id}` → `Riassunto`. `POST /api/riassunti/{id}/riprova`. `DELETE /api/riassunti/{id}`. Per rifarlo: `DELETE` e poi `POST`. `GET /api/riassunti/{id}/pdf` → `application/pdf`, con la firma (25).

`Riassunto`: `{"id": 4, "schema_id": 3, "materia_id": 7, "forma": "misto", "titolo": "…", "stato": "pronto", "frase": null, "errore": null, "nota": null, "stima_eur": 0.01, "creato_il": "…", "pronto_il": "…", "parole": 480, "contenuto": {"parti": [{"titolo": "…", "paragrafi": ["…"], "punti": ["…"]}]}}`.
`stato`: `in_coda` | `in_preparazione` | `pronto` | `errore`. Si chiede ogni 3 s finché è in lavorazione. Una novità `riassunto_pronto` o `riassunto_errore` avvisa quando finisce.
In `punti` i `paragrafi` sono vuoti; in `discorsivo` i `punti` sono vuoti; in `misto` vengono prima i paragrafi e sotto i punti. `titolo` di una parte può essere `null`. `**grassetto**` come negli schemi.

## 27. Cartelle (v1.6, richiesta di Andrea del 10/11)

Un livello solo: materia → cartelle → materiale. Nelle cartelle va solo il materiale (lezioni, schemi e verifiche
simulate no). Un materiale sta in una cartella sola, oppure fuori. Le scelte di Andrea: ogni cartella ha la sua
pagina; quando la elimina sceglie ogni volta se tenere i file; i file nuovi li mette Lectio nella cartella giusta.

`Cartella`: `{"id": 3, "materia_id": 7, "nome": "Arte greca", "file": 8, "messi_da_lectio": 2, "creata_il": "…"}`.
`file` = tutto il materiale dentro (anche quello in lettura o in errore); `messi_da_lectio` = quanti ce ne ha messi
Lectio nelle ultime 24 ore.

- `GET /api/materie/{id}/cartelle` → `[Cartella]` in ordine alfabetico.
- `POST /api/materie/{id}/cartelle` `{"nome": "Arte greca"}` → 201 `Cartella`. Nome 1-40 caratteri (spazi in più
  tolti), unico nella materia senza distinguere maiuscole: 409 "C'è già una cartella con questo nome in questa
  materia."; vuoto o troppo lungo: 422 con `campo: "nome"`.
- `PATCH /api/cartelle/{id}` `{"nome": "…"}` → `Cartella` (stesse regole). 404 se non c'è.
- `DELETE /api/cartelle/{id}` `{"conferma": true, "con_i_file": false}` → `{"ok": true, "file_eliminati": 0}`.
  Con `con_i_file: false` i file restano nella materia, fuori dalle cartelle; con `true` si cancellano come con
  `DELETE /api/materiale/{id}` (anche il file sul NAS; se va via una verifica o un'interrogazione la scheda del prof si
  rifà). Senza `conferma: true`: 422 con `campo: "conferma"`.
- `POST /api/materie/{id}/sposta` `{"materiali": [4, 7], "cartella_id": 3}` → `{"spostati": 2}`. `cartella_id: null`
  = fuori dalle cartelle. Da 1 a 500 id, tutti di questa materia, altrimenti 422 (`campo` `materiali` o
  `cartella_id`).
- `GET /api/cartelle/{id}/proposti` → `{"proposti": [12, 15]}`: il materiale pronto fuori dalle cartelle che parla di
  questa cartella, da spuntare già in "Metti dei file qui". Gratis, senza AI.

`Materiale` ha in più `"cartella_id"` (null = fuori) e `"cartella_da"`: `"utente"` (l'ha messo Andrea) | `"lectio"`
(l'ha messo Lectio) | null.
- `POST /api/materie/{id}/materiale`: campo facoltativo `cartella_id` (nel multipart come testo, nel JSON come numero).
  Con la cartella il materiale va lì; senza, la sceglie Lectio dopo averlo letto (27.1). Una cartella di un'altra
  materia o che non c'è: 422 con `campo: "cartella_id"`.
- `PATCH /api/materiale/{id}` accetta `"cartella_id"` (numero, o null = fuori): lo sposta. Se il campo manca non cambia
  niente.

### 27.1 Lectio mette i file nuovi nella cartella giusta

Solo il materiale caricato senza cartella dalla 1.3.0 in poi, una volta sola, dopo che il server l'ha letto (e, con la
chiave, dopo il titolo scritto da Lectio). Niente AI, gratis:
1. se tutte le parole del nome di una cartella sono nel titolo del file, e quelle di nessun'altra cartella, va lì
   (parole di almeno 4 lettere, per radice; quelle presenti in più del 60% del materiale della materia non contano);
2. altrimenti si confronta con i file che ogni cartella ha già (parole in comune, quelle rare nella materia contano di
   più): va nella cartella più simile solo se lo è almeno il doppio di ogni altra cartella e del materiale che sta
   fuori, e oltre un minimo.
Se non è chiaro resta fuori, e non si riprova. Il materiale spostato da Andrea (anche fuori) Lectio non lo tocca più.
Provato sul materiale vero di Storia (37 pagine di libro, quattro cartelle di prova): nessun file messo dove non
c'entrava; quelli dubbi restano fuori.

Quando Lectio mette un file in una cartella arriva una novità `materiale_in_cartella` (sezione 11): "Ho messo «Mirone e
il Discobolo» nella cartella Arte greca".

### 27.2 Le cartelle nella scelta del materiale

- `GET /api/materie/{id}/suggeriti`: il nome della cartella conta come il titolo di ogni suo file, così l'argomento
  "Arte greca" sceglie i file della cartella Arte greca.
- Nelle app (Nuova lezione, Nuovo schema, Nuova verifica simulata) una pillola per cartella spunta o toglie in un tocco
  tutto il suo materiale pronto (`docs/aspetto.md` §42.7).
