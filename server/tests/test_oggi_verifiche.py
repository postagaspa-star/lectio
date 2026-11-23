"""Oggi dal server 1.4.5 (richieste di Andrea del 23/11/2026): la prossima verifica ha anche il suo schema, le verifiche
di oggi sono fatte dalle 13, "Dopo la verifica" si può chiudere e il Ripasso lampo di Oggi conta solo le carte per le
verifiche in arrivo (contratto 7, 10, 20)."""

import pytest

from aiuti import aggiungi_testo, crea_materia, lezione_pronta
from app import db
from test_eventi_oggi import evento
from test_studio import crea_schema, materia_pronta, studio_tutto

pagamento = pytest.mark.pagamento


def oggi(client, auth) -> dict:
    r = client.get("/api/oggi", headers=auth)
    assert r.status_code == 200, r.text
    return r.json()


def carte_dello_schema(servizi, schema_id: int) -> set[int]:
    with db.aperta(servizi.settings.db_path) as conn:
        return {r[0] for r in conn.execute("SELECT id FROM carte WHERE chiave LIKE ?", (f"schema:{schema_id}:%",))}


def test_le_verifiche_di_oggi_sono_fatte_dalle_13_e_il_racconto_si_chiude(client, auth, servizi, orologio):
    m = crea_materia(client, auth)
    aggiungi_testo(client, auth, m["id"])
    di_oggi = evento(client, auth, m["id"], "2026-10-27")  # l'orologio delle prove parte alle 10 di martedì 27
    lezione_pronta(client, auth, servizi, m["id"], evento_id=di_oggi["id"])
    domani = evento(client, auth, m["id"], "2026-10-28", argomento="Augusto")
    o = oggi(client, auth)
    assert o["prossima"]["evento"]["id"] == di_oggi["id"] and o["prossima"]["tra_giorni"] == 0
    assert o["da_raccontare"] == [] and [x["evento"]["id"] for x in o["piu_avanti"]] == [domani["id"]]
    orologio.avanza(hours=2, minutes=59)  # 12:59 a Roma: ancora in arrivo
    assert oggi(client, auth)["prossima"]["evento"]["id"] == di_oggi["id"]
    orologio.avanza(minutes=1)  # 13:00: fatta
    o = oggi(client, auth)
    assert o["prossima"]["evento"]["id"] == domani["id"] and o["prossima"]["tra_giorni"] == 1 and o["piu_avanti"] == []
    assert [x["evento"]["id"] for x in o["da_raccontare"]] == [di_oggi["id"]]

    # Chiusa senza raccontare: non torna più, e l'esito resta vuoto.
    r = client.post(f"/api/eventi/{di_oggi['id']}/nascondi-racconto", headers=auth)
    assert r.status_code == 200 and r.json() == {"ok": True}
    assert oggi(client, auth)["da_raccontare"] == []
    orologio.avanza(days=1)
    assert oggi(client, auth)["da_raccontare"] == []
    assert next(e for e in client.get("/api/eventi", headers=auth).json() if e["id"] == di_oggi["id"])["esito"] is None
    assert client.post("/api/eventi/9999/nascondi-racconto", headers=auth).status_code == 404


@pagamento
def test_la_prossima_ha_il_suo_schema_e_dopo_si_racconta_anche_con_lo_schema(client, auth, servizi, orologio):
    m = materia_pronta(client, auth, servizi)
    e = evento(client, auth, m["id"], "2026-10-29")
    o = oggi(client, auth)
    assert o["prossima"]["schema"] is None and o["prossima"]["lezione"] is None
    s = crea_schema(client, auth, m["id"], evento_id=e["id"])
    assert oggi(client, auth)["prossima"]["schema"]["id"] == s["id"]
    assert oggi(client, auth)["prossima"]["schema"]["stato"] == "in_coda"
    studio_tutto(servizi)
    schema = oggi(client, auth)["prossima"]["schema"]
    assert schema["stato"] == "pronto" and schema["evento_id"] == e["id"] and schema["contenuto"] is None
    # Uno schema nuovo ancora in preparazione non nasconde quello pronto.
    crea_schema(client, auth, m["id"], evento_id=e["id"], argomento="Tiberio")
    assert oggi(client, auth)["prossima"]["schema"]["id"] == s["id"]
    # Senza lezione, con lo schema pronto: il giorno della verifica dalle 13 si racconta.
    orologio.avanza(days=2, hours=3)
    o = oggi(client, auth)
    assert o["prossima"] is None and [x["evento"]["id"] for x in o["da_raccontare"]] == [e["id"]]


@pagamento
def test_il_ripasso_lampo_di_oggi_conta_solo_le_verifiche_in_arrivo(client, auth, servizi, orologio):
    m = materia_pronta(client, auth, servizi)
    e = evento(client, auth, m["id"], "2026-10-29")
    con_data = crea_schema(client, auth, m["id"], evento_id=e["id"])
    senza_data = crea_schema(client, auth, m["id"], argomento="Tiberio e Seiano")
    studio_tutto(servizi)
    per_verifica, altre = carte_dello_schema(servizi, con_data["id"]), carte_dello_schema(servizi, senza_data["id"])
    assert per_verifica and altre
    assert oggi(client, auth)["lampo"]["da_fare"] == len(per_verifica)
    solo = client.get("/api/lampo", params={"solo_verifiche": True, "limite": 50}, headers=auth).json()
    assert solo["da_fare"] == len(per_verifica) and {c["id"] for c in solo["carte"]} == per_verifica
    # Nella materia (e senza il filtro) restano tutte.
    tutte = client.get("/api/lampo", params={"materia_id": m["id"], "limite": 50}, headers=auth).json()
    assert tutte["da_fare"] == len(per_verifica | altre)
    assert client.get(f"/api/materie/{m['id']}", headers=auth).json()["conteggi"]["lampo"] == len(per_verifica | altre)
    # Il giorno della verifica contano fino alle 13, poi la verifica è fatta.
    orologio.avanza(days=2)
    assert oggi(client, auth)["lampo"]["da_fare"] == len(per_verifica)
    orologio.avanza(hours=3)
    assert oggi(client, auth)["lampo"] == {"da_fare": 0, "minuti": 0}
    assert client.get("/api/lampo", params={"solo_verifiche": True}, headers=auth).json()["carte"] == []
