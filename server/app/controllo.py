"""Il controllo dopo la scrittura (contratto 17.1).

Dare al modello solo i pezzi giusti non basta a obbligarlo. Per questo ogni parte scritta si
rilegge: un secondo passaggio confronta le frasi con i pezzi usati, le regole fisse di stile.py
trovano quello che suona finto. Il pezzo si fa riscrivere una volta sola; quello che resta fuori
dai documenti si toglie."""

import json
import logging

from . import ai, prompt_studio, stile

log = logging.getLogger("uvicorn.error")

CHIAVI_DI_TESTO = {"testo", "risposta", "riepilogo", "errori", "voci", "paragrafi", "introduzione", "chiusura",
                   "righe", "valore", "risposta_attesa", "carte", "domande", "campi", "blocchi", "sezioni", "parti", "punti"}
FRASI_MASSIME = 120


def foglie(o, chiave: str | None = None):
    """Ogni testo dell'oggetto, come (contenitore, chiave): titoli, tipi e fonti restano fuori."""
    if isinstance(o, dict):
        for k, v in o.items():
            if k not in CHIAVI_DI_TESTO:
                continue
            if isinstance(v, str):
                yield o, k
            elif isinstance(v, (list, dict)):
                yield from foglie(v, k)
    elif isinstance(o, list):
        for i, v in enumerate(o):
            if isinstance(v, str):
                yield o, i
            elif isinstance(v, (list, dict)):
                yield from foglie(v, chiave)


def _elenco_frasi(oggetto) -> list[tuple]:
    """(contenitore, chiave, indice della frase nel suo testo, frase); le domande non hanno fatti da controllare."""
    elenco = []
    for contenitore, chiave in foglie(oggetto):
        for i, frase in enumerate(stile.dividi_frasi(contenitore[chiave])):
            if not frase.rstrip().endswith("?"):
                elenco.append((contenitore, chiave, i, frase))
    return elenco[:FRASI_MASSIME]


def togli_vuoti(o) -> None:
    """Dopo aver tolto delle frasi: via gli elementi rimasti senza niente dentro."""
    if isinstance(o, dict):
        for v in o.values():
            togli_vuoti(v)
        if isinstance(o.get("righe"), list):
            o["righe"] = [r for r in o["righe"] if not (isinstance(r, list) and any(not str(c).strip() for c in r))]
    elif isinstance(o, list):
        for v in o:
            togli_vuoti(v)
        tenuti = []
        for v in o:
            if isinstance(v, str) and not v.strip():
                continue
            if isinstance(v, dict) and v.get("tipo") != "immagine" and (
                    ("testo" in v and not str(v["testo"]).strip())
                    or ("risposta" in v and not str(v["risposta"]).strip())
                    or ("voci" in v and not v["voci"])
                    or ("righe" in v and not v["righe"])):
                continue
            tenuti.append(v)
        o[:] = tenuti


def togli_frasi(fuori: list[tuple]) -> None:
    """Toglie le frasi indicate, una voce alla volta."""
    per_voce: dict[tuple[int, object], tuple] = {}
    for contenitore, chiave, i, _ in fuori:
        per_voce.setdefault((id(contenitore), chiave), (contenitore, chiave, set()))[2].add(i)
    for contenitore, chiave, indici in per_voce.values():
        frasi = stile.dividi_frasi(contenitore[chiave])
        contenitore[chiave] = " ".join(f for i, f in enumerate(frasi) if i not in indici)


def _fonti_in_testo(brani: list[dict], altro: str) -> str:
    parti = [f"[{b['id']}] {b['testo']}" for b in brani]
    if altro:
        parti.append(altro)
    return "\n\n".join(parti)


def _valida_controllo(o: dict) -> list[str]:
    if not isinstance(o.get("fuori"), list):
        return ['serve "fuori", una lista (vuota se va tutto bene)']
    return []


def frasi_fuori(ctx, oggetto, fonti: str, *, cosa: str, materia_id, lezione_id, prima, riferimento=None, costi=None) -> list[tuple]:
    elenco = _elenco_frasi(oggetto)
    if not elenco or not fonti.strip():
        return []
    risposta = ai.chiedi_json(
        cassa=ctx.cassa, fornitore=ctx.testo, compito="verifica",
        messaggi=prompt_studio.messaggi_verifica([e[3] for e in elenco], fonti),
        modello=ctx.settings.modello_piano, valida=_valida_controllo, max_token=1500, cosa=f"Controllo · {cosa}",
        dati={}, materia_id=materia_id, lezione_id=lezione_id, prima_di_ogni_chiamata=prima, riferimento=riferimento, costi=costi,
    )
    trovate = []
    for voce in risposta["fuori"]:
        try:
            n = int(voce["n"] if isinstance(voce, dict) else voce)
        except (TypeError, ValueError, KeyError):
            continue
        if 1 <= n <= len(elenco):
            trovate.append(elenco[n - 1])
    return trovate


def _stile_di(oggetto, fonti: str) -> list[str]:
    testo = " ".join(str(c[k]) for c, k in foglie(oggetto))
    return stile.problemi(testo, fonti)


def rifinisci(oggetto, parlato: bool = True):
    for contenitore, chiave in foglie(oggetto):
        contenitore[chiave] = stile.ripulisci(contenitore[chiave], parlato=parlato)
    togli_vuoti(oggetto)
    return oggetto


def rivedi(ctx, oggetto: dict, brani: list[dict], *, compito: str, messaggi: list[dict], modello: str, valida,
           max_token: int, cosa: str, altro: str = "", materia_id=None, lezione_id=None, prima=None, dati=None,
           parlato: bool = True, riferimento=None, costi=None) -> dict:
    """Il pezzo controllato: fatti solo dai documenti, lingua normale.
    In modalità prova non c'è un modello che possa controllare: restano le regole fisse."""
    oggetto = json.loads(json.dumps(oggetto))
    if not ctx.testo.a_pagamento:
        return rifinisci(oggetto, parlato)
    fonti = _fonti_in_testo(brani, altro)
    comuni = {"cosa": cosa, "materia_id": materia_id, "lezione_id": lezione_id, "prima": prima, "riferimento": riferimento, "costi": costi}
    fuori = frasi_fuori(ctx, oggetto, fonti, **comuni)
    da_sistemare = _stile_di(oggetto, fonti)
    if fuori or da_sistemare:
        try:
            nuovo = ai.chiedi_json(
                cassa=ctx.cassa, fornitore=ctx.testo, compito=compito,
                messaggi=messaggi + [
                    {"role": "assistant", "content": json.dumps(oggetto, ensure_ascii=False)},
                    {"role": "user", "content": prompt_studio.richiesta_riscrittura([e[3] for e in fuori], da_sistemare)},
                ],
                modello=modello, valida=valida, max_token=max_token, cosa=f"{cosa} (riscrittura)", dati=dati or {},
                materia_id=materia_id, lezione_id=lezione_id, prima_di_ogni_chiamata=prima, riferimento=riferimento, costi=costi,
            )
        except ai.RispostaNonUsabile:
            log.warning("%s: riscrittura non usabile, tolgo le frasi segnalate", cosa)
        else:
            oggetto = nuovo
            fuori = frasi_fuori(ctx, oggetto, fonti, **comuni)
    if fuori:
        log.info("%s: tolte %s frasi che nei documenti non ci sono", cosa, len(fuori))
        togli_frasi(fuori)
    return rifinisci(oggetto, parlato)
