// I difetti trovati dalla revisione del programma per PC (1.0.0): ogni test falliva con la pagina di prima.
// Si lanciano con: node --test pc/prove/js
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const B = require('./banco');

const opz = { skip: B.SENZA_JSDOM };
test.afterEach(() => B.chiudiTutte());

async function apri(opzioniServer, prima, opzioniPagina) {
  const s = B.server(opzioniServer);
  if (prima) prima(s);
  const p = await B.pagina(s, opzioniPagina);
  await p.aspetta(() => p.q('.abbina') || (p.q('.menu .voce-menu') && p.A.S.giro >= 1));
  return p;
}

async function allaDomanda(p) {
  p.A.vai('#lettore/12');
  await p.aspetta(() => p.audio && p.q('[data-azione="play"]') && !p.q('[data-azione="play"]').disabled);
  p.A.lettoreSalto(571, false);
  p.clic(p.q('[data-azione="play"]'));
  await p.suona(4);
  await p.aspetta(() => p.q('#risposta-domanda'));
}

const ultimoPut = (p, id) => p.s.chiamate('PUT', '/server/api/lezioni/' + id + '/ascolto').pop();

// --- 1. Domanda lasciata senza risposta -----------------------------------------------------------

test('1. con una domanda aperta il punto salvato è l\'inizio della domanda, e alla ripresa la domanda si riapre', opz, async () => {
  const p = await apri();
  await allaDomanda(p);
  assert.equal(p.w.lectioPrimaDiChiudere(), 'in_corso');
  await p.aspetta(() => p.w.lectioStatoChiusura() === 'pulito');
  assert.equal(ultimoPut(p, 12).corpo.posizione_s, 566, 'inizio della domanda, non la fine');
  // Riaprendo (stesso computer, punto salvato più recente), si riparte DALLA domanda, ferma col campo.
  const salvato = JSON.parse(p.w.localStorage.getItem('lectio.pos.12'));
  assert.equal(salvato.posizione_s, 566);
  p.A.chiudiLettore(false);
  await B.dorme(20);
  p.A.ascolta(12, { suona: true });
  await p.aspetta(() => p.q('#risposta-domanda'));
  assert.equal(p.audio.paused, true, 'non riparte saltando la domanda');
  assert.match(p.q('.scheda-domanda').textContent, /Perché Tiberio passò gli ultimi anni a Capri/);
});

test('1. un punto salvato tra la fine della domanda e la risposta (versione di prima) riapre la domanda', opz, async () => {
  const p = await apri({}, (s) => {
    const l = s.esempio.stato.lezioni.find((x) => x.id === 12);
    l.ascolto = { posizione_s: 572.9, finita: false, aggiornato_il: '2026-10-27T20:00:00+01:00' };
  });
  p.A.vai('#lettore/12');
  await p.aspetta(() => p.q('#risposta-domanda'));
  assert.equal(p.audio.paused, true);
});

// --- 2. Riprendendo una lezione già caricata vince il punto più recente del server ----------------------

test('2. riprendere una lezione già nel lettore rilegge l\'ascolto: se il telefono è andato avanti, ci si sposta lì', opz, async () => {
  const p = await apri();
  p.A.vai('#lettore/10');
  await p.aspetta(() => p.audio && p.audio.currentTime === 667);
  // Intanto il telefono ascolta fino a 900 (più recente).
  const l = p.s.esempio.stato.lezioni.find((x) => x.id === 10);
  l.ascolto = { posizione_s: 900, finita: false, aggiornato_il: new Date(Date.now() + 60000).toISOString() };
  await p.A.ascolta(10, { suona: true });
  assert.equal(Math.round(p.audio.currentTime), 900);
  assert.equal(p.audio.paused, false);
});

// --- 3. Tasti multimediali solo mentre suona o alla domanda --------------------------------------------

test('3. i tasti multimediali si prendono solo mentre suona o è fermo a una domanda; spenti all\'avvio', opz, async () => {
  const p = await apri();
  const tasti = () => p.s.chiamate('POST', '/locale/tasti').map((x) => x.corpo.attivi);
  assert.deepEqual(tasti(), [false], 'all\'avvio la pagina li spegne');
  p.A.vai('#lettore/12');
  await p.aspetta(() => p.audio && !p.q('[data-azione="play"]').disabled);
  await B.dorme(30);
  assert.deepEqual(tasti(), [false], 'lezione caricata ma ferma: niente tasti presi');
  p.A.lettoreSalto(571, false);
  p.clic(p.q('[data-azione="play"]'));
  await p.aspetta(() => tasti().length === 2);
  assert.equal(tasti()[1], true);
  await p.suona(4);
  await p.aspetta(() => p.q('#risposta-domanda'));
  await B.dorme(30);
  assert.equal(tasti()[tasti().length - 1], true, 'fermo alla domanda: il play serve ancora');
  p.clic(p.q('[data-azione="chiudi-lezione"]'));
  await p.aspetta(() => tasti()[tasti().length - 1] === false);
});

test('3. in pausa normale i tasti tornano agli altri programmi', opz, async () => {
  const p = await apri();
  p.A.vai('#lettore/12');
  await p.aspetta(() => p.audio && !p.q('[data-azione="play"]').disabled);
  p.A.lettoreSalto(100, false);
  p.clic(p.q('[data-azione="play"]'));
  await p.suona(1);
  p.clic(p.q('[data-azione="play"]'));
  const tasti = () => p.s.chiamate('POST', '/locale/tasti').map((x) => x.corpo.attivi);
  await p.aspetta(() => tasti()[tasti().length - 1] === false && tasti().length === 3);
});

// --- 4. Scheda del prof ---------------------------------------------------------------------------

test('4. scheda del prof: un argomento nuovo ha esempio vuoto (mai null); righe troppo lunghe si fermano con la frase', opz, async () => {
  const p = await apri();
  p.A.vai('#materia/1/scheda');
  await p.aspetta(() => p.q('#sc-argomenti'));
  p.scrivi(p.q('#sc-argomenti'), p.q('#sc-argomenti').value + '\n' + 'x'.repeat(81));
  p.clic(p.q('[data-azione="salva-scheda"]'));
  await p.aspetta(() => p.q('.messaggio.errore'));
  assert.match(p.q('.messaggio.errore').textContent, /al massimo 80 caratteri/);
  assert.equal(p.s.chiamate('PUT', '/server/api/materie/1/scheda').length, 0);
  p.scrivi(p.q('#sc-argomenti'), 'Cause e conseguenze\nAugusto e il senato');
  p.clic(p.q('[data-azione="salva-scheda"]'));
  await p.aspetta(() => p.s.chiamate('PUT', '/server/api/materie/1/scheda').length === 1);
  const corpo = p.s.chiamate('PUT', '/server/api/materie/1/scheda')[0].corpo;
  assert.deepEqual(corpo.argomenti[1], { nome: 'Augusto e il senato', volte: 0, esempio: '' });
  assert.equal(corpo.argomenti[0].volte, 4);
  assert.ok(corpo.argomenti.every((a) => typeof a.esempio === 'string'));
});

// --- 5. La visita guidata non sporca la lezione vera; Scollega svuota ------------------------------------

test('5. la visita guidata non scrive punti di ascolto sul computer (la lezione 12 vera resta pulita)', opz, async () => {
  const p = await apri();
  await p.A.avviaVisita(false);
  for (let i = 1; i <= 7; i++) await p.A.visitaVai(i);
  await p.aspetta(() => p.q('#risposta-domanda'), 4000);
  p.scrivi(p.q('#risposta-domanda'), 'Seiano');
  p.clic(p.q('[data-azione="invia-risposta"]'));
  await p.aspetta(() => p.q('[data-visita-azione="inizia"]'), 4000);
  p.clic(p.q('[data-visita-azione="inizia"]'));
  await p.aspetta(() => !p.q('.visita'));
  const chiavi = Object.keys(p.w.localStorage).filter((k) => k.startsWith('lectio.pos.') || k === 'lectio.coda.risposte' || k === 'lectio.velocita');
  assert.deepEqual(chiavi, []);
});

test('5 e 13. Scollega con una lezione in ascolto: prima il punto va sul server, poi si scollega e si svuota la memoria', opz, async () => {
  const p = await apri();
  p.w.localStorage.setItem('lectio.pos.10', JSON.stringify({ posizione_s: 50, finita: false, aggiornato_il: '2026-10-20T10:00:00Z', inviato: false }));
  p.w.localStorage.setItem('lectio.coda.risposte', JSON.stringify([{ lezione_id: 12, domanda_id: 31, testo: 'x', saltata: false, quando: 'q' }]));
  p.A.vai('#lettore/12');
  await p.aspetta(() => p.audio && !p.q('[data-azione="play"]').disabled);
  p.A.lettoreSalto(200, false);
  p.clic(p.q('[data-azione="play"]'));
  await p.suona(1);
  p.A.vai('#impostazioni');
  await p.aspetta(() => p.q('[data-azione="scollega"]'));
  p.clic(p.q('[data-azione="scollega"]'));
  await p.aspetta(() => p.q('[data-azione="conferma"]'));
  p.clic(p.q('[data-azione="conferma"]'));
  await p.aspetta(() => p.s.chiamate('POST', '/locale/scollega').length === 1);
  const put = p.s.log.findIndex((x) => x.metodo === 'PUT' && x.percorso === '/server/api/lezioni/12/ascolto' && x.corpo.posizione_s >= 200);
  const scollega = p.s.log.findIndex((x) => x.percorso === '/locale/scollega');
  assert.ok(put >= 0 && put < scollega, 'il punto parte prima di scollegare');
  assert.equal(p.A.lettore.id, null, 'lettore chiuso: il file audio non resta aperto');
  await p.aspetta(() => p.q('.abbina'));
  assert.equal(p.w.localStorage.getItem('lectio.pos.10'), null);
  assert.equal(p.w.localStorage.getItem('lectio.coda.risposte'), null);
});

// --- 6. Correzione lenta: esito sconosciuto, mai pagata due volte ----------------------------------------

test('6. correzione col tempo scaduto: si rilegge mia_risposta; se è già corretta la si mostra e non si rimanda', opz, async () => {
  const p = await apri();
  await allaDomanda(p);
  p.s.risposta = (m, percorso, corpo) => {
    if (m === 'POST' && /\/domande\/32\/risposta$/.test(percorso)) {
      p.s.esempio.richiesta(m, percorso, corpo); // il server l'ha corretta, ma la risposta non è arrivata in tempo
      return [504, { errore: 'Il server ci sta mettendo troppo.', codice: 'esito_sconosciuto' }];
    }
    return undefined;
  };
  p.scrivi(p.q('#risposta-domanda'), 'Perché non si fidava del senato');
  p.clic(p.q('[data-azione="invia-risposta"]'));
  await p.aspetta(() => p.q('.correzione .esito'));
  assert.equal(p.q('[data-stato="rete"]'), null, 'non è "senza rete"');
  assert.equal(p.w.localStorage.getItem('lectio.coda.risposte'), null, 'niente in coda: non si ripaga');
  assert.equal(p.s.chiamate('POST', '/server/api/lezioni/12/domande/32/risposta').length, 1);
  assert.ok(p.s.chiamate('GET', '/server/api/lezioni/12').length >= 1);
});

test('6. esito sconosciuto e non ancora corretta: resta in coda; prima di rimandarla si ricontrolla e non si paga due volte', opz, async () => {
  const p = await apri();
  await allaDomanda(p);
  p.s.risposta = (m, percorso) => (m === 'POST' && /\/risposta$/.test(percorso) ? [504, { errore: 'Il server ci sta mettendo troppo.', codice: 'esito_sconosciuto' }] : undefined);
  p.scrivi(p.q('#risposta-domanda'), 'Si fidava di Seiano');
  p.clic(p.q('[data-azione="invia-risposta"]'));
  await p.aspetta(() => p.q('.risposta-giusta'));
  assert.match(p.q('.scheda-domanda').textContent, /non so se l'ha corretta/);
  assert.equal(JSON.parse(p.w.localStorage.getItem('lectio.coda.risposte')).length, 1);
  // Nel frattempo il server l'aveva corretta: rimandando la coda si vede e non parte un secondo POST.
  p.s.risposta = null;
  p.s.esempio.richiesta('POST', '/server/api/lezioni/12/domande/32/risposta', { testo: 'Si fidava di Seiano', saltata: false });
  const prima = p.s.chiamate('POST', '/server/api/lezioni/12/domande/32/risposta').length;
  await p.A.mandaCode();
  assert.equal(p.s.chiamate('POST', '/server/api/lezioni/12/domande/32/risposta').length, prima);
  assert.equal(JSON.parse(p.w.localStorage.getItem('lectio.coda.risposte')).length, 0);
});

test('6. la pagina aspetta la correzione più dei 45 s delle altre richieste', opz, () => {
  const api = fs.readFileSync(path.join(process.env.LECTIO_UI || path.join(__dirname, '..', '..', 'ui'), 'api.js'), 'utf8');
  assert.match(api, /ATTESA_CORREZIONE_MS = 210000/);
  assert.match(api, /domande\\\/\\d\+\\\/risposta/);
});

// --- 8. Cambiando lezione il punto di quella di prima non si perde -------------------------------------

test('8. aprendo un\'altra lezione il punto di quella di prima va sul server', opz, async () => {
  const p = await apri();
  p.A.vai('#lettore/12');
  await p.aspetta(() => p.audio && !p.q('[data-azione="play"]').disabled);
  p.A.lettoreSalto(100, false);
  p.clic(p.q('[data-azione="play"]'));
  await p.suona(3);
  const prima = p.s.chiamate('PUT', '/server/api/lezioni/12/ascolto').length;
  await p.A.ascolta(10, {});
  const dopo = p.s.chiamate('PUT', '/server/api/lezioni/12/ascolto');
  assert.ok(dopo.length > prima, 'PUT della lezione 12 prima di aprire la 10');
  assert.ok(dopo[dopo.length - 1].corpo.posizione_s >= 102.9, String(dopo[dopo.length - 1].corpo.posizione_s));
});

// --- 9. Il riquadro multimediale di Windows non scavalca la domanda ------------------------------------

test('9. avanti/indietro dal riquadro multimediale di Windows non scavalcano la domanda aperta', opz, async () => {
  const p = await apri();
  await allaDomanda(p);
  const t = p.audio.currentTime;
  p.media.nexttrack();
  p.media.seekforward();
  p.media.previoustrack();
  await B.dorme(10);
  assert.equal(p.audio.currentTime, t);
  assert.ok(p.q('#risposta-domanda'));
  // Senza domanda aperta funzionano.
  p.clic(p.q('[data-azione="ripeti-domanda"]'));
  p.media.nexttrack();
  assert.ok(Math.abs(p.audio.currentTime - 596) < 0.01);
});

// --- 10. La scheda della domanda ha un'uscita -------------------------------------------------------------

test('10. "Chiudi la lezione" (anche Esc) dalla scheda della domanda: punto all\'inizio della domanda, finestra libera', opz, async () => {
  const p = await apri();
  await allaDomanda(p);
  p.tasto('Escape', {}, p.q('#risposta-domanda'));
  await p.aspetta(() => !p.q('#domanda-velo'));
  assert.equal(p.A.lettore.id, null);
  await p.aspetta(() => ultimoPut(p, 12) && ultimoPut(p, 12).corpo.posizione_s === 566);
  await p.aspetta(() => !/lettore/.test(p.w.location.hash)); // si torna alla pagina di prima
  // Di nuovo, col pulsante.
  await allaDomanda(p);
  p.clic(p.q('[data-azione="chiudi-lezione"]'));
  await p.aspetta(() => !p.q('#domanda-velo') && p.A.lettore.id === null);
});

// --- 14. "Cambia": solo materiale pronto, stima coerente, mai vuoto ---------------------------------------

test('14. Cambia: si manda solo materiale già letto, la stima riceve la scelta, con niente scelto si blocca', opz, async () => {
  const p = await apri({}, (s) => {
    s.esempio.stato.materiale[5] = [
      { id: 501, materia_id: 5, tipo: 'appunti', titolo: 'Equazioni', data: '2026-10-20', stato: 'pronto' },
      { id: 502, materia_id: 5, tipo: 'appunti', titolo: 'Delta', data: '2026-10-21', stato: 'pronto' },
      { id: 503, materia_id: 5, tipo: 'appunti', titolo: 'Ancora in lettura', data: '2026-10-27', stato: 'in_lettura' },
    ];
  });
  p.A.nuovaLezione(5);
  await p.aspetta(() => p.pulsante('Cambia') && /Prepara la lezione ·/.test(p.q('[data-azione="prepara"]').textContent));
  assert.match(p.q('.riga-usero').textContent, /2 appunti di Matematica/, 'quello in lettura non conta');
  p.clic(p.pulsante('Cambia'));
  await p.aspetta(() => p.pulsante('Equazioni'));
  assert.equal(p.pulsante('Ancora in lettura'), undefined);
  p.clic(p.pulsante('Equazioni'));
  await p.aspetta(() => p.s.chiamate('POST', '/server/api/lezioni/stima').some((x) => Array.isArray(x.corpo.materiali)));
  assert.deepEqual(p.s.chiamate('POST', '/server/api/lezioni/stima').filter((x) => x.corpo.materiali).pop().corpo.materiali, [502]);
  p.clic(p.pulsante('Delta'));
  await p.aspetta(() => p.q('[data-azione="prepara"]').disabled && /Scegli almeno un appunto o una verifica/.test(p.q('.finestra').textContent));
  assert.equal(p.s.chiamate('POST', '/server/api/lezioni/stima').filter((x) => x.corpo.materiali && !x.corpo.materiali.length).length, 0);
  p.clic(p.pulsante('Delta'));
  await p.aspetta(() => !p.q('[data-azione="prepara"]').disabled);
  p.scrivi(p.q('#nl-argomento'), 'Equazioni di secondo grado');
  p.clic(p.q('[data-azione="prepara"]'));
  await p.aspetta(() => p.s.chiamate('POST', '/server/api/lezioni').some((x) => x.percorso === '/server/api/lezioni'));
  assert.deepEqual(p.s.chiamate('POST', '/server/api/lezioni').find((x) => x.percorso === '/server/api/lezioni').corpo.materiali, [502]);
});

// --- Server 1.0.1: avviso del materiale e spesa già impegnata --------------------------------------------

test('server 1.0.1: l\'avviso del materiale letto a metà e la parte di spesa già impegnata si vedono', opz, async () => {
  const p = await apri({}, (s) => {
    s.esempio.stato.materiale[1][0].avviso = 'Ho letto solo le prime 70 pagine: il resto supera il limite';
    const prima = s.esempio.richiesta;
    s.esempio.richiesta = (m, percorso, corpo) => {
      const r = prima(m, percorso, corpo);
      if (percorso === '/server/api/lezioni/stima' && r.stato === 200) r.dati.impegnato_eur = 0.25;
      return r;
    };
  });
  p.A.vai('#materia/1');
  await p.aspetta(() => p.q('[data-avviso="materiale"]'));
  assert.match(p.q('[data-avviso="materiale"]').textContent, /prime 70 pagine/);
  p.A.nuovaLezione(1);
  await p.aspetta(() => p.q('[data-impegnato]'));
  assert.match(p.q('[data-impegnato]').textContent, /di cui 0,25 € per lavori già partiti/);
});

test('server 1.0.1: un 503 con la frase del server (modello senza prezzo) si mostra com\'è, non come "senza rete"', opz, async () => {
  const p = await apri();
  p.s.risposta = (m, percorso) => (percorso === '/server/api/lezioni/stima' ? [503, { errore: 'Il modello scelto non ha un prezzo nel listino: va sistemato il server.', codice: 'fornitore_non_disponibile' }] : undefined);
  p.A.nuovaLezione(1);
  await p.aspetta(() => /non ha un prezzo nel listino/.test(p.q('.finestra').textContent));
  assert.equal(p.q('[data-stato="rete"]'), null);
});

// --- Indirizzo da fuori casa ------------------------------------------------------------------------------

test('15.5. Impostazioni: l\'indirizzo da fuori casa parte vuoto (niente indirizzi nel codice), si scrive a mano, un errore si vede sotto', opz, async () => {
  const p = await apri({}, (s) => {
    const prima = s.locale;
    s.locale = (m, percorso, corpo) => {
      if (percorso === '/locale/fuori-casa') {
        if (!corpo.indirizzo) return [200, { ok: true, indirizzo_fuori: 'https://lectio.esempio.ts.net', indirizzo_fuori_a_mano: false }];
        return /^https:\/\//.test(corpo.indirizzo) ? [200, { ok: true, indirizzo_fuori: corpo.indirizzo, indirizzo_fuori_a_mano: true }] : [422, { errore: 'L\'indirizzo da fuori casa deve essere sicuro, cioè cominciare con https.', codice: 'indirizzo' }];
      }
      return prima(m, percorso, corpo);
    };
  });
  p.A.vai('#impostazioni');
  await p.aspetta(() => p.q('#imp-fuori'));
  assert.equal(p.q('#imp-fuori').value, '');
  p.q('#imp-fuori').value = 'http://192.168.0.20:8500';
  p.q('#imp-fuori').dispatchEvent(new p.w.Event('change', { bubbles: true }));
  await p.aspetta(() => /https/.test(p.q('.voce-imp .errore-campo').textContent));
  p.q('#imp-fuori').value = 'https://lectio-2.esempio.ts.net';
  p.q('#imp-fuori').dispatchEvent(new p.w.Event('change', { bubbles: true }));
  await p.aspetta(() => p.s.chiamate('POST', '/locale/fuori-casa').length === 2);
  assert.equal(p.s.chiamate('POST', '/locale/fuori-casa')[1].corpo.indirizzo, 'https://lectio-2.esempio.ts.net');
  // Svuotato a mano: torna quello che manda il server.
  p.q('#imp-fuori').value = '';
  p.q('#imp-fuori').dispatchEvent(new p.w.Event('change', { bubbles: true }));
  await p.aspetta(() => p.q('#imp-fuori').value === 'https://lectio.esempio.ts.net');
});
