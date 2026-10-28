// I test dell'interfaccia vera (pc/ui) in jsdom, con un server finto (banco.js): abbinamento, Oggi, lettore con
// le fermate alle domande e le risposte scritte (domande obbligatorie e saltabili), tasti mentre si scrive, punto di
// ascolto (vince il più recente), senza rete, Nuova lezione (stime che si accavallano, tetto, "Userò"), Nuova
// materia, Per Gemini, file trascinati, visita guidata (avanza solo con l'azione giusta, nessuna chiamata al server).
// Si lanciano con: node --test pc/prove/js
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const B = require('./banco');

const opz = { skip: B.SENZA_JSDOM };
test.afterEach(() => B.chiudiTutte());

async function apri(opzioniServer, opzioniPagina, prima) {
  const s = B.server(opzioniServer);
  if (prima) prima(s);
  const p = await B.pagina(s, opzioniPagina);
  // Pronta: la pagina di abbinamento, oppure il menu con la prima pagina già disegnata.
  await p.aspetta(() => p.q('.abbina') || (p.q('.menu .voce-menu') && p.A.S.giro >= 1));
  return p;
}

/** Apre la lezione 12 nel lettore grande e la porta poco prima della domanda 2 (fine_domanda_s = 572,8). */
async function allaDomanda(p, prima) {
  if (prima) prima(p);
  p.A.vai('#lettore/12');
  await p.aspetta(() => p.audio && p.q('[data-azione="play"]') && !p.q('[data-azione="play"]').disabled);
  p.A.lettoreSalto(571, false);
  p.clic(p.q('[data-azione="play"]'));
  assert.equal(p.audio.paused, false);
  await p.suona(4);
  await p.aspetta(() => p.q('#domanda-velo .scheda-domanda'));
}

// --- Avvio e abbinamento ---------------------------------------------------------------------------

test('primo avvio: abbinamento con l\'indirizzo Tailscale già scritto, Collega spento senza 8 cifre, errore chiaro', opz, async () => {
  const p = await apri({ abbinato: false, tutorialVisto: false });
  assert.ok(p.q('.abbina'));
  assert.match(p.testo(), /Collega Lectio al server di casa/);
  // Nessun indirizzo vero nel codice: il campo parte vuoto, col segnaposto della forma.
  assert.equal(p.q('#abbina-server').value, '');
  assert.equal(p.q('#abbina-server').getAttribute('placeholder'), 'http://192.168.x.x:8500');
  const collega = p.q('[data-azione="collega"]');
  assert.equal(collega.disabled, true);
  p.scrivi(p.q('#abbina-codice'), '1111 2222');
  assert.equal(collega.disabled, true, 'senza indirizzo non si collega');
  p.scrivi(p.q('#abbina-server'), 'http://192.168.0.20:8500');
  assert.equal(collega.disabled, false);
  p.clic(collega);
  await p.aspetta(() => p.q('.messaggio.errore'));
  assert.match(p.q('.messaggio.errore').textContent, /Il codice non è giusto/);
  assert.deepEqual(p.s.chiamate('POST', '/locale/abbina')[0].corpo, { server: 'http://192.168.0.20:8500', codice: '11112222' });
  p.scrivi(p.q('#abbina-codice'), '12345678');
  p.clic(collega);
  // Collegato: le pagine vere, e la visita guidata parte da sola la prima volta.
  await p.aspetta(() => p.q('.menu .voce-menu'));
  await p.aspetta(() => p.q('.visita-fumetto'));
  assert.match(p.q('.visita-fumetto').textContent, /1 di 8/);
});

test('Oggi: la prossima verifica con la sua lezione, Riprendi, Dopo la verifica, Più avanti e la spesa nel menu', opz, async () => {
  const p = await apri();
  await p.aspetta(() => p.q('[data-visita="prossima"]'));
  const t = p.testo();
  assert.match(t, /LA PROSSIMA|La prossima/i);
  assert.match(t, /Impero romano da Augusto a Nerone/);
  assert.match(t, /giovedì 29 ottobre · scritta/);
  assert.ok(p.q('[data-azione="ascolta-prossima"]'));
  assert.match(t, /Le fonti del diritto/);
  assert.match(t, /Com'è andata la verifica di Inglese\?/);
  assert.match(t, /Scienze umane/);
  await p.aspetta(() => /Questo mese 0,80 € di 2 €/.test(p.q('.menu').textContent));
});

// --- Il lettore: fermate alle domande e risposte scritte ----------------------------------------------

test('alla domanda l\'audio si ferma, sale la scheda col campo già col cursore; Invia spento col campo vuoto', opz, async () => {
  const p = await apri();
  await allaDomanda(p);
  assert.equal(p.audio.paused, true);
  assert.ok(p.audio.currentTime >= 572.8 && p.audio.currentTime < 573.3, 'fermo alla fine della domanda: ' + p.audio.currentTime);
  assert.match(p.q('.scheda-domanda').textContent, /DOMANDA DEL BLOCCO · 2 DI 6/);
  assert.match(p.q('.scheda-domanda').textContent, /Perché Tiberio passò gli ultimi anni a Capri/);
  await p.aspetta(() => p.d.activeElement && p.d.activeElement.id === 'risposta-domanda');
  assert.equal(p.q('[data-azione="invia-risposta"]').disabled, true);
});

test('mentre si scrive nel campo, barra spaziatrice e tasti multimediali NON comandano l\'audio', opz, async () => {
  const p = await apri();
  await allaDomanda(p);
  const campo = p.q('#risposta-domanda');
  campo.focus();
  const ev = p.tasto(' ', {}, campo);
  assert.equal(ev.defaultPrevented, false, 'lo spazio scrive nel campo');
  p.w.lectioTasto('play');
  p.w.lectioTasto('avanti');
  p.tasto('MediaPlayPause', {}, campo);
  await B.dorme(20);
  assert.equal(p.audio.paused, true);
  assert.ok(p.audio.currentTime < 573.3);
  assert.equal(p.s.chiamate('POST', '/server/api/lezioni/12/domande').length, 0);
});

test('Invia risposta (anche Ctrl+Invio): Correggo…, correzione con esito e commento, poi la risposta giusta in ascolto e Avanti', opz, async () => {
  const p = await apri();
  p.s.ritardo = (m, percorso) => (percorso.includes('/risposta') ? 80 : 1);
  await allaDomanda(p);
  const campo = p.q('#risposta-domanda');
  p.scrivi(campo, 'Perché non si fidava del senato');
  assert.equal(p.q('[data-azione="invia-risposta"]').disabled, false);
  p.tasto('Enter', { ctrlKey: true }, campo);
  await p.aspetta(() => p.q('.scheda-domanda .correggo'));
  assert.match(p.q('.correggo').textContent, /Correggo…/);
  await p.aspetta(() => p.q('.correzione .esito'));
  const post = p.s.chiamate('POST', '/server/api/lezioni/12/domande/32/risposta');
  assert.equal(post.length, 1);
  assert.deepEqual(post[0].corpo, { testo: 'Perché non si fidava del senato', saltata: false });
  assert.match(p.q('.correzione .esito').textContent, /Quasi|Giusta|Da rivedere/);
  assert.ok(!p.q('.correzione .esito.errore'));
  // La risposta giusta parte da inizio_risposta_s e si legge sotto.
  assert.equal(p.audio.paused, false);
  assert.ok(Math.abs(p.audio.currentTime - 575.3) < 0.01);
  assert.match(p.q('.risposta-giusta').textContent, /Risposta giusta · in ascolto/i);
  // Alla fine della risposta si ferma di nuovo con Avanti; Avanti fa ripartire.
  await p.suona(16);
  assert.equal(p.audio.paused, true);
  assert.ok(p.audio.currentTime >= 589);
  p.clic(p.q('[data-azione="avanti"]'));
  assert.equal(p.audio.paused, false);
  assert.equal(p.q('#domanda-velo'), null);
});

test('domande obbligatorie: "Salta comunque" chiede conferma in linea; Rispondo torna al campo, Sì salta manda saltata', opz, async () => {
  const p = await apri();
  await allaDomanda(p);
  assert.equal(p.q('[data-azione="salta"]'), null);
  p.clic(p.q('[data-azione="salta-comunque"]'));
  await p.aspetta(() => p.q('[data-conferma="salto"]'));
  assert.match(p.q('[data-conferma="salto"]').textContent, /Saltarla\? Finisce in Da ripassare\./);
  p.clic(p.q('[data-azione="rispondo"]'));
  await p.aspetta(() => !p.q('[data-conferma="salto"]'));
  assert.equal(p.s.chiamate('POST', '/server/api/lezioni/12/domande').length, 0);
  p.clic(p.q('[data-azione="salta-comunque"]'));
  await p.aspetta(() => p.q('[data-azione="si-salta"]'));
  p.clic(p.q('[data-azione="si-salta"]'));
  await p.aspetta(() => p.s.chiamate('POST', '/server/api/lezioni/12/domande/32/risposta').length === 1);
  assert.deepEqual(p.s.chiamate('POST', '/server/api/lezioni/12/domande/32/risposta')[0].corpo, { testo: '', saltata: true });
  await p.aspetta(() => p.q('[data-esito="saltata"]'));
  assert.match(p.q('.scheda-domanda').textContent, /Saltata · in Da ripassare/);
  await p.aspetta(() => !p.audio.paused);
  assert.ok(Math.abs(p.audio.currentTime - 575.3) < 0.01);
});

test('domande obbligatorie a finestra dietro: il tasto play ripete la domanda e l\'audio si riferma, niente salto', opz, async () => {
  const p = await apri();
  await allaDomanda(p);
  p.s.focus = false;
  p.w.lectioTasto('play');
  await B.dorme(10);
  assert.equal(p.s.chiamate('POST', '/server/api/lezioni/12/domande').length, 0);
  assert.equal(p.audio.paused, false);
  assert.ok(Math.abs(p.audio.currentTime - 566.0) < 0.01, 'riparte da inizio_s: ' + p.audio.currentTime);
  await p.suona(8);
  assert.equal(p.audio.paused, true);
  assert.ok(p.audio.currentTime >= 572.8 && p.audio.currentTime < 573.3);
  await p.aspetta(() => p.q('#risposta-domanda'));
});

test('domande saltabili: pulsante Salta normale; a finestra dietro il tasto play salta (risposta saltata)', opz, async () => {
  const p = await apri();
  p.s.esempio.richiesta('PATCH', '/server/api/lezioni/12', { domande_saltabili: true });
  await allaDomanda(p);
  assert.equal(p.q('[data-azione="salta-comunque"]'), null);
  assert.ok(p.q('[data-azione="salta"]'));
  // Con la finestra davanti il tasto porta il cursore nel campo e non salta.
  p.d.body.focus();
  p.q('#risposta-domanda').blur();
  p.w.lectioTasto('play');
  await B.dorme(10);
  assert.equal(p.s.chiamate('POST', '/server/api/lezioni/12/domande').length, 0);
  assert.equal(p.d.activeElement.id, 'risposta-domanda');
  // Finestra dietro (non si può scrivere): play = Salta.
  p.s.focus = false;
  p.w.lectioTasto('play');
  await p.aspetta(() => p.s.chiamate('POST', '/server/api/lezioni/12/domande/32/risposta').length === 1);
  assert.equal(p.s.chiamate('POST', '/server/api/lezioni/12/domande/32/risposta')[0].corpo.saltata, true);
  await p.aspetta(() => !p.audio.paused);
});

test('la scelta saltabili/obbligatorie si cambia dal menu del lettore (PATCH della lezione)', opz, async () => {
  const p = await apri();
  p.A.vai('#lettore/12');
  await p.aspetta(() => p.audio && p.q('[data-azione="menu-lettore"]'));
  p.clic(p.q('[data-azione="menu-lettore"]'));
  await p.aspetta(() => p.q('[data-azione="cambia-saltabili"]'));
  assert.match(p.q('[data-azione="cambia-saltabili"]').textContent, /si possono saltare/);
  p.clic(p.q('[data-azione="cambia-saltabili"]'));
  await p.aspetta(() => p.s.chiamate('PATCH', '/server/api/lezioni/12').length === 1);
  assert.deepEqual(p.s.chiamate('PATCH', '/server/api/lezioni/12')[0].corpo, { domande_saltabili: true });
});

test('Ripeti la domanda: torna all\'inizio della domanda, si riferma, e il testo scritto resta', opz, async () => {
  const p = await apri();
  await allaDomanda(p);
  p.scrivi(p.q('#risposta-domanda'), 'Per Seiano');
  p.clic(p.q('[data-azione="ripeti-domanda"]'));
  assert.equal(p.q('#domanda-velo'), null);
  assert.ok(Math.abs(p.audio.currentTime - 566.0) < 0.01);
  await p.suona(8);
  await p.aspetta(() => p.q('#risposta-domanda'));
  assert.equal(p.q('#risposta-domanda').value, 'Per Seiano');
});

test('"Risposte scritte" spento: niente campo, Senti la risposta fa partire la risposta (anche col tasto play)', opz, async () => {
  const p = await apri({}, {}, (s) => s.esempio.richiesta('PATCH', '/server/api/impostazioni', { risposte_scritte: false }));
  await allaDomanda(p);
  assert.equal(p.q('#risposta-domanda'), null);
  assert.ok(p.q('[data-azione="senti-risposta"]'));
  p.s.focus = false;
  p.w.lectioTasto('play');
  await B.dorme(5);
  assert.equal(p.audio.paused, false);
  assert.ok(Math.abs(p.audio.currentTime - 575.3) < 0.01);
  assert.equal(p.s.chiamate('POST', '/server/api/lezioni/12/domande').length, 0);
});

test('un salto (capitolo) oltre la domanda non la fa scattare; "Torna dov\'eri" per 5 s riporta indietro', opz, async () => {
  const p = await apri();
  p.A.vai('#lettore/12');
  await p.aspetta(() => p.audio && p.q('.capitolo'));
  p.A.lettoreSalto(560, false);
  p.clic(p.q('[data-azione="play"]'));
  await p.suona(1);
  p.clic(p.q('.capitolo[data-capitolo="3"]'));
  assert.ok(Math.abs(p.audio.currentTime - 589) < 0.01);
  await p.aspetta(() => p.q('[data-azione="torna"]'));
  await p.suona(3);
  assert.equal(p.q('#domanda-velo'), null);
  assert.equal(p.audio.paused, false);
  p.clic(p.q('[data-azione="torna"]'));
  assert.ok(p.audio.currentTime > 560 && p.audio.currentTime < 562);
  // La pillola sparisce da sola (qui il tempo è accorciato).
  p.clic(p.q('.capitolo[data-capitolo="1"]'));
  await p.aspetta(() => p.q('[data-azione="torna"]'));
  await p.aspetta(() => !p.q('[data-azione="torna"]'), 2000);
});

test('velocità: la pillola passa 1,0× → 1,2× e a 1,6× la domanda ferma lo stesso', opz, async () => {
  const p = await apri();
  p.A.vai('#lettore/12');
  await p.aspetta(() => p.audio && p.q('[data-azione="velocita"]'));
  assert.equal(p.q('[data-azione="velocita"]').textContent, '1,0×');
  p.clic(p.q('[data-azione="velocita"]'));
  await p.aspetta(() => p.q('[data-azione="velocita"]').textContent === '1,2×');
  assert.equal(p.audio.playbackRate, 1.2);
  p.A.lettoreVelocita(1.6);
  p.A.lettoreSalto(569, false);
  p.clic(p.q('[data-azione="play"]'));
  await p.suona(4);
  await p.aspetta(() => p.q('#risposta-domanda'));
  assert.equal(p.audio.paused, true);
});

// --- Punto di ascolto: vince il più recente, sincronia, senza rete ------------------------------------

test('vince il più recente: il punto salvato sul computer più nuovo di quello del server vale lui', opz, async () => {
  const p = await apri();
  p.w.localStorage.setItem('lectio.pos.10', JSON.stringify({ posizione_s: 900, finita: false, aggiornato_il: '2026-10-27T09:00:00+01:00', inviato: true }));
  p.A.vai('#lettore/10');
  await p.aspetta(() => p.audio);
  await p.aspetta(() => p.audio.currentTime > 0);
  assert.equal(p.audio.currentTime, 900);
  p.A.chiudiLettore(false);
  // Più vecchio del server (667 alle 08:10 del 26/10): vale il server.
  p.w.localStorage.setItem('lectio.pos.10', JSON.stringify({ posizione_s: 100, finita: false, aggiornato_il: '2026-10-25T09:00:00+01:00', inviato: true }));
  p.A.ascolta(10, {});
  await p.aspetta(() => p.audio && p.audio.currentTime === 667);
});

test('il punto di ascolto va sul server in pausa, con aggiornato_il; senza rete resta in coda e parte quando torna', opz, async () => {
  const p = await apri();
  p.A.vai('#lettore/12');
  await p.aspetta(() => p.audio && p.q('[data-azione="play"]') && !p.q('[data-azione="play"]').disabled);
  p.A.lettoreSalto(100, false);
  p.clic(p.q('[data-azione="play"]'));
  await p.suona(2);
  p.clic(p.q('[data-azione="play"]'));
  await p.aspetta(() => p.s.chiamate('PUT', '/server/api/lezioni/12/ascolto').length >= 1);
  const ultima = p.s.chiamate('PUT', '/server/api/lezioni/12/ascolto').pop().corpo;
  assert.ok(ultima.posizione_s >= 101.9 && ultima.posizione_s <= 102.3, String(ultima.posizione_s));
  assert.equal(ultima.finita, false);
  assert.ok(!Number.isNaN(Date.parse(ultima.aggiornato_il)));
  // Rete giù: il PUT fallisce, il punto resta sul computer non mandato; poi torna la rete e parte.
  p.s.risposta = (m, percorso) => (percorso.startsWith('/server/') ? 'rete' : undefined);
  p.clic(p.q('[data-azione="play"]'));
  await p.suona(1);
  p.clic(p.q('[data-azione="play"]'));
  await p.aspetta(() => p.q('[data-stato="rete"]'));
  assert.match(p.q('[data-stato="rete"]').textContent, /Senza rete: vedi solo le lezioni scaricate/);
  const salvato = JSON.parse(p.w.localStorage.getItem('lectio.pos.12'));
  assert.equal(salvato.inviato, false);
  p.s.risposta = null;
  const prima = p.s.chiamate('PUT', '/server/api/lezioni/12/ascolto').length;
  await p.A.api.get('/server/api/salute');
  await p.aspetta(() => p.s.chiamate('PUT', '/server/api/lezioni/12/ascolto').length > prima);
  await p.aspetta(() => !p.q('[data-stato="rete"]'));
});

test('senza rete alla domanda: la risposta resta in coda, si vede subito la risposta giusta, parte quando torna la rete', opz, async () => {
  const p = await apri();
  await allaDomanda(p);
  p.s.risposta = (m, percorso) => (percorso.startsWith('/server/') ? 'rete' : undefined);
  p.scrivi(p.q('#risposta-domanda'), 'Si fidava solo di Seiano');
  p.clic(p.q('[data-azione="invia-risposta"]'));
  await p.aspetta(() => p.q('.risposta-giusta'));
  assert.match(p.q('.scheda-domanda').textContent, /la correggo quando torna la rete/);
  assert.match(p.q('.risposta-giusta').textContent, /Perché non si fidava del senato/);
  const coda = JSON.parse(p.w.localStorage.getItem('lectio.coda.risposte'));
  assert.equal(coda.length, 1);
  assert.equal(coda[0].testo, 'Si fidava solo di Seiano');
  p.s.risposta = null;
  await p.A.mandaCode();
  assert.equal(JSON.parse(p.w.localStorage.getItem('lectio.coda.risposte')).length, 0);
  assert.equal(p.s.chiamate('POST', '/server/api/lezioni/12/domande/32/risposta').filter((x) => x.corpo.testo === 'Si fidava solo di Seiano').length, 2);
});

test('chiudendo la finestra la pagina salva prima il punto (lectioPrimaDiChiudere → in_corso → pulito)', opz, async () => {
  const p = await apri();
  p.A.vai('#lettore/12');
  await p.aspetta(() => p.audio && !p.q('[data-azione="play"]').disabled);
  p.A.lettoreSalto(300, false);
  const prima = p.s.chiamate('PUT', '/server/api/lezioni/12/ascolto').length;
  assert.equal(p.w.lectioPrimaDiChiudere(), 'in_corso');
  await p.aspetta(() => p.w.lectioStatoChiusura() === 'pulito');
  assert.ok(p.s.chiamate('PUT', '/server/api/lezioni/12/ascolto').length > prima);
});

// --- Nuova lezione: stime, tetto, Userò --------------------------------------------------------------

test('Nuova lezione: stime che si accavallano, vince l\'ultima durata scelta; il pulsante dice il costo', opz, async () => {
  const p = await apri();
  // La stima dei 15 minuti arriva DOPO quella dei 45: deve vincere comunque la 45 (l'ultima chiesta).
  p.s.ritardo = (m, percorso, corpo) => (percorso === '/server/api/lezioni/stima' ? (corpo.durata_min === 15 ? 150 : 5) : 1);
  p.A.vai('#materia/1');
  await p.aspetta(() => p.q('[data-azione="nuova-lezione"]'));
  p.clic(p.q('[data-azione="nuova-lezione"]'));
  await p.aspetta(() => p.q('[data-durata="15"]') && /0,25 €/.test(p.q('[data-azione="prepara"]').textContent));
  assert.equal(p.q('[data-azione="prepara"]').textContent, 'Prepara la lezione · 0,25 €');
  p.clic(p.q('[data-durata="15"]'));
  p.clic(p.q('[data-durata="45"]'));
  await B.dorme(250);
  assert.equal(p.q('[data-durata="45"]').getAttribute('aria-pressed'), 'true');
  assert.equal(p.q('[data-azione="prepara"]').textContent, 'Prepara la lezione · 0,56 €');
  // La voce gratis costa solo il testo.
  p.clic(p.q('[data-voce="gratis"]'));
  await p.aspetta(() => p.q('[data-azione="prepara"]').textContent === 'Prepara la lezione · 0,04 €');
});

test('tetto: la voce espressiva che sforerebbe è spenta con "Sforerebbe il tetto di 2 €" e si prepara con la gratis', opz, async () => {
  const p = await apri({ speso: 1.85 });
  p.A.nuovaLezione(1);
  await p.aspetta(() => p.q('[data-voce="espressiva"]') && p.q('[data-voce="espressiva"]').disabled);
  assert.match(p.q('[data-voce="espressiva"]').textContent, /Sforerebbe il tetto di 2 €/);
  assert.equal(p.q('[data-voce="gratis"]').getAttribute('aria-pressed'), 'true');
  assert.equal(p.q('[data-azione="prepara"]').textContent, 'Prepara la lezione · 0,02 €');
  assert.match(p.q('.riga-mese').textContent, /dopo questa 1,87 € di 2 €/);
  p.clic(p.q('[data-azione="prepara"]'));
  await p.aspetta(() => p.s.chiamate('POST', '/server/api/lezioni').filter((x) => x.percorso === '/server/api/lezioni').length === 1);
  const corpo = p.s.chiamate('POST', '/server/api/lezioni').find((x) => x.percorso === '/server/api/lezioni').corpo;
  assert.equal(corpo.voce, 'azure:it-IT-IsabellaMultilingualNeural');
});

test('Nuova lezione: "Userò" senza caricamenti; materiali mandati solo se si tocca Cambia; domande obbligatorie di base', opz, async () => {
  const p = await apri();
  p.A.nuovaLezione(1);
  await p.aspetta(() => p.q('.riga-usero') && /0,25 €/.test(p.q('[data-azione="prepara"]').textContent));
  assert.match(p.q('.riga-usero').textContent, /1 verifica, 1 interrogazione e 2 appunti di Storia/);
  assert.equal(p.tutti('.finestra input[type="file"]').length, 0);
  assert.equal(p.q('[data-saltabili="false"]').getAttribute('aria-pressed'), 'true');
  p.clic(p.q('[data-azione="prepara"]'));
  await p.aspetta(() => p.q('.fasi'));
  const corpo1 = p.s.chiamate('POST', '/server/api/lezioni').find((x) => x.percorso === '/server/api/lezioni').corpo;
  assert.equal('materiali' in corpo1, false);
  assert.equal(corpo1.domande_saltabili, false);
  assert.equal(corpo1.argomento, 'Impero romano da Augusto a Nerone');
  assert.equal(corpo1.evento_id, 3);
  assert.match(p.q('.finestra').textContent, /Puoi uscire: ti avviso io/);
  // Con Cambia: un appunto spento → materiali = verifiche + interrogazioni + appunti accesi.
  p.A.chiudiTuttiIVeli();
  p.A.nuovaLezione(1);
  await p.aspetta(() => p.pulsante('Cambia') && /0,25 €/.test(p.q('[data-azione="prepara"]').textContent));
  p.clic(p.pulsante('Cambia'));
  await p.aspetta(() => p.pulsante('Appunti: Augusto e il principato'));
  p.clic(p.pulsante('Appunti: Augusto e il principato'));
  p.clic(p.q('[data-saltabili="true"]'));
  await p.aspetta(() => /1 appunto di Storia/.test(p.q('.riga-usero').textContent));
  p.clic(p.q('[data-azione="prepara"]'));
  await p.aspetta(() => p.s.chiamate('POST', '/server/api/lezioni').filter((x) => x.percorso === '/server/api/lezioni').length === 2);
  const corpo2 = p.s.chiamate('POST', '/server/api/lezioni').filter((x) => x.percorso === '/server/api/lezioni')[1].corpo;
  assert.deepEqual(corpo2.materiali.sort((a, b) => a - b), [7, 8, 10]);
  assert.equal(corpo2.domande_saltabili, true);
});

test('In preparazione: si chiede ogni 20 s (qui accorciato) e a fine lavoro compare la spunta con Ascolta', opz, async () => {
  const p = await apri();
  p.A.nuovaLezione(1);
  await p.aspetta(() => /0,25 €/.test(p.q('[data-azione="prepara"]').textContent));
  p.clic(p.q('[data-azione="prepara"]'));
  await p.aspetta(() => p.q('.fasi'));
  const id = p.s.chiamate('POST', '/server/api/lezioni').length && p.s.esempio.stato.lezioni[0].id;
  // L'esempio fa avanzare la lezione col tempo: la si porta subito alla fine.
  p.s.esempio.stato.lezioni[0]._t0 = Date.now() - 60000;
  await p.aspetta(() => p.q('[data-azione="ascolta-pronta"]'), 3000);
  assert.ok(p.s.chiamate('GET', '/server/api/lezioni/' + id).length >= 1);
  assert.ok(p.q('.spunta-grande'));
});

// --- Materie ---------------------------------------------------------------------------------------

test('Nuova materia: anteprima dal vivo, primo colore libero, Crea spento senza nome, errore sotto il nome', opz, async () => {
  const p = await apri();
  p.A.vai('#materie');
  await p.aspetta(() => p.q('[data-azione="nuova-materia"]') && p.q('.scheda-materia'));
  assert.match(p.testo(), /Storia/);
  assert.match(p.testo(), /3 da ripassare/);
  p.clic(p.q('[data-azione="nuova-materia"]'));
  await p.aspetta(() => p.q('#materia-nome'));
  const crea = p.q('[data-azione="salva-materia"]');
  assert.equal(crea.disabled, true);
  // Usati: ambra, petrolio, viola, blu, verde → il primo libero è rosa.
  assert.equal(p.q('.cerchio-colore[aria-checked="true"]').dataset.colore, 'rosa');
  p.scrivi(p.q('#materia-nome'), 'italiano');
  p.scrivi(p.q('#materia-prof'), 'Bianchi');
  assert.match(p.q('.anteprima-materia').textContent, /italiano/);
  assert.match(p.q('.anteprima-materia').textContent, /Prof\. Bianchi/);
  assert.equal(crea.disabled, false);
  p.clic(p.q('.cerchio-colore[data-colore="oliva"]'));
  assert.ok(p.q('.anteprima-materia').classList.contains('m-oliva'));
  // Nome già usato: l'errore del server sotto il campo.
  p.scrivi(p.q('#materia-nome'), 'STORIA');
  p.clic(crea);
  await p.aspetta(() => p.q('.errore-campo').textContent);
  assert.match(p.q('.errore-campo').textContent, /C'è già una materia con questo nome|Esiste già/);
  p.scrivi(p.q('#materia-nome'), 'Italiano');
  p.clic(p.q('[data-azione="salva-materia"]'));
  await p.aspetta(() => /#materia\/\d+/.test(p.w.location.hash) && p.q('.testata-materia h1') && p.q('.testata-materia h1').textContent === 'Italiano');
  assert.ok(p.q('.testata-materia').classList.contains('m-oliva'));
});

test('Modifica materia: Elimina chiede conferma dicendo cosa sparisce', opz, async () => {
  const p = await apri();
  p.A.vai('#materia/1');
  await p.aspetta(() => p.q('[aria-label="Modifica materia"]'));
  p.clic(p.q('[aria-label="Modifica materia"]'));
  await p.aspetta(() => p.q('[data-azione="elimina-materia"]'));
  p.clic(p.q('[data-azione="elimina-materia"]'));
  await p.aspetta(() => p.q('[data-azione="conferma"]'));
  assert.match(p.testo(), /Spariscono verifiche, appunti, lezioni e audio di Storia/);
  p.clic(p.q('[data-azione="conferma"]'));
  await p.aspetta(() => p.s.chiamate('DELETE', '/server/api/materie/1').length === 1);
  assert.deepEqual(p.s.chiamate('DELETE', '/server/api/materie/1')[0].corpo, { conferma: true });
});

test('Materia: Da ripassare apre il lettore fermo su quella domanda', opz, async () => {
  const p = await apri();
  p.A.vai('#materia/1');
  await p.aspetta(() => p.q('.riga-ripasso'));
  assert.match(p.q('.riga-ripasso').textContent, /Quasi/);
  p.clic(p.q('.riga-ripasso'));
  await p.aspetta(() => p.q('#risposta-domanda'));
  assert.match(p.w.location.hash, /#lettore\/12/);
  assert.match(p.q('.scheda-domanda').textContent, /Perché Tiberio passò gli ultimi anni a Capri/);
  assert.equal(p.audio.paused, true);
});

test('file trascinato nella materia: il riquadro si accende, poi tipo/titolo/data e i byte vanno al programma', opz, async () => {
  const p = await apri();
  p.A.vai('#materia/1');
  await p.aspetta(() => p.q('.zona-trascina'));
  const file = new p.w.File(['MATERIA: Storia | PROF: Rossi'], 'verifica_12_ottobre.txt', { type: 'text/plain' });
  const dt = { types: ['Files'], files: [file], dropEffect: 'none' };
  const evento = (tipo) => { const e = new p.w.Event(tipo, { bubbles: true, cancelable: true }); e.dataTransfer = dt; return e; };
  p.w.dispatchEvent(evento('dragenter'));
  assert.ok(p.q('.zona-trascina').classList.contains('accesa'));
  const caduta = evento('drop');
  p.w.dispatchEvent(caduta);
  assert.equal(caduta.defaultPrevented, true, 'il file non apre una pagina nuova');
  assert.equal(p.q('.zona-trascina').classList.contains('accesa'), false);
  await p.aspetta(() => p.q('[data-azione="aggiungi-file"]'));
  assert.match(p.q('.foglio').textContent, /Aggiungi a Storia/);
  p.clic(p.q('[data-azione="aggiungi-file"]'));
  await p.aspetta(() => p.s.chiamate('POST', '/locale/carica').length === 1);
  const c = p.s.chiamate('POST', '/locale/carica')[0];
  const q = new URLSearchParams(c.percorso.split('?')[1]);
  assert.equal(q.get('materia_id'), '1');
  assert.equal(q.get('tipo'), 'verifica');
  assert.equal(q.get('titolo'), 'verifica 12 ottobre');
  assert.equal(q.get('nome'), 'verifica_12_ottobre.txt');
  assert.equal(c.byte, 29);
  await p.aspetta(() => p.q('.riga-materiale.in-lettura'));
});

test('un file di formato sconosciuto si ferma subito con la frase', opz, async () => {
  const p = await apri();
  p.A.vai('#materia/1');
  await p.aspetta(() => p.q('.zona-trascina'));
  p.A.suFileTrascinati([new p.w.File(['x'], 'foto.heic')]);
  await p.aspetta(() => p.q('.avviso-breve'));
  assert.match(p.q('.avviso-breve').textContent, /HEIC/);
  assert.equal(p.s.chiamate('POST', '/locale/carica').length, 0);
});

// --- Per Gemini ------------------------------------------------------------------------------------

test('Per Gemini: "Copia il testo per Gemini" diventa "Copiato" con la spunta e torna; se non va lo dice', opz, async () => {
  const p = await apri();
  p.A.vai('#gemini');
  await p.aspetta(() => /Trascrivi questa verifica/.test(p.q('#testo-gemini').textContent));
  const b = p.q('[data-azione="copia-gemini"]');
  p.clic(b);
  await p.aspetta(() => b.textContent === 'Copiato');
  assert.ok(b.classList.contains('fatto'));
  assert.match(p.s.chiamate('POST', '/locale/copia')[0].corpo.testo, /^Trascrivi questa verifica/);
  await p.aspetta(() => b.textContent === 'Copia il testo per Gemini', 1500);
  p.s.copia = false;
  p.clic(b);
  await p.aspetta(() => p.q('.errore-campo'));
  assert.match(p.q('.errore-campo').textContent, /Non sono riuscito a copiarlo/);
  // Apri Gemini: lo apre il programma nel browser.
  p.clic(p.q('[data-azione="apri-gemini"]'));
  await p.aspetta(() => p.s.chiamate('POST', '/locale/apri-gemini').length === 1);
  // Il testo resta sul computer per quando non c'è rete.
  assert.match(JSON.parse(p.w.localStorage.getItem('lectio.gemini')).testo, /Trascrivi/);
});

// --- Impostazioni ------------------------------------------------------------------------------------

test('Impostazioni: Domande predefinite, Risposte scritte, tetto; ogni cambio va al server', opz, async () => {
  const p = await apri();
  p.A.vai('#impostazioni');
  await p.aspetta(() => p.q('#imp-scritte'));
  const t = p.testo();
  for (const voce of ['Server di casa', 'Voce predefinita', 'Durata predefinita', 'Domande predefinite', 'Fermati alle domande', 'Risposte scritte', 'Velocità predefinita', 'Tetto del mese', 'Tema', 'Come funziona Lectio', 'Versione 1.0.0']) {
    assert.ok(t.includes(voce), 'manca ' + voce);
  }
  p.clic(p.pulsante('Si possono saltare'));
  await p.aspetta(() => p.s.chiamate('PATCH', '/server/api/impostazioni').length === 1);
  assert.deepEqual(p.s.chiamate('PATCH', '/server/api/impostazioni')[0].corpo, { domande_saltabili: true });
  p.q('#imp-scritte').checked = false;
  p.q('#imp-scritte').dispatchEvent(new p.w.Event('change', { bubbles: true }));
  await p.aspetta(() => p.s.chiamate('PATCH', '/server/api/impostazioni').length === 2);
  assert.deepEqual(p.s.chiamate('PATCH', '/server/api/impostazioni')[1].corpo, { risposte_scritte: false });
  p.clic(p.pulsante('Scuro'));
  await p.aspetta(() => p.s.chiamate('POST', '/locale/tema').length === 1);
  assert.equal(p.d.documentElement.getAttribute('data-tema'), 'scuro');
});

// --- Visita guidata ------------------------------------------------------------------------------------

test('visita guidata: avanza solo con l\'azione giusta, in modalità esempio, senza nessuna chiamata al server', opz, async () => {
  const p = await apri();
  await p.aspetta(() => p.q('[data-visita="prossima"]'));
  const prima = p.s.log.length;
  await p.A.avviaVisita(false);
  const fumetto = () => p.q('.visita-fumetto').textContent;
  await p.aspetta(() => /1 di 8/.test(fumetto()));
  assert.ok(p.q('[data-stato="esempio"]'), 'pillola Esempio');
  assert.match(fumetto(), /La prossima verifica/);
  assert.ok(p.q('[data-visita-azione="salta"]'));
  // Passo 1: da leggere → Avanti.
  p.clic(p.q('[data-visita-azione="avanti"]'));
  await p.aspetta(() => /2 di 8/.test(fumetto()));
  // Passo 2 è pratico: niente Avanti, e cliccare altrove non serve.
  assert.equal(p.q('[data-visita-azione="avanti"]'), null);
  p.clic(p.q('[data-blocco="alto"]'));
  p.clic(p.q('[data-azione="ascolta-prossima"]') || p.q('.voce-menu'));
  await B.dorme(500);
  assert.match(fumetto(), /2 di 8/);
  // L'azione giusta: clic su Storia.
  p.clic(p.q('[data-visita="pillola-prossima"]'));
  await p.aspetta(() => /3 di 8/.test(fumetto()));
  p.clic(p.q('[data-visita-azione="avanti"]'));
  await p.aspetta(() => /4 di 8/.test(fumetto()));
  p.clic(p.q('[data-visita="aggiungi"]'));
  await p.aspetta(() => /5 di 8/.test(fumetto()) && p.q('[data-visita="gemini-riga"]'));
  // Indietro torna al passo 4 (foglio chiuso), poi di nuovo avanti con l'azione.
  p.clic(p.q('[data-visita-azione="indietro"]'));
  await p.aspetta(() => /4 di 8/.test(fumetto()) && !p.q('.foglio'));
  p.clic(p.q('[data-visita="aggiungi"]'));
  await p.aspetta(() => /5 di 8/.test(fumetto()));
  p.clic(p.q('[data-visita-azione="avanti"]'));
  await p.aspetta(() => /6 di 8/.test(fumetto()) && !p.q('.foglio'));
  p.clic(p.q('[data-visita="nuova-lezione"]'));
  await p.aspetta(() => /7 di 8/.test(fumetto()));
  await p.aspetta(() => p.q('[data-visita-azione="avanti"]') && !p.q('[data-visita-azione="avanti"]').disabled);
  p.clic(p.q('[data-visita-azione="avanti"]'));
  await p.aspetta(() => /8 di 8/.test(fumetto()) && p.q('#risposta-domanda'), 4000);
  assert.match(fumetto(), /Scrivi la tua risposta e premi Invia/);
  assert.match(p.q('.tasto-disegnato').textContent, /Ctrl.*Invio/);
  // Una parola sola basta (correzione finta).
  p.scrivi(p.q('#risposta-domanda'), 'Seiano');
  p.clic(p.q('[data-azione="invia-risposta"]'));
  await p.aspetta(() => /Ecco fatto/.test(fumetto()), 4000);
  // Nessuna chiamata al server durante tutta la visita.
  const durante = p.s.log.slice(prima);
  assert.deepEqual(durante.filter((x) => x.percorso.startsWith('/server/')), []);
  assert.deepEqual(durante.filter((x) => x.percorso.startsWith('/locale/')).map((x) => x.percorso), []);
  p.clic(p.q('[data-visita-azione="inizia"]'));
  await p.aspetta(() => !p.q('.visita'));
  await p.aspetta(() => p.s.chiamate('POST', '/locale/tutorial-visto').length === 1);
  assert.equal(p.q('[data-stato="esempio"]'), null);
});

test('visita guidata: "Salta la visita" e Esc la chiudono e si torna ai dati veri', opz, async () => {
  const p = await apri();
  await p.aspetta(() => p.q('[data-visita="prossima"]'));
  await p.A.avviaVisita(false);
  await p.aspetta(() => p.q('[data-visita-azione="salta"]'));
  p.tasto('Escape', {}, p.d.body);
  await p.aspetta(() => !p.q('.visita'));
  assert.equal(p.w.LectioApi.inEsempio(), false);
  await p.A.avviaVisita(false);
  await p.aspetta(() => p.q('[data-visita-azione="salta"]'));
  p.clic(p.q('[data-visita-azione="salta"]'));
  await p.aspetta(() => !p.q('.visita'));
  assert.equal(p.w.LectioApi.inEsempio(), false);
});
