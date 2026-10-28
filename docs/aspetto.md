# Lectio — aspetto e uso

Guida comune all'app Android e al programma PC. Il prototipo sta in `docs/prototipo/` (tela Design:
`Main` = Oggi, `Materia`, `NuovaLezione`, `Lettore`, `Gemini`, `Pc`): aprire quei file e riprodurne misure,
colori, pesi e spaziature. Quello che il prototipo non mostra si fa nello stesso stile, seguendo questa guida.

## Per chi e per cosa

Andrea, studente di liceo. Prepara la lezione a casa (sul PC trascrive le verifiche con Gemini e trascina il file,
sul telefono lo condivide da Gemini) e la **ascolta in giro con gli auricolari, spesso a schermo spento**.
Risultato che vuole: arrivare alla verifica vera avendo ripassato quello che chiede *il suo* prof e avendo già
risposto a voce alle sue domande. L'app la usa solo lui.

## 0. Principi (dalla ricerca, non gusti)

1. **Parti dalla prossima verifica, non dalle materie.** La pagina Oggi risponde a "cosa devo fare adesso?":
   la prossima verifica, la sua lezione (pronta o da preparare), cosa riprendere, cosa raccontare.
   (Laws of UX; app di conto alla rovescia per esami: progresso a colpo d'occhio e avvio immediato.)
2. **Domande ogni 5 minuti, non solo alla fine.** Lezione a blocchi di 4-6 minuti, ognuno chiuso da una
   domanda: nello studio di Szpunar (PNAS 2013) le domande dopo ogni pezzo di 5,5 minuti dimezzano la mente
   che vaga (19% contro 41%) e alzano il risultato finale (90% contro 68%).
3. **Domanda, pausa, risposta.** L'app si ferma davvero alla domanda; rispondi a voce, poi un tocco (o il tasto
   play degli auricolari) fa sentire la risposta. È il ritmo Pimsleur: chiedi, aspetta, conferma.
4. **Si ascolta senza guardare.** Comandi grandi in basso, controlli da schermata di blocco e da auricolari,
   avvisi chiari in notifica. (Ricerca sulle interfacce "eyes-free"; legge di Fitts.)
5. **Capitoli come l'indice di un libro.** Ogni blocco è un capitolo: fatto (spunta, tenue), in corso
   (accento), da fare. Un tocco salta lì; dopo un salto compare per 5 secondi "Torna dov'eri" (Overcast).
6. **Il costo si vede prima, il tetto non è un muro.** Il pulsante dice quanto costa ("Prepara · 0,25 €"), la
   barra dice quanto resta nel mese e quando si azzera; all'80% un avviso calmo; al tetto la strada è la voce
   gratis, non un blocco muto. (Pattern di uso e limiti nelle app a consumo.)
7. **L'attesa lavora in sottofondo.** Preparare una lezione richiede minuti: si vedono le fasi con un numero
   ("Registro la voce · 3 di 7"), si può uscire, una notifica dice "pronta". Niente rotelle infinite.
8. **Il testo per Gemini a un tocco, da ovunque.** Ha una voce sua nella barra in basso e un collegamento nel
   foglio "Aggiungi". Il pulsante dice cosa copia ("Copia il testo per Gemini") e diventa "Copiato" con la
   spunta per 2 secondi; se la copia fallisce lo dice.
9. **L'AI propone, Andrea corregge.** La scheda del prof la scrive l'AI dalle verifiche, con le prove ("in 3
   verifiche su 4"); Andrea può modificarla e le sue note non vengono mai toccate.
10. **Colore 60-30-10.** Fondo neutro, schede bianche, accento inchiostro solo sulle azioni principali e sul
    capitolo in corso. Ogni materia ha un suo colore, ma solo in pallini, piastrelle e testate. Rosso solo
    per gli errori.

## 1. Fondamenta

### Carattere
**Figtree** (licenza OFL, già sul PC in `C:\Users\andre\sol-studio\font-figtree`: incluso nell'app e nel
pacchetto PC, niente richieste a Google durante l'uso). Pesi 400, 500, 600, 700, 800.

| Ruolo | Dimensione / peso | Esempio |
|---|---|---|
| Titolo pagina | 30 / 800, spaziatura -0,6 | "Oggi", "Storia" |
| Titolo grande di una scheda | 24 / 800, spaziatura -0,4 | "Verifica sull'Impero romano" |
| Domanda nel lettore | 22 / 800, interlinea 1,3 | "Perché Augusto non si fece mai chiamare re?" |
| Titolo sezione | 19 / 800 | "Cosa chiede il prof" |
| Testo forte | 16-17 / 700 | titolo di una lezione in elenco |
| Testo | 16 / 400-500, interlinea 1,5 | testo della lezione, scheda del prof |
| Secondario | 14 / 500, colore tenue | "giovedì 30 ottobre · tra 2 giorni" |
| Etichetta | 12 / 700, maiuscolo, spaziatura 0,08em | "LA PROSSIMA", "DOMANDA 2 DI 6" |
| Numeri del lettore | 13 / 600, cifre tabellari | "07:42", "-12:18" |

### Colori (chiaro / scuro)
| Ruolo | Chiaro | Scuro |
|---|---|---|
| Fondo | `#F5F4F0` | `#111114` |
| Superficie (schede, fogli) | `#FFFFFF` | `#1B1B20` |
| Superficie 2 (binari, pulsanti tondi, campi) | `#ECEAE4` | `#26262C` |
| Testo | `#1A1A1F` | `#F1F1F4` |
| Testo tenue | `#5E5E68` | `#A3A3AE` |
| Testo molto tenue (solo ≥ 14 px) | `#74747D` | `#8C8C96` |
| Riga sottile | `#ECEBE6` | `#2B2B31` |
| Accento (pulsante pieno, capitolo in corso, barra) | `#3D4ED8`, testo bianco | `#6F7CF2`, testo `#0E0F2A` |
| Accento tenue (pulsanti secondari, voce attiva) | `#ECEEFE`, testo `#2F3CB0` | `#23264A`, testo `#B4BBFF` |
| Fatto (spunta) | `#2B8A3E` | `#51CF66` |
| Avviso (80% del tetto) | tenue `#FFF4E0`, testo `#8A5506` | tenue `#3A2C14`, testo `#FFC56B` |
| Errore (solo errori) | pieno `#C92A2A`, tenue `#FDEEEC`, testo `#9B1C1C` | pieno `#E03131`, tenue `#3A1C1C`, testo `#FFA8A8` |

**Colori delle materie** (il server manda il nome in `Materia.colore`; pieno / tenue / testo scuro).
Nel tema scuro il tenue è il pieno al 22% sulla superficie scura e il testo è una tinta chiara.

| Nome | Pieno | Tenue | Testo | Esempio |
|---|---|---|---|---|
| `ambra` | `#C27C0E` | `#FBF1DE` | `#8A5506` | Storia |
| `rosa` | `#D6336C` | `#FCE8EF` | `#A61E4D` | Italiano |
| `petrolio` | `#0C8599` | `#E3F6F8` | `#0B6474` | Diritto ed economia |
| `viola` | `#7048E8` | `#F1ECFE` | `#5532C9` | Scienze umane |
| `blu` | `#1C7ED6` | `#E7F1FC` | `#1864AB` | Matematica |
| `verde` | `#2B8A3E` | `#EAF6EC` | `#1F6A30` | Inglese |
| `arancio` | `#E8590C` | `#FDEDE3` | `#B4440A` | Spagnolo |
| `oliva` | `#5C940D` | `#EFF6E3` | `#466F0B` | Scienze naturali |

### Forme, ombre, movimento
- Griglia 4/8 px. Margini laterali 20 px sul telefono, 32 sul PC.
- Raggi: schede 22, piastrella della lezione 20, pulsanti pieni 18, campi 14, pillole 999.
- Ombre (solo chiaro): scheda `0 1px 2px rgba(20,20,30,.04)`; scheda in rilievo
  `0 1px 2px rgba(20,20,30,.04), 0 10px 28px rgba(20,20,30,.07)`; pulsante principale `0 8px 20px` del
  suo colore al 28%. Nel tema scuro niente ombre: superfici più chiare.
- Pulsante principale alto 58 dp, a tutta larghezza in basso; tocchi ≥ 48 dp; play del lettore 76 dp.
- Movimento: 200-350 ms, curva morbida, niente rimbalzi né coriandoli; tutto tolto se il sistema chiede meno
  animazioni (Android: animazioni disattivate; PC: `prefers-reduced-motion`). Dettagli nella sezione 10.
- Icone a tratto 2 px, mai emoji. Il simbolo di Lectio: quadrato arrotondato accento con tre barrette
  verticali bianche di altezza diversa (pagine che diventano suono), come nel prototipo.

## 2. Navigazione

**Telefono:** barra in basso con **4 voci**: **Oggi**, **Materie**, **Lezioni**, **Per Gemini**. Voce attiva:
pillola accento tenue dietro l'icona, testo accento. **Mini-lettore** sopra la barra quando c'è una lezione
caricata (piastrella colore materia, titolo, tempo che manca, play/pausa; un tocco apre il lettore).
Impostazioni dal cerchio con l'ingranaggio in alto a destra nella pagina Oggi.

**PC:** menu laterale a sinistra (240 px): simbolo + "Lectio", poi Oggi, Materie, Lezioni, Per Gemini; in fondo
Impostazioni. Contenuto al centro (max 1040 px). Barra del lettore fissa in basso nel contenuto quando c'è una
lezione caricata. In ogni pagina di una materia e in Per Gemini si possono **trascinare i file** (riquadro
tratteggiato che si accende al passaggio).

## 3. Oggi (pagina iniziale)

Come `docs/prototipo/Main.dc.html`, dai dati di `GET /api/oggi`:
- Riga in alto: simbolo + "Lectio"; a destra cerchio con ingranaggio (Impostazioni).
- Titolo "Oggi" (30/800) e sotto, secondario, la data ("martedì 28 ottobre").
- **LA PROSSIMA**: scheda in rilievo con pallino e nome della materia, titolo della verifica (24/800),
  "giovedì 30 ottobre · tra 2 giorni", e sotto lo stato della sua lezione:
  - pronta → riga "Lezione pronta · 20 min · 6 domande" + pulsante pieno **Ascolta**;
  - in preparazione → fase e barra ("Registro la voce · 3 di 7");
  - nessuna → pulsante pieno **Prepara la lezione**.
- **Riprendi**: la lezione lasciata a metà (piastrella, titolo, barra, "mancano 8 min", play tondo).
- **Dopo la verifica**: per ogni verifica passata senza esito, riga "Com'è andata la verifica di Inglese?" +
  pulsante secondario **Racconta**.
- **Più avanti**: le altre verifiche/interrogazioni (pallino, materia, cosa, quando), al massimo 4, poi
  "Vedi tutte".
- In fondo, piccolo: **Questo mese 0,80 € di 2 €** con barra sottile; sotto "si azzera il 1° novembre".
  All'80% la riga diventa avviso tenue.
- Vuota (primo uso): "Aggiungi la prima materia" con pulsante pieno, e "Prima volta? Guarda come funziona"
  (avvia la visita guidata).

## 4. Materie e pagina della materia

**Materie** (prototipo `Materie.dc.html`): titolo "Materie" e pulsante pieno **Nuova materia** in basso
(zona del pollice). Elenco di schede: piastrella del colore con l'iniziale, nome (17/700), "Prof. Rossi ·
4 verifiche · centratura 63%", e a destra, se ci sono, "3 da ripassare" (pillola avviso). Vuota: "Nessuna
materia ancora" + **Nuova materia**.

**Nuova materia** (foglio dal basso, stesso foglio per **Modifica materia** dal menu della testata):
campo **Nome** (es. "Storia", obbligatorio, tastiera con maiuscola), campo **Prof** (cognome, facoltativo, es.
"Rossi"), **Colore**: 8 cerchi da 44 dp, il primo non usato già scelto, quello scelto con anello e spunta;
anteprima dal vivo della scheda della materia sopra i campi (piastrella, nome, prof) che cambia mentre scrivi.
Pulsante pieno **Crea materia** (spento finché il nome è vuoto). Errore del server sotto il campo nome
("Esiste già una materia con questo nome"). In Modifica: in fondo pulsante testo **Elimina materia** →
conferma che dice cosa sparisce ("verifiche, appunti, lezioni e audio di Storia").

**Materia** (prototipo `Materia.dc.html`):
- Testata con fondo tenue della materia: nome (30/800, testo scuro della materia), "Prof. Rossi", tre numeri
  piccoli: verifiche lette, lezioni, centratura.
- **Cosa chiede il prof** (la scheda): argomenti che tornano come pillole con il conteggio ("Cause e
  conseguenze · 4"); tipo di domande come barra divisa con legenda (aperte 60%, definizioni 25%, date 15%);
  "Cosa corregge" e "Cosa premia" come elenchi brevi; "Basata su 4 verifiche e 1 interrogazione" in
  secondario; **Le tue note** (testo di Andrea, se c'è). Pulsante testo **Modifica** → pagina con i campi
  della scheda e le note.
- **Prossime**: verifiche e interrogazioni in arrivo + **Aggiungi data** (foglio: scritta/orale, argomento,
  data).
- **Materiale**: righe con icona del tipo (verifica, interrogazione, appunti, libro), titolo, data; tocco = vedi
  il testo letto (per controllare cosa ha capito l'AI), con **Elimina** nel menu. Pulsante secondario
  **Aggiungi** → foglio "Aggiungi" (sezione 5).
- **Da ripassare**: le domande a cui hai risposto male, a metà o che hai saltato (testo della domanda, esito
  con parola e colore, lezione). Tocco → apre il lettore fermo su quella domanda per rispondere di nuovo.
- **Lezioni** della materia.
- Pulsante pieno fisso in basso: **Nuova lezione**.

**Il materiale si carica SOLO qui**, nella materia (o con "Condividi" scegliendo la materia): ogni lezione di
quella materia lo usa da sola. Nella creazione di una lezione non si carica niente.

## 5. Aggiungi materiale (foglio dal basso)

Righe grandi con icona: **File** (scegli PDF, Word, testo), **Appunti** (scrivi o incolla), **Interrogazione
orale** (cosa ti ha chiesto, cosa hai risposto, cosa ha detto), **Foto** (con sotto, tenue, "circa 0,4 cent a
foto"). In alto una riga accento tenue: **Testo per Gemini** → apre Per Gemini. Dopo la scelta: tipo
(verifica corretta / interrogazione / appunti / libro), titolo, data → **Aggiungi**. Mentre il server legge il
file: riga con segnaposto che pulsa; poi il titolo; se c'è un errore, la frase del server e **Riprova**.

**Condividi da altre app (telefono):** Lectio compare in "Condividi" per testo, PDF, Word e immagini; si apre
direttamente il foglio con "In quale materia?" e il tipo già su "Verifica corretta".

## 6. Nuova lezione

Prototipo `NuovaLezione.dc.html`. Pagina (telefono) o finestra larga (PC):
- Titolo "Nuova lezione" e pallino + materia.
- **Argomento** (campo, 16 px): "L'Impero romano da Augusto a Nerone".
- **Per** (pillola): la prossima verifica della materia, già scelta se c'è; "Nessuna" possibile.
- **Userò**: una riga sola "4 verifiche e 3 appunti di Storia" con pulsante testo **Cambia** (apre le
  pillole spuntabili degli appunti; le verifiche si usano sempre). Niente caricamenti qui.
- **Durata**: binario a segmenti **15 · 20 · 30 · 45 min** (minimo 15, predefinita dalle impostazioni).
- **Domande**: binario a due segmenti **Obbligatorie · Si possono saltare** (predefinito dalle impostazioni,
  di base Obbligatorie). Si può cambiare anche dopo, dal menu del lettore.
- **Voce**: due schede affiancate: **Lorenzo** "voce espressiva" con il costo di questa durata, e **Voce
  gratis** "Isabella" con "0 €" e i caratteri gratis rimasti nel mese. Se la voce espressiva sforerebbe il
  tetto, la sua scheda è spenta con la frase "Sforerebbe il tetto di 2 €" e si sceglie la gratis.
- Riga **Questo mese 0,80 € → dopo questa 1,05 € di 2 €** con barra a due toni (speso / questa lezione).
- Pulsante pieno in basso: **Prepara la lezione · 0,25 €** (con voce gratis: "· 0,02 €", il solo testo).
- Dopo il tocco: la stessa pagina diventa **In preparazione**: quattro fasi con spunta (Leggo il materiale,
  Scrivo la lezione, Registro la voce · n di m, Monto l'audio), barra, "Puoi uscire: ti avviso io". Fine:
  spunta grande che compare + **Ascolta** (peak-end, niente feste). Errore: frase del server + **Riprova**.
- Modalità prova (server senza chiavi): pillola "Prova" in alto e costi a 0 con la frase "Voce di prova,
  robotica: serve la chiave Mistral per quella vera".

## 7. Lettore

Prototipo `Lettore.dc.html`:
- In alto: freccia giù (chiudi), pillola materia, a destra **Scarica** (icona; scaricata = spunta) e menu.
- Piastrella grande del colore della materia con il titolo (24/800) e "20 min · 4 capitoli · 6 domande".
- **Capitoli** (elenco): fatto = spunta verde e testo tenue; in corso = testo accento e barrette che si
  muovono piano (ferme con meno animazioni); da fare = normale. Ogni riga: titolo e durata.
- Barra di avanzamento trascinabile con tempo passato e tempo che manca; segni sottili dove ci sono domande.
- Comandi: **-15 s**, **play/pausa** (76 dp, accento pieno), **+30 s**; sotto: velocità (pillola "1,0×", tocco
  = 0,8 / 1,0 / 1,2 / 1,4 / 1,6), **Testo** (mostra il testo della lezione sincronizzato al capitolo).
- Dopo un salto (capitolo o barra): pillola "Torna dov'eri" per 5 s.
- **Alla domanda** (se "Fermati alle domande" è acceso): l'audio si ferma e sale la scheda della domanda
  (prototipo `Lettore.dc.html`): etichetta "DOMANDA 2 DI 6", testo 22/800, pulsante testo **Ripeti la
  domanda**, e il **campo della risposta** (più righe, 16 px, fondo superficie 2, raggio 14, segnaposto "Scrivi
  la tua risposta"; sul telefono la tastiera si apre da sola, il suo microfono serve per dettare). Pulsante
  pieno **Invia risposta** (spento col campo vuoto) e il salto, che dipende dalla scelta fatta creando la
  lezione: con domande **saltabili** pulsante testo **Salta**; con domande **obbligatorie** pulsante testo più
  piccolo e tenue **Salta comunque**, che apre sotto una conferma in linea ("Saltarla? Finisce in Da
  ripassare." con **Sì, salta** e **Rispondo**). Dopo l'invio: segnaposto che
  pulsa "Correggo…" (di solito 1-3 s), poi la **correzione**: parola dell'esito con icona e colore (Giusta =
  verde fatto; Quasi = avviso ambra; Da rivedere = tenue ardesia `#EEF0F2` / testo `#343A40`, scuro `#2A2D31` /
  `#C9CED4`; mai rosso), il commento (16 px), "Mancava:" con pillole, poi la risposta giusta che parte in
  audio con il suo testo sotto, e **Avanti**. Saltata: niente correzione, parte subito la risposta giusta e la
  domanda va in "Da ripassare".
- **A schermo spento** (non si può scrivere): con domande **saltabili** il tasto play degli auricolari o della
  schermata di blocco vale come "Salta": parte la risposta e la domanda finisce in "Da ripassare"; la notifica
  dice "Domanda 2 di 6 · rispondi nell'app o premi play per saltarla". Con domande **obbligatorie** il play
  ripete la domanda e l'audio resta fermo; la notifica dice "Domanda 2 di 6 · rispondi nell'app".
- Senza rete: la risposta resta in coda, si vede subito la risposta giusta per confrontare, la correzione
  arriva dopo (e la domanda compare in "Da ripassare" se serve).
- Con "Risposte scritte" spento nelle impostazioni: niente campo, solo **Senti la risposta** (comportamento di
  prima).
- Finita la lezione: riga "Finita" con spunta e, se la verifica c'è, "Dopo la verifica raccontami com'è andata".
- Telefono: controlli in notifica e schermata di blocco (titolo, materia, capitolo), auricolari.
  Si ascolta anche senza rete se la lezione è scaricata; la posizione si salva sul server quando c'è rete
  (così PC e telefono riprendono dallo stesso punto).

## 8. Com'è andata (dopo la verifica)

Una pagina: titolo "Com'è andata la verifica di Storia?"; le domande previste dalla lezione come righe con
interruttore **È uscita**; campo **Altre domande uscite** (una per riga); **Voto** facoltativo; pulsante pieno
**Salva**. Risultato: anello "4 di 6 domande previste" che si riempie, frase "Ho aggiornato la scheda del prof",
pulsante **Carica la verifica corretta** (apre Aggiungi con tipo già scelto). Niente feste.

## 9. Per Gemini

Prototipo `Gemini.dc.html`. Titolo "Per Gemini". Tre passi grandi numerati (sono il contenuto della pagina,
non istruzioni scritte in piccolo):
1. **Copia il testo** → pulsante pieno **Copia il testo per Gemini** (diventa "Copiato" con spunta per 2 s;
   se fallisce: "Non sono riuscito a copiarlo: tieni premuto sul testo qui sotto").
2. **Apri Gemini e manda la foto della verifica** → pulsante secondario **Apri Gemini** (telefono: app Gemini
   se c'è, altrimenti gemini.google.com; PC: gemini.google.com nel browser).
3. **Manda il file a Lectio** → telefono: "Condividi → Lectio" (pulsante secondario **Scegli il file** come
   alternativa); PC: riquadro tratteggiato "Trascina qui il file" + **Scegli il file**.
Sotto, la scheda col testo intero (16/400, selezionabile), preso da `GET /api/gemini` (copia salvata
nell'app per quando non c'è rete).

## 10. Le altre pagine (stesso stile)

- **Lezioni**: elenco per data, filtro a pillole per materia; ogni riga: piastrella colore, titolo, materia,
  durata, stato (pronta, ascoltata per metà con barra, finita con spunta, in preparazione con fase), icona
  scaricata. Menu: Scarica / Togli dal telefono / Elimina (con conferma).
- **Impostazioni**: Collegamento (server, stato, "Scollega"), Voce predefinita, Durata predefinita, Fermati
  alle domande (interruttore), Risposte scritte (interruttore, acceso di base), Domande predefinite (Obbligatorie / Si possono
  saltare), Velocità predefinita, **Tetto del mese** (0,50-10 €, predefinito 2 €; la riga
  dice quanto si è speso), Tema (sistema / chiaro / scuro), **Come funziona Lectio** (visita guidata),
  Aggiornamenti (versione + "Aggiorna" se c'è), Informazioni.
- **Primo avvio / abbinamento**: simbolo, "Collega Lectio al server di casa", campo indirizzo (vuoto,
  segnaposto "http://192.168.x.x:8500"; l'indirizzo da fuori casa lo manda poi il server), campo codice di 8 cifre, pulsante pieno **Collega**. Errori chiari con cosa fare.
- **Senza rete**: pillola in alto "Senza rete: vedi solo le lezioni scaricate"; le azioni che servono il server
  sono spente con la frase del perché.
- **Errori**: sempre la frase del server (italiano semplice) e cosa fare; mai codici.
- **Stati vuoti**: una frase e un pulsante (es. Lezioni vuota: "Nessuna lezione ancora" + **Nuova lezione**).

## 11. Testi: niente istruzioni permanenti

Niente frasi d'aiuto fisse scritte in piccolo ("Puoi anche…", "Oppure…"). Restano solo: etichette brevi,
errori chiari con cosa fare, conferme, avvisi su soldi e dati, e i tre passi di Per Gemini (che sono il
contenuto della pagina). Si impara con la visita guidata. Italiano semplice, frasi corte, niente punti
esclamativi, niente gergo (non "TTS", "token", "API": "voce", "testo", "server di casa").

## 12. Visita guidata (tutorial) dentro l'app vera

Come per Sol 2.1: modalità esempio (stesse schermate, dati finti in memoria, nessuna chiamata al server,
pillola "Esempio"), riflettore con buco arrotondato che scivola (velo nero 55%, al buio 65%, anello accento
che pulsa piano), fumetto vicino al buco con "3 di 8", titolo corto, UNA frase; "Indietro" sempre, "Avanti" solo
nei passi da leggere, "Salta la visita" sempre. Nei passi pratici si va avanti solo facendo l'azione vera
(mano animata sul telefono, tasto disegnato sul PC). Parte da sola al primo avvio dopo l'abbinamento e si
riapre da Impostazioni.

Passi (telefono):
1. **La prossima verifica** (scheda in Oggi): "Qui c'è sempre la prossima verifica e la sua lezione." Avanti.
2. **Apri la materia** (tocca la pillola Storia): pratico.
3. **Cosa chiede il prof** (la scheda): "L'ho scritta io dalle tue verifiche. Puoi correggerla." Avanti.
4. **Aggiungi una verifica** (tocca Aggiungi): pratico; il foglio si apre.
5. **Testo per Gemini** (riga accento nel foglio): "Copialo, dallo a Gemini con la foto, poi manda qui il
   file." Avanti.
6. **Nuova lezione** (tocca il pulsante): pratico.
7. **Durata e costo** (binario + pulsante): "Scegli quanto dura. Il costo lo vedi prima." Avanti.
8. **La domanda** (lettore d'esempio fermo alla domanda): "Scrivi la tua risposta e premi Invia: te la
   correggo." Pratico: si va avanti dopo aver toccato Invia (anche con una parola sola; correzione finta).
9. Fine: il velo si scioglie, "Ecco fatto", pulsante **Inizia**.
Sul PC gli stessi passi, con clic al posto dei tocchi e il riquadro "Trascina qui" al passo 5.

## 13. Animazioni

Transizioni tra pagine (scorrimento + dissolvenza, indietro al contrario); elementi nuovi che entrano
dall'alto; fogli che salgono dal basso col fondo che si scurisce (si chiudono trascinando giù); la scheda della
domanda che sale; numeri che contano (costo, centratura); barre che si riempiono; il riflettore che scivola;
la spunta di fine preparazione che compare. 150-350 ms, solo trasformazioni e opacità, niente cicli continui
tranne le barrette del capitolo in corso (lente, ferme con meno animazioni).

## 14. Cosa garantisce ogni pezzo

- I soldi: nessuna lezione parte se sforerebbe il tetto; il costo si vede prima; la voce gratis è la strada.
- I dati: verifiche e appunti restano sul NAS; nel repo pubblico solo dati finti (Prof. Rossi, Prof. Bianchi).
- Ogni esito si vede: niente errori muti, niente attese senza fase.
