/*
 * Lectio per il computer: cartelle e zip.
 * Da quello che Andrea sceglie o trascina (file, zip, cartelle, anche uno dentro l'altro) tira fuori i file che Lectio sa
 * leggere, uno per uno. Lo zip non si carica tutto in memoria: si legge l'indice in fondo al file e ogni file si
 * scompatta solo quando parte verso il server. I file che Lectio non legge (video, Excel, …) restano fuori e si dice quali.
 * Si prova con Node (pc/prove/js/archivi.test.js).
 */
(function (radice, fabbrica) {
  'use strict';
  const L = radice && radice.LectioLogica ? radice.LectioLogica : (typeof require === 'function' ? require('./logica.js') : null);
  const Ar = fabbrica(L);
  if (typeof module === 'object' && module.exports) module.exports = Ar;
  if (radice) radice.LectioArchivi = Ar;
})(typeof window !== 'undefined' ? window : null, function (L) {
  'use strict';

  /** File mandati in una volta: oltre, si mandano i primi e si dice di aggiungere gli altri dopo. */
  const MASSIMO_FILE = 300;
  /** Uno zip dentro lo zip si apre in memoria: fino a 200 MB. */
  const MASSIMO_ZIP_DENTRO = 200 * 1024 * 1024;
  /** Zip dentro zip dentro zip: oltre il terzo livello non si scende. */
  const PROFONDITA = 3;
  /** I nomi negli zip vecchi di Windows (senza UTF-8) sono nella code page 850 dell'Europa occidentale. */
  const CP850 =
    'ÇüéâäàåçêëèïîìÄÅ' + 'ÉæÆôöòûùÿÖÜø£Ø×ƒ' + 'áíóúñÑªº¿®¬½¼¡«»' + '░▒▓│┤ÁÂÀ©╣║╗╝¢¥┐' +
    '└┴┬├─┼ãÃ╚╔╩╦╠═╬¤' + 'ðÐÊËÈıÍÎÏ┘┌█▄¦Ì▀' + 'ÓßÔÒõÕµþÞÚÛÙýÝ¯´' + '­±‗¾¶§÷¸°¨·¹³²■ ';

  /** Perché un file resta fuori: [una cosa, più cose]. */
  const MOTIVI = {
    formato: ['che Lectio non sa leggere', 'che Lectio non sa leggere'],
    heic: ['in HEIC (salvalo come JPG)', 'in HEIC (salvali come JPG)'],
    grande: ['più grande di 20 MB', 'più grandi di 20 MB'],
    vuoto: ['vuoto', 'vuoti'],
    password: ['protetto da password', 'protetti da password'],
    compressione: ['compresso in un modo che non so aprire', 'compressi in un modo che non so aprire'],
    rotto: ['che non riesco ad aprire', 'che non riesco ad aprire'],
  };

  class ZipRotto extends Error {}

  const u16 = (v, i) => v.getUint16(i, true);
  const u32 = (v, i) => v.getUint32(i, true);
  const u64 = (v, i) => Number(v.getBigUint64(i, true));

  function nomeBase(percorso) {
    const parti = String(percorso || '').split(/[\\/]+/).filter(Boolean);
    return parti.length ? parti[parti.length - 1] : '';
  }

  function eZip(nome) {
    return /\.zip$/i.test(String(nome || ''));
  }

  /** Avanzi che nessuno vuole caricare: cartelle e file nascosti, quelli del Mac, Thumbs.db, i file "~$" di Word aperto. */
  function daIgnorare(percorso) {
    const parti = String(percorso || '').split(/[\\/]+/).filter(Boolean);
    if (!parti.length) return true;
    if (parti.some((p) => p === '__MACOSX' || p.startsWith('.'))) return true;
    const nome = parti[parti.length - 1].toLowerCase();
    return nome === 'thumbs.db' || nome === 'desktop.ini' || nome.startsWith('~$');
  }

  /** null se Lectio lo legge, altrimenti il motivo (chiave di MOTIVI). */
  function motivo(nome, byte) {
    const e = L.estensione(nome);
    if (e === '.heic' || e === '.heif') return 'heic';
    if (!L.ESTENSIONI.includes(e)) return 'formato';
    if (Number(byte) > L.MASSIMO_BYTE) return 'grande';
    if (Number(byte) === 0) return 'vuoto';
    return null;
  }

  function decodificaNome(byte, utf8) {
    if (utf8) return new TextDecoder('utf-8').decode(byte);
    try {
      return new TextDecoder('utf-8', { fatal: true }).decode(byte);
    } catch (e) {
      let s = '';
      for (const b of byte) s += b < 128 ? String.fromCharCode(b) : CP850[b - 128];
      return s;
    }
  }

  async function pezzo(blob, da, a) {
    return new DataView(await blob.slice(da, a).arrayBuffer());
  }

  /** L'indice dello zip (la "directory centrale" in fondo al file), anche ZIP64. */
  async function indiceZip(blob) {
    const n = blob.size;
    if (n < 22) throw new ZipRotto();
    const coda = Math.min(n, 22 + 65535 + 20);
    const v = await pezzo(blob, n - coda, n);
    let fine = -1;
    for (let i = v.byteLength - 22; i >= 0; i--) {
      if (u32(v, i) === 0x06054b50) { fine = i; break; }
    }
    if (fine < 0) throw new ZipRotto();
    let quante = u16(v, fine + 10);
    let lunghezza = u32(v, fine + 12);
    let inizio = u32(v, fine + 16);
    if (fine >= 20 && u32(v, fine - 20) === 0x07064b50) {
      const dove = u64(v, fine - 20 + 8);
      if (dove + 56 <= n) {
        const z = await pezzo(blob, dove, dove + 56);
        if (u32(z, 0) === 0x06064b50) {
          quante = u64(z, 32);
          lunghezza = u64(z, 40);
          inizio = u64(z, 48);
        }
      }
    }
    if (inizio + lunghezza > n) throw new ZipRotto();
    const cd = await pezzo(blob, inizio, inizio + lunghezza);
    const voci = [];
    let p = 0;
    for (let k = 0; k < quante && p + 46 <= cd.byteLength; k++) {
      if (u32(cd, p) !== 0x02014b50) break;
      const segni = u16(cd, p + 8);
      const metodo = u16(cd, p + 10);
      let compresso = u32(cd, p + 20);
      let intero = u32(cd, p + 24);
      const ln = u16(cd, p + 28);
      const le = u16(cd, p + 30);
      const lc = u16(cd, p + 32);
      let locale = u32(cd, p + 42);
      if (p + 46 + ln + le > cd.byteLength) break;
      let nome = decodificaNome(new Uint8Array(cd.buffer, cd.byteOffset + p + 46, ln), (segni & 0x800) !== 0);
      let q = p + 46 + ln;
      const fineExtra = q + le;
      while (q + 4 <= fineExtra) {
        const id = u16(cd, q);
        const dim = u16(cd, q + 2);
        const dati = q + 4;
        if (dati + dim > fineExtra) break;
        if (id === 0x0001) {
          // ZIP64: i numeri che non stanno in 4 byte, nell'ordine intero, compresso, posizione.
          let r = dati;
          if (intero === 0xFFFFFFFF && r + 8 <= dati + dim) { intero = u64(cd, r); r += 8; }
          if (compresso === 0xFFFFFFFF && r + 8 <= dati + dim) { compresso = u64(cd, r); r += 8; }
          if (locale === 0xFFFFFFFF && r + 8 <= dati + dim) { locale = u64(cd, r); r += 8; }
        } else if (id === 0x7075 && dim > 5) {
          // Il nome in UTF-8 scritto a parte (Info-ZIP) per gli zip senza il segno UTF-8.
          try {
            nome = new TextDecoder('utf-8', { fatal: true }).decode(new Uint8Array(cd.buffer, cd.byteOffset + dati + 5, dim - 5));
          } catch (e) { /* resta quello di prima */ }
        }
        q = dati + dim;
      }
      p = fineExtra + lc;
      nome = nome.replace(/\\/g, '/');
      voci.push({ nome, metodo, cifrato: (segni & 1) !== 0, compresso, intero, locale, cartella: nome.endsWith('/') });
    }
    return voci;
  }

  /** Scompatta un pezzo "deflate" fino a `atteso` byte: se ne escono di più lo zip mente e ci si ferma. */
  async function scompatta(blob, atteso) {
    if (typeof DecompressionStream !== 'function') throw new ZipRotto();
    const lettore = blob.stream().pipeThrough(new DecompressionStream('deflate-raw')).getReader();
    const uscita = new Uint8Array(atteso);
    let n = 0;
    for (;;) {
      const { done, value } = await lettore.read();
      if (done) break;
      if (n + value.length > atteso) {
        try { await lettore.cancel(); } catch (e) { /* già chiuso */ }
        throw new ZipRotto();
      }
      uscita.set(value, n);
      n += value.length;
    }
    if (n !== atteso) throw new ZipRotto();
    return uscita.buffer;
  }

  /** I byte di un file dello zip (solo quando serve). */
  async function datiVoce(blob, v) {
    const testa = await pezzo(blob, v.locale, v.locale + 30);
    if (testa.byteLength < 30 || u32(testa, 0) !== 0x04034b50) throw new ZipRotto();
    const inizio = v.locale + 30 + u16(testa, 26) + u16(testa, 28);
    if (inizio + v.compresso > blob.size) throw new ZipRotto();
    const dati = blob.slice(inizio, inizio + v.compresso);
    if (v.metodo === 0) {
      if (v.compresso !== v.intero) throw new ZipRotto();
      return dati.arrayBuffer();
    }
    return scompatta(dati, v.intero);
  }

  function leggiFile(f) {
    if (typeof f.arrayBuffer === 'function') return f.arrayBuffer();
    return new Promise((ok, no) => {
      const r = new FileReader();
      r.onload = () => ok(r.result);
      r.onerror = () => no(r.error);
      r.readAsArrayBuffer(f);
    });
  }

  const inOrdine = (a, b) => a.localeCompare(b, 'it', { numeric: true, sensitivity: 'base' });

  /** Le voci di una cartella trascinata (le dà a gruppi: si chiede finché non arriva un gruppo vuoto). */
  function vociCartella(cartella) {
    const lettore = cartella.createReader();
    const tutte = [];
    return new Promise((ok, no) => {
      const giro = () => lettore.readEntries((gruppo) => {
        if (!gruppo.length) { ok(tutte); return; }
        tutte.push(...gruppo);
        giro();
      }, no);
      giro();
    });
  }

  function fileDi(voce) {
    return new Promise((ok, no) => voce.file(ok, no));
  }

  function nuovo() {
    return { buoni: [], saltati: [], radici: [], troppi: false };
  }

  function saltato(st, percorso, perche, byte) {
    st.saltati.push({ nome: nomeBase(percorso), percorso, motivo: perche, byte: byte == null ? null : byte });
  }

  function pieno(st) {
    if (st.buoni.length >= MASSIMO_FILE) { st.troppi = true; return true; }
    return false;
  }

  function voce(st, radice, percorso, byte, leggi) {
    const nome = nomeBase(percorso);
    const m = motivo(nome, byte);
    if (m) { saltato(st, percorso, m, byte); return; }
    st.buoni.push({ name: nome, size: byte, percorso, radice, leggi });
  }

  async function daZip(st, radice, blob, percorso, profondita) {
    if (profondita >= PROFONDITA) { saltato(st, percorso, 'rotto'); return; }
    let voci;
    try {
      voci = await indiceZip(blob);
    } catch (e) {
      saltato(st, percorso, 'rotto');
      return;
    }
    voci.sort((a, b) => inOrdine(a.nome, b.nome));
    for (const v of voci) {
      if (v.cartella || daIgnorare(v.nome)) continue;
      if (pieno(st)) return;
      const p = percorso + '/' + v.nome;
      if (v.cifrato) { saltato(st, p, 'password'); continue; }
      if (v.metodo !== 0 && v.metodo !== 8) { saltato(st, p, 'compressione'); continue; }
      if (eZip(v.nome)) {
        if (v.intero > MASSIMO_ZIP_DENTRO) { saltato(st, p, 'grande'); continue; }
        let dentro;
        try {
          dentro = new Blob([await datiVoce(blob, v)]);
        } catch (e) {
          saltato(st, p, 'rotto');
          continue;
        }
        await daZip(st, radice, dentro, p, profondita + 1);
        continue;
      }
      voce(st, radice, p, v.intero, () => datiVoce(blob, v));
    }
  }

  async function daFile(st, radice, file, percorso, profondita) {
    if (daIgnorare(percorso)) return;
    if (pieno(st)) return;
    if (eZip(file.name)) await daZip(st, radice, file, percorso, profondita);
    else voce(st, radice, percorso, file.size, () => leggiFile(file));
  }

  async function daCartella(st, radice, cartella, percorso) {
    let figli;
    try {
      figli = await vociCartella(cartella);
    } catch (e) {
      saltato(st, percorso, 'rotto');
      return;
    }
    figli.sort((a, b) => inOrdine(a.name, b.name));
    for (const f of figli) {
      const p = percorso + '/' + f.name;
      if (daIgnorare(p)) continue;
      if (pieno(st)) return;
      if (f.isDirectory) {
        await daCartella(st, radice, f, p);
      } else if (f.isFile) {
        let file;
        try {
          file = await fileDi(f);
        } catch (e) {
          saltato(st, p, 'rotto');
          continue;
        }
        await daFile(st, radice, file, p, 0);
      }
    }
  }

  /**
   * Da file, zip e cartelle (quelle trascinate: { cartella: FileSystemDirectoryEntry }; quelle scelte col pulsante:
   * file con webkitRelativePath) all'elenco da mandare. Ogni file buono: { name, size, percorso, radice, leggi() }.
   * `radici` dice da dove vengono (per "12 file da Storia.zip").
   */
  async function espandi(elementi) {
    const st = nuovo();
    const indici = new Map();
    const radiceDi = (tipo, nome) => {
      const chiave = tipo + ':' + nome;
      if (!indici.has(chiave)) {
        indici.set(chiave, st.radici.length);
        st.radici.push({ tipo, nome });
      }
      return indici.get(chiave);
    };
    for (const el of Array.from(elementi || [])) {
      if (!el) continue;
      if (pieno(st)) break;
      if (el.cartella && el.cartella.isDirectory) {
        await daCartella(st, radiceDi('cartella', el.cartella.name), el.cartella, el.cartella.name);
        continue;
      }
      const relativo = String(el.webkitRelativePath || '');
      if (relativo.includes('/')) {
        await daFile(st, radiceDi('cartella', relativo.split('/')[0]), el, relativo, 0);
      } else {
        await daFile(st, radiceDi(eZip(el.name) ? 'zip' : 'file', el.name), el, el.name, 0);
      }
    }
    return st;
  }

  /** I file trascinati, presi SUBITO (dopo l'evento "drop" il browser non li dà più): file e cartelle. */
  function daTrascinati(dataTransfer) {
    const dt = dataTransfer;
    if (!dt) return [];
    const voci = dt.items ? Array.from(dt.items).filter((x) => x.kind === 'file') : [];
    if (voci.length && typeof voci[0].webkitGetAsEntry === 'function') {
      const presi = voci.map((x) => {
        const entrata = x.webkitGetAsEntry();
        if (entrata && entrata.isDirectory) return { cartella: entrata };
        return x.getAsFile();
      }).filter(Boolean);
      if (presi.length) return presi;
    }
    return dt.files ? Array.from(dt.files) : [];
  }

  function elencoNomi(nomi, quanti) {
    const max = quanti || 3;
    if (nomi.length <= max) {
      return nomi.length > 1 ? nomi.slice(0, -1).join(', ') + ' e ' + nomi[nomi.length - 1] : nomi.join('');
    }
    const altri = nomi.length - max;
    return nomi.slice(0, max).join(', ') + (altri === 1 ? ' e un altro' : ' e altri ' + altri);
  }

  /** Le frasi per i file rimasti fuori, una per motivo: "Lascio fuori 2 file che Lectio non sa leggere: a.mp4 e b.xlsx." */
  function frasiSaltati(saltati) {
    const gruppi = new Map();
    for (const s of saltati || []) {
      if (!gruppi.has(s.motivo)) gruppi.set(s.motivo, []);
      gruppi.get(s.motivo).push(s.nome);
    }
    const frasi = [];
    for (const [m, nomi] of gruppi) {
      const parole = MOTIVI[m] || MOTIVI.rotto;
      const uno = nomi.length === 1;
      frasi.push('Lascio fuori ' + (uno ? '1 file ' + parole[0] : nomi.length + ' file ' + parole[1]) + ': ' + elencoNomi(nomi) + '.');
    }
    return frasi;
  }

  /** "12 file da Storia.zip", "12 file dalla cartella Storia", altrimenti "12 file". */
  function descrizione(st) {
    const n = st.buoni.length;
    const quanti = n === 1 ? '1 file' : n + ' file';
    const radici = new Set(st.buoni.map((b) => b.radice));
    if (radici.size === 1) {
      const r = st.radici[[...radici][0]];
      if (r && r.tipo === 'zip') return quanti + ' da ' + r.nome;
      if (r && r.tipo === 'cartella') return quanti + ' dalla cartella ' + r.nome;
    }
    return quanti;
  }

  /** Quando non resta niente da mandare: la frase giusta. */
  function fraseNiente(st) {
    if (st.saltati.length === 1 && st.radici.length === 1 && st.radici[0].tipo === 'file') {
      const s = st.saltati[0];
      return L.controllaFile(s.nome, s.byte == null ? 1 : s.byte) || 'Questo file non lo leggo.';
    }
    if (st.saltati.length && st.saltati.every((s) => s.motivo === 'rotto')) return 'Non riesco ad aprire lo zip: forse è rovinato o non è finito di scaricare.';
    const dove = st.radici.length === 1 && st.radici[0].tipo === 'zip' ? 'Nello zip' : st.radici.length === 1 && st.radici[0].tipo === 'cartella' ? 'Nella cartella' : 'Qui';
    return dove + ' non c\'è niente che Lectio sa leggere: PDF, Word, ODT, RTF, testo o foto JPG, PNG, WEBP.';
  }

  return {
    MASSIMO_FILE, MASSIMO_ZIP_DENTRO, PROFONDITA, CP850, MOTIVI, ZipRotto,
    eZip, daIgnorare, motivo, decodificaNome, indiceZip, datiVoce, espandi, daTrascinati,
    elencoNomi, frasiSaltati, descrizione, fraseNiente, nomeBase,
  };
});
