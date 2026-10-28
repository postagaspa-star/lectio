/*
 * Lectio per il computer: le sole chiamate che l'interfaccia fa.
 *
 * La pagina è servita da https://lectio.locale/ e chiama SOLO /locale/* (il programma) e /server/*
 * (girato al server di casa col token, che la pagina non vede mai). Il programma le intercetta dentro
 * WebView2: niente porte aperte sul computer.
 *
 * In modalità esempio (visita guidata, foto delle pagine) le chiamate vanno a un server finto in memoria
 * (esempio.js) e non parte NIENTE verso la rete.
 *
 * Qui non si decide niente sui dati: si consegna { ok, stato, dati, rete, memoria, errore }.
 */
(function (radice) {
  'use strict';

  const ATTESA_LOCALE_MS = 10000;
  const ATTESA_ABBINAMENTO_MS = 40000;
  const ATTESA_SERVER_MS = 45000;
  const ATTESA_CARICA_MS = 200000;
  const ATTESA_SCARICA_MS = 16 * 60000;
  // La correzione di una risposta scritta: il programma aspetta fino a 200 s, la pagina un po' di più.
  const ATTESA_CORREZIONE_MS = 210000;
  const FRASE_RETE = 'Lectio non riesce a raggiungere il server di casa. Controlla la connessione a internet e riprova.';

  let esempio = null;

  function percorsoAmmesso(percorso) {
    return typeof percorso === 'string' && /^\/(locale|server)\//.test(percorso);
  }

  function attesa(percorso) {
    if (percorso === '/locale/abbina' || percorso === '/locale/scollega') return ATTESA_ABBINAMENTO_MS;
    if (/^\/locale\/lezioni\/\d+\/scarica$/.test(percorso)) return ATTESA_SCARICA_MS;
    if (percorso.startsWith('/locale/carica')) return ATTESA_CARICA_MS;
    if (/^\/server\/api\/lezioni\/\d+\/domande\/\d+\/risposta$/.test(percorso)) return ATTESA_CORREZIONE_MS;
    if (percorso.startsWith('/server/')) return ATTESA_SERVER_MS;
    return ATTESA_LOCALE_MS;
  }

  function normalizza(stato, dati, memoria) {
    const codice = Number(stato) || 0;
    const ok = codice >= 200 && codice < 300;
    let errore = null;
    if (!ok) {
      errore = (dati && typeof dati === 'object' && typeof dati.errore === 'string' && dati.errore) || 'L\'operazione non è riuscita. Riprova tra poco.';
    }
    const rete = codice === 0 || !!(dati && dati.codice === 'rete');
    return { ok, stato: codice, dati, rete, memoria: memoria || null, errore };
  }

  async function daEsempio(metodo, percorso, corpo) {
    // Un attimo d'attesa, come un server vero: così si vedono anche i segnaposto.
    await new Promise((r) => setTimeout(r, 25));
    const r = esempio.richiesta(metodo, percorso, corpo);
    return normalizza(r.stato, r.dati, null);
  }

  async function chiama(metodo, percorso, corpo, opzioni) {
    if (!percorsoAmmesso(percorso)) throw new Error('Chiamata fuori dall\'accordo: ' + percorso);
    if (esempio) return daEsempio(metodo, percorso, corpo);
    const o = opzioni || {};
    const controllo = new AbortController();
    const timer = setTimeout(() => controllo.abort(), attesa(percorso));
    try {
      const richiesta = {
        method: metodo,
        cache: 'no-store',
        credentials: 'same-origin',
        headers: { Accept: 'application/json' },
        signal: controllo.signal,
      };
      if (o.byte !== undefined) {
        // I file trascinati o scelti: byte grezzi (un ArrayBuffer arriva intero al programma).
        richiesta.headers['Content-Type'] = 'application/octet-stream';
        richiesta.body = o.byte;
      } else if (corpo !== undefined) {
        richiesta.headers['Content-Type'] = 'application/json';
        richiesta.body = JSON.stringify(corpo);
      }
      const risposta = await fetch(percorso, richiesta);
      const testo = await risposta.text();
      let dati = null;
      if (testo) {
        try { dati = JSON.parse(testo); } catch (e) { dati = null; }
      }
      return normalizza(risposta.status, dati, risposta.headers.get('X-Lectio-Memoria'));
    } catch (e) {
      return { ok: false, stato: 0, dati: null, rete: true, memoria: null, errore: FRASE_RETE };
    } finally {
      clearTimeout(timer);
    }
  }

  /** Mette un file (byte) in una materia: /locale/carica?materia_id=…&tipo=…&titolo=…&data=…&nome=… */
  function carica(byte, campi) {
    const q = new URLSearchParams();
    for (const [k, v] of Object.entries(campi || {})) if (v !== undefined && v !== null && v !== '') q.set(k, String(v));
    if (esempio) {
      // In esempio il file non va da nessuna parte: diventa una riga finta della materia.
      return daEsempio('POST', '/server/api/materie/' + campi.materia_id + '/materiale', { tipo: campi.tipo, titolo: campi.titolo, data: campi.data, testo: 'x'.repeat(Math.min(5000, byte ? byte.byteLength : 0)) });
    }
    return chiama('POST', '/locale/carica?' + q.toString(), undefined, { byte });
  }

  radice.LectioApi = {
    get: (p) => chiama('GET', p),
    post: (p, c) => chiama('POST', p, c === undefined ? {} : c),
    put: (p, c) => chiama('PUT', p, c),
    patch: (p, c) => chiama('PATCH', p, c),
    elimina: (p, c) => chiama('DELETE', p, c),
    carica,
    /** Accende (con un server finto di esempio.js) o spegne la modalità esempio. */
    modoEsempio(server) { esempio = server || null; },
    inEsempio() { return !!esempio; },
    FRASE_RETE,
  };
})(window);
