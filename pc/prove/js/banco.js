// Banco di prova per l'interfaccia (pc/ui): carica index.html e gli script in jsdom e risponde alle richieste con
// un server finto: per /server/* quello di esempio.js (che segue il contratto), per /locale/* un programma finto.
// Si possono forzare risposte (errori, rete giù) e si tiene il registro di tutte le chiamate.
// Dati solo finti (Prof. Rossi, Bianchi).
//
// jsdom non è nel repository: si cerca con require('jsdom') (npm install jsdom in questa cartella), poi nella
// variabile d'ambiente LECTIO_JSDOM, poi nel file jsdom.percorso accanto a questo (non versionato).
// Senza jsdom i test dell'interfaccia si saltano e lo dicono.
'use strict';
const fs = require('fs');
const path = require('path');

// LECTIO_UI: un'altra cartella dell'interfaccia (per esempio quella di una versione vecchia, per vedere che i test la bocciano).
const UI = process.env.LECTIO_UI || path.join(__dirname, '..', '..', 'ui');
const SCRIPT = ['logica.js', 'esempio.js', 'api.js', 'base.js', 'pagine.js', 'lezione.js', 'lettore.js', 'visita.js', 'avvio.js'];
const dorme = (ms) => new Promise((r) => setTimeout(r, ms));
const copia = (x) => JSON.parse(JSON.stringify(x));

function caricaJsdom() {
  try { return require('jsdom'); } catch (e) { /* non installato qui */ }
  const candidati = [process.env.LECTIO_JSDOM];
  const file = path.join(__dirname, 'jsdom.percorso');
  if (fs.existsSync(file)) candidati.push(fs.readFileSync(file, 'utf8').trim());
  for (const c of candidati.filter(Boolean)) {
    try { return require(c); } catch (e) { /* percorso sbagliato */ }
  }
  return null;
}
const jsdom = caricaJsdom();
const SENZA_JSDOM = jsdom ? false : 'jsdom non trovato: npm install jsdom in pc/prove/js, oppure LECTIO_JSDOM=<cartella di jsdom>';
const Esempio = require(path.join(UI, 'esempio.js'));

/** Il server finto: stato del programma (/locale) + server di esempio (/server). */
function server(opzioni) {
  const o = opzioni || {};
  const s = {
    esempio: Esempio.crea(o),
    stato: {
      abbinato: o.abbinato !== false, server: o.abbinato === false ? null : 'https://lectio.esempio.ts.net', indirizzo_fuori: null, versione: '1.0.0',
      tutorial_visto: o.tutorialVisto !== false, tema: 'sistema', in_preparazione: false, scaricate: [], byte_scaricate: 0, diagnosi: false,
      aggiornamento: { attivo: false }, nome_dispositivo: 'Computer di prova',
    },
    log: [],
    risposta: null,   // (metodo, percorso, corpo) => [stato, dati] | 'rete' | undefined
    ritardo: () => 1, // ms di attesa per una richiesta
    copia: true,      // /locale/copia riesce?
    abbina: null,
    focus: true,      // document.hasFocus()
  };
  s.chiamate = (metodo, inizio) => s.log.filter((x) => (!metodo || x.metodo === metodo) && x.percorso.startsWith(inizio));
  s.locale = (metodo, p, corpo) => {
    let m;
    if (p === '/locale/stato') return [200, copia(s.stato)];
    if (p === '/locale/abbina') {
      if (s.abbina) return s.abbina(corpo);
      if (corpo.codice !== '12345678') return [422, { errore: 'Il codice non è giusto oppure è scaduto (vale 15 minuti). Creane uno nuovo e riprova.', codice: 'codice' }];
      s.stato.abbinato = true;
      return [200, { ok: true }];
    }
    if (p === '/locale/scollega') { s.stato.abbinato = false; return [200, { ok: true, revocato_sul_server: true }]; }
    if (p === '/locale/tutorial-visto') { s.stato.tutorial_visto = true; return [200, { ok: true }]; }
    if (p === '/locale/tema') { s.stato.tema = corpo.tema; return [200, { ok: true, tema: corpo.tema }]; }
    if (p === '/locale/copia') return s.copia ? [200, { ok: true }] : [422, { errore: 'Non sono riuscito a copiarlo: seleziona il testo qui sotto e copialo con Ctrl+C.', codice: 'appunti' }];
    if (p === '/locale/apri-gemini' || p === '/locale/tasti' || p === '/locale/aggiorna-adesso') return [200, { ok: true }];
    if ((m = /^\/locale\/lezioni\/(\d+)\/scarica$/.exec(p))) {
      const id = Number(m[1]);
      if (!s.stato.scaricate.includes(id)) s.stato.scaricate.push(id);
      return [200, { ok: true, url: 'https://audio.lectio.locale/lezione-' + id + '-aaaaaaaaaaaaaaaa.mp3', byte: 1000 }];
    }
    if ((m = /^\/locale\/lezioni\/(\d+)\/scaricamento$/.exec(p))) return [200, { in_corso: false, percentuale: 100, scaricata: true }];
    if ((m = /^\/locale\/lezioni\/(\d+)$/.exec(p)) && metodo === 'DELETE') { s.stato.scaricate = s.stato.scaricate.filter((x) => x !== Number(m[1])); return [200, { ok: true }]; }
    if (p === '/locale/lezioni/scaricate') return [200, { lezioni: [], byte: 0 }];
    if (p.startsWith('/locale/carica')) {
      const q = new URLSearchParams(p.split('?')[1] || '');
      const materia = Number(q.get('materia_id'));
      const nuovo = { id: 900 + s.log.length, materia_id: materia, tipo: q.get('tipo') || 'verifica', titolo: q.get('titolo') || q.get('nome'), data: q.get('data') || null, origine: 'file', nome_file: q.get('nome'), caratteri: 0, stato: 'in_lettura', errore: null, costo_eur: 0, creato_il: new Date().toISOString() };
      (s.esempio.stato.materiale[materia] = s.esempio.stato.materiale[materia] || []).unshift(nuovo);
      return [201, nuovo];
    }
    return [404, { errore: 'Non trovato.', codice: 'non_trovato' }];
  };
  return s;
}

const aperte = new Set();

/** Una pagina vera in jsdom col server finto. `s` = server(); opzioni.tempi accorcia i tempi. */
async function pagina(s, opzioni) {
  const o = opzioni || {};
  const html = fs.readFileSync(path.join(UI, 'index.html'), 'utf8').replace(/<script[\s\S]*?<\/script>/g, '').replace(/<meta http-equiv[^>]*>/, '');
  const dom = new jsdom.JSDOM(html, { url: 'https://lectio.locale/index.html' + (o.hash || ''), runScripts: 'outside-only', pretendToBeVisual: true });
  const w = dom.window;
  const vita = { chiusa: false };
  w.LectioTempi = Object.assign({ preparazione: 120, sincronia: 400, torna: 300, copiato: 150, lettura: 120, rete: 100000, riparte: 100, avviso: 200 }, o.tempi || {});
  w.LectioCreaAudio = (url, durata) => { const a = new w.LectioApp.AudioFinto(durata); a.url = url; a.aMano = o.audioVero !== true; p.audio = a; return a; };
  w.document.hasFocus = () => s.focus;
  // Il riquadro multimediale di Windows (Media Session): si tengono le azioni per provarle.
  const media = {};
  Object.defineProperty(w.navigator, 'mediaSession', { value: { metadata: null, setActionHandler: (nome, f) => { media[nome] = f; } }, configurable: true });
  w.HTMLElement.prototype.scrollIntoView = function () {};
  w.fetch = async (url, opz) => {
    // Pagina chiusa: la richiesta non torna più (niente codice che gira su una finestra chiusa).
    if (vita.chiusa) return new Promise(() => {});
    const op = opz || {};
    const u = new w.URL(url, 'https://lectio.locale/');
    const percorso = u.pathname + u.search;
    const metodo = (op.method || 'GET').toUpperCase();
    let corpo;
    let byte = null;
    if (typeof op.body === 'string') { try { corpo = JSON.parse(op.body); } catch (e) { corpo = op.body; } }
    else if (op.body) { byte = op.body.byteLength != null ? op.body.byteLength : null; }
    s.log.push({ metodo, percorso, corpo, byte, t: Date.now() });
    await dorme(typeof s.ritardo === 'function' ? s.ritardo(metodo, percorso, corpo) : s.ritardo);
    if (vita.chiusa) return new Promise(() => {});
    let r = s.risposta ? s.risposta(metodo, percorso, corpo) : undefined;
    if (r === 'rete') throw new TypeError('Failed to fetch');
    if (!r) {
      if (percorso.startsWith('/locale/')) r = s.locale(metodo, percorso, corpo);
      else {
        const e = s.esempio.richiesta(metodo, percorso, corpo === undefined ? undefined : corpo);
        r = [e.stato, e.dati];
      }
    }
    const [stato, dati] = r;
    return { status: stato, headers: { get: () => null }, text: async () => (dati === undefined ? '' : JSON.stringify(dati)) };
  };
  for (const f of SCRIPT) w.eval(fs.readFileSync(path.join(UI, f), 'utf8') + '\n//# sourceURL=' + f);
  const p = {
    w,
    d: w.document,
    s,
    A: w.LectioApp,
    audio: null,
    media,
    q: (sel, dentro) => (dentro || w.document).querySelector(sel),
    tutti: (sel, dentro) => Array.from((dentro || w.document).querySelectorAll(sel)),
    testo: () => w.document.body.textContent.replace(/\s+/g, ' '),
    clic(el) {
      if (!el) throw new Error('elemento da cliccare non trovato');
      el.dispatchEvent(new w.MouseEvent('mousedown', { bubbles: true }));
      el.click();
    },
    scrivi(el, testo) {
      el.focus();
      el.value = testo;
      el.dispatchEvent(new w.Event('input', { bubbles: true }));
    },
    tasto(chiave, extra, el) {
      const e = new w.KeyboardEvent('keydown', Object.assign({ key: chiave, bubbles: true, cancelable: true }, extra || {}));
      (el || w.document.activeElement || w.document.body).dispatchEvent(e);
      return e;
    },
    /** L'audio suona per `secondi` a passi da `passo` (come il lettore ogni 200 ms), finché non si ferma. */
    async suona(secondi, passo) {
      const a = p.audio;
      const n = Math.round(secondi / (passo || 0.2));
      for (let i = 0; i < n && !a.paused; i++) {
        a.avanza((passo || 0.2) * a.playbackRate);
        if (i % 10 === 9) await dorme(0);
      }
      await dorme(5);
    },
    pulsante(testo) {
      return Array.from(w.document.querySelectorAll('button')).find((b) => b.textContent.replace(/\s+/g, ' ').trim().startsWith(testo));
    },
    async aspetta(condizione, ms) {
      const fine = Date.now() + (ms || 3000);
      while (Date.now() < fine) {
        let x;
        try { x = condizione(); } catch (e) { x = null; }
        if (x) return x;
        await dorme(15);
      }
      throw new Error('tempo scaduto: ' + condizione.toString().slice(0, 160));
    },
    chiudi() {
      aperte.delete(p);
      if (p.chiusa) return;
      p.chiusa = true;
      vita.chiusa = true;
      try { if (p.audio) p.audio.pause(); } catch (e) { /* niente */ }
      w.close();
    },
  };
  aperte.add(p);
  return p;
}

function chiudiTutte() {
  for (const p of Array.from(aperte)) p.chiudi();
}

module.exports = { jsdom, SENZA_JSDOM, server, pagina, chiudiTutte, dorme, copia, L: require(path.join(UI, 'logica.js')), Esempio };
