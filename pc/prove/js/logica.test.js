// I test della logica pura dell'interfaccia (pc/ui/logica.js): date e soldi in italiano, fasi della preparazione,
// fermate alle domande (soglie, salti, velocità), tasto play nei due modi delle domande, "vince il più recente",
// stime e tetto. Si lanciano con: node --test pc/prove/js
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const L = require('../../ui/logica.js');

const D = { id: 32, tipo: 'blocco', n: 2, di: 6, testo: 'Perché Tiberio andò a Capri?', risposta: 'Non si fidava del senato.', inizio_s: 566.0, fine_domanda_s: 572.8, inizio_risposta_s: 575.3, fine_s: 589.0 };
const D2 = { id: 34, tipo: 'interrogazione', n: 4, di: 6, testo: 'Principato e monarchia?', risposta: '…', inizio_s: 925, fine_domanda_s: 931, inizio_risposta_s: 933.5, fine_s: 985 };

/** Suona da `da` a `a` a passi di `passo` secondi (come il lettore ogni 200 ms), e dà gli eventi. */
function suona(f, da, a, passo, velocita) {
  const eventi = [];
  for (let t = da; t <= a + 1e-9; t = Math.round((t + passo) * 1000) / 1000) {
    const e = f.osserva(t, velocita);
    if (e) { eventi.push(Object.assign({ t }, e)); if (e.tipo === 'domanda' || e.tipo === 'fine_risposta') break; }
  }
  return eventi;
}

// --- Testi, soldi, date ---------------------------------------------------------------------------

test('soldi: centesimi sempre, tetto tondo, costo zero senza decimali', () => {
  assert.equal(L.euro(0.25), '0,25 €');
  assert.equal(L.euro(0.8), '0,80 €');
  assert.equal(L.euroTondo(2), '2 €');
  assert.equal(L.euroTondo(0.5), '0,50 €');
  assert.equal(L.euroCosto(0), '0 €');
  assert.equal(L.euroCosto(0.02), '0,02 €');
  assert.equal(L.migliaia(412000), '412.000');
  assert.equal(L.etichettaPrepara(0.25), 'Prepara la lezione · 0,25 €');
  assert.equal(L.etichettaPrepara(0), 'Prepara la lezione · 0 €');
});

test('date in italiano: giorno della settimana, "tra 2 giorni", "Si azzera il 1° novembre"', () => {
  assert.equal(L.dataLunga('2026-10-29'), 'giovedì 29 ottobre');
  assert.equal(L.dataLunga('2026-10-27T18:00:00+01:00'), 'martedì 27 ottobre');
  assert.equal(L.quando(0), 'oggi');
  assert.equal(L.quando(1), 'domani');
  assert.equal(L.quando(2), 'tra 2 giorni');
  assert.equal(L.quando(-1), 'ieri');
  assert.equal(L.siAzzera('2026-11-01'), 'Si azzera il 1° novembre');
  assert.equal(L.giorniFra('2026-10-27', '2026-11-02'), 6);
});

test('tempi del lettore con cifre fisse', () => {
  assert.equal(L.tempo(462), '07:42');
  assert.equal(L.tempo(3723), '1:02:03');
  assert.equal(L.meno(725), '-12:05');
  assert.equal(L.durataBreve(301), '5:01');
  assert.equal(L.mancano(1150, 667), 'mancano 8 min');
});

test('velocità: 0,8 → 1,0 → 1,2 → 1,4 → 1,6 → 0,8', () => {
  const visti = [];
  let v = 0.8;
  for (let i = 0; i < 5; i++) { visti.push(L.etichettaVelocita(v)); v = L.prossimaVelocita(v); }
  assert.deepEqual(visti, ['0,8×', '1,0×', '1,2×', '1,4×', '1,6×']);
  assert.equal(v, 0.8);
  assert.equal(L.durataVicina(25), 20);
  assert.equal(L.durataVicina(60), 45);
});

test('fasi di "In preparazione": Registro la voce · 3 di 7, barra, coda, errore, pronta', () => {
  const f = L.fasi({ stato: 'in_preparazione', avanzamento: { fase: 'voce', fatto: 3, totale: 7 } });
  assert.deepEqual(f.righe.map((r) => r.stato), ['fatto', 'fatto', 'corso', 'dafare']);
  assert.equal(f.righe[2].nome, 'Registro la voce · 3 di 7');
  assert.equal(f.percentuale, Math.round((2 + 3 / 7) / 4 * 100));
  assert.equal(L.fasi({ stato: 'in_coda', avanzamento: {} }).inCoda, true);
  assert.equal(L.fasi({ stato: 'in_coda', avanzamento: {} }).percentuale, 0);
  assert.equal(L.fasi({ stato: 'in_preparazione', avanzamento: { fase: 'piano' } }).righe[0].stato, 'corso');
  const e = L.fasi({ stato: 'errore', avanzamento: { fase: 'scrittura' } });
  assert.deepEqual(e.righe.map((r) => r.stato), ['fatto', 'errore', 'dafare', 'dafare']);
  assert.equal(L.fasi({ stato: 'pronta', avanzamento: {} }).percentuale, 100);
});

test('stato delle lezioni in elenco: a metà con la barra, finita, in preparazione', () => {
  assert.equal(L.statoLezione({ stato: 'pronta', durata_s: 1150, ascolto: { posizione_s: 667, finita: false } }).frase, 'mancano 8 min');
  assert.equal(L.statoLezione({ stato: 'pronta', durata_s: 1150, ascolto: { posizione_s: 1150, finita: true } }).tipo, 'finita');
  assert.equal(L.statoLezione({ stato: 'in_preparazione', avanzamento: { frase: 'Registro la voce · 3 di 7' } }).frase, 'Registro la voce · 3 di 7');
  assert.equal(L.statoLezione({ stato: 'pronta', durata_s: 1150, ascolto: { posizione_s: 0 } }).tipo, 'pronta');
});

test('capitoli: fatto, in corso, da fare', () => {
  const caps = [{ inizio_s: 0, fine_s: 301 }, { inizio_s: 301, fine_s: 589 }, { inizio_s: 589, fine_s: 901 }];
  assert.deepEqual(L.capitoliStato(caps, 462), ['fatto', 'corso', 'dafare']);
  assert.deepEqual(L.capitoliStato(caps, 0), ['corso', 'dafare', 'dafare']);
  assert.deepEqual(L.capitoliStato(caps, 901), ['fatto', 'fatto', 'corso']);
  assert.equal(L.capitoloA(caps, 600), 2);
});

test('etichette delle domande', () => {
  assert.equal(L.etichettaDomanda(D), 'DOMANDA DEL BLOCCO · 2 DI 6');
  assert.equal(L.etichettaDomanda(D2), 'DOMANDA 4 DI 6');
});

// --- Fermate alle domande ---------------------------------------------------------------------

test('fermata: ascoltando di fila si ferma appena passata la fine della domanda', () => {
  const f = new L.Fermate([D, D2]);
  f.salto(560);
  const ev = suona(f, 560, 600, 0.2, 1);
  assert.equal(ev.length, 1);
  assert.equal(ev[0].tipo, 'domanda');
  assert.equal(ev[0].domanda.id, 32);
  assert.ok(ev[0].t >= 572.8 && ev[0].t < 573.1, 'si ferma subito dopo 572,8: ' + ev[0].t);
  assert.equal(f.stato, 'domanda');
});

test('fermata: un salto oltre la domanda non la fa scattare (barra, capitolo, +30)', () => {
  const f = new L.Fermate([D]);
  f.salto(500);
  f.osserva(500, 1);
  f.salto(600); // salto in avanti
  assert.equal(f.osserva(600, 1), null);
  assert.equal(f.osserva(600.2, 1), null);
  assert.equal(f.stato, 'ascolto');
  // Un salto "nascosto" (l'audio va avanti di molto tra due osservazioni) non conta come ascolto di fila.
  const g = new L.Fermate([D]);
  g.osserva(560, 1);
  assert.equal(g.osserva(590, 1), null);
});

test('fermata: con la velocità a 1,6 si ferma lo stesso (passi più lunghi), ma non per un salto', () => {
  const f = new L.Fermate([D]);
  f.salto(565);
  const ev = suona(f, 565, 580, 0.2 * 1.6, 1.6);
  assert.equal(ev.length, 1);
  assert.equal(ev[0].tipo, 'domanda');
  assert.ok(L.Fermate.limite(1.6) > 0.2 * 1.6 && L.Fermate.limite(1.6) < 5);
  const g = new L.Fermate([D]);
  g.osserva(570, 1.6);
  assert.equal(g.osserva(570 + L.Fermate.limite(1.6) + 0.5, 1.6), null);
});

test('fermata: ferma se si parte poco prima della fine della domanda, non se si parte nel silenzio', () => {
  const f = new L.Fermate([D]);
  f.salto(572.5);
  assert.equal(suona(f, 572.5, 574, 0.2, 1).length, 1);
  const g = new L.Fermate([D]);
  g.salto(573.5); // nel silenzio di 2,5 s dopo la domanda: niente fermata
  assert.equal(suona(g, 573.5, 590, 0.2, 1).length, 0);
});

test('risposta: Invia → correzione → la risposta parte da inizio_risposta_s e alla fine ci si ferma (Avanti)', () => {
  const f = new L.Fermate([D]);
  f.salto(570);
  suona(f, 570, 574, 0.2, 1);
  assert.equal(f.stato, 'domanda');
  assert.equal(f.inCorrezione(), true);
  assert.equal(f.stato, 'correzione');
  const da = f.sentiRisposta();
  assert.equal(da, 575.3);
  assert.equal(f.stato, 'risposta');
  const ev = suona(f, 575.3, 595, 0.2, 1);
  assert.equal(ev.length, 1);
  assert.equal(ev[0].tipo, 'fine_risposta');
  assert.ok(ev[0].t >= 589);
  assert.equal(f.stato, 'dopo');
  f.avanti();
  assert.equal(f.stato, 'ascolto');
  // Dopo Avanti la stessa domanda non riscatta (si è già oltre).
  assert.equal(suona(f, 589.2, 600, 0.2, 1).length, 0);
});

test('Ripeti la domanda: torna a inizio_s e la fermata scatta di nuovo', () => {
  const f = new L.Fermate([D]);
  f.salto(570);
  suona(f, 570, 574, 0.2, 1);
  const t = f.ripetiDomanda();
  assert.equal(t, 566.0);
  assert.equal(f.stato, 'ascolto');
  const ev = suona(f, 566.0, 580, 0.2, 1);
  assert.equal(ev.length, 1);
  assert.equal(ev[0].domanda.id, 32);
});

test('Salta: la risposta parte subito, senza correzione', () => {
  const f = new L.Fermate([D]);
  f.apri(D);
  assert.equal(f.stato, 'domanda');
  assert.equal(f.sentiRisposta(), 575.3);
  assert.equal(f.stato, 'risposta');
});

test('fermate spente ("Fermati alle domande" spento): l\'audio non si ferma mai', () => {
  const f = new L.Fermate([D, D2], { attive: false });
  f.salto(560);
  assert.equal(suona(f, 560, 1000, 0.25, 1).length, 0);
});

test('un salto durante la scheda la chiude; dentro la risposta no', () => {
  const f = new L.Fermate([D]);
  f.apri(D);
  f.salto(100);
  assert.equal(f.stato, 'ascolto');
  const g = new L.Fermate([D]);
  g.apri(D);
  g.sentiRisposta();
  g.salto(580); // -15/+30 dentro la risposta
  assert.equal(g.stato, 'risposta');
});

// --- Tasto play nei due modi delle domande (§15.4) -------------------------------------------------

test('tasto play: mentre si scrive nel campo non fa niente', () => {
  for (const saltabili of [true, false]) {
    assert.equal(L.azioneTastoPlay({ stato: 'domanda', scrivendo: true, finestraAttiva: true, saltabili }), 'niente');
    assert.equal(L.azioneTastoPlay({ stato: 'ascolto', scrivendo: true, finestraAttiva: true, saltabili }), 'niente');
  }
});

test('tasto play alla domanda con la finestra davanti: porta il cursore nel campo', () => {
  assert.equal(L.azioneTastoPlay({ stato: 'domanda', scrivendo: false, finestraAttiva: true, saltabili: true }), 'fuoco');
  assert.equal(L.azioneTastoPlay({ stato: 'domanda', scrivendo: false, finestraAttiva: true, saltabili: false }), 'fuoco');
});

test('tasto play alla domanda a finestra dietro: saltabili = Salta, obbligatorie = ripete la domanda', () => {
  assert.equal(L.azioneTastoPlay({ stato: 'domanda', finestraAttiva: false, saltabili: true }), 'salta');
  assert.equal(L.azioneTastoPlay({ stato: 'domanda', finestraAttiva: false, saltabili: false }), 'ripeti');
});

test('tasto play con "Risposte scritte" spento: Senti la risposta; durante la correzione niente; dopo: Avanti', () => {
  assert.equal(L.azioneTastoPlay({ stato: 'domanda', risposteScritte: false, finestraAttiva: false, saltabili: false }), 'senti');
  assert.equal(L.azioneTastoPlay({ stato: 'correzione', finestraAttiva: false }), 'niente');
  assert.equal(L.azioneTastoPlay({ stato: 'dopo', finestraAttiva: true }), 'avanti');
  assert.equal(L.azioneTastoPlay({ stato: 'ascolto', finestraAttiva: true }), 'playpausa');
  assert.equal(L.azioneTastoPlay({ stato: 'risposta', finestraAttiva: false }), 'playpausa');
});

test('il salto alla domanda: "Salta" normale o "Salta comunque" con conferma in linea', () => {
  assert.deepEqual(L.modoSalto(true), { etichetta: 'Salta', conferma: false });
  const o = L.modoSalto(false);
  assert.equal(o.etichetta, 'Salta comunque');
  assert.equal(o.conferma, true);
  assert.equal(o.domanda, 'Saltarla? Finisce in Da ripassare.');
  assert.equal(o.si, 'Sì, salta');
  assert.equal(o.no, 'Rispondo');
});

test('esiti della correzione: parole e colori, mai rosso', () => {
  assert.deepEqual(L.esitoRisposta('giusta'), { parola: 'Giusta', classe: 'giusta' });
  assert.deepEqual(L.esitoRisposta('parziale'), { parola: 'Quasi', classe: 'quasi' });
  assert.deepEqual(L.esitoRisposta('sbagliata'), { parola: 'Da rivedere', classe: 'rivedere' });
  for (const e of ['giusta', 'parziale', 'sbagliata', 'saltata', 'non_corretta']) assert.ok(!/errore|rosso/.test(L.esitoRisposta(e).classe));
  assert.equal(L.rispostaValida('  '), false);
  assert.equal(L.rispostaValida('Seiano'), true);
  assert.equal(L.rispostaValida('x'.repeat(2001)), false);
});

// --- Vince il più recente ------------------------------------------------------------------------

test('vince il più recente: tra server e computer conta aggiornato_il', () => {
  const server = { posizione_s: 100, finita: false, aggiornato_il: '2026-10-27T10:00:00+01:00' };
  const piuNuovo = { posizione_s: 300, finita: false, aggiornato_il: '2026-10-27T10:05:00+01:00' };
  const piuVecchio = { posizione_s: 50, finita: false, aggiornato_il: '2026-10-27T09:00:00+01:00' };
  assert.equal(L.piuRecente(server, piuNuovo), piuNuovo);
  assert.equal(L.piuRecente(server, piuVecchio), server);
  assert.equal(L.piuRecente(null, piuVecchio), piuVecchio);
  assert.equal(L.piuRecente(server, null), server);
  // A pari data vale il server.
  assert.equal(L.piuRecente(server, Object.assign({}, piuNuovo, { aggiornato_il: server.aggiornato_il })), server);
  // Da dove si riparte: finita → dall'inizio; quasi alla fine → dall'inizio.
  assert.equal(L.puntoDiPartenza({ posizione_s: 300, finita: false }, 1187), 300);
  assert.equal(L.puntoDiPartenza({ posizione_s: 1187, finita: true }, 1187), 0);
  assert.equal(L.puntoDiPartenza({ posizione_s: 1186, finita: false }, 1187), 0);
});

// --- Stime e tetto ---------------------------------------------------------------------------------

const VOCI = [
  { id: 'mistral:voice_482', nome: 'Lorenzo', tipo: 'espressiva', disponibile: true },
  { id: 'azure:it-IT-IsabellaMultilingualNeural', nome: 'Isabella', tipo: 'gratis', disponibile: true },
];
const stimaE = (consentita) => ({ costo_testo_eur: 0.02, costo_voce_eur: 0.23, costo_eur: 0.25, spesa_mese_eur: 0.8, tetto_eur: 2, dopo_eur: 1.05, consentita, motivo: consentita ? null : 'Sforerebbe il tetto di 2 €: usa la voce gratis', voce_gratis_caratteri_rimasti: 412000 });
const stimaG = { costo_testo_eur: 0.02, costo_voce_eur: 0, costo_eur: 0.02, spesa_mese_eur: 0.8, tetto_eur: 2, dopo_eur: 0.82, consentita: true, motivo: null, voce_gratis_caratteri_rimasti: 412000 };

test('voci: Lorenzo col costo della voce, gratis a 0 € coi caratteri rimasti', () => {
  const s = L.statoVoci(VOCI, { espressiva: stimaE(true), gratis: stimaG }, 2);
  assert.equal(s.espressiva.costoVoce, 0.23);
  assert.equal(s.espressiva.costo, 0.25);
  assert.equal(s.espressiva.spenta, false);
  assert.equal(s.gratis.costo, 0.02);
  assert.equal(s.gratis.caratteri, 412000);
  assert.equal(L.sceltaVoce('espressiva', s), 'espressiva');
  assert.equal(L.sceltaVoce('gratis', s), 'gratis');
});

test('tetto: la voce espressiva che sforerebbe è spenta con la frase, e si sceglie la gratis', () => {
  const s = L.statoVoci(VOCI, { espressiva: stimaE(false), gratis: stimaG }, 2);
  assert.equal(s.espressiva.spenta, true);
  assert.equal(s.espressiva.motivo, 'Sforerebbe il tetto di 2 €');
  assert.equal(L.sceltaVoce('espressiva', s), 'gratis');
  // Anche la gratis oltre il tetto: nessuna voce, il pulsante resta spento.
  const t = L.statoVoci(VOCI, { espressiva: stimaE(false), gratis: Object.assign({}, stimaG, { consentita: false, motivo: 'Hai finito i soldi del mese.' }) }, 2);
  assert.equal(L.sceltaVoce('gratis', t), null);
  assert.equal(t.gratis.motivo, 'Hai finito i soldi del mese.');
});

test('modalità prova: solo la voce di prova, a 0', () => {
  const s = L.statoVoci(VOCI.concat([{ id: 'prova:espeak', nome: 'Voce di prova', tipo: 'prova', disponibile: true }]), {}, 2);
  assert.ok(s.prova);
  assert.equal(s.prova.costo, 0);
  assert.equal(L.sceltaVoce('espressiva', s), 'prova');
});

test('barra del mese a due toni: speso e questa lezione, mai oltre il 100%', () => {
  assert.deepEqual(L.barraMese(0.8, 0.25, 2), { speso: 40, questa: 13 });
  assert.deepEqual(L.barraMese(1.85, 0.25, 2), { speso: 93, questa: 7 });
  assert.equal(L.spesaInAvviso({ speso_eur: 1.6, tetto_eur: 2 }), true);
  assert.equal(L.spesaInAvviso({ speso_eur: 1.5, tetto_eur: 2 }), false);
});

test('vince la più recente anche per le stime: la risposta di una richiesta vecchia si butta', () => {
  const seq = L.creaSequenza();
  const prima = seq.nuova();
  const seconda = seq.nuova();
  assert.equal(seq.valida(prima), false);
  assert.equal(seq.valida(seconda), true);
});

// --- File, materie, rotte ---------------------------------------------------------------------------

test('file: formati letti (anche le foto HEIC), file troppo grandi rifiutati con la frase', () => {
  assert.equal(L.controllaFile('verifica.pdf', 1000), null);
  assert.equal(L.controllaFile('foto.JPG', 1000), null);
  assert.equal(L.controllaFile('IMG_0042.HEIC', 1000), null);
  assert.equal(L.controllaFile('foto.heif', 1000), null);
  assert.ok(L.eImmagine('IMG_0042.HEIC'), 'conta come foto (costo OCR)');
  assert.match(L.controllaFile('video.mp4', 1000), /non lo leggo/);
  assert.match(L.controllaFile('grande.pdf', 21 * 1024 * 1024), /20 MB/);
});

test('materie: primo colore libero, sottotitolo, "Basata su"', () => {
  assert.equal(L.primoColoreLibero([{ colore: 'ambra' }, { colore: 'rosa' }]), 'petrolio');
  assert.equal(L.primoColoreLibero([]), 'ambra');
  assert.equal(L.sottoMateria({ prof: 'Rossi', conteggi: { verifiche: 4 }, centratura: { percentuale: 63 } }, true), 'Prof. Rossi · 4 verifiche lette · centratura 63%');
  assert.equal(L.prof('Prof.ssa Bianchi'), 'Prof.ssa Bianchi');
  assert.equal(L.basataSu({ verifiche: 4, interrogazioni: 1 }), 'Basata su 4 verifiche e 1 interrogazione');
  assert.equal(L.codiceValido('1234 5678'), true);
  assert.equal(L.codiceValido('1234567'), false);
});

test('rotte della pagina', () => {
  assert.deepEqual(L.leggiRotta('#materia/3'), { vista: 'materia', id: 3 });
  assert.deepEqual(L.leggiRotta('#materia/3/scheda'), { vista: 'scheda', id: 3 });
  assert.deepEqual(L.leggiRotta('#lettore/12'), { vista: 'lettore', id: 12 });
  assert.deepEqual(L.leggiRotta('#lezione/12'), { vista: 'lezione', id: 12 });
  assert.deepEqual(L.leggiRotta('#qualcosa'), { vista: 'oggi' });
  assert.deepEqual(L.leggiRotta('#lettore/abc'), { vista: 'lezioni' });
});

// --- Dopo la revisione ------------------------------------------------------------------------------

test('domanda aperta: un punto tra inizio_s e inizio_risposta_s cade dentro la domanda (si riapre alla ripresa)', () => {
  assert.equal(L.domandaAperta([D, D2], 566.0).id, 32);
  assert.equal(L.domandaAperta([D, D2], 572.9).id, 32);
  assert.equal(L.domandaAperta([D, D2], 575.3), null); // dalla risposta in poi no
  assert.equal(L.domandaAperta([D, D2], 500), null);
  assert.equal(L.domandaAperta([D, D2], 930).id, 34);
});

test('materiale spuntato: in automatico i suggeriti (tutto se il server non sa suggerire), poi la scelta di Andrea; solo pronti', () => {
  const m = [{ id: 1, tipo: 'verifica', stato: 'pronto' }, { id: 2, tipo: 'verifica', stato: 'in_lettura' }, { id: 3, tipo: 'appunti', stato: 'pronto' },
    { id: 4, tipo: 'appunti', stato: 'pronto' }, { id: 5, tipo: 'appunti', stato: 'in_lettura' }, { id: 6, tipo: 'interrogazione' }, { id: 7, tipo: 'appunti', stato: 'errore' }];
  assert.deepEqual(L.materialiSpuntati(m, true, [4, 2, 1], []), [1, 4], 'suggeriti, ma solo quelli pronti, nell\'ordine dell\'elenco');
  assert.deepEqual(L.materialiSpuntati(m, true, null, []), [1, 3, 4, 6], 'il server non sa suggerire: tutto il pronto');
  assert.deepEqual(L.materialiSpuntati(m, true, [], [3]), [], 'argomento vuoto: niente');
  assert.deepEqual(L.materialiSpuntati(m, false, [4], [3, 5, 7]), [3], 'la scelta di Andrea vale sui suggeriti; in lettura e in errore no');
  const g = L.gruppiMateriale(m);
  assert.deepEqual(g.studio.map((x) => x.id), [3, 4, 5], 'in errore non compare');
  assert.deepEqual(g.prof.map((x) => x.id), [1, 2, 6]);
  assert.deepEqual(L.filtraPerTitolo([{ titolo: 'Città e università' }, { titolo: 'Augusto' }], 'CITTA').map((x) => x.titolo), ['Città e università']);
  assert.equal(L.sottoMateriale({ tipo: 'appunti', origine: 'foto', data: '2026-11-04' }), 'Appunti · foto · 4 novembre');
  assert.equal(L.sottoMateriale({ tipo: 'verifica', origine: 'file', data: '2026-10-12' }), 'Verifica · file · 12 ottobre');
});

test('struttura della lezione in parole (contratto §16.1), per i tre tipi e le quattro durate', () => {
  assert.equal(L.struttura('spiegazione', 15), '3 parti, una domanda dopo ognuna e 4 alla fine');
  assert.equal(L.struttura('spiegazione', 20), '4 parti, una domanda dopo ognuna e 5 alla fine');
  assert.equal(L.struttura('spiegazione', 30), '6 parti, una domanda dopo ognuna e 6 alla fine');
  assert.equal(L.struttura('spiegazione', 45), '9 parti, una domanda dopo ognuna e 8 alla fine');
  assert.deepEqual([15, 20, 30, 45].map((d) => L.struttura('ripasso', d)),
    ['I punti chiave in breve e 6 domande alla fine', 'I punti chiave in breve e 8 domande alla fine', 'I punti chiave in breve e 10 domande alla fine', 'I punti chiave in breve e 13 domande alla fine']);
  assert.deepEqual([15, 20, 30, 45].map((d) => L.struttura('interrogazione', d).split(' ')[0]), ['9', '12', '18', '27']);
  assert.equal(L.struttura('interrogazione', 20), '12 domande come le fa il prof, con la risposta giusta dopo ognuna');
  // Fuori tabella: 3 + durata/10 domande alla fine.
  assert.equal(L.finaliSpiegazione(60), 9);
  assert.equal(L.struttura('spiegazione', 60), '12 parti, una domanda dopo ognuna e 9 alla fine');
  assert.equal(L.titoloLezione('ripasso', 30), 'Ripasso di 30 minuti');
  assert.equal(L.titoloLezione(undefined, 20), 'Spiegazione di 20 minuti');
});

test('spunti: aggiungono la frase in fondo e la tolgono; mai una frase a metà oltre i 600 caratteri', () => {
  const s1 = L.SPUNTI[1].frase;
  assert.equal(s1, 'Insisti su date e nomi.');
  let r = L.alternaSpunto('', s1);
  assert.deepEqual(r, { testo: 'Insisti su date e nomi.', ok: true });
  r = L.alternaSpunto('Parla di Seiano.  ', s1);
  assert.equal(r.testo, 'Parla di Seiano. Insisti su date e nomi.');
  r = L.alternaSpunto(r.testo, s1);
  assert.equal(r.testo, 'Parla di Seiano.');
  r = L.alternaSpunto('Prima. ' + s1 + ' Dopo.', s1);
  assert.equal(r.testo, 'Prima. Dopo.');
  r = L.alternaSpunto('x'.repeat(590), s1);
  assert.equal(r.ok, false);
  assert.equal(r.testo.length, 590);
  assert.equal(L.SPUNTI.length, 6);
  assert.ok(L.SPUNTI.every((x) => !/!/.test(x.frase + x.etichetta)));
});

test('riepilogo: materiale, indicazioni, per', () => {
  assert.equal(L.riepilogoMateriale([]), 'Scegli almeno un materiale');
  assert.equal(L.riepilogoMateriale(['Mirone e il Discobolo']), 'Mirone e il Discobolo');
  assert.equal(L.riepilogoMateriale(['A', 'B']), '2 materiali: A e B');
  assert.equal(L.riepilogoMateriale(['A', 'B', 'C']), '3 materiali: A, B e un altro');
  assert.equal(L.riepilogoMateriale(['A', 'B', 'C', 'D', 'E']), '5 materiali: A, B e altri 3');
  assert.equal(L.riepilogoIndicazioni('   '), 'Nessuna: segue il materiale e la scheda del prof');
  assert.equal(L.riepilogoIndicazioni('a'.repeat(200)), 'a'.repeat(140) + '…');
  assert.equal(L.riepilogoPer(null, false), 'Nessuna data · domande obbligatorie');
  assert.equal(L.riepilogoPer({ tipo: 'orale', data: '2026-11-10' }, true), 'L\'interrogazione di martedì 10 novembre · domande che si possono saltare');
  assert.equal(L.etichettaEvento({ tipo: 'scritta', data: '2026-10-29' }), 'Verifica di giovedì 29 ottobre');
});

test('un materiale: famiglia del tipo, nota sotto il titolo, solo i campi cambiati', () => {
  assert.equal(L.famigliaDi('libro').nome, 'Da studiare');
  assert.equal(L.famigliaDi('interrogazione').nome, 'Già corretta dal prof');
  assert.equal(L.famigliaDi('altro').nome, 'Da studiare');
  assert.equal(L.notaTitolo({ titolo_da: 'lectio', origine: 'foto', nome_file: 'IMG_1.jpg' }), 'Dalla foto IMG_1.jpg · titolo scritto da Lectio leggendo la foto');
  assert.equal(L.notaTitolo({ titolo_da: 'lectio', origine: 'file', nome_file: 'cap9.pdf' }), 'Dal file cap9.pdf · titolo scritto da Lectio leggendo il file');
  assert.equal(L.notaTitolo({ titolo_da: 'file', origine: 'file', nome_file: 'cap9.pdf' }), 'Dal file cap9.pdf');
  assert.equal(L.notaTitolo({ titolo_da: 'utente', origine: 'file', nome_file: 'cap9.pdf' }), null);
  assert.equal(L.notaTitolo({ titolo: 'vecchio' }), null, 'un server vecchio senza titolo_da: niente nota');
  const x = { titolo: 'Caligola e Claudio', tipo: 'appunti' };
  assert.deepEqual(L.campiCambiati(x, { titolo: ' Caligola  e Claudio ', tipo: 'appunti' }), {});
  assert.deepEqual(L.campiCambiati(x, { titolo: 'Caligola e Claudio', tipo: 'libro' }), { tipo: 'libro' });
  assert.deepEqual(L.campiCambiati(x, { titolo: 'Caligola, Claudio ', tipo: 'appunti' }), { titolo: 'Caligola, Claudio' });
});

test('scheda del prof: esempio mai null, volte tenute; limiti del server con la frase del campo', () => {
  const sc = { argomenti: [{ nome: 'Date chiave', volte: 3, esempio: null }], tipi_domanda: [{ tipo: 'aperta', quota: 1 }] };
  const c = L.schedaDaMandare(sc, { argomenti: 'Date chiave\nNuovo argomento', corregge: 'a\n\nb', premia: '', lunghezza: ' 5 righe ', consigli: '', note: 'mie' });
  assert.deepEqual(c.argomenti, [{ nome: 'Date chiave', volte: 3, esempio: '' }, { nome: 'Nuovo argomento', volte: 0, esempio: '' }]);
  assert.deepEqual(c.cosa_corregge, ['a', 'b']);
  assert.equal(c.lunghezza_risposte, '5 righe');
  assert.equal(L.controllaScheda(c), null);
  assert.match(L.controllaScheda(Object.assign({}, c, { argomenti: [{ nome: 'x'.repeat(81), volte: 0, esempio: '' }] })), /80 caratteri/);
  assert.match(L.controllaScheda(Object.assign({}, c, { consigli: ['y'.repeat(201)] })), /Consigli: ogni riga al massimo 200/);
  assert.match(L.controllaScheda(Object.assign({}, c, { cosa_premia: Array(21).fill('z') })), /Cosa premia: al massimo 20 righe/);
  assert.match(L.controllaScheda(Object.assign({}, c, { note_mie: 'n'.repeat(5001) })), /5\.000 caratteri/);
});

// --- Cartelle (§42, contratto §27) ------------------------------------------------------------------

test('cartelle: rotta della pagina dentro la materia, conteggi e frasi in italiano', () => {
  assert.deepEqual(L.leggiRotta('#materia/1/cartella/3'), { vista: 'cartella', id: 1, cartella: 3 });
  assert.deepEqual(L.leggiRotta('#materia/1/cartella/abc'), { vista: 'materia', id: 1 });
  assert.equal(L.contaFile(0), 'Vuota');
  assert.equal(L.contaFile(1), '1 file');
  assert.equal(L.contaFile(8), '8 file');
  assert.equal(L.messiDaLectio(0), '');
  assert.equal(L.messiDaLectio(1), '1 messo ora da Lectio');
  assert.equal(L.messiDaLectio(2), '2 messi ora da Lectio');
  assert.equal(L.fraseMettiQui(1), 'Metti qui 1 file');
  assert.equal(L.fraseMettiQui(3), 'Metti qui 3 file');
  assert.equal(L.fraseFattoMetti(3, 'Arte greca'), 'Fatto: 3 file in Arte greca');
  // L'articolo si sceglie da come si legge il numero: "gli otto", "gli undici", "i tre".
  assert.equal(L.fraseEliminaConFile(8), 'Elimina anche gli 8 file');
  assert.equal(L.fraseEliminaConFile(11), 'Elimina anche gli 11 file');
  assert.equal(L.fraseEliminaConFile(80), 'Elimina anche gli 80 file');
  assert.equal(L.fraseEliminaConFile(3), 'Elimina anche i 3 file');
  assert.equal(L.fraseEliminaConFile(1), 'Elimina anche il file');
  assert.equal(L.fraseDentro(8), 'Dentro ci sono 8 file.');
  assert.equal(L.fraseDentro(1), 'Dentro c\'è 1 file.');
  assert.equal(L.fraseCartellaEliminata(0), 'Cartella eliminata');
  assert.equal(L.fraseCartellaEliminata(8), 'Cartella e 8 file eliminati');
  assert.equal(L.pulisciNomeCartella('  Arte   greca '), 'Arte greca');
});

test('cartelle: fuori e dentro, i due gruppi di "Metti dei file qui" e la loro riga', () => {
  const cartelle = [{ id: 3, nome: 'Arte greca' }, { id: 4, nome: 'Arte romana' }];
  const lista = [
    { id: 1, tipo: 'appunti', titolo: 'Mirone', data: '2026-11-04', cartella_id: null },
    { id: 2, tipo: 'libro', titolo: 'Fidia', cartella_id: 3 },
    { id: 3, tipo: 'appunti', titolo: 'Il Colosseo', cartella_id: 4 },
    { id: 4, tipo: 'verifica', titolo: 'Verifica', data: '2026-10-12' },
  ];
  assert.deepEqual(L.materialeFuori(lista).map((x) => x.id), [1, 4]);
  assert.deepEqual(L.materialeDentro(lista, 3).map((x) => x.id), [2]);
  const g = L.gruppiMetti(lista, 3);
  assert.deepEqual(g.fuori.map((x) => x.id), [1, 4]);
  assert.deepEqual(g.altre.map((x) => x.id), [3]);
  assert.equal(L.sottoMetti(lista[0], cartelle), 'Appunti · 4 novembre');
  assert.equal(L.sottoMetti(lista[2], cartelle), 'In Arte romana · Appunti');
  // Nelle righe della scelta il nome della cartella va dopo il tipo, e la ricerca lo trova.
  assert.equal(L.sottoMateriale({ tipo: 'appunti', origine: 'foto', data: '2026-11-04', cartella_id: 3 }, cartelle), 'Appunti · Arte greca · foto · 4 novembre');
  assert.equal(L.sottoMateriale({ tipo: 'appunti', origine: 'foto', data: '2026-11-04', cartella_id: null }, cartelle), 'Appunti · foto · 4 novembre');
  assert.deepEqual(L.filtraPerTitolo(lista, 'romana', cartelle).map((x) => x.id), [3]);
  assert.deepEqual(L.filtraPerTitolo(lista, 'romana').map((x) => x.id), [], 'senza cartelle si cerca solo nel titolo');
});

test('cartelle: le pillole della scelta del materiale spuntano o tolgono tutto il materiale pronto della cartella', () => {
  const cartelle = [{ id: 3, nome: 'Arte greca' }, { id: 4, nome: 'Arte romana' }, { id: 5, nome: 'Vuota' }];
  const materiale = [
    { id: 1, stato: 'pronto', cartella_id: 3 },
    { id: 2, stato: 'pronto', cartella_id: 3 },
    { id: 3, stato: 'in_lettura', cartella_id: 3 },
    { id: 4, stato: 'in_lettura', cartella_id: 4 },
    { id: 5, stato: 'pronto', cartella_id: null },
  ];
  // Solo le cartelle con materiale pronto, e solo quello pronto.
  assert.deepEqual(L.cartelleConPronti(materiale, cartelle), [{ id: 3, nome: 'Arte greca', ids: [1, 2] }]);
  assert.equal(L.cartellaTuttaScelta([1], [1, 2]), false);
  assert.equal(L.cartellaTuttaScelta([5, 2, 1], [1, 2]), true);
  assert.deepEqual(L.alternaCartella([5, 1], [1, 2]), [5, 1, 2]);
  assert.deepEqual(L.alternaCartella([5, 1, 2], [1, 2]), [5]);
});

test('un materiale: la cartella si manda solo se è cambiata, e null (fuori) si manda davvero', () => {
  const x = { titolo: 'Fidia', tipo: 'appunti', cartella_id: 3 };
  assert.deepEqual(L.campiCambiati(x, { titolo: 'Fidia', tipo: 'appunti', cartella_id: 3 }), {});
  assert.deepEqual(L.campiCambiati(x, { titolo: 'Fidia', tipo: 'appunti', cartella_id: null }), { cartella_id: null });
  assert.deepEqual(L.campiCambiati(x, { titolo: 'Fidia', tipo: 'appunti', cartella_id: 4 }), { cartella_id: 4 });
  assert.deepEqual(L.campiCambiati({ titolo: 'Fidia', tipo: 'appunti' }, { titolo: 'Fidia', tipo: 'appunti', cartella_id: null }), {}, 'un server vecchio senza cartella_id: niente');
  assert.deepEqual(L.campiCambiati(x, { titolo: 'Fidia', tipo: 'appunti' }), {}, 'senza il campo non cambia niente');
});

// --- Il pulsante Nuovo e "Cosa vuoi fare?" (1.3.0, guida §43) ------------------------------------------------

test('Cosa vuoi fare?: cinque righe nell\'ordine giusto, con nome, frase e icona', () => {
  const r = L.righeNuovo({ lampo: 5, pronti: 2, senzaRete: false });
  assert.deepEqual(r.map((x) => x.scelta), ['lezione', 'schema', 'riassunto', 'simulata', 'lampo']);
  assert.deepEqual(r.map((x) => x.nome), ['Lezione da ascoltare', 'Schema', 'Riassunto', 'Verifica simulata', 'Ripasso lampo']);
  assert.deepEqual(r.map((x) => x.frase), [
    'Te la spiego a voce, con le domande lungo la strada',
    'Da leggere: i punti chiave, con le immagini',
    'Da uno schema già pronto: per punti o discorsivo',
    'Come quella del prof, poi la correggo',
    '5 schede · circa 2 min',
  ]);
  assert.deepEqual(r.map((x) => x.icona), ['lezioni', 'testo', 'riassunto', 'verifica', 'lampo']);
  assert.ok(r.every((x) => !x.spenta));
  assert.equal(L.righeNuovo({ lampo: 1, pronti: 1 })[4].frase, '1 scheda · circa 1 min');
  for (const x of r) assert.doesNotMatch(x.nome + x.frase, /!/);
});

test('Cosa vuoi fare?: righe spente con il perché nella frase', () => {
  const vuote = L.righeNuovo({ lampo: 0, pronti: 0, senzaRete: false });
  assert.deepEqual(vuote.filter((x) => x.spenta).map((x) => [x.scelta, x.frase]), [['riassunto', 'Prima serve uno schema pronto'], ['lampo', 'Niente per oggi']]);
  // Non si sa ancora quanti schemi ci sono: Riassunto aspetta spento, con la sua frase.
  const attesa = L.righeNuovo({ lampo: 3, pronti: null })[2];
  assert.equal(attesa.spenta, true);
  assert.equal(attesa.frase, 'Da uno schema già pronto: per punti o discorsivo');
  // Senza rete sul PC (nessuna scheda salvata, §35.5) tutto spento, anche il Ripasso lampo.
  const giu = L.righeNuovo({ lampo: 5, pronti: 2, senzaRete: true });
  assert.ok(giu.every((x) => x.spenta && x.frase === 'Serve la rete'));
  // Con schede salvate (telefono) il Ripasso lampo resta acceso.
  const salvate = L.righeNuovo({ lampo: 5, pronti: 2, senzaRete: true, schedeSalvate: true });
  assert.deepEqual(salvate.filter((x) => !x.spenta).map((x) => x.scelta), ['lampo']);
});

test('Nuovo è spento solo se nel foglio non si può toccare niente', () => {
  assert.equal(L.nuovoSpento({ senzaRete: false }), false);
  assert.equal(L.nuovoSpento({ senzaRete: true }), true);
  assert.equal(L.nuovoSpento({ senzaRete: true, schedeSalvate: true }), false);
});

test('Da quale schema?: solo gli schemi pronti, i più recenti prima', () => {
  const lista = [
    { id: 3, stato: 'pronto', creato_il: '2026-10-26T17:00:00+01:00' },
    { id: 5, stato: 'in_preparazione', creato_il: '2026-11-01T17:00:00+01:00' },
    { id: 7, stato: 'pronto', creato_il: '2026-11-02T17:00:00+01:00' },
    { id: 4, stato: 'pronto', creato_il: '2026-10-26T17:00:00+01:00' },
    { id: 6, stato: 'errore', creato_il: '2026-11-03T17:00:00+01:00' },
  ];
  assert.deepEqual(L.schemiPronti(lista).map((x) => x.id), [7, 4, 3]);
  assert.deepEqual(L.schemiPronti(null), []);
});
