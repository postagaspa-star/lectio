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
| `giada` | `#00A089` | `#DFF6F0` | `#026C5C` | Fisica |
| `senape` | `#928100` | `#F4F1DD` | `#695D02` | Filosofia |
| `orchidea` | `#C04EC8` | `#FAEBFA` | `#8A2C91` | Storia dell'arte |
| `prugna` | `#A34A80` | `#FEEAF4` | `#883E6B` | Latino |
| `cacao` | `#916748` | `#F9EEE6` | `#76543A` | Geografia |
| `lavagna` | `#636F8E` | `#ECF0FA` | `#525C75` | Educazione civica |
| `salvia` | `#7B9575` | `#EBF3EA` | `#4E634A` | Scienze motorie |
| `lavanda` | `#A380C8` | `#F4ECFE` | `#694D86` | Religione |

Valori completi delle 8 nuove nel tema scuro, contrasti e scelta del colore: sezione 37.

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
foto"). In alto una riga accento tenue: **Testo per Gemini** → apre Per Gemini. Dopo la scelta di un file o di
una foto: **Che cos'è** (le due famiglie della sezione 5.1, partenza su **Appunti**), titolo facoltativo
(segnaposto "Lo scrive Lectio leggendo il file"), data → **Aggiungi**. Mentre il server legge il file: riga con
segnaposto che pulsa; poi il titolo; se c'è un errore, la frase del server e **Riprova**.

**Condividi da altre app (telefono):** Lectio compare in "Condividi" per testo, PDF, Word e immagini; si apre
direttamente il foglio con "In quale materia?" e "Che cos'è" su **Appunti**.

### 5.1 Un materiale: titolo e che cos'è (v1.4)

Prototipo `MaterialePc.dc.html` (finestra di 640 px sul PC, pagina sul telefono). Si apre toccando una riga del
materiale nella materia.
- In alto la pillola della materia e la X tonda (44 px, `#ECEAE4`).
- **TITOLO** (etichetta 12/700 maiuscola, spaziata 0,08 em): campo 52 px, testo 18/700, bordo 2 px `#ECEAE4`,
  raggio 14. Sotto, 13/600 tenue: "Dalla foto <nome file> · titolo scritto da Lectio leggendo la foto" se
  `titolo_da` è `lectio`; "Dal file <nome file>" se è `file`; niente se è `utente`.
- **CHE COS'È**: due schede affiancate (sul telefono una sotto l'altra), raggio 18, padding 14/16:
  **Da studiare** "Lectio lo spiega e ci fa le domande" con le pillole **Appunti** · **Libro**; **Già corretta
  dal prof** "Lectio impara come chiede e corregge" con **Verifica** · **Interrogazione**. La famiglia scelta ha
  bordo 2 px accento e fondo bianco, l'altra bordo `#ECEAE4` e fondo `#FAFAF8`. Pillola scelta: bordo 2 px
  accento, fondo accento tenue, testo `#2F3CB0`.
- **COSA HO LETTO · 1.976 CARATTERI**: riquadro bianco raggio 18 con l'inizio del testo letto (15/500, interlinea
  1,5) e "Mostra tutto".
- In basso **Salva** (pieno accento; dopo il salvataggio "Salvato" per 2 s) e a destra **Elimina** (testo rosso
  errore, con conferma). Salva manda `PATCH /api/materiale/{id}` con i soli campi cambiati.

## 6. Nuova lezione (v1.4)

Prototipi `NuovaLezionePc.dc.html` (PC) e `NuovaLezioneTel.dc.html` (telefono). Principio: quattro decisioni
numerate dall'alto in basso, e a fianco (PC) o in fondo (telefono) un riepilogo vivo che dice in parole cosa
uscirà e quanto costa, con il pulsante. Le scelte si vedono tutte, niente "Cambia" che nasconde.

Intestazione: X tonda, "Nuova lezione" (PC 30/800, telefono 26/800), pillola della materia.

Sezioni (numero in un tondo accento tenue 28 px, titolo 18/800):
1. **Su cosa** — ARGOMENTO (campo 52 px, 17/600). PER: pillole "Nessuna data" e la prossima verifica o
   interrogazione della materia ("Interrogazione di lunedì 10 novembre"), già scelta se c'è.
2. **Da quale materiale** — a destra del titolo "8 di 15 scelti" (14/700 tenue). Pillole **Sull'argomento**
   (predefinita) · **Tutti** · **Nessuno**, campo **Cerca nel materiale** (filtra per titolo). Due gruppi con
   etichetta maiuscola 12/700: **APPUNTI E LIBRO** e **VERIFICHE E INTERROGAZIONI DEL PROF** (se vuoto: "Nessuna
   ancora", tenue). Righe (PC in due colonne, telefono una): casella 22 px raggio 7 (spuntata = accento con
   spunta bianca), titolo 15/700 su una riga con i puntini, sotto "Appunti · foto · 4 novembre" 13/500 tenue, a
   destra la pillola tenue "argomento" se è tra i suggeriti. Riga scelta: bordo 2 px `#A9B1F5`, fondo `#F6F7FF`
   (scuro: bordo `#3B4290`, fondo `#1E2140`). Materiale ancora da leggere: riga spenta con "Lo sto leggendo",
   non si spunta. In errore: non compare.
   Finché Andrea non tocca una casella valgono i suggeriti del server (`GET /api/materie/{id}/suggeriti`,
   400 ms dopo l'ultima lettera dell'argomento); appena ne tocca una, le caselle restano come le ha messe lui
   finché non preme di nuovo **Sull'argomento**.
3. **Come la vuoi** — tre schede tipo (PC affiancate, telefono binario a tre segmenti con la frase sotto):
   **Spiegazione** "Te lo spiego dall'inizio, con domande lungo la strada"; **Ripasso** "Più veloce: i punti
   chiave e tante domande alla fine"; **Interrogazione** (telefono "Interroga") "Ti interrogo come il prof, poi
   ti dico la risposta giusta". LE TUE INDICAZIONI: area di testo (4 righe, 15/500) con contatore "n / 600" e
   segnaposto "Per esempio: insisti su quello che il prof chiede sempre" (vale per ogni materia). Sotto, spunti a
   pillola che aggiungono o tolgono una frase dal testo ("+ Spiegalo da zero" → "✓ Spiegalo da zero"): Spiegalo
   da zero, Insisti su date e nomi, Confronta gli autori, Descrivi bene le opere, Domande più difficili, Tanti
   esempi (telefono: Da zero, Date e nomi, Confronta, Descrivi le opere, Più difficili, Esempi). Frasi:
   "Spiegalo partendo da zero, come se non l'avessi mai studiato." · "Insisti su date e nomi." · "Confronta gli
   autori tra loro: cosa cambia da uno all'altro." · "Descrivi bene le opere, come se le avessi davanti." ·
   "Fammi domande più difficili di quelle del prof." · "Usa tanti esempi concreti."
   DURATA: binario 15 · 20 · 30 · 45 min. DOMANDE: binario **Obbligatorie** · **Si possono saltare**.
4. **Voce** — una scheda per voce di `GET /api/voci` (nome, "voce espressiva"/"voce gratis", costo di questa
   durata). Una voce non disponibile è spenta, bordo tratteggiato, "Non ancora attiva".

**Riepilogo** (PC: colonna destra larga 380 px che resta ferma scorrendo; telefono: barra fissa in basso):
- etichetta "LA TUA LEZIONE", titolo "<Tipo> di 20 minuti" (24/800);
- struttura in parole dal contratto 16.1 ("6 parti, una domanda dopo ognuna e 5 alla fine");
- righe SU (argomento) · MATERIALE ("Mirone e il Discobolo" / "3 materiali: A, B e altri 1") · INDICAZIONI
  (prime 140 lettere o "Nessuna: segue il materiale e la scheda del prof") · PER (+ "domande obbligatorie" o
  "che si possono saltare"). Cosa manca in ambra `#8A5506` 700: "Manca l'argomento", "Scegli almeno un
  materiale";
- barra del mese a due toni e "dopo questa 0,32 € di 2 €";
- pulsante **Prepara la lezione · 0,27 €** (56 px, raggio 16). Spento (`#ECEAE4`, testo `#74747D`) se manca
  l'argomento o il materiale o se la stima non lo consente (sotto, la frase del server).
Telefono: la barra in basso mostra "Spiegazione · 20 min · 3 materiali", "mese: 0,32 € di 2 €" e il pulsante.

Dopo il tocco: come prima, **In preparazione** con le fasi; errore → frase del server + **Riprova**.
Modalità prova: pillola "Prova" e costi a 0 con la frase sulla voce robotica.

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
  Aggiornamenti (versione e "Si aggiorna da sola"; con una versione pronta sul computer "Si installa da sola quando
  chiudi Lectio" + **Aggiorna adesso**), Informazioni.
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
7. **Come la vuoi** (riepilogo + pulsante): "Scegli materiale, tipo e indicazioni: qui vedi cosa esce e quanto costa." Avanti.
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

## 30. Le schermate nuove: regole comuni

Le sezioni 30-40 aggiungono Schemi, Lettura dello schema, "Non ho capito", Ripasso lampo, Verifica simulata e i 16
colori. Partono da 30 perché nei commenti del codice "§15" e "§16" vogliono già dire contratto API. Campi, stati e
indirizzi stanno in `docs/contratto-api.md` §17-22; qui ci sono solo aspetto e comportamento. Non è un redesign:
stessa faccia di Nuova lezione e del Lettore (Figtree, schede bianche, pillole, riepilogo vivo). Non c'è un
prototipo per queste pagine: valgono le misure scritte qui.

### 30.1 Principi (dalla ricerca)

1. **Si legge come un libro.** Righe da 60-75 lettere, mai oltre 80
   ([Baymard](https://baymard.com/research-articles/line-length-readability)). Misurato sul file di Figtree con un
   testo italiano: a 18 px una lettera è larga in media 7,9 px, quindi 580 px sono 73 lettere. Più larga no.
2. **Grassetto e foto servono a capire, non ad abbellire.** Il grassetto segna solo i termini chiave, una foto sta
   accanto al testo di cui parla con la didascalia attaccata, niente che non aiuti
   ([principi di Mayer](https://services.dartmouth.edu/TDClient/1806/Portal/KB/PrintArticle?ID=171655)). Quindi
   niente foto d'apertura decorative: "Tante immagini" vuol dire più immagini utili.
3. **Si mostra da dove viene, non quanto è sicuro.** Mai percentuali di sicurezza: un segno "Libro, pag. 42"
   controllabile con un'occhiata
   ([ZURB](https://radar.zurb.com/article/your-ai-feature-works-nobody-can-tell-if-its-lying),
   [NotebookLM](https://blog.google/technology/ai/notebooklm-goes-global-support-for-websites-slides-fact-check/)).
4. **"Non c'è" è una risposta, non un errore.** Se i documenti non coprono qualcosa si dice chiaro, con un tono
   calmo (mai rosso) e il passo dopo: **Aggiungi materiale**. Ciò che esce si chiama per nome: "stima", non "voto".
5. **L'indice si vede sempre.** Sul PC colonna ferma con la parte corrente accesa, tutte le parti dentro; sul
   telefono un indice sticky nascosto dietro un'icona viene ignorato, quindi la barra porta il nome della parte
   ([NN/g](https://www.nngroup.com/articles/table-of-contents/)).
6. **Fogli solo per cose brevi, mai uno sopra l'altro.** X sempre visibile (la maniglia molti non la vedono), il
   tasto Indietro chiude ([NN/g](https://www.nngroup.com/articles/bottom-sheet/)). Le fonti si aprono dentro il
   foglio, non in un altro foglio.
7. **Ci si giudica in tre parole, sempre allo stesso posto.** La risposta si apre con un gesto voluto, la domanda
   resta visibile, i pulsanti non si spostano
   ([schede con autovalutazione](https://screensdesign.com/articles/flashcard-answer-reveal-self-grading-ux/)). La
   parola di mezzo deve spiegarsi da sola: è quella che si usa male
   ([Anki](https://forums.ankiweb.net/t/how-to-prevent-users-from-misusing-hard-ideas-are-welcome/49092?page=8)).
8. **Allenamento, non esame.** Le verifiche di prova abbassano l'ansia (g = -0,52 su 24 studi,
   [Yang e altri 2023](https://link.springer.com/article/10.1007/s10648-023-09801-w)) se la posta è bassa e si possono
   rifare ([Hechinger](https://hechingerreport.org/proof-points-lowering-test-anxiety-in-the-classroom/)). Il conto
   alla rovescia al secondo agita, quindi solo minuti, sottile e nascondibile
   ([Johns Hopkins](https://ctl.jhsph.edu/blog/posts/redesigned-countdown-timer/),
   [scelta del
   timer](https://sigcse2023.sigcse.org/details/sigcse-ts-2023-papers/55/Providing-a-Choice-of-Time-Trackers-on-Online-Assessments),
   [WCAG 2.2.1](https://www.w3.org/WAI/WCAG22/Understanding/timing-adjustable.html)).
9. **Si vede sempre se è salvato.** Stato visibile in ogni momento e mai un pulsante Salva accanto al salvataggio
   automatico ([saasui](https://www.saasui.design/blog/saas-autosave-save-states-ux-patterns),
   [Primer](https://primer.style/ui-patterns/saving)).

### 30.2 Testi nuovi nella scala tipografica

| Ruolo | Dimensione / peso | Dove |
|---|---|---|
| Testo di lettura | PC 18 / 400, interlinea 1,6; telefono 17 / 400, interlinea 1,55 | paragrafi dello schema, risposta di "Non ho capito" |
| Titolo di una parte | PC 24 / 800, telefono 22 / 800, interlinea 1,25 | titolo di una sezione dello schema |
| Titolo nel testo | 19 / 800 | `titolo` di un paragrafo ("Descrizione", "Analisi") |
| Segno di fonte | 13 / 500, Testo tenue | "Libro, pag. 42" |
| Voto stimato | 44 / 800, spaziatura -0,8, cifre tabellari | risultati della verifica simulata |

Il Testo molto tenue resta solo da 14 px in su (sezione 1): segni di fonte e crediti delle foto usano il Testo tenue.
**Fondo blocco** (dentro una scheda bianca): chiaro `#F5F4F0`, scuro `#26262C`.

### 30.3 Stati che valgono ovunque

- **Costi:** ogni pulsante che spende dice quanto, con la stima del server (`Prepara lo schema · 0,08 €`). Finché la
  stima
  non arriva il pulsante è spento e senza prezzo. Stessa barra del mese di Nuova lezione. Unica eccezione "Non ho
  capito": il
  server non dà una stima prima, costa meno di un centesimo e lo si dice dopo.
- **Errori:** la frase del server e **Riprova**; il testo scritto non si perde mai.
- **Senza rete:** la regola della sezione 10 (azioni spente con la frase del perché). I casi particolari sono nelle
  singole sezioni.
- **Modalità prova:** pillola "Prova" e costi a 0, come in Nuova lezione.
- **Tema scuro:** tutto con i gettoni della sezione 1; niente ombre, superfici più chiare.

## 31. Dove si entra

### 31.1 Oggi

Tra **Riprendi** e **Dopo la verifica** una scheda **Ripasso lampo** (raggio 22, padding 14/16, intera toccabile,
altezza
minima 72): piastrella 44 con Superficie 2 e icona `lampo` color Testo; "Ripasso lampo" 17/700; sotto "8 schede ·
circa 3
min" 14/500 tenue (da `lampo.da_fare` e `lampo.minuti`, "1 scheda" al singolare); a destra pulsante secondario
**Inizia**
(accento tenue, alto 48).
- `da_fare` a 0: una riga alta 56 con la spunta verde e "Ripasso lampo · niente per oggi" 15/600 tenue. Non si tocca.
- Mai conteggi di giorni, serie o "hai saltato".
- Sul telefono senza rete, se ci sono schede salvate, **Inizia** resta acceso (sezione 35.5).

### 31.2 Materia

- In testa alla sezione **Da ripassare**, la stessa scheda per quella materia ("5 schede · circa 2 min", da
  `conteggi.lampo`, minuti = schede × 20 s arrotondati in su). Con 0: "Niente per oggi". La sezione ora compare
  sempre,
  anche senza domande da ripassare.
- La sezione **Lezioni** diventa un binario a tre segmenti uguali, alto 48: **Lezioni** · **Schemi** · **Simulate**.
  Si parte da Lezioni; da una notifica si parte dal segmento giusto.
- Il pulsante pieno fisso in basso segue il segmento: **Nuova lezione** · **Nuovo schema** · **Nuova verifica
  simulata**
  (dissolvenza 200 ms).
- **Riga di uno schema** (come le righe delle lezioni): piastrella con icona `testo` color pieno della materia, titolo
  16/700 (al massimo 2 righe), sotto 14/500 tenue secondo lo stato:
  pronto "A capitoli · Completo" (forma "A capitoli" o "Schede per opera", `auto` non si scrive; la lista non porta il
  contenuto, quindi qui niente parti né minuti);
  `in_coda` "In coda"; `in_preparazione` la `frase` del server e barra sottile `fatto`/`totale`; `errore` "Non è
  riuscito"
  (testo errore) e **Riprova**; `annullato` "Annullato". Menu con i puntini: **Scarica PDF** (se pronto),
  **Elimina** (con conferma).
- **Riga di una simulata:** piastrella con icona `verifica`. `pronta` "Pronta · 50 min"; `in_corso` "A metà"
  e pulsante secondario **Riprendi**; `in_correzione` "La sto correggendo"; `corretta` "Corretta" e, se la lista porta
  `esito`, "· stima circa 6,5"; `errore` come sopra. Menu: **Scarica PDF**, **Elimina** (con conferma).
- Vuoti: "Nessuno schema ancora", "Nessuna verifica simulata ancora" (15/500 tenue). Il pulsante è già quello fisso.

### 31.3 Lettore

Nella riga sotto i comandi, dopo **Testo**: pillola **Non ho capito** (alta 48, icona `domanda` 18, 14/700,
Superficie 2).
A 360 dp ci stanno tutte e tre (circa 270 dp). Non compare mentre è aperta la scheda di una domanda.

## 32. Nuovo schema

Stessa finestra di Nuova lezione sul PC (stesse classi `nl-*`, stessa regola sul contenitore: riepilogo a destra da
940 px,
sotto più barra ferma se è più stretto), pagina sul telefono. Intestazione: X tonda, "Nuovo schema" (PC 30/800,
telefono
26/800), pillola della materia. Riepilogo a destra largo 380 px.

1. **Su cosa** — identica alla sezione 6: ARGOMENTO e pillole PER (manda `evento_id` se scelta una data).
2. **Da quale materiale** — identica alla sezione 6: stesse pillole, ricerca, gruppi, suggeriti.
3. **Come lo vuoi** — tre gruppi:
   - FORMA. PC: tre schede affiancate (classe `nl-scheda`). Telefono: binario con la frase sotto, colonne `1,6fr 1fr
     1fr`.

     | `forma` | PC | Telefono | Frase |
     |---|---|---|---|
     | `auto` (scelta di partenza) | Lo decide Lectio | Lo decide Lectio | Scelgo io la forma adatta a questo argomento |
     | `capitoli` | A capitoli | A capitoli | Una parte dopo l'altra, dall'inizio alla fine |
     | `schede` | Schede per opera o autore | Schede | Una scheda per ogni opera o autore: dati, descrizione, analisi |

   - LIVELLO. Binario **Essenziale** · **Completo** (di partenza) · **Approfondito**, colonne `1fr 1fr 1,25fr`.
   - LE TUE INDICAZIONI. Come la sezione 6: area di testo, contatore "n / 600", segnaposto "Per esempio: metti una
     tabella
     con le date". Spunti a pillola che aggiungono o tolgono una frase:

     | PC | Telefono | Frase |
     |---|---|---|
     | Tante immagini | Tante immagini | Metti tante immagini, quando aiutano a capire. |
     | Spiegalo da zero | Da zero | Spiegalo partendo da zero, come se non l'avessi mai studiato. |
     | Insisti su date e nomi | Date e nomi | Insisti su date e nomi. |
     | Confronta gli autori | Confronta | Confronta gli autori tra loro: cosa cambia da uno all'altro. |
     | Descrivi bene le opere | Descrivi le opere | Descrivi bene le opere, come se le avessi davanti. |
     | Tanti esempi | Esempi | Usa tanti esempi concreti. |
     | Segui il prof | Come il prof | Dai più spazio a quello che il prof chiede di solito. |

**Riepilogo** (stessi pezzi della sezione 6):
- etichetta "IL TUO SCHEMA", titolo 24/800 "Schema essenziale" / "Schema completo" / "Schema approfondito";
- sotto 15/600 tenue la forma ("A capitoli" · "Schede per opera o autore" · "La forma la scelgo io");
- righe SU · MATERIALE · INDICAZIONI · PER · IMMAGINI ("circa 3", da `immagini`; riga assente se 0) · PRONTO ("in
  circa 4 min");
- cosa manca in ambra 700 come in sezione 6;
- barra del mese, pulsante **Prepara lo schema · 0,08 €** (56, raggio 16). Spento se manca qualcosa; con
  `consentita: false`
  sotto la frase `motivo`.

Telefono e finestra stretta: barra ferma con "Completo · A capitoli · 3 materiali" (15/800, una riga con i puntini),
"mese: 0,29 € di
2 €" (13/600) e il pulsante.

Dopo il tocco: si apre la pagina dello schema in preparazione (33.9). Senza rete: "Senza rete non si può preparare
uno schema."

## 33. Lettura dello schema

Una pagina vera (sul PC nel contenuto, menu laterale visibile; sul telefono a tutto schermo, senza barra in basso,
con il
mini-lettore se c'è una lezione caricata).

### 33.1 Disposizione

**PC, contenuto da 900 px in su (a 1280: 976 utili).** Griglia centrata: indice 232 px, spazio 40, schede 672 px.
Colonna del
testo `max-width: 580px`: la scheda ha padding 28 a sinistra e 64 a destra (lì sta il pulsante al margine, 33.5).

**PC, contenuto sotto i 900 px (finestra minima 900).** Niente colonna indice: una pillola ferma in alto "Indice · 3
di 7 ·
Titolo" (come la barra del telefono) apre il foglio dell'indice. Le schede prendono tutta la larghezza con lo stesso
padding: il
testo arriva a 504 px, 64 lettere.

**Telefono.** Margine 12, scheda raggio 20, padding 16: a 360 dp restano 304 dp (41 lettere), a 390 dp 334. In
orizzontale o su
tablet il testo si ferma a 580 dp, centrato.

### 33.2 Testata

Riga in alto: freccia indietro (48), pillola della materia, a destra **Scarica PDF** (PC: pillola con icona e
parola, Superficie 2;
telefono: tondo 48 con icona `scarica`) e menu puntini (**Elimina**, con conferma). Sotto: titolo (PC 30/800,
telefono 26/800),
e "A capitoli · Completo · 8 parti · 12 min di lettura" 14/500 tenue.

Poi, se serve, la **nota di ciò che manca** (33.7), e sul telefono l'**indice nel testo**: scheda con etichetta
INDICE e righe da
48 dp (al massimo 6, poi **Mostra tutte (12)**). Sul PC c'è già la colonna.

Ogni sezione del contenuto è una scheda bianca (raggio 22, 16 tra una e l'altra). In testa: tondo 28 con `n` (tenue
e testo della
materia, 14/800), titolo. **Ascolta questa parte**: pillola Superficie 2, icona `lezioni` 18, 14/700, alta 44
(telefono 48); sul
PC a destra del titolo se c'è posto, sul telefono su una riga sua sotto il titolo. Apre Nuova lezione con
`argomento` = titolo della
parte e i materiali dello schema già spuntati.

### 33.3 I blocchi

Distanze: 20 tra blocchi, 16 tra due paragrafi. Tipi sconosciuti: si saltano. `**parola**` diventa peso 700, stesso
colore, mai
colori né sottolineature. Allineato a sinistra, mai giustificato.

- **`paragrafo`** — Testo di lettura. `titolo` se c'è: Titolo nel testo sopra.
- **`punti`** — pallino 7 px pieno della materia, rientro 18, 8 tra le voci. `titolo`: 16/700.
- **`passaggi`** — in colonna, anche sul PC. Ogni voce: Fondo blocco, raggio 12, padding 10/14, 16/600. Tra una voce
  e l'altra
  freccia `giu` 18 px (testo della materia) con 4 sopra e sotto. Ultima voce 16/800. Non si tagliano mai.
- **`definizione`** — fondo tenue della materia, raggio 14, padding 12/14; `termine` 16/800 testo della materia;
  `testo` 16/500
  colore "scuro" della materia (`--m-scuro`).
- **`tabella`** — `titolo` 16/700. Intestazione 12/700 maiuscola tenue, celle 15/500 con padding 10/12, righe
  separate da 1 px
  `--riga`, prima colonna 15/700. Sotto i 480 px con 4 o più colonne ogni riga diventa una schedina (Fondo blocco,
  raggio 12): prima
  cella 16/700 e sotto "ETICHETTA valore". Con 3 o meno resta tabella e il testo va a capo.
- **`prof`** — fondo avviso tenue, barra sinistra 3 px testo avviso, raggio 14, padding 12/14. Etichetta "DAL PROF"
  (12/700)
  con icona `verifica` 16, `testo` 16/600 testo avviso. Mai rosso, anche se il testo è "qui hai sbagliato".
- **`immagine`** — 33.6.
- **Scheda opera** (forma `schede`, sezione con `campi`) — la prima `immagine` e i `campi` stanno insieme in testa:
  PC foto a sinistra
  (240 px), a destra righe con l'etichetta maiuscola 12/700 tenue larga 92 px e il valore 16/600; sotto i 480 px
  foto a tutta
  larghezza e poi i campi. I paragrafi "Descrizione" e "Analisi" seguono con Titolo nel testo.

### 33.4 Da dove viene (segno di fonte)

Sotto il blocco, 4 px più in basso, su una riga sua: l'`etichetta` delle fonti (Segno di fonte): "Libro, pag. 42"
oppure "Libro, pag.
42 · Verifica del 12/10"; al massimo due, poi "+1". Nessun fondo, nessuna icona. Se blocchi di fila hanno le stesse
fonti, il segno sta solo
sull'ultimo della serie. Niente `fonti`: niente segno.

Sul telefono a riposo è testo semplice: si tocca dalla riga degli strumenti (33.5). Sul PC è un link (sottolineato
al passaggio).
Una fonte: apre il materiale (5.1). Più fonti: foglio con una riga per fonte. Da una pagina di materiale si torna
indietro e si è
nello stesso punto.

### 33.5 "Non ho capito" su un blocco

Vale per `paragrafo`, `punti`, `passaggi`, `definizione`, `prof`.

**Telefono.** Un tocco sul blocco: il blocco prende il Fondo blocco (raggio 12) e sotto scende (200 ms) la riga
degli strumenti:
pillola accento tenue **Non ho capito** (alta 48, icona `domanda`, 14/700) e pillola Superficie 2 con la fonte
("Libro, pag. 42", al
massimo il 45% della larghezza con i puntini; più fonti: "2 fonti"). Una sola riga aperta alla volta; un altro tocco
la chiude.

**PC.** Al passaggio del mouse o col fuoco da tastiera il blocco prende il Fondo blocco e nel margine destro compare
un tondo 40 con
`domanda` ed etichetta "Non ho capito" al passaggio. Il testo resta selezionabile.

### 33.6 Immagini

Larghezza piena della colonna, raggio 16, proporzioni vere, altezza massima 420 sul PC e 300 sul telefono. Subito
sotto (8 px) `didascalia`
14/600, poi `credito` 12/500 tenue. Un tocco apre la foto a tutto schermo (si ingrandisce con due dita o la rotella,
X per chiudere).

In alto a destra della foto un tondo da 40 con i puntini (area di tocco 48, fondo `rgba(20,20,30,.55)`, icona
bianca): sempre visibile sul
telefono, al passaggio o col fuoco sul PC. Menu:
- **Cambia foto**: la foto pulsa con la scritta "Cerco un'altra foto", poi la nuova entra in dissolvenza (250 ms).
  Se non ce n'è (404) resta quella
  di prima e compare l'avviso breve "Non ne ho trovate altre".
- **Togli foto**: sotto la foto conferma in linea "Toglierla?" con **Togli** e **Lascia**; poi il blocco si richiude
  (220 ms).

Se la foto non si carica: riquadro Fondo blocco, raggio 16, alto 160, "Foto non disponibile" e **Riprova**. La
didascalia resta.

### 33.7 Quando qualcosa non si è trovato

Se `nota`, `copertura.mancanti` o `avvisi` hanno contenuto: subito sotto il titolo una scheda con fondo avviso
tenue, raggio 18, padding
14/16, testo avviso:
- icona `domanda`, "Non ho trovato tutto" 16/800;
- `nota` 15/500; poi "Nei tuoi documenti non c'è:" 15/700 con `mancanti` a punti (al massimo 5, poi "e altri 3");
- `avvisi` uno per riga 15/500;
- pulsante secondario **Aggiungi materiale** (apre il foglio della sezione 5).

Se è tutto a posto questa scheda non esiste, e neanche il conteggio dei concetti.

### 33.8 Indice e posizione

- **PC:** colonna ferma (`sticky`, in alto 24 px, scorre da sola se è più alta). Etichetta INDICE, una riga per
  parte, tutte: 15/600 tenue, al
  massimo 2 righe, raggio 10, alta almeno 40. Parte corrente: fondo accento tenue, testo accento tenue, 800.
  Scorrendo si accende quella giusta e la
  colonna la tiene in vista.
- **Telefono:** barra di lettura ferma in basso, alta 60 più lo spazio di sistema, Superficie, riga sopra 1 px.
  Tutta toccabile: icona `indice`,
  pillola "3 di 7" (13/700, cifre tabellari), titolo della parte 15/700 su una riga con i puntini, freccia in su.
  Non si nasconde scorrendo.
- **Foglio dell'indice** (telefono e PC stretto): "Indice" 21/800, X; righe alte almeno 56 con tondo del numero e
  titolo fino a 2 righe; parte
  corrente come sul PC. Si apre già portato sulla parte corrente.
- Salto: scorrimento morbido 300 ms (senza animazioni: subito). Dopo un salto, pillola "Torna dov'eri" per 5 s
  (sezione 0, punto 5).
- Riaprendo lo schema si riparte dalla parte letta per ultima, senza dirlo.
- **In fondo allo schema**, se `carte` > 0: scheda "12 schede sono già in Ripasso lampo." con pulsante secondario
  **Ripassa adesso**.

### 33.9 Stati

- **In caricamento:** tre segnaposto che pulsano.
- **`in_coda` / `in_preparazione`:** vista come In preparazione di una lezione: titolo, la `frase` del server, barra
  `fatto`/`totale`,
  pulsante testo **Annulla**. Si può uscire; notifica "Lo schema di Storia è pronto" (`schema_pronto`) o l'errore
  (`schema_errore`).
- **`errore`:** la frase `errore`, **Riprova** (`riprova`, riparte da dove era), pulsante testo **Elimina**.
- **`sezioni` vuoto:** "Nei documenti scelti non ho trovato abbastanza per uno schema." e **Scegli altro materiale**
  (Nuovo schema con i campi
  già compilati).
- **Scarica PDF:** l'etichetta diventa "Preparo il PDF…" e il pulsante si spegne. PC: finestra Salva con nome, poi
  avviso "PDF salvato" con **Apri**.
  Telefono: va nei Download, poi avviso "PDF nei Download" con **Apri**. Errore: frase del server e **Riprova**.
- **Senza rete:** ogni schema aperto almeno una volta resta sul dispositivo (testo e foto) e si legge; **Non ho
  capito**, **Ascolta questa
  parte**, **Scarica PDF**, **Cambia foto**, **Togli foto** spenti con pillola "Senza rete" in alto. Uno schema mai
  aperto: "Si apre quando
  torna la rete." e **Riprova**.

Il PDF lo fa il server: stessi blocchi, niente pulsanti; segni di fonte e crediti restano.

## 34. "Non ho capito"

Foglio dal basso, uguale da uno schema e dal Lettore (PC: `apriFoglio`, largo 600, Esc chiude). Nessun altro foglio
sopra. La parte importante sta in basso.

**Dall'alto:** maniglia; "Non ho capito" 21/800 e X tonda (48).
**Passaggio:** riquadro Fondo blocco, raggio 14, padding 10/12, 15/500, massimo 3 righe con i puntini; un tocco lo
apre tutto.
- Da uno schema: il testo del blocco (è il `passaggio` che si manda, con `voci` una per riga; `sezione` è la `n`
  della parte).
- Dal Lettore: l'audio va in pausa; sopra il riquadro etichetta "AL MINUTO 5:12 · LA CRISI DEL TRECENTO" (minuto e
  capitolo). Il testo arriva col
  `passaggio` della risposta; finché non c'è, solo l'etichetta. Si chiude e l'audio riparte da lì, se stava suonando.

**In basso, fermi sopra la tastiera** (è l'unica zona da raggiungere col pollice):
1. Tre spunti (pillole alte 44, vanno a capo) che riempiono il campo: **Più semplice** ("Spiegalo in modo più
   semplice.") · **Un esempio** ("Fammi un
   esempio.") · **Una parola** ("Cosa vuol dire questa parola?").
2. Campo su una riga che cresce fino a 3 (52, 16 px, Superficie 2, raggio 14), segnaposto "Cosa non ti è chiaro".
   Tastiera chiusa all'inizio.
   Contatore solo da 260 ("280 / 300").
3. Pulsante pieno **Spiegamelo** (56), sempre acceso: il campo è facoltativo. PC: Invio manda, Maiusc+Invio va a capo.

**Attesa** (fino a 40 s): al posto dei comandi tre righe segnaposto che pulsano e "Rileggo i tuoi documenti…"
15/600; dopo 15 s
"Ci vuole ancora qualche secondo". La X resta. Il foglio può salire fino all'88% (250 ms) quando arriva la risposta.

**Risposta** (`trovato: true`):
- `risposta` in Testo di lettura, sopra il piede.
- Etichetta "DA DOVE L'HO PRESO" e una riga per `fonti` (alta 56): icona del tipo, `etichetta` 15/700, `titolo`
  13/500 tenue, freccia. Apre il materiale come
  pagina; tornando indietro il foglio è ancora lì con la risposta.
- "Costata meno di 1 cent" 13/500 tenue in fondo (sotto 0,01 €; altrimenti il costo).
- Piede fermo: **Ho capito** (pieno) e **Non ancora** (secondario), metà ciascuno, 52. **Non ancora** rimette campo
  e **Spiegamelo** sotto la risposta; la nuova
  si aggiunge sotto e ci si porta lì.

**Non c'è** (`trovato: false`): riquadro ardesia tenue (`--ardesia-tenue`, `--ardesia-testo`), raggio 18, padding
14/16, icona `domanda`, "Nei tuoi documenti non
c'è" 16/800, sotto la `risposta` del server (dice cosa manca). Piede: **Aggiungi materiale** (pieno; chiude questo
foglio e apre quello della sezione 5) e **Chiudi**
(secondario). Niente spiegazione a memoria.

**Errori:** la frase del server e **Riprova**, testo del campo tenuto. Tetto finito: la frase del server. **Senza
rete:** **Spiegamelo** spento con "Senza rete non posso
rileggere i tuoi documenti."

## 35. Ripasso lampo

### 35.1 Disposizione

**Telefono:** pagina intera senza barra in basso, margini 16. **PC:** finestra larga `min(640px, 100%)`, alta
`calc(100vh - 48px)`. Dall'alto:
- Riga: X (48), in mezzo "3 di 12" 13/600 cifre tabellari, a destra **Annulla** (compare dopo il primo esito) e menu
  puntini (**Togli questa scheda**: conferma in linea
  "Toglierla per sempre?" con **Togli** e **Lascia**).
- Barra sottile 4 px (binario Superficie 2, accento) a tutta larghezza; avanza in 250 ms.
- **Scheda** (raggio 22, padding 20, in rilievo, alta almeno metà dello spazio, ancorata in alto, scorre da sola):
  pillola della materia (`materia`, `colore`); `domanda` 22/800 interlinea 1,3.
- **Zona in basso** alta 72 più lo spazio di sistema, ferma: stessa posizione per "Mostra la risposta" e per i tre
  esiti.

### 35.2 Una scheda

1. **Domanda.** Il pulsante pieno **Mostra la risposta** (58, a tutta larghezza) sta nella zona in basso. Prima del
   tocco la risposta non va nemmeno nei dati letti dai lettori di schermo.
2. **Risposta.** Sotto la domanda, che resta dov'è: riga 1 px, etichetta "RISPOSTA", `risposta` 17/500 interlinea
   1,5, segno di fonte (33.4) se ci sono `fonti`. Entra con dissolvenza + 8 px in 220 ms. Il fuoco va alla risposta.
3. **Esito.** Nella stessa zona, tre pulsanti uguali (gap 8, raggio 18, alti almeno 72, padding laterale 4): icona
   22 sopra la
   parola, 15/700 da 390 dp in su e 14/700 sotto.

| Pulsante | `esito` | Icona | Fondo chiaro / scuro | Testo chiaro / scuro |
|---|---|---|---|---|
| Non lo sapevo | `non_sapevo` | `ripeti` | `#EEF0F2` / `#2A2D31` | `#343A40` / `#C9CED4` |
| Quasi | `quasi` | `quasi` | `#FFF4E0` / `#3A2C14` | `#8A5506` / `#FFC56B` |
| Lo sapevo | `sapevo` | `spunta` | `#EAF6EC` / `#1F3327` | `#1F6A30` / `#8FD9A0` |

Mai accento né rosso: nessuna risposta è "quella da premere". I colori sono quelli di Giusta / Quasi / Da rivedere
del Lettore.

- **Doppio tocco:** i tre pulsanti non rispondono per i primi 300 ms, così un doppio tocco su "Mostra la risposta"
  non dà un voto per sbaglio.
- **Cambio scheda:** quella finita esce a sinistra (-24 px e dissolvenza, 180 ms), la nuova entra da destra (240
  ms). Niente gesti per dare l'esito.
- **Annulla:** l'ultimo esito si manda al server solo quando arriva l'esito della scheda dopo o quando si esce.
  **Annulla** lo butta e riporta la scheda con la risposta aperta. Una volta sola.
- **PC, tastiera:** Spazio mostra la risposta; 1, 2, 3 danno l'esito; Esc esce. Tasto disegnato piccolo (12/700)
  nell'angolo di ogni pulsante.
- **Uscire a metà:** nessuna conferma; quello fatto è salvato.

### 35.3 Fine

Niente festa, niente complimenti. Scheda centrata: "Ripasso finito" 24/800; "12 schede in 3 minuti" 14/500 tenue;
tre riquadri affiancati coi colori della tabella sopra, numero 24/800 che conta (350 ms) e
parola 13/700 ("7 Lo sapevo", "3 Quasi", "2 Non lo sapevo"; vanno a capo se serve). Sotto, "Domani 5 schede" 15/600
tenue (solo se `domani` > 0). Pulsante pieno **Fine**. Se `da_fare` > 0, pulsante testo **Continua con altre**
(senza
numeri: nessun arretrato da inseguire).

### 35.4 Niente da fare

`carte` vuoto: scheda centrata, spunta verde, "Per oggi niente da ripassare" 19/800, "Domani 3 schede" 15/600 tenue
(se `domani` > 0), pulsante pieno **Torna a Oggi** (dalla materia: **Torna alla materia**).

### 35.5 Attese, errori, rete

- Caricamento: segnaposto della scheda. Errore del server: la frase e **Riprova**.
- **Telefono senza rete:** valgono le ultime schede salvate (si salvano ogni volta che si aprono Oggi o la materia
  con la rete). Pillola "Senza rete"; gli esiti restano in coda e partono in ordine al ritorno, una volta sola
  ciascuno.
  Senza schede salvate: "Senza rete non ho le schede di oggi." e **Riprova**.
- **PC senza rete:** stessa frase, nessuna copia salvata.
- Un esito che il server rifiuta: avviso breve "Non ho salvato l'ultimo esito: riprovo", si va avanti.

## 36. Verifica simulata

### 36.1 Nuova verifica simulata

Stessa finestra e stesso schema di Nuova lezione. Intestazione "Nuova verifica simulata" (PC 30/800, telefono
26/800, va a capo), pillola della materia.
1. **Su cosa** e 2. **Da quale materiale**: come la sezione 6.
3. **Come la vuoi**: DURATA binario **15 · 30 · 50 · 60 min** (parte da 50). LE TUE INDICAZIONI: area di testo con
   contatore, segnaposto "Per esempio: più domande sul Seicento". Niente spunti.

**Riepilogo:** "LA TUA VERIFICA", "Verifica di 50 minuti" 24/800, righe SU · MATERIALE · INDICAZIONI · PER · FORMATO
("Come le tue 4 verifiche"; con `conteggi.verifiche` a 0 in ambra 700 "Nessuna verifica caricata: uso un formato
normale") · PRONTA ("in circa 2 min"), barra del mese, **Prepara la verifica · 0,03 €**.
Telefono: barra ferma "50 min · 3 materiali", mese, pulsante.

In preparazione: come 33.9 con la `frase` del server. Errore e **Riprova** uguali.

### 36.2 Pronta

Pagina centrata (max 640): pillola della materia, titolo 24/800, "8 domande · 100 punti · 50 minuti" 15/600 tenue,
la `nota` in riquadro avviso se c'è. Pulsante pieno **Inizia** (58) e sotto secondario **Fai su carta** (52).
**Fai su carta** scarica il PDF come in 33.9; poi l'avviso resta sotto i pulsanti: "Quando hai finito, premi Inizia
e copia qui le risposte: te le correggo."

### 36.3 Mentre la fai

**Tempo** (`orologio` + "22 min" 13/700 tenue, cifre tabellari, pillola Superficie 2, alta 44, area 48): solo
minuti, arrotondati in su, mai secondi; "meno di 1 min" alla fine. Un tocco la nasconde: resta il solo tondo con
`orologio`; la scelta vale anche per le prossime.
- Colore sempre tenue: mai ambra, mai rosso.
- Tempo visibile: avviso breve "Mancano 5 minuti" per 4 s. Tempo nascosto: quell'avviso non compare.
- A tempo finito non si blocca niente: avviso "Il tempo è finito. Puoi continuare." che resta finché non lo chiudi;
  il tempo diventa "+6 min".
- Il tempo conta solo con la pagina aperta. Si tiene sul dispositivo (ogni 10 s): uscire equivale a una pausa, senza
  pulsante Pausa.

**Domanda:** scheda raggio 22, padding 20 (PC 24). Se `sezione` c'è: etichetta sopra la prima domanda del gruppo.
"DOMANDA 3 · 10 PUNTI" 12/700 tenue, `testo` 18/600 interlinea 1,45.
**Risposta:** area di testo 16/400, Superficie 2, raggio 14, almeno 5 righe, cresce col testo, segnaposto "Scrivi la
tua risposta". Sotto a destra lo stato del salvataggio, 13/600 tenue:

| Stato | Testo |
|---|---|
| Scrivendo (dopo 800 ms: `PATCH`) | Salvo… |
| Fatto | Salvata |
| Senza rete | Salvata sul telefono / Salvata sul computer (copia locale, si manda al ritorno della rete) |
| Errore | Non riesco a salvare (testo errore) e **Riprova** |

Ogni risposta si scrive prima sul dispositivo, poi si manda. Mai un pulsante Salva.

**Telefono, 390 e 360:** una domanda alla volta. Riga in alto: X, "Domanda 3 di 8" 14/700, tempo. Barra in basso
sopra la tastiera (alta 60): tondo 48 indietro, **Avanti** (secondario, resto dello spazio), tondo 48 `indice`.
Ultima domanda: al posto di **Avanti**, **Consegna** (pieno).
Foglio delle domande (tondo `indice`): griglia di numeri 48 × 48, 6 per riga. Scritta: fondo e testo "fatto" tenue.
Vuota: bordo 2 px `--riga`. Corrente: bordo 2 px accento. Sotto, **Consegna** (pieno). Niente scorrimento laterale
tra le domande (confligge con il testo).

**PC da 900 px in su:** tutte le domande in colonna (schede max 672) e a sinistra la colonna ferma da 232: tempo in
alto, etichetta DOMANDE, righe alte 44 con numero, punti e stato (spunta verde se scritta), **Consegna** secondario
in fondo. In fondo alle domande **Consegna** pieno. Come un foglio vero: si vede tutto e si salta dove si vuole.
**PC stretto:** tutte in colonna; barra ferma in alto con tempo, pillola **Domande** (foglio) e **Consegna**
secondario.

**Uscire** (X): conferma "Uscire? Le risposte restano salvate." con **Esci** e **Resto**. Si riprende da Oggi o
dalla materia con **Riprendi**.

**Consegna:** foglio "Consegnare?", "5 scritte su 8 · 3 vuote" 16/600; pulsante pieno **Consegna** e secondario
**Torna alle domande**. Errore: "Non sono riuscito a consegnare. Le risposte sono salve." e **Riprova**. Senza rete:
spento con "Senza rete non posso correggerla. Le risposte restano salvate."

**Correzione** (`in_correzione`, si chiede ogni 3-5 s): vista come 33.9, "La sto correggendo"; si può uscire.

### 36.4 Risultati

Colonna centrata (max 720 sul PC).

**Testata** (scheda con fondo tenue della materia, raggio 22, padding 20):
- etichetta "VOTO STIMATO" 12/700 testo della materia;
- "circa 6,5" Voto stimato, colore "scuro" della materia ("circa" sulla stessa riga; misura 175 px, a 360 dp ci sta);
- "62 punti su 100 · la sufficienza è a 58" 15/600 (la soglia solo se c'è);
- "È una stima di Lectio. Il voto vero lo dà il prof." 14/500 testo della materia. Questa frase resta sempre: è un
  avviso, non un aiuto.
- "Consegnata dopo 56 minuti su 50" 14/500, solo se è andato oltre il tempo.

**Una riga per domanda**, nell'ordine della verifica (scheda raggio 18, padding 14/16, intestazione tutta toccabile,
alta almeno 56, freccia che gira in 200 ms):
"DOMANDA 3" 12/700 tenue, pillola esito a sinistra, punti "8 su 10" 15/700 a destra (cifre tabellari), sotto `testo`
16/600 (3 righe finché è chiusa). Chiuse le Giuste; aperte tutte le altre.

| `esito` | Parola | Icona | Colori |
|---|---|---|---|
| `giusta` | Giusta | `spunta` | fatto tenue / testo fatto |
| `parziale` | Quasi | `quasi` | avviso tenue / testo avviso |
| `sbagliata` | Da rivedere | `ripeti` | ardesia tenue / testo ardesia |
| `non_corretta` | Confronta tu | `confronta` | senza fondo, bordo 2 px tratteggiato `--spenta-bordo`, testo tenue |

Aperta: "LA TUA RISPOSTA" e il testo 15/500 ("Non hai risposto" 15/500 tenue se vuota); "COMMENTO" e `commento`
16/500; "MANCAVA" e pillole `mancava` (Superficie 2, come nel Lettore); "RISPOSTA ATTESA" in riquadro
`--risposta-fondo`, testo `--risposta-testo`, raggio 14; segni di fonte (33.4).
`non_corretta`: niente commento né risposta attesa (se il server non la manda), solo la risposta di Andrea e, in
tenue 15/500, "Nei tuoi documenti non c'è la soluzione. Confronta tu con il libro o con il prof."

**In fondo** (telefono: fermo in basso): se `da_rifare` > 0, "3 domande da rifare sono già in Ripasso lampo."
15/600, pulsante pieno **Ripassa adesso**. Sempre: secondario **Rifai la verifica** (riapre 36.1 con tutto già
compilato, il costo si rivede).

## 37. I 16 colori delle materie

Nomi per il codice (minuscoli, senza accenti; il server e le app accettano solo questi, uno sconosciuto diventa
ambra). Nomi a schermo: la stessa parola con la maiuscola.

**Valori completi.** PC: come `.m-*` in `stile.css` (quattro valori chiari, tre scuri; il pieno non cambia).
Android: nella `TAVOLOZZA` vanno pieno, tenue e testo chiari; il tema scuro lo calcola `coloreMateria`.

| Nome | Pieno | Tenue | Testo | Scuro | Tenue scuro | Testo scuro | "Scuro" scuro |
|---|---|---|---|---|---|---|---|
| `giada` | `#00A089` | `#DFF6F0` | `#026C5C` | `#012E27` | `#153837` | `#82DDC8` | `#C6F1E6` |
| `senape` | `#928100` | `#F4F1DD` | `#695D02` | `#2D2801` | `#353119` | `#D8CB7D` | `#EEE8C2` |
| `orchidea` | `#C04EC8` | `#FAEBFA` | `#8A2C91` | `#3B133E` | `#3F2645` | `#EDB1F0` | `#FADAFB` |
| `prugna` | `#A34A80` | `#FEEAF4` | `#883E6B` | `#3A1B2E` | `#392535` | `#FCADD9` | `#FFDAED` |
| `cacao` | `#916748` | `#F9EEE6` | `#76543A` | `#332419` | `#352C29` | `#E8C0A3` | `#F7E2D3` |
| `lavagna` | `#636F8E` | `#ECF0FA` | `#525C75` | `#232732` | `#2B2D38` | `#B8C8F1` | `#DEE6FB` |
| `salvia` | `#7B9575` | `#EBF3EA` | `#4E634A` | `#222B20` | `#303633` | `#B5D3AE` | `#DCECD9` |
| `lavanda` | `#A380C8` | `#F4ECFE` | `#694D86` | `#2D213A` | `#393145` | `#D7BBF7` | `#EDDFFE` |

**Contrasti calcolati** (formula WCAG; il minimo per il testo è 4,5:1, per pallini e spunta 3:1). "PC" usa i valori
qui sopra; "Android" usa i valori che calcola `coloreMateria` (`lerp` di Compose, spazio Oklab).

| Nome | Chiaro: testo su tenue | Chiaro: scuro su tenue | Scuro PC: testo su tenue | Scuro PC: "scuro" su tenue | Scuro Android: testo su tenue | Scuro Android: "scuro" su tenue | Pieno e spunta bianca | Pieno su `#1B1B20` PC / Android |
|---|---|---|---|---|---|---|---|---|
| `giada` | 5,63 | 13,07 | 7,94 | 10,36 | 7,88 | 10,65 | 3,29 | 5,22 / 5,82 |
| `senape` | 5,83 | 13,05 | 7,95 | 10,57 | 7,65 | 10,76 | 3,92 | 4,38 / 4,98 |
| `orchidea` | 6,43 | 13,59 | 7,72 | 10,47 | 7,61 | 10,80 | 4,07 | 4,22 / 4,82 |
| `prugna` | 6,20 | 13,29 | 8,13 | 11,08 | 7,19 | 10,96 | 5,46 | 3,14 / 3,69 |
| `cacao` | 5,94 | 13,08 | 8,10 | 10,87 | 7,32 | 10,90 | 4,96 | 3,46 / 4,03 |
| `lavagna` | 5,85 | 13,08 | 8,19 | 10,95 | 7,31 | 10,91 | 5,01 | 3,43 / 3,99 |
| `salvia` | 5,78 | 12,93 | 7,58 | 10,02 | 7,88 | 10,65 | 3,28 | 5,23 / 5,83 |
| `lavanda` | 6,08 | 13,15 | 7,26 | 9,76 | 7,91 | 10,64 | 3,24 | 5,29 / 5,90 |

Il testo sul bianco è almeno 6,36:1 e su `#F5F4F0` almeno 5,78:1. Distanza fra colori (differenza percettiva Oklab):
la coppia nuova più vicina è giada-salvia a 0,081, sopra la coppia già esistente più vicina (verde-oliva, 0,063).
Dal rosso degli errori (`#C92A2A`, `#E03131`) la nuova più vicina è prugna a 0,130; dall'accento lavanda a 0,101.
Nessuna è un rosso.
Per chi non distingue bene i colori alcune coppie si confondono (già oggi ambra-arancio): perciò il colore non è mai
l'unica informazione, c'è sempre il nome della materia.

### 37.1 La scelta del colore

Sostituisce la riga "8 cerchi da 44 dp" della sezione 4. Due elenchi:
- **Primo libero** (`COLORI`, si propone il primo non usato): ambra, rosa, petrolio, viola, blu, verde, arancio,
  oliva, giada, orchidea, senape, lavanda, cacao, prugna, salvia, lavagna.
- **Ordine a schermo** (per tinta, così sembrano una sfumatura e non coriandoli): rosa, arancio, cacao, ambra,
  senape, oliva / salvia, verde, giada, petrolio, blu, lavagna / viola, lavanda, orchidea, prugna.

- **Telefono:** 6 colonne, 3 righe (6 + 6 + 4, l'ultima allineata a sinistra). A 360 dp, con il foglio a padding 24,
  ogni casella è 52 × 52 dp: area di tocco piena, cerchio visibile 40. Scelto: spunta bianca 18, anello 2 dp a 3 dp
  di distanza (diametro 50, non tocca il vicino). Alto in tutto 156 dp.
- **PC:** 8 colonne, 2 righe (`repeat(8, 44px)`, gap 12: 436 px); la seconda riga comincia da giada. Sotto i 480 px
  di foglio anche qui 6 colonne.
- Accanto all'etichetta COLORE il nome scelto, che cambia dal vivo: "COLORE · Giada"; se è già usato da un'altra
  materia, sotto 13/600 tenue "già usato da Storia".
- Un colore usato resta sceglibile e ha un puntino bianco 8 al centro. L'anteprima della scheda resta sopra e cambia
  colore in 200 ms.
- Ruoli per i lettori di schermo: gruppo di scelta singola; ogni cerchio dice nome e, se c'è, "già usato da Storia".

## 38. Parole lunghe su schermi stretti

Larghezze misurate col carattere vero (Figtree, peso indicato), a 360 dp e con tutto a grandezza normale.

| Dove | Spazio | Testo | Larghezza | Regola |
|---|---|---|---|---|
| Binario Forma, colonne 1,6/1/1 | 124 / 78 / 78 | Lo decide Lectio · A capitoli · Schede (14/700) | 106 · 61 · 48 | ci sta |
| Binario Livello, colonne 1/1/1,25 | 86 / 86 / 108 | Essenziale · Completo · Approfondito (14/700) | 66 · 64 · 88 | ci sta |
| Binario Lezioni/Schemi/Simulate | 101 ciascuno | 14/700 | 45 · 48 · 57 | "Verifiche simulate" (117) non ci starebbe: per questo Simulate |
| Binario Durata | 69 ciascuno | "50 min" 14/700 | 44 | ci sta |
| Tre esiti, 360 dp | 104 ciascuno | "Non lo sapevo" 14/700 | 91 | una riga |
| Tre esiti, da 390 dp | 114 ciascuno | "Non lo sapevo" 15/700 | 98 | una riga |
| Pulsante pieno | 328 | "Prepara lo schema · 0,08 €" 17/800 | 211 | ci sta |
| Pulsante pieno | 328 | "Nuova verifica simulata" 17/800 | 188 | ci sta |
| Pillola sezione | 182 con icona | "Ascolta questa parte" | 134 | riga sua sotto il titolo |

Regole:
- **Etichette e pulsanti:** altezza minima, mai fissa; il testo va a capo tra le parole. Mai troncare un pulsante.
- **Con i puntini solo:** righe di elenco (titoli in Materiale, barra dell'indice, riassunto della barra ferma). Il
  titolo intero si vede aprendo.
- **Binari con i caratteri di sistema ingranditi** (Android oltre 1,3, PC zoom): i segmenti vanno in colonna, alti 48.
- **Tre esiti con i caratteri ingranditi:** "Non lo / sapevo" su due righe; la zona in basso cresce con loro.
- **Non contare sulla sillabazione:** niente `hyphens: auto` nelle etichette (sul PC con WebView2 l'italiano non è
  verificato). Testi scritti da Andrea e titoli del server: `overflow-wrap: anywhere`.
- **Tabelle:** 33.3. **Campi `Autore`/`Datazione`/`Luogo`:** valore su più righe, etichetta ferma in alto.

## 39. Visita guidata delle cose nuove

Stesso meccanismo della sezione 12 (dati di esempio, riflettore, una frase per passo, si avanza facendo l'azione).
Parte una volta sola, la prima volta dopo l'aggiornamento che porta queste pagine; si riapre da Impostazioni →
**Come funziona Lectio**, dopo quella di sempre. Materia d'esempio: Storia.

Passi (telefono):
1. **Schemi** (binario della materia, tocca Schemi): "Qui trovi gli schemi da leggere." Pratico.
2. **Nuovo schema** (tocca il pulsante): "Si crea come una lezione." Pratico.
3. **Come lo vuoi** (forma, livello, riepilogo): "Scegli forma e livello: qui vedi cosa esce e quanto costa." Avanti.
4. **Un'immagine utile** (spunto Tante immagini): "Vuoi più foto? Basta questo." Avanti.
5. **Indice** (barra di lettura, tocca): "Da qui salti a ogni parte." Pratico; il foglio si apre.
6. **Un paragrafo** (tocca il paragrafo d'esempio): "Toccalo per vedere cosa puoi fare." Pratico.
7. **Non ho capito** (tocca la pillola): "Te lo rispiego solo con i tuoi documenti." Pratico; risposta finta nel
   foglio.
8. **Da dove viene** (riga delle fonti): "Qui controlli da dove l'ho preso." Avanti.
9. **Ripasso lampo** (scheda in Oggi, tocca Inizia): "Poche schede al giorno, tre minuti." Pratico.
10. **Mostra la risposta** (tocca): "Prima prova a ricordare." Pratico.
11. **Tre parole** (i tre esiti, tocca Quasi): "Quasi: te ne ricordavi solo una parte." Pratico.
12. **Verifica simulata** (binario, tocca Simulate): "Una verifica come quelle del tuo prof, per allenarti." Pratico.
13. **Il tempo** (pillola del tempo, tocca): "Se ti agita, toccalo e sparisce." Pratico.
14. **La stima** (testata dei risultati): "È una stima. Il voto vero lo dà il prof." Avanti.
15. Fine: il velo si scioglie, "Ecco fatto", pulsante **Inizia**.

Sul PC gli stessi passi, con clic al posto dei tocchi; al passo 6 il pulsante è quello al margine destro del blocco;
ai passi 10-11 tasto disegnato (Spazio, poi 2).

## 40. Movimento e icone nuove

Movimento: solo trasformazioni e opacità, curva `cubic-bezier(.2, .8, .2, 1)` dove non è detto altro. Tutto tolto
con meno animazioni (Android: animazioni disattivate; PC: `prefers-reduced-motion`): i cambi diventano immediati e i
numeri non contano.

| Cosa | Durata |
|---|---|
| Foglio che sale, che si alza per la risposta | 300 ms, 250 ms |
| Riga degli strumenti, foto tolta | 200 ms, 220 ms |
| Risposta del Ripasso che compare, zona degli esiti | 220 ms, 200 ms |
| Scheda che esce, scheda che entra | 180 ms, 240 ms |
| Barra di avanzamento | 250 ms |
| Salto nell'indice | 300 ms |
| Numeri di fine ripasso | 350 ms |
| Domanda che si apre nei risultati | 240 ms (freccia 200) |
| Colore scelto (anteprima) | 200 ms |

Le uniche cose che pulsano sono i segnaposto (già in uso, ferme con meno animazioni).

Icone nuove, a tratto 2 px, stesso disegno su PC e Android (`viewBox 0 0 24 24`, percorsi liberi da Feather, licenza
MIT):

| Nome | Percorso |
|---|---|
| `lampo` | `M13 2 3 14h9l-1 8 10-12h-9z` |
| `domanda` | `M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20z M9.1 9a3 3 0 0 1 5.8 1c0 2-3 3-3 3 M12 17h.01` |
| `indice` | `M8 6h13 M8 12h13 M8 18h13 M3 6h.01 M3 12h.01 M3 18h.01` |
| `orologio` | `M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20z M12 6v6l4 2` |
| `confronta` | `M17 3l4 4-4 4 M3 7h18 M7 21l-4-4 4-4 M21 17H3` |
| `quasi` | `M5 12h14` |
| `riassunto` | `M21 6H3 M17 10H3 M21 14H3 M17 18H3` |

## 41. Riassunti scritti

Da uno schema pronto si ottiene un riassunto in tre forme. Campi e indirizzi: `docs/contratto-api.md` §26. Stessa
faccia della lettura dello schema.

### 41.1 Dove si entra

Solo dalla lettura dello schema, quando è `pronto`:
- **Testata.** PC: pillola **Riassunto** (Superficie 2, icona `riassunto`, stessa misura di Scarica PDF) a sinistra di
  Scarica PDF. Telefono: tondo 48 con icona `riassunto`, a sinistra del tondo di Scarica PDF.
- **In fondo allo schema**, sotto la scheda del Ripasso lampo: scheda bianca con "Riassunto" 19/800 e pulsante
  secondario **Fai il riassunto**.

Entrambi aprono il foglio di scelta. Se c'è già almeno un riassunto, la scritta del pulsante in fondo diventa **Apri
il riassunto** e porta alla pagina (41.3) sull'ultimo fatto.

### 41.2 Foglio di scelta

"Che riassunto vuoi?" 21/800, X sempre visibile. Tre righe una sotto l'altra, Superficie 2, raggio 16, alte almeno
88, tutte toccabili, 8 tra una e l'altra:

| Nome (17/700) | Frase (14/500, tenue) |
|---|---|
| **Per punti** | Una cosa per riga |
| **Discorsivo** | Da leggere di seguito |
| **Via di mezzo** | Un po' di testo e i punti da ricordare |

A sinistra di ogni riga una miniatura 56×56 (Fondo blocco, raggio 12) che mostra la forma, con linee grige alte 3 px:
Per punti = 4 righe corte, ognuna con un pallino nel colore della materia; Discorsivo = 5 righe piene, l'ultima corta;
Via di mezzo = 2 righe piene e sotto 2 righe corte con pallino.

A destra di ogni riga: il prezzo `riassunti.stima_eur` ("0,01 €", 13/700 tenue; in modalità prova "Prova"); se quella
forma esiste già al posto del prezzo "Pronto", "Lo sto scrivendo" oppure "Si è fermato".

Un tocco crea subito il riassunto (`POST`) e apre la sua pagina; se esiste già, la apre e basta. Se il tetto è
finito, il foglio resta aperto e sotto le righe compare la frase del server.

### 41.3 Pagina del riassunto

Una pagina vera, come la lettura dello schema, ma con una sola colonna centrata: scheda bianca, raggio 22, larga al
massimo 672, testo al massimo 580. Niente indice, niente "Non ho capito", niente segni di fonte.

**Testata.** Freccia indietro (torna allo schema), pillola della materia, a destra **Scarica PDF** (come 33.2) e menu
puntini con **Rifai** (conferma "Lo riscrivo da capo?", poi `DELETE` e `POST`) ed **Elimina** (con conferma). Sotto:
titolo (PC 30/800, telefono 26/800) e "480 parole · 3 min di lettura" 14/500 tenue (minuti = parole / 200, almeno 1).

**Le tre forme.** Sotto il titolo una barra a tre segmenti alta 48, a tutta larghezza della colonna: Per punti,
Discorsivo, Via di mezzo. Segmento attivo: fondo accento tenue, peso 800. Il cambio è una dissolvenza da 180 ms. Se si
tocca una forma che non esiste ancora, al posto del testo c'è una scheda Fondo blocco con pulsante principale
**Scrivilo · 0,01 €**.

**Il testo.** Per ogni parte: `titolo` come Titolo nel testo (19/800), `paragrafi` come Testo di lettura (16 tra un
paragrafo e l'altro), `punti` come il blocco `punti` di 33.3 (pallino 7 px del colore della materia, 8 tra le voci).
Se ci sono paragrafi e punti, 14 tra i due; 28 tra una parte e l'altra. `**parola**` diventa peso 700. Si può
selezionare e copiare.

### 41.4 Stati

- **In caricamento:** tre segnaposto che pulsano.
- **`in_coda` / `in_preparazione`:** la `frase` del server (o "Scrivo il riassunto") e tre segnaposto. Si può uscire;
  quando è finito arriva la notifica (`riassunto_pronto`, `riassunto_errore`).
- **`errore`:** la frase `errore`, **Riprova** e pulsante testo **Elimina**.
- **`nota`:** scheda con fondo avviso tenue sotto il titolo, come 33.7.
- **Scarica PDF:** come 33.9.
- **Senza rete:** un riassunto aperto almeno una volta resta sul dispositivo e si legge; Scarica PDF, Rifai, Elimina e
  le forme non ancora fatte spente, con la pillola "Senza rete" in alto. Se non è mai stato aperto: "Si apre quando torna
  la rete." e **Riprova**.
