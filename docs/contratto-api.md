# Lectio — contratto delle API (v1.8)

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

### 5.1 Il modulo delle verifiche (server 1.6.0, computer 1.5.0; a righe e con il contesto dal server 1.6.1, computer 1.5.1)
Richiesta di Andrea del 24/11/2026: oltre alla scheda, quando legge una verifica o un'interrogazione (corretta o no) Lectio
compila un secondo modulo che serve solo a fare verifiche di prova e interrogazioni il più possibile uguali a quelle del prof.
Dal server 1.6.1 si compila in una chiamata sua, subito dopo quella della scheda, con gli stessi documenti (circa 0,2
centesimi in più per ogni aggiornamento); se quella chiamata non riesce, resta il modulo di prima.
`GET /api/materie/{id}/scheda` ha in più (un'app che non li conosce li ignora):
```json
"verifiche": {
  "struttura": "1) Tabella da completare: 6 equivalenze e 8 numeri in notazione scientifica.\n2) Tre problemi brevi.\n3) L'ultimo problema chiede di motivare il ragionamento.",
  "contesto": "Problemi con situazioni reali: un terreno su cui costruire case, la distanza Terra-Luna.",
  "consegne": ["Scomponi i seguenti polinomi [8 punti]:", "ILLUSTRA IL RAGIONAMENTO: motiva i passaggi logici."],
  "punti": "Punti scritti accanto a ogni esercizio.", "punti_totali": 30, "sufficienza": 18,
  "regole_punti": ["0,5 con imprecisione", "Il participio passato deve essere corretto"],
  "difficolta": "Esercizi diretti sulle regole, l'ultimo più difficile (equazione con il parametro).",
  "approfondimento": "Chiede il perché dei passaggi, non solo il risultato.",
  "cosa_da": "Due testi da leggere con le domande sotto.",
  "richieste": ["Specificare se l'equazione è pura, spuria o completa"]
},
"interrogazioni": {
  "inizio": "Parte con una domanda larga sull'argomento e lascia parlare.",
  "andamento": "Rilancia sui concetti che tiri fuori: «ti ricordi perché…».",
  "durata": "Più studenti insieme, 4-6 domande a testa.",
  "profondita": "Vuole le definizioni precise e il perché.",
  "cosa_vuole": ["Definizioni precise"],
  "esempi": ["Ti ricordi perché per i pitagorici il numero è il principio?"]
}
```
- I due oggetti ci sono sempre; un campo vuoto è `""`, `[]` o `null`. Le app mostrano solo i campi pieni e nascondono una
  parte del tutto vuota. `interrogazioni` l'AI la compila solo dalle interrogazioni e dagli esiti orali, `verifiche` solo
  dalle verifiche e dagli esiti scritti: senza quei documenti resta vuota.
- `consegne` ed `esempi` sono copiati parola per parola dai documenti (le `consegne` solo dalle verifiche, mai dagli esiti) e
  arrivano senza il numero davanti e con le formule in testo normale; ogni altra voce l'AI la scrive solo con una prova
  copiata dai documenti, e il server scarta quelle senza prova. `punti_totali` (intero) e `sufficienza` (numero, anche con
  il mezzo punto) solo se sono scritti nei documenti.
- I campi di testo (`struttura`, `contesto`, `punti`, `difficolta`, `approfondimento`, `cosa_da`, `inizio`, `andamento`,
  `durata`, `profondita`) dal server 1.6.1 possono avere più righe (a capo nel
  testo JSON): una cosa per riga, ognuna con la sua prova (una riga senza prova si scarta, le altre restano). `contesto` (server 1.6.1, computer 1.5.1) dice come il prof
  presenta le domande e di cosa parlano: situazioni reali, personaggi, frasi della vita di tutti i giorni, un libro letto in
  classe, oppure esercizi astratti. Un computer che non conosce `contesto` lo ignora e, nel `PUT`, non lo manda: resta.
- Limiti: campi di testo al massimo 800 caratteri (prima della 1.6.1: 300); `consegne` al massimo 6 voci da 300 caratteri; `regole_punti`, `richieste`,
  `cosa_vuole` al massimo 6 voci da 200; `esempi` al massimo 6 da 200; `punti_totali` da 1 a 1000; `sufficienza` maggiore di
  0 e minore di `punti_totali`.
- `PUT /api/materie/{id}/scheda` accetta anche `verifiche` e `interrogazioni`, anche con solo alcuni campi: i campi mandati
  diventano quelli di Andrea e un aggiornamento dell'AI non li cambia più (come il resto della scheda: le liste ricordano le
  voci aggiunte e tolte, i testi e i numeri restano quelli scritti da lui; un testo svuotato resta vuoto).
- Dove si usa: la verifica di prova segue `verifiche` (struttura, contesto, consegne, totale dei punti, difficoltà, approfondimento,
  testi da leggere, richieste) anche quando tra i materiali scelti non c'è una verifica; la correzione segue `regole_punti`
  e, se ci sono `punti_totali` e `sufficienza`, la sufficienza del prof (la soglia dell'esito e il voto stimato: la
  sufficienza vale 6). Le lezioni fanno le domande dell'interrogazione come dice `interrogazioni`.

## 6. Materiale

`Materiale`: `{"id":7,"materia_id":1,"tipo":"verifica","titolo":"Verifica 12 ottobre","data":"2026-10-12","origine":"file","nome_file":"verifica.pdf","caratteri":5321,"stato":"pronto","errore":null,"costo_eur":0.0,"creato_il":"…"}`
`tipo`: `verifica` (corretta, di solito dal testo di Gemini) | `interrogazione` | `appunti` | `libro` | `altro`.
`origine`: `file` | `foto` | `scritto`. `stato`: `in_lettura` | `pronto` | `errore`.

- `POST /api/materie/{id}/materiale` multipart: `file` (uno) + `tipo`, `titolo`, `data` facoltativi; oppure
  JSON `{"tipo","titolo","data","testo"}` per appunti/interrogazioni scritti. Formati: `.txt .md .pdf .docx
  .odt .rtf` e immagini `.jpg .jpeg .png .webp .heic .heif`. Le foto HEIC/HEIF (server 1.3.6) diventano JPEG sul
  server (qualità 90, lato lungo al massimo 3000 px, girate come vanno viste; `nome_file` e `formato` diventano
  `.jpg`); l'app del telefono le converte già lei prima di mandarle. Massimo 20 MB a file e
  200.000 caratteri letti. PDF con testo: letto gratis; PDF scansionato o immagine: OCR Mistral a pagamento
  (stima prima: se sfora il tetto → 402). Dal server 1.4.3 una foto piccola (lato lungo fino a 1600 px, per esempio da
  WhatsApp) la legge `gpt-6-luna` quando c'è la chiave OpenAI, al massimo 0,001 € a foto: il `testo` comincia con una
  riga che spiega i segni (`[[ ]]` sottolineato, «Appunto a mano (letto così, da controllare…)», `[?]`, `[tagliato]`) e la
  spesa si chiama «Lettura di «…» (foto piccola)». Se luna non riesce si legge con l'OCR come prima. Titolo predefinito: dal file (per i testi di Gemini, la riga
  `MATERIA/PROF/DATA/TIPO` se c'è).
  Cartelle e zip (app 1.3.3): il server non li riceve mai. Le app aprono la cartella o lo zip (anche le
  sottocartelle e gli zip dentro gli zip), lasciano fuori i file che il server non legge e mandano gli altri qui, uno
  per richiesta, con lo stesso `tipo`, `cartella_id` e `data` e senza `titolo`. Così il limite dei 20 MB vale per
  ogni file e non per lo zip intero. Un 400/413/422 su un file: quel file resta fuori e si va avanti; rete, 402 e
  5xx fermano l'invio, che riparte dal file fermo.
- `GET /api/materie/{id}/materiale` → `[Materiale]` (più recenti prima).
- `GET /api/materiale/{id}` → `Materiale` + `"testo"` (quello che il server ha letto).
- `DELETE /api/materiale/{id}`.
- `POST /api/materiale/{id}/riprova` (se `errore`).

## 7. Verifiche e interrogazioni in arrivo (eventi)

`Evento`: `{"id":3,"materia_id":1,"tipo":"scritta","argomento":"Impero romano","data":"2026-10-30","esito":null,"lezione_id":12}`
(`tipo` `scritta` | `orale`; `lezione_id` = l'ultima lezione fatta per questo evento; `esito`, se c'è, ha `domande_uscite`, `voci_uscite`,
`altre_domande`, `voto`, `previste_uscite`, `totale_vere`, `percentuale`).
- `GET /api/eventi?da=YYYY-MM-DD` (predefinito: oggi meno 30 giorni), `POST /api/materie/{id}/eventi`,
  `PATCH /api/eventi/{id}`, `DELETE /api/eventi/{id}`.
- `POST /api/eventi/{id}/esito`
  `{"domande_uscite":[31,33,36],"altre_domande":"Chi era Seiano?\nCos'era il pretorio?","voto":"7"}` →
  `{"previste_uscite":3,"totale_vere":5,"percentuale":60}`. Le `domande_uscite` sono id di domande della
  lezione dell'evento; `totale_vere` = uscite + righe non vuote di `altre_domande`. Fa partire l'aggiornamento
  della scheda.
- `GET /api/eventi/{id}/previste` (server 1.4.6) → `{"gruppi": [{"fonte": "lezione" | "simulata" | "schema", "id": 14,
  "voci": [{"chiave": "schema:14:3", "testo": "Victor dell'Aveyron"}]}]}`: quello che Lectio aveva preparato per la
  verifica, da spuntare in "Com'è andata", in quest'ordine: le domande dell'ultima lezione (`domanda:{id}`), le domande
  dell'ultima verifica simulata pronta, in corso o corretta (`simulata:{id}:{n}`), le parti con un titolo dell'ultimo
  schema pronto (`schema:{id}:{n}`, n da 1; il quadro d'insieme finale, una sola tabella, resta fuori). Un gruppo
  senza voci non c'è; 404 se l'evento non c'è.
- Dal server 1.4.6 l'esito accetta anche `"voci_uscite": ["schema:14:3", "simulata:4:2", "domanda:31"]` (al massimo
  300, ognuna tra quelle di `/previste`, se no 422 sul campo `voci_uscite`). Le `domanda:` si uniscono alle
  `domande_uscite` (contate una volta sola); le altre contano tra le `previste_uscite` e restano nell'esito come
  `voci_uscite`, con il loro testo per la scheda del prof (le parti dello schema come "argomento: …").
- `POST /api/eventi/{id}/nascondi-racconto` → `{"ok": true}` (server 1.4.5): chiude la scheda "Dopo la verifica" di Oggi
  senza raccontare; l'esito resta `null` e l'evento non torna in `da_raccontare`.

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

**Dalla 1.3.9 (scelta di Andrea del 15/11/2026)** tutti i testi, scrittura e lavori leggeri, li scrive `gpt-6-luna` di
OpenAI (0,10/0,50 $ per milione, ragionamento `low`; `LECTIO_OPENAI_KEY`), con la riserva `deepseek-v4-1-flash-1017`
ospitata da Mistral (0,30/1,20 $, "anteprima pubblica", non ragiona; `LECTIO_MODELLO_RISERVA`). Il motivo: Medium 3.5
si spegne il 30/04/2027 e luna costa circa quindici volte meno. La riserva entra quando OpenAI rifiuta la richiesta
(chiave, credito finito, errore), quando è occupato anche dopo i 3 ritenti o quando dà due risposte non usabili: si
rifà la stessa richiesta con DeepSeek, e le righe di spesa lo dicono ("(riserva)", fornitore `mistral`). Senza la
chiave OpenAI scrive direttamente la riserva (anche le stime usano il suo prezzo). OCR, voce e ricerca nei documenti
restano su Mistral. **Dalla 1.4.4 (scelta di Andrea del 22/11/2026)** tutto quello che va a Mistral (riserva, OCR,
voce, ricerca) passa dall'indirizzo europeo `https://api.eu.mistral.ai/v1`, che elabora solo in centri UE/EFTA e costa
1,1 volte il listino (`ricarico_mistral` in `prezzi.json`, già nelle stime); `LECTIO_MISTRAL_URL` lo cambia. Quello che
scrive luna va ancora a OpenAI negli Stati Uniti. La storia di prima, per chi riprende i modelli Mistral dal file `.env`:

Modelli predefiniti fino alla 1.3.8: piano e scheda `mistral-small-latest`, scrittura
`mistral-medium-latest` (ora Medium 3.5: 1,50/7,50 $ per milione, contati giusti dalla 1.3.2; `mistral-large-4` costa
meno a token ma ragiona sempre prima di rispondere, provato l'11/11/2026: il ragionamento si paga e taglia le risposte,
il server gli chiede il livello basso e gli lascia spazio, ma non conviene). Negli schemi (dalla 1.3.3) la scrittura
fa solo il piano e la sua rilettura; parti, aggiunte e quadro d'insieme li scrive `mistral-small-latest` (Small 4,
`LECTIO_MODELLO_PARTI`): sugli stessi 4 documenti tutto Medium ~0,09 €, così 0,025 € con parti ordinate. Provato l'11/11/2026
anche il resto con Small 4, sugli stessi documenti e sullo stesso piano: "Non ho capito" uguale a Medium per un decimo del
prezzo, quindi dalla 1.3.4 lo fa il modello piccolo (`LECTIO_MODELLO_PIANO`); restano su Medium le lezioni (Small 4 sbaglia
più date, inventa dettagli e lascia frasi monche dopo il controllo: 0,04 € invece di 0,36 € per 45 minuti, ma peggio), i
riassunti (Small 4 salta 5 argomenti su 18) e le verifiche simulate (Small 4 fa crocette senza le risposte da scegliere;
con Medium costano già 2 centesimi). Riprovato la sera stessa con le correzioni della 1.3.5, che valgono per tutti i
modelli: date in cifre come nei documenti (a voce le trasforma in parole il server, `stile.numeri_a_voce`), una frase
tolta dal controllo non lascia più il paragrafo monco (si toglie tutto il paragrafo se era la prima frase, più di metà
o la frase che regge la seguente), crocette, vero/falso e collegamenti controllati e fatti correggere una volta (quelli
ancora rotti si tolgono), riassunto con una parte per ogni sezione dello schema, "magari" e "sul libro è scritto"
segnalati allo stile. Con Small 4 date, crocette e argomenti saltati si sistemano (22 sezioni → 26 parti); restano i
dettagli inventati (un santo patrono, i solidi di Platone, Russell al posto di Galileo e Cartesio) e il controllo non
li vede nemmeno fatto da Medium, che in più segnalava come fuori 10 frasi giuste su 11: per questo lezioni, riassunti e
simulate restano su Medium. OCR `mistral-ocr-3`
(metà prezzo di `mistral-ocr-latest`, che ora è OCR 4.1), voce `voxtral-mini-tts-3-0` con `voice_id` `voice_482`
(Lorenzo, unica voce italiana preimpostata). Chiamata voce Mistral: `POST https://api.eu.mistral.ai/v1/audio/speech` (dal server 1.4.4 l'indirizzo europeo di Mistral, +10%)
`{"model","input","voice_id","response_format":"mp3"}` → JSON con `audio_data` in base64. Azure:
`POST https://<regione>.tts.speech.microsoft.com/cognitiveservices/v1` con SSML,
`X-Microsoft-OutputFormat: audio-24khz-48kbitrate-mono-mp3`.

## 10. Oggi

`GET /api/oggi` →
```json
{"oggi": "2026-10-28",
 "prossima": {"evento": {…Evento}, "materia": {"id":1,"nome":"Storia","colore":"ambra"}, "lezione": {…Lezione o null}, "schema": {…Schema senza contenuto, o null}, "tra_giorni": 2},
 "riprendi": {…Lezione con posizione, o null},
 "da_raccontare": [{"evento": {…}, "materia": {…}}],
 "piu_avanti": [{"evento": {…}, "materia": {…}, "tra_giorni": 6}],
 "spesa": {"speso_eur": 0.80, "tetto_eur": 2.00, "percentuale": 40, "si_azzera_il": "2026-11-01", "avviso": false},
 "modalita_prova": false}
```
`prossima` = l'evento futuro (o di oggi) più vicino; `riprendi` = l'ultima lezione ascoltata non finita;
`da_raccontare` = eventi passati (fino a 30 giorni) senza esito con una lezione; `piu_avanti` = i 4 eventi
seguenti; `avviso` = spesa ≥ 80% del tetto.

Dal server 1.4.5 (richieste di Andrea del 23/11/2026):
- **Le verifiche si fanno al mattino**: dalle 13 (ora italiana) gli eventi di oggi sono già fatti. Prima delle 13 sono
  ancora in `prossima`/`piu_avanti`; dalle 13 passano a `da_raccontare` (e la prossima diventa quella dopo).
- `prossima.schema`: lo schema fatto per quella verifica (l'ultimo pronto, se no l'ultimo non annullato), come nella
  lista di `GET /api/schemi`. Le app lo mostrano prima della lezione; senza schema e senza lezione propongono
  **Prepara lo schema**.
- `da_raccontare`: eventi già fatti, senza esito e non nascosti (`POST /api/eventi/{id}/nascondi-racconto`), per cui
  Lectio ha preparato qualcosa: una lezione pronta, uno schema pronto o una verifica simulata.
- `lampo` conta solo le carte per le verifiche in arrivo (sezione 20).

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

`LECTIO_MISTRAL_KEY`, `LECTIO_OPENAI_KEY` (1.3.9), `LECTIO_AZURE_KEY`, `LECTIO_AZURE_REGIONE` (es. `westeurope`),
`TS_AUTHKEY`; facoltativi: `LECTIO_MODELLO_PIANO`, `LECTIO_MODELLO_SCRITTURA`, `LECTIO_MODELLO_PARTI`,
`LECTIO_MODELLO_RISERVA`, `LECTIO_MODELLO_OCR`, `LECTIO_MODELLO_VOCE`, `LECTIO_VOCE_MISTRAL` (predefinito `voice_482`).
Le scrive Andrea con `ssh -t "<utente>"@<ip-del-nas> "bash /volume1/docker/Lectio-NAS/imposta-chiave.sh"` (domanda
nascosta, riavvio da solo; con `openai` dopo il nome dello script chiede subito quella). Senza Mistral → modalità prova.
Senza OpenAI → i testi li scrive la riserva su Mistral. Senza Azure → niente voce gratis (la voce espressiva resta).
In `/api/salute` `testo.fornitore` è `openai` quando scrive luna, `mistral` quando scrive Mistral.

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

Dal server 1.4.5 `GET /api/lampo?solo_verifiche=true` dà solo le carte nate da uno schema, da una lezione o da una
verifica simulata fatti per una verifica che deve ancora esserci (dalle 13 quelle di oggi sono già fatte, sezione 10).
`lampo` di `GET /api/oggi` conta solo queste: la scheda di Oggi apre `/api/lampo?solo_verifiche=true`. Le altre carte
restano nella pagina della materia (`materia_id`, `Materia.conteggi.lampo`), che le conta tutte.

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

### 21.1 Esercizi con i numeri, grafici e allegati (server 1.5.0, computer 1.4.0)
Gli esercizi con i numeri (calcoli, equazioni, disequazioni, sistemi, scomposizioni) hanno sempre numeri nuovi rispetto ai materiali: dai
materiali si prendono tipo, difficoltà e richieste. La soluzione la calcola il server con un programma (`calcolo.py`), non l'AI; un esercizio
che il programma non sa risolvere, o con gli stessi numeri dei materiali, non entra. Le materie con i numeri sono quelle con nel nome
matematica, fisica, chimica, algebra, geometria, statistica, aritmetica o trigonometria.

Ogni domanda di `domande` può avere in più (un'app che non li conosce li ignora):
- `forma`: `"testo"` (la casella di sempre), `"formule"` (la casella con la barra della matematica: le domande aperte delle materie con i
  numeri), `"conti"` (un esercizio con i numeri: la casella dei passaggi con la barra e un campo per ogni voce di `risultati`). Senza `forma`
  vale `"testo"`.
- `risultati`: solo con `"conti"`, i nomi dei campi del risultato: `["Semidispersione massima"]`, `["Soluzione"]`, `["x", "y"]`.
- `tabella`: `null` oppure `{"titolo": null, "colonne": ["x (cm)", "Δx (cm)"], "valori": [["1,72", "0,02"], ["1,58", "0,03"]]}`: testi già
  scritti all'italiana, da mostrare così come sono.
- `grafico`: `null` oppure `{"tipo": "punti" | "linee", "titolo": null, "asse_x": "t (s)", "asse_y": "s (m)", "serie": [{"nome": null,
  "punti": [[0, 0], [1, 2.5]]}]}`, oppure `{"tipo": "barre", "titolo": null, "asse_x": "…", "asse_y": "…", "categorie": ["A", "B"],
  "serie": [{"nome": null, "valori": [3, 5]}]}`. I numeri sono numeri JSON; le funzioni arrivano già come `"linee"` di punti. Le app
  disegnano assi, tacche e numeri all'italiana; se lo 0 è dentro l'intervallo, l'asse passa per lo 0.

`Simulata` ha in più `risultati` (`{"3": ["0,07 cm"]}`: quello scritto nei campi del risultato, nell'ordine di `domanda.risultati`) e
`allegati` (`{"3": [{"id": "k3J9x2aQ", "nome": "foglio.jpg", "tipo": "foto" | "pdf"}]}`).

`PATCH /api/simulate/{id}` accetta anche `risultati`, nello stesso formato: si unisce domanda per domanda come `risposte` (al massimo 6
campi per domanda, 200 caratteri l'uno).
`POST /api/simulate/{id}/allegati` (multipart: `n` = il numero della domanda, `file`; massimo 20 MB; jpg, jpeg, png, webp, heic, heif,
pdf) → 201 `{"id": "…", "nome": "…", "tipo": "foto" | "pdf"}`. Al massimo 4 file per domanda; le HEIC diventano JPEG. Solo con la
verifica `pronta` o `in_corso`, se no 409. `DELETE /api/simulate/{id}/allegati/{allegato_id}` → `{"ok": true}` (stessi stati).
Alla correzione il server legge i file (le foto piccole con luna, il resto con l'OCR) e li manda alla correzione con la risposta.

Nell'`esito`, ogni domanda con `"conti"` ha in più `controllo`: `[{"cosa": "Semidispersione massima", "scritto": "0,25", "atteso": "0,07 cm",
"esito": "giusto" | "non_arrotondato" | "sbagliato" | "mancante" | "da_leggere"}]`, il confronto fatto dal programma (`da_leggere`: il
programma non riesce a leggere quello che è scritto e decide la correzione). I `punti` di una domanda e il totale possono avere il mezzo
punto (`1.5`).
La correzione conta solo quello che la domanda chiede (niente punti tolti per un arrotondamento o un'unità di misura non chiesti); negli
esercizi un errore di calcolo con il metodo giusto vale circa metà dei punti.

### 21.2 Testi da leggere (server 1.6.0, computer 1.5.0)
Se il prof dà testi da leggere con le domande sotto (lo dice il modulo delle verifiche, §5.1), la verifica di prova mette un pezzo dei
materiali copiato parola per parola. Ogni domanda di `domande` può avere `brano`: `null` oppure `{"testo": "…", "fonte": "Antropologia,
pag. 45"}` (`testo` al massimo 1500 caratteri, a capo con `\n`; `fonte` il titolo del materiale, o `null`). Le domande sullo stesso testo
hanno lo stesso `brano`: le app lo mostrano una volta sola, sopra la prima domanda del gruppo (e di nuovo quando cambia), anche nell'esito.
Il server scarta un testo che non è copiato dai materiali. Il PDF lo stampa in un riquadro sopra la prima domanda.

### 21.3 Come le verifiche del prof (server 1.6.0)
Nessun cambio al formato: sono controlli del server su quello che scrive l'AI.
- Tipi di domanda: ogni tipo compare al massimo quanto in `tipi_domanda` della scheda (§23), arrotondato per eccesso; un tipo che lì non
  c'è non compare. Si conta come la scheda: un esercizio con i conti è un esercizio, una domanda che chiede che cos'è una cosa è una
  definizione se la scheda conta le definizioni. Nelle materie con i numeri gli esercizi con i conti non sono mai di troppo e le domande
  di teoria («che cos'è», «spiega come si calcola», «descrivi i passaggi») si contano a parte: nessuna se le verifiche del prof (tra i
  materiali o nelle consegne del modulo) non ne hanno, se no al massimo la quota delle definizioni. Le domande di troppo si chiedono di
  rifare; quelle che restano si tolgono, ma la verifica non perde mai più di metà delle domande.
- La consegna di un esercizio con un valore da calcolare non dà la formula né il procedimento: se la dà, si chiede di toglierla e, se
  resta, il server la toglie dal testo (o scarta l'esercizio se senza formula non si capisce cosa chiede).
- La correzione giudica il significato: simboli diversi per la stessa grandezza, altre parole o un altro ordine sono giusti; una parola
  imprecisa toglie al massimo mezzo punto.
- Esercizi che si capiscono (server 1.6.1): una tabella con righe «x1, x2…», «completa la tabella» quando i risultati vanno nei campi,
  gli stessi dati nel testo e nella tabella o un risultato lungo (come 1/49) senza chiedere di arrotondare si chiedono di sistemare. Se
  il calcolo arrotonda ma il testo non lo chiede, il server aggiunge la frase («Arrotonda il risultato a due cifre significative.»); se il
  testo dice «a due cifre», il server precisa «significative» o «decimali» come nel calcolo. Contano come teoria anche «spiega quando»,
  «quando si dice» e «in che cosa consiste».
- Il controllo dei fatti non tocca le consegne delle domande, solo le risposte attese.

## 22. Colori delle materie
Sono 16: `ambra`, `rosa`, `petrolio`, `viola`, `blu`, `verde`, `arancio`, `oliva`, `giada`, `senape`, `orchidea`, `prugna`, `cacao`, `lavagna`, `salvia`, `lavanda`. I valori sono in `docs/aspetto.md`. `POST` e `PATCH` delle materie accettano solo questi; un nome che un'app non conosce diventa ambra.

## 23. Scheda del prof: numeri contati
`argomenti[].volte`, `argomenti[].esempio` e `tipi_domanda` non li scrive più il modello: li conta il server dalle domande vere, una per una, copiate dai documenti.
Una domanda vale "definizione" solo se chiede davvero di definire qualcosa. `cosa_corregge`, `cosa_premia` e `consigli` restano solo se hanno una prova copiata dai documenti.
Dal server 1.6.0 un esercizio con tanti punti dello stesso tipo (frasi da completare, polinomi da scomporre) conta come una domanda sola, e
`argomenti[].esempio` arriva senza il numero davanti e con le formule in testo normale (niente LaTeX), come le consegne del modulo (§5.1).

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

## 28. Modifiche dopo (v1.7, richiesta di Andrea del 12/11)

Una lezione, uno schema, un riassunto o una verifica simulata già fatti si possono cambiare: con l'AI ("Modifica", Andrea
scrive cosa vuole), a mano (schemi e riassunti, gratis) e tornando alla versione di prima. Il programma PC lo fa dalla
1.3.6; il telefono per ora no (le app che non conoscono questi campi li ignorano).

### 28.1 Modifica con l'AI

`tipo`: `lezione` | `schema` | `riassunto` | `simulata` | `mappa` (29). `id` = l'id della lezione, dello schema, del riassunto o della
verifica. `parti`: i numeri delle parti da cambiare (vedi `parti_modificabili` in 28.3); lista vuota = "Tutto": Lectio
decide da solo che cosa fare in ogni parte (piano qui sotto). `richiesta`: 1-600 caratteri.

- `POST /api/modifiche/stima` `{"tipo": "schema", "id": 12, "richiesta": "Aggiungi le date", "parti": [2, 3]}` → gli
  stessi campi della stima degli schemi (`costo_eur`, `spesa_mese_eur`, `impegnato_eur`, `tetto_eur`, `dopo_eur`,
  `consentita`, `motivo`, `si_azzera_il`) più `"minuti_preparazione"` e `"fino_a": true|false` (`true` con "Tutto": il
  costo è il massimo, se Lectio cambia tutte le parti; di solito costa meno).
- `POST /api/modifiche` (stesso corpo) → 201 `Modifica` in `in_coda`. 404 se l'oggetto non c'è; 409 se l'oggetto non si
  può modificare adesso (lezione non `pronta`, schema o riassunto non `pronto`, verifica non `pronta`, `in_corso` o
  `corretta`) o se ha già una modifica in coda o in preparazione; 422 con `campo` `richiesta` o `parti`; 402 se il tetto
  non basta (come le lezioni).
- `GET /api/modifiche?attive=1` → `[Modifica]` in coda o in preparazione (serve al PC per sapere se c'è lavoro in corso).
  `GET /api/modifiche/{id}` → `Modifica`.
- `POST /api/modifiche/{id}/annulla` (in coda, in preparazione o in errore: diventa `annullata`, l'oggetto resta com'era;
  le spese già fatte restano nel conto). `POST /api/modifiche/{id}/riprova` (solo `errore`: riparte da dove si era
  fermata, senza ripagare le parti già riscritte).

`Modifica`: `{"id": 4, "tipo": "schema", "oggetto_id": 12, "materia_id": 5, "richiesta": "Aggiungi le date",
"parti": [2, 3], "stato": "in_preparazione", "fase": "scrittura", "fatto": 1, "totale": 2, "frase": "Riscrivo la parte 3",
"errore": null, "nota": null, "cambiate": [], "stima_eur": 0.06, "creata_il": "…", "pronta_il": null}`.
`stato`: `in_coda` | `in_preparazione` | `pronta` | `errore` | `annullata`. Si chiede ogni 3-5 s finché è in lavorazione
(oppure si richiede l'oggetto, che la contiene). `cambiate` = le parti cambiate davvero (numeri della versione nuova).
`nota`: quello che Lectio non ha potuto fare, per esempio "I tuoi documenti non dicono le date della quarta crociata:
quelle non le ho aggiunte." Novità (sezione 11): `modifica_pronta` ("Ho modificato lo schema «…»") e `modifica_errore`
("La modifica dello schema «…» si è fermata: tocca Riprova"), con `materia_id` e, per le lezioni, `lezione_id`.

Come lavora il server: riscrive con il modello di scrittura solo le parti scelte, una alla volta, partendo dal
testo di adesso, dalla richiesta e dagli stessi documenti e regole della prima volta; poi il solito controllo sui
documenti (17.1). Se la richiesta chiede cose che i documenti non dicono, non le inventa e lo scrive nella `nota`. Si fa
una modifica alla volta per oggetto; quelle delle lezioni aspettano in coda con le lezioni, le altre con gli schemi.

Il piano della modifica (server 1.4.3): quando le parti in gioco sono più di una (o ci sono file aggiunti), prima una
richiesta breve legge il testo intero di tutte quelle parti insieme alla richiesta e decide per ognuna un'azione:
- `riscrivi` con un `compito` preciso (che cosa cambiare in quella parte) ed eventualmente `unisci`: le parti il cui
  contenuto va messo dentro questa (che poi si tolgono);
- `togli`: la parte sparisce. Il primo e l'ultimo capitolo di una lezione non si tolgono; non si possono togliere tutte
  le parti (allora la modifica va in errore con una frase chiara).
Con `parti` indicate si toccano solo quelle. Chi riscrive una parte riceve il suo compito, la richiesta intera solo come
sfondo e, se c'è da unire, il testo delle parti tolte (con le loro fonti). Dopo una parte tolta le parti si rinumerano da
1 senza buchi: anche `cambiate` e `parti` delle modifiche di prima seguono i numeri nuovi, e così le schede del Ripasso
lampo dello schema. La `nota` comincia con quello che Lectio ha fatto davvero ("Ho tolto la parte «…» e ho messo il suo
contenuto in «…».") e poi dice quello che non ha potuto fare. "Torna a prima" rimette anche le parti tolte.
- **Lezione:** le parti sono i capitoli. Durante la modifica la lezione resta `pronta` e si ascolta quella di prima. La
  voce si rifà solo per i capitoli cambiati (con la voce della lezione: con Giovanni è gratis); gli altri capitoli
  riusano l'audio di prima. Alla fine cambiano `audio.sha256`, `durata_s`, `capitoli`, `domande`, `testo`: le app
  riscaricano l'audio. Le domande rimaste uguali tengono il loro `id` e le risposte date; quelle cambiate o tolte spariscono
  dalla lezione, ma le risposte date restano salvate (e le schede del Ripasso lampo restano). `ascolto.posizione_s` va
  allo stesso punto se quel capitolo non è cambiato, altrimenti all'inizio del capitolo cambiato.
- **Schema:** le parti sono le sezioni (anche il quadro d'insieme). Le foto restano dove sono; le schede del Ripasso
  lampo delle parti cambiate si rifanno. I riassunti fatti prima diventano "vecchi" (28.3).
- **Riassunto:** le parti sono le parti del riassunto. Si riscrive partendo dallo schema, come la prima volta; la
  risposta può anche togliere parti (`"togli": [3]`), con la stessa nota e la stessa rinumerazione.
- **Verifica simulata:** le parti sono le domande; la richiesta può anche aggiungerne o toglierne. Se era `corretta`
  diventa una prova nuova (`pronta`, senza risposte né `esito`): quella di prima, con il suo voto, resta come versione
  da parte (28.2). Se era `in_corso` le risposte alle domande rimaste uguali restano.

### 28.2 Versione di prima

Prima di ogni cambiamento (modifica con l'AI, correzione a mano) il server mette da parte la versione di adesso.
`POST /api/lezioni/{id}/scambia-versione`, `POST /api/schemi/{id}/scambia-versione`,
`POST /api/riassunti/{id}/scambia-versione`, `POST /api/simulate/{id}/scambia-versione`,
`POST /api/mappe/{id}/scambia-versione` → l'oggetto. Rimette la versione
messa da parte e mette da parte quella di adesso: toccando di nuovo si torna alla versione modificata. 409 se non c'è una
versione da parte o se c'è una modifica in coda o in preparazione. Si tengono le ultime 3 versioni (le lezioni 1, per lo
spazio dell'audio).

### 28.3 Campi in più

`Lezione` (completa), `Schema` (con `contenuto`), `Riassunto` e `Simulata` (completa) hanno in più:
- `"modifica"`: l'ultima `Modifica` di quell'oggetto (in qualunque stato), o `null`;
- `"versione_da_parte"`: `{"ruolo": "prima" | "dopo", "perche": "modifica" | "a_mano", "richiesta": "Aggiungi le date",
  "quando": "…"}` o `null`. `ruolo: "prima"` = è la versione di prima di un cambiamento (pulsante "Torna a prima");
  `"dopo"` = è una versione cambiata messa da parte con lo scambio (pulsante "Rimetti la modifica");
- `"parti_modificabili"`: `[{"n": 1, "titolo": "Le crociate"}]`. Schema: le sezioni (`n` di `contenuto.sezioni`).
  Riassunto: le parti, contate da 1 (titolo `null` → "Parte 3"). Simulata: le domande (`titolo` = inizio del testo).
  Lezione: i capitoli. Vuota quando l'oggetto non si può modificare.
`Riassunto` ha anche `"schema_cambiato": true` se lo schema è stato cambiato dopo che il riassunto è stato scritto (le app
lo dicono e offrono Rifai).

### 28.4 Correzione a mano (schemi e riassunti, gratis)

- `PUT /api/schemi/{id}/testo` `{"titolo": "…", "contenuto": {…}}` → `Schema`. Il `contenuto` ha la stessa forma di
  quello ricevuto (sezione 18): contano solo i testi (`sezioni[].titolo`, `campi[].valore`, e nei blocchi `titolo`,
  `testo`, `termine`, `voci`, `intestazione`, `righe`, `didascalia`); tipi, ordine, foto e fonti restano quelli del
  server. Le voci vuote si tolgono; un blocco rimasto senza testo sparisce. `**grassetto**` resta com'è.
- `PUT /api/riassunti/{id}/testo` `{"titolo": "…", "contenuto": {"parti": [{"titolo": "…", "paragrafi": ["…"],
  "punti": ["…"]}]}}` → `Riassunto`. Stesso numero di parti; paragrafi e punti vuoti si tolgono.
- Testi al massimo 20.000 caratteri ciascuno, titolo al massimo 120. 409 se non è pronto o c'è una modifica in coda o in
  preparazione; 422 se la forma non torna (`campo: "contenuto"`).
Prima di salvare il server mette da parte la versione di adesso (`perche: "a_mano"`), quindi "Torna a prima" funziona
anche qui.

### 28.5 File aggiunti a una modifica (v1.9, richiesta di Andrea del 21/11)

Con una modifica si possono dare a Lectio dei file nuovi (una foto del libro, una scheda del prof). Le app li caricano
prima come materiale normale della materia (sezione 6: vanno nella loro cartella con la solita regola, 27) e poi
mandano i loro id nel corpo di stima e modifica:
`{"tipo": "schema", "id": 12, "richiesta": "Aggiungi quello che c'è nei file", "parti": [3], "materiali": [41, 42]}`.
- `materiali`: al massimo 20 id, materiale della stessa materia dell'oggetto e non in `errore` (altrimenti 422 con
  `campo: "materiali"`). Possono essere ancora `in_lettura`: la modifica resta `in_coda` (con `frase` "Aspetto di finire
  di leggere i file") finché i file non sono letti; un file che intanto finisce in errore si lascia fuori e la `nota` lo
  dice.
- I file diventano documenti in più per la riscrittura: per lezioni, schemi e verifiche entrano tra i documenti di ogni
  parte riscritta (e restano tra i documenti dell'oggetto, per le modifiche dopo); per riassunti e mappe il loro testo va
  nella richiesta. Il controllo sui documenti (17.1) li conta come gli altri. La stima tiene conto del loro testo.
- Il legame resta: il file rimane nella materia anche dopo, legato alla modifica. Se si annulla la modifica il legame
  non conta più (il file resta nella materia); se si cancella il file, il legame sparisce e l'oggetto resta com'è.

Campi in più per mostrare il legame nei due versi:
- `Materiale` (sezione 6) ha `"usato_per"`: `[{"tipo": "schema", "id": 12, "titolo": "Gli inizi dell'antropologia",
  "modifica_id": 9, "stato": "pronta", "parti": [{"n": 3, "titolo": "Evoluzionismo"}]}]` (vuota se nessuna modifica).
- `Lezione`, `Schema`, `Riassunto`, `Simulata` e `Mappa` (28.3) hanno `"file_delle_modifiche"`: `[{"materiale_id": 41,
  "titolo": "Foto pag. 52", "modifica_id": 9, "stato": "pronta", "parti": [{"n": 3, "titolo": "Evoluzionismo"}]}]`.
In tutti e due `parti` sono le parti cambiate (`cambiate`) quando la modifica è `pronta`, prima quelle chieste (vuota con
"Tutto"); le modifiche annullate non ci sono.

## 29. Mappe (v1.8, richiesta di Andrea del 17/11)

Da uno schema `pronto` il server prepara una **mappa**: `tipo` `date` (la linea del tempo, solo le date e a cosa si
riferiscono) oppure `concetti` (i concetti fondamentali divisi per parti). Una mappa per tipo e per schema. La fonte è
solo lo schema (già controllato sui documenti); il disegno (riquadri, raggiera, linea del tempo) lo fanno le app. Il
programma PC le mostra dalla 1.3.7 (a riquadri dalla 1.3.8); il telefono per ora no. Aspetto: `docs/aspetto.md` §45.
**Server 1.4.1 (21/11):** ogni concetto ha la `frase`; `forma` `riquadri` | `raggiera`; niente più `collegamenti`
nuovi; il `verbo` c'è solo tra due concetti (mai sotto l'argomento o sotto una parte).

### 29.1 Indirizzi

- `POST /api/schemi/{id}/mappe` `{"tipo": "date" | "concetti"}` → 201 `Mappa` in `in_coda`. Se quella mappa c'è già
  (non in errore) torna quella, senza costi. Se era in errore riparte. 409 se lo schema non è `pronto`; 409 con il
  messaggio "In questo schema ci sono troppo poche date per una linea del tempo: prova la mappa dei concetti." se si
  chiede `date` e `mappe.date_possibili` è `false`; 402 se il tetto non basta (come le lezioni).
- `GET /api/schemi/{id}/mappe` → `[Mappa]` di quello schema. `GET /api/mappe/{id}` → `Mappa`.
- `POST /api/mappe/{id}/riprova` (solo `errore`; 409 altrimenti) → `Mappa`. `DELETE /api/mappe/{id}` → `{"ok": true}`.
- Modifica con l'AI: `POST /api/modifiche` con `"tipo": "mappa"` (28.1). La mappa è una parte sola (`parti_modificabili`
  ha un elemento): si riscrive intera, dallo schema e dalla mappa di adesso, poi gli stessi controlli della prima volta.
- `POST /api/mappe/{id}/scambia-versione` (28.2) e `PUT /api/mappe/{id}/testo` (29.5).

### 29.2 La Mappa

```json
{"id": 7, "schema_id": 3, "materia_id": 1, "tipo": "concetti", "titolo": "Mappa: L'età giulio-claudia",
 "stato": "pronta", "frase": null, "errore": null, "nota": null, "stima_eur": 0.01,
 "creato_il": "…", "pronto_il": "…", "elementi": 16, "contenuto": {…},
 "schema_cambiato": false, "firma": "…",
 "modifica": null, "versione_da_parte": null, "parti_modificabili": [{"n": 1, "titolo": "…", "inizio": "…"}]}
```

`stato`: `in_coda` | `in_preparazione` | `pronta` | `errore`. Si chiede ogni 3 s finché è in lavorazione; `frase` dice
cosa sta facendo ("Cerco le date", "Disegno la mappa"). `elementi` = quanti concetti o quante date. `nota`: un avviso da
mostrare sopra la mappa, o `null` (oggi solo in modalità prova: "Mappa di prova: …"; quello che una Modifica non ha
potuto fare sta nella `nota` della `Modifica`, 28.1). `schema_cambiato`: lo schema è stato
cambiato dopo la mappa (le app offrono Rifalla). `firma`: la riga che il PC stampa in bianco in fondo al PDF (25).
`modifica`, `versione_da_parte`, `parti_modificabili`: come 28.3. `contenuto` è `null` finché non è pronta.

### 29.3 Il contenuto

**Concetti** (al massimo 25 concetti):

```json
{"domanda": "Come passa il potere da un imperatore all'altro?", "forma": "riquadri",
 "concetti": [
   {"id": 1, "testo": "Dinastia giulio-claudia", "genitore": null, "verbo": null, "frase": null, "spiegazione": "…", "sezione": 1},
   {"id": 2, "testo": "Augusto", "genitore": 1, "verbo": null, "frase": "Nel 27 a.C. il senato dà a Ottaviano il titolo di Augusto.",
    "spiegazione": "…", "sezione": 2},
   {"id": 7, "testo": "Principato", "genitore": 2, "verbo": null, "frase": "…", "spiegazione": "…", "sezione": 2},
   {"id": 14, "testo": "Istituzioni della Repubblica", "genitore": 7, "verbo": "tiene in piedi le", "frase": "…",
    "spiegazione": "…", "sezione": 2}],
 "collegamenti": []}
```

Un albero solo: il concetto con `genitore: null` è l'**argomento**; i suoi figli sono le **parti** (di solito 3-7); sotto
le parti i concetti (1-4 per parte, al massimo un livello in più). Gli `id` vanno da 1 in ordine dall'alto (in
larghezza). `testo`: il nome, 1-5 parole. `frase`: una frase di 8-20 parole che spiega il concetto con nomi e date
esatti, senza ripetere il nome (`null` per l'argomento; qualcuna può mancare). `verbo`: la frase sulla freccia dal
genitore, quasi sempre `null`; c'è solo tra due concetti (il genitore non è l'argomento né una parte), per una causa, una
conseguenza o un passaggio vero, e si legge "genitore + verbo + concetto" ("Istituzioni della Repubblica" sotto
"Principato": "Principato *tiene in piedi le* Istituzioni della Repubblica"). Sotto l'argomento e sotto le parti il
server lo mette a `null`, e le app non lo disegnano nemmeno nelle mappe di prima. `collegamenti`: dalla 1.4.1 sempre `[]`; le mappe fatte prima possono averne (frecce tra rami), le app non
li disegnano più. `forma`: `riquadri` | `raggiera` (quella consigliata dal modello; l'utente può cambiarla; le mappe di
prima possono dire `albero`: si leggono come `riquadri`). `sezione`: il numero della sezione dello schema da cui viene
(per "Apri nello schema"), o `null`. `domanda`: la domanda focale, o `null`.

**Date** (da 3 a 40 date, in ordine di tempo):

```json
{"eventi": [
  {"id": 1, "data": "27 a.C.", "anno": 27, "ac": true, "fine": null, "fine_ac": null, "x": -26, "x_fine": null,
   "circa": false, "mese": null, "cosa": "Ottaviano diventa Augusto", "spiegazione": "…", "sezione": 2},
  {"id": 5, "data": "41-54", "anno": 41, "ac": false, "fine": 54, "fine_ac": false, "x": 41, "x_fine": 54, "…": "…"}]}
```

`data` è come la scrive lo schema ("XV secolo", "circa 1450", "12 ottobre 1492"). `anno`/`ac` e `fine`/`fine_ac` sono gli
anni controllati (un periodo ha `fine`); un secolo diventa il periodo dei suoi cento anni. `x` e `x_fine` sono gli anni
**astronomici** (1 a.C. = 0, 27 a.C. = -26, l'anno zero non esiste), più la frazione del mese se c'è: sono loro che
dicono dove va la data sulla linea. `circa`: la data è incerta (pallino tratteggiato). Ogni data è stata cercata nello
schema: quelle che lì non ci sono si tolgono.

Lo schema completo (`GET /api/schemi/{id}`) ha in più `"mappe": {"stima_eur": 0.01, "date_possibili": true,
"fatte": [{"id": 7, "tipo": "concetti", "stato": "pronta"}]}` (`null` se lo schema non è pronto). `date_possibili`:
nello schema ci sono almeno 3 date (se no la linea del tempo non si propone).

### 29.4 Come lavora il server

Una chiamata al modello di scrittura con lo schema numerato (al massimo 90.000 caratteri) chiede i pezzi; poi, senza AI,
si controllano le date nel testo dello schema (numeri, secoli in romani o in lettere, a.C.): una data che non c'è si fa
correggere una volta, poi si toglie. Poi il solito controllo dei fatti (17.1) su ogni concetto dentro il suo genitore
("Crisi del Trecento: Peste nera."), su ogni verbo a parte ("Peste nera *provoca* Calo della popolazione."), su ogni
frase e su ogni spiegazione, con una riscrittura possibile; quello che resta fuori dallo schema si toglie (un verbo
sbagliato toglie solo la freccia, una frase sbagliata solo la frase). Se mancano le frasi a più di un quarto dei
concetti, o una frase supera le 32 parole, si fa riscrivere. Se restano meno di 4 concetti o meno di 3 date la mappa va in `errore`. Costa circa 1 centesimo
(`stima_eur`), aspetta in coda con schemi e riassunti. Novità (sezione 11): `mappa_pronta` ("La mappa dei concetti di
Storia è pronta", "La linea del tempo di Storia è pronta") e `mappa_errore` ("La mappa di Storia si è fermata: tocca
Riprova"), con `materia_id`.

### 29.5 Correzione a mano (gratis)

`PUT /api/mappe/{id}/testo` `{"titolo": "…", "contenuto": {…}}` → `Mappa`. Il `contenuto` ha la forma ricevuta: contano
solo i testi, chi sta sotto a chi e l'ordine restano quelli del server. Stessi elementi nello stesso ordine (se no 422).
- Concetti: `concetti[].testo` (al massimo 80; vuoto = resta quello di prima), `concetti[].frase` (300; vuota = niente
  frase), `concetti[].verbo` (40; sotto l'argomento e sotto le parti si ignora), `concetti[].spiegazione` (600), `domanda` (160). Le mappe di
  prima della 1.4.1: anche `collegamenti[].verbo` (40) e `.spiegazione` (600); se mancano restano come sono.
- Date: `eventi[].data` (40), `eventi[].cosa` (120; vuoto = la data si toglie), `eventi[].spiegazione` (600). Una data
  riscritta con un anno diverso ("41-54", "XV secolo", "12 ottobre 1492") si sposta sulla linea: il server ricalcola
  `anno`, `fine`, `mese`, `x`, `x_fine` e riordina.
- Titolo al massimo 120. 409 se non è `pronta` o c'è una modifica in coda o in preparazione; 422 se la forma non torna
  (`campo: "contenuto"`). Prima di salvare il server mette da parte la versione di adesso (28.2).

### 29.6 Il PDF

Il PDF della mappa lo fa il programma PC (WebView2, stampa in PDF), non il server: A4 orizzontale, sempre in chiaro,
con la `firma` in fondo. Non costa niente.
