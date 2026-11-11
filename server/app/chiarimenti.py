""""Non ho capito" (contratto 19): rispiega un passaggio usando solo i documenti. Se la risposta
nei documenti non c'è, lo dice: non è un errore."""

import json
import sqlite3

from . import ai, clock, controllo, memoria, prompt_studio
from .spesa import CARATTERI_BLOCCO_MASSIMI

FRASE_NON_TROVATO = "Nei tuoi documenti questo non c'è"
ULTIMI = 50


def _valida(o: dict) -> list[str]:
    if not isinstance(o.get("trovato"), bool):
        return ['serve "trovato", vero o falso']
    if o["trovato"] and not (isinstance(o.get("risposta"), str) and o["risposta"].strip()):
        return ['manca "risposta"']
    return []


def _per_api(r: sqlite3.Row) -> dict:
    return {"id": r["id"], "passaggio": r["passaggio"], "domanda": r["domanda"], "risposta": r["risposta"], "trovato": bool(r["trovato"]),
            "fonti": json.loads(r["fonti"] or "[]"), "costo_eur": round(float(r["costo_eur"] or 0), 4), "quando": clock.per_utente(r["quando"])}


def passaggio_lezione(lezione: sqlite3.Row, posizione: float) -> str:
    """Il pezzo di lezione che si stava ascoltando (dai tempi del capitolo, in proporzione)."""
    capitoli = json.loads(lezione["capitoli"] or "[]")
    testi = {t["capitolo"]: t["paragrafi"] for t in json.loads(lezione["testo"] or "[]")}
    if not capitoli:
        return ""
    capitolo = next((c for c in capitoli if c["inizio_s"] <= posizione < c["fine_s"]), capitoli[-1])
    paragrafi = testi.get(capitolo["n"]) or []
    if not paragrafi:
        return ""
    durata = max(0.1, capitolo["fine_s"] - capitolo["inizio_s"])
    quota = max(0.0, min(1.0, (posizione - capitolo["inizio_s"]) / durata))
    totale = sum(len(p) for p in paragrafi) or 1
    fin_qui = 0
    for i, p in enumerate(paragrafi):
        fin_qui += len(p)
        if fin_qui / totale >= quota:
            return "\n".join(paragrafi[max(0, i - 1): i + 1]) if len(p) < 200 else p
    return paragrafi[-1]


def chiedi(ctx, conn: sqlite3.Connection, materia: sqlite3.Row, *, passaggio: str, domanda: str, lezione_id: int | None,
           schema_id: int | None) -> dict:
    passaggio, domanda = (passaggio or "").strip()[:4000], (domanda or "").strip()[:300]
    memoria.assicura(conn, materia["id"], ctx.settings.dati_dir)
    tutti, _ = memoria.brani_di(conn, materia["id"])
    costi: list[float] = []
    trovato, risposta, fonti = False, None, []
    cosa_manca = None
    if tutti:
        brani = memoria.scegli(ctx, f"{passaggio} {domanda}".strip(), tutti, CARATTERI_BLOCCO_MASSIMI, materia_id=materia["id"])
        nome = {"nome": materia["nome"], "prof": materia["prof"]}
        messaggi = prompt_studio.messaggi_chiarimento(nome, passaggio, domanda, brani)
        # 1.3.4: il modello piccolo. Provato l'11/11/2026 su 3 domande vere: risposte uguali a quelle di Medium
        # (anche il "nei documenti non c'è" onesto), 0,0007 € a domanda invece di 0,0074 €.
        modello = ctx.settings.modello_piano
        dati = {"brani": brani}
        o = ai.chiedi_json(cassa=ctx.cassa, fornitore=ctx.testo, compito="chiarimento", messaggi=messaggi, modello=modello, valida=_valida,
                           max_token=700, cosa="Non ho capito", dati=dati, materia_id=materia["id"], costi=costi)
        cosa_manca = o.get("cosa_manca")
        if o["trovato"]:
            riveduto = controllo.rivedi(ctx, o, brani, compito="chiarimento", messaggi=messaggi, modello=modello, valida=_valida, max_token=700,
                                        cosa="Non ho capito", materia_id=materia["id"], dati=dati, costi=costi)
            risposta = str(riveduto.get("risposta") or "").strip()
            trovato = bool(risposta)
            if trovato:
                fonti = memoria.fonti_di(riveduto.get("fonti") or [b["id"] for b in brani[:2]], brani)
    if not trovato:
        coda = f" Manca: {str(cosa_manca).strip().rstrip('.')}." if cosa_manca else ""
        risposta = f"{FRASE_NON_TROVATO}.{coda} Se ce l'hai sul libro o negli appunti, aggiungilo e chiedimelo di nuovo."
    cur = conn.execute(
        "INSERT INTO chiarimenti (materia_id, lezione_id, schema_id, passaggio, domanda, risposta, trovato, fonti, costo_eur, quando)"
        " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        (materia["id"], lezione_id, schema_id, passaggio, domanda, risposta, int(trovato), json.dumps(fonti, ensure_ascii=False), sum(costi), clock.adesso()))
    return _per_api(conn.execute("SELECT * FROM chiarimenti WHERE id = ?", (cur.lastrowid,)).fetchone())


def elenco(conn: sqlite3.Connection, materia_id: int) -> list[dict]:
    return [_per_api(r) for r in conn.execute("SELECT * FROM chiarimenti WHERE materia_id = ? ORDER BY id DESC LIMIT ?", (materia_id, ULTIMI))]
