"""1.3.9: i testi li scrive gpt-6-luna di OpenAI, con DeepSeek V4.1 Flash su Mistral come riserva.
Tutto con fornitori finti: nessuna chiamata vera a OpenAI o a Mistral."""

import json

import httpx
import pytest

from aiuti import aggiungi_testo, crea_materia, nuova_lezione, prepara_tutto
from app import ai, db
from app.config import MODELLO_RISERVA_DEFAULT, carica_settings, riassunto_config
from app.rete import ErroreFornitore, controlla
from app.spesa import Prezzi, TettoRaggiunto
from app.testo_ai import Scrittori
from app.testo_ai import TestoMistral as ChatMistral
from app.testo_ai import TestoOpenAI as ChatOpenAI

pagamento = pytest.mark.pagamento
openai = pytest.mark.openai
LUNA = "gpt-6-luna"
RISERVA = MODELLO_RISERVA_DEFAULT
CHAT_OPENAI = "api.openai.com/v1/chat/completions"


def spese(servizi, filtro="1=1"):
    with db.aperta(servizi.settings.db_path) as conn:
        return [dict(r) for r in conn.execute(f"SELECT * FROM spese WHERE {filtro} ORDER BY id")]


def chat_di(server, ospite):
    return [d for (p, d), o in zip(server.chiamate, server.ospiti) if p == "/v1/chat/completions" and o == ospite]


def chiedi(servizi, valida=lambda o: [], cosa="Prova"):
    st = servizi.settings
    return ai.chiedi_json(cassa=servizi.cassa, fornitore=servizi.ctx.testo, compito="x",
                          messaggi=[{"role": "system", "content": "Rispondi in JSON."}, {"role": "user", "content": "x"}],
                          modello=st.modello_scrittura, valida=valida, max_token=500, cosa=cosa)


# --- chi scrive, a seconda delle chiavi ---------------------------------------------------------------


def test_senza_chiave_openai_scrive_la_riserva(ambiente, monkeypatch):
    monkeypatch.setenv("LECTIO_MISTRAL_KEY", "k")
    s = carica_settings()
    assert (s.modello_piano, s.modello_scrittura, s.modello_parti) == (RISERVA, RISERVA, RISERVA)
    # Un modello Mistral scelto dal file .env resta quello.
    monkeypatch.setenv("LECTIO_MODELLO_SCRITTURA", "mistral-medium-latest")
    assert carica_settings().modello_scrittura == "mistral-medium-latest"


def test_con_la_chiave_openai_scrive_luna(ambiente, monkeypatch):
    monkeypatch.setenv("LECTIO_MISTRAL_KEY", "k")
    monkeypatch.setenv("LECTIO_OPENAI_KEY", "sk-segreta-finta")
    s = carica_settings()
    assert (s.modello_piano, s.modello_scrittura, s.modello_parti) == (LUNA, LUNA, LUNA)
    assert s.modello_riserva == RISERVA
    riga = riassunto_config(s)
    assert "openai=impostata" in riga and "sk-segreta-finta" not in riga and f"riserva={RISERVA}" in riga


def test_i_prezzi_dei_modelli_nuovi_ci_sono():
    from app.config import PREZZI_PATH_DEFAULT

    p = Prezzi.carica(PREZZI_PATH_DEFAULT)
    assert p.costo_testo(LUNA, 1_000_000, 1_000_000) == pytest.approx(0.60 * 0.86)
    assert p.costo_testo(RISERVA, 1_000_000, 1_000_000) == pytest.approx(1.50 * 0.86 * 1.1)  # Mistral, indirizzo UE
    # Su luna la cache letta costa il 10%, quella scritta 1,25 volte l'entrata (al posto del prezzo normale).
    assert p.costo_testo(LUNA, 1_000_000, 0, token_cache=400_000, token_cache_scritti=600_000) == pytest.approx(
        (0.4 * 0.01 + 0.6 * 0.125) * 0.86)
    # I token scritti in cache non possono essere più di quelli d'entrata non letti dalla cache.
    assert p.costo_testo(LUNA, 1000, 0, token_cache=800, token_cache_scritti=900) == pytest.approx(
        (800 * 0.01 + 200 * 0.125) / 1e6 * 0.86)


# --- la richiesta a OpenAI ------------------------------------------------------------------------------


def _trasporto(risposte, registro):
    def gestisci(richiesta):
        registro.append(richiesta)
        return risposte.pop(0)

    return httpx.MockTransport(gestisci)


def _risposta(testo='{"ok": true}', **uso):
    return httpx.Response(200, json={"model": LUNA, "choices": [{"message": {"content": testo}, "finish_reason": "stop"}],
                                     "usage": {"prompt_tokens": 2000, "completion_tokens": 300, **uso}})


def test_richiesta_a_openai():
    registro = []
    t = ChatOpenAI("chiave", transport=_trasporto([_risposta(prompt_tokens_details={
        "cached_tokens": 1200, "cache_write_tokens": 700})], registro))
    r = t.genera(compito="x", messaggi=[{"role": "user", "content": "Rispondi in JSON."}], modello=LUNA,
                 max_token=3500, ragionamento="low", chiave_cache="lectio:schema:3:parte")
    assert r.testo == '{"ok": true}' and r.token_entrata == 2000 and r.token_uscita == 300
    assert r.token_cache == 1200 and r.token_cache_scritti == 700 and not r.troncata
    assert registro[0].url == "https://api.openai.com/v1/chat/completions"
    assert registro[0].headers["authorization"] == "Bearer chiave"
    corpo = json.loads(registro[0].content)
    assert corpo["max_completion_tokens"] == 3500 and "max_tokens" not in corpo
    assert corpo["reasoning_effort"] == "low" and "temperature" not in corpo
    assert corpo["response_format"] == {"type": "json_object"} and corpo["prompt_cache_key"] == "lectio:schema:3:parte"


def test_openai_foto_nel_suo_formato_e_parola_json():
    registro = []
    t = ChatOpenAI("chiave", transport=_trasporto([_risposta(), _risposta()], registro))
    foto = {"role": "user", "content": [{"type": "text", "text": "Guarda la foto. Schema JSON: {}"},
                                        {"type": "image_url", "image_url": "data:image/jpeg;base64,AAAA"}]}
    t.genera(compito="foto", messaggi=[foto], modello=LUNA, max_token=80)
    inviato = json.loads(registro[0].content)["messages"]
    assert inviato == [{"role": "user", "content": [{"type": "text", "text": "Guarda la foto. Schema JSON: {}"},
                                                     {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,AAAA"}}]}]
    assert foto["content"][1]["image_url"] == "data:image/jpeg;base64,AAAA"  # il messaggio di partenza non cambia
    # Senza la parola JSON nei messaggi OpenAI rifiuterebbe la modalità JSON: la si aggiunge.
    t.genera(compito="x", messaggi=[{"role": "user", "content": "ciao"}], modello=LUNA, max_token=80)
    secondo = json.loads(registro[1].content)
    assert secondo["messages"][0] == {"role": "system", "content": "Rispondi solo con un oggetto JSON."}
    assert secondo["temperature"] == 0.4 and "reasoning_effort" not in secondo


def test_credito_openai_finito_non_si_ritenta():
    r = httpx.Response(429, json={"error": {"code": "insufficient_quota", "message": "You exceeded your current quota"}},
                       request=httpx.Request("POST", "https://api.openai.com/v1/chat/completions"))
    with pytest.raises(ErroreFornitore) as e:
        controlla(r, "OpenAI")
    assert e.value.frase.startswith("Il credito di OpenAI è finito") and not e.value.temporaneo


def test_catena():
    m = ChatMistral("k")
    o = ChatOpenAI("k")
    assert Scrittori(m, o, RISERVA).catena(LUNA) == [(o, LUNA), (m, RISERVA)]
    assert Scrittori(m, None, RISERVA).catena(LUNA) == [(m, RISERVA)]
    assert Scrittori(m, o, RISERVA).catena("mistral-medium-latest") == [(m, "mistral-medium-latest")]
    assert Scrittori(m, o, RISERVA).nome == "openai" and Scrittori(m, None, RISERVA).nome == "mistral"


# --- la riserva -----------------------------------------------------------------------------------------


@pagamento
@openai
def test_luna_risponde_e_si_paga_a_prezzo_di_luna(servizi, server):
    server.chat_forzata = '{"a": 1}'
    assert chiedi(servizi) == {"a": 1}
    assert server.ospiti == ["api.openai.com"]
    [riga] = spese(servizi)
    assert riga["fornitore"] == "openai" and riga["stato"] == "fatta" and riga["cosa"] == "Prova"
    corpo = server.chiamate[0][1]
    assert corpo["model"] == LUNA and corpo["reasoning_effort"] == "low" and corpo["max_completion_tokens"] == 500 + 3000
    entrata = sum(len(m["content"]) for m in corpo["messages"]) // 4
    assert riga["costo_eur"] == pytest.approx(servizi.cassa.prezzi.costo_testo(LUNA, entrata, len('{"a": 1}') // 4))


@pagamento
@openai
def test_openai_non_accetta_la_chiave_passa_subito_alla_riserva(servizi, server):
    server.guasti[CHAT_OPENAI] = [401]
    server.chat_forzata = '{"a": 2}'
    assert chiedi(servizi) == {"a": 2}
    assert server.ospiti == ["api.openai.com", "api.eu.mistral.ai"]
    assert server.chiamate[1][1]["model"] == RISERVA and "reasoning_effort" not in server.chiamate[1][1]
    [riga] = spese(servizi)  # la chiamata rifiutata non costa niente
    assert riga["fornitore"] == "mistral" and riga["cosa"] == "Prova (riserva)"


@pagamento
@openai
def test_openai_occupato_ritenta_poi_riserva(servizi, server):
    server.guasti[CHAT_OPENAI] = [503] * 4
    server.chat_forzata = '{"a": 3}'
    assert chiedi(servizi) == {"a": 3}
    assert server.ospiti == ["api.openai.com"] * 4 + ["api.eu.mistral.ai"]
    assert [r["fornitore"] for r in spese(servizi)] == ["mistral"]


@pagamento
@openai
def test_openai_forse_ha_lavorato_la_stima_resta_nel_conto(servizi, server):
    server.guasti[CHAT_OPENAI] = [500] * 4
    server.chat_forzata = '{"a": 4}'
    assert chiedi(servizi) == {"a": 4}
    righe = spese(servizi)
    assert [r["fornitore"] for r in righe] == ["openai"] * 4 + ["mistral"]
    assert all(r["stato"] == "stimata" for r in righe[:4]) and righe[4]["stato"] == "fatta"


@pagamento
@openai
def test_risposte_non_usabili_di_luna_passano_alla_riserva(servizi, server):
    server.rotte_per_ospite["api.openai.com"] = 2
    server.chat_forzata = '{"titolo": "ok"}'
    valida = lambda o: [] if "titolo" in o else ['manca "titolo"']  # noqa: E731
    assert chiedi(servizi, valida=valida) == {"titolo": "ok"}
    assert server.ospiti == ["api.openai.com", "api.openai.com", "api.eu.mistral.ai"]
    # La riserva riparte dalla richiesta di prima, senza le correzioni fatte a luna.
    assert len(server.chiamate[2][1]["messages"]) == 2


@pagamento
@openai
def test_se_anche_la_riserva_non_va_errore_chiaro(servizi, server):
    server.guasti[CHAT_OPENAI] = [401]
    server.guasti["api.eu.mistral.ai/v1/chat/completions"] = [402]
    with pytest.raises(ErroreFornitore) as e:
        chiedi(servizi)
    assert "credito di Mistral" in e.value.frase


@pagamento
@openai
def test_il_tetto_non_si_aggira_con_la_riserva(servizi, server, client, auth):
    client.patch("/api/impostazioni", json={"tetto_eur": 0.5}, headers=auth)
    with db.aperta(servizi.settings.db_path) as conn:
        conn.execute("INSERT INTO spese (quando, mese, cosa, fornitore, quantita, unita, costo_eur) VALUES"
                     " ('2026-10-27T09:00:00Z', '2026-10', 'Spesa di prova', 'mistral', 1, 'token', 0.5)")
    with pytest.raises(TettoRaggiunto):
        chiedi(servizi)
    assert server.chiamate == []


@pagamento
@openai
def test_lezione_intera_con_luna(client, auth, servizi, server):
    assert isinstance(servizi.ctx.testo, Scrittori) and servizi.ctx.testo.openai is not None
    assert client.get("/api/salute", headers=auth).json()["testo"] == {"stato": "pronto", "fornitore": "openai"}
    m = crea_materia(client, auth)
    aggiungi_testo(client, auth, m["id"])
    l = nuova_lezione(client, auth, m["id"], voce="mistral:voice_482")
    prepara_tutto(servizi)
    assert client.get(f"/api/lezioni/{l['id']}", headers=auth).json()["stato"] == "pronta"
    chat = chat_di(server, "api.openai.com")
    assert chat and not chat_di(server, "api.eu.mistral.ai")
    assert all(c["model"] == LUNA and "temperature" not in c for c in chat)
    testo = [r for r in spese(servizi, f"lezione_id = {l['id']}") if r["unita"] == "token" and "Ricerca" not in r["cosa"]]
    assert testo and all(r["fornitore"] == "openai" for r in testo)


@pagamento
@openai
def test_lezione_intera_con_openai_spento(client, auth, servizi, server):
    server.guasti[CHAT_OPENAI] = [503] * 400
    m = crea_materia(client, auth)
    aggiungi_testo(client, auth, m["id"])
    l = nuova_lezione(client, auth, m["id"], voce="mistral:voice_482")
    prepara_tutto(servizi)
    assert client.get(f"/api/lezioni/{l['id']}", headers=auth).json()["stato"] == "pronta"
    riserva = chat_di(server, "api.eu.mistral.ai")
    assert riserva and all(c["model"] == RISERVA for c in riserva)
    testo = [r for r in spese(servizi, f"lezione_id = {l['id']}") if r["unita"] == "token" and "Ricerca" not in r["cosa"]]
    assert testo and all(r["fornitore"] == "mistral" and r["cosa"].endswith("(riserva)") for r in testo)


@pagamento
def test_salute_senza_openai_dice_mistral(client, auth):
    assert client.get("/api/salute", headers=auth).json()["testo"] == {"stato": "pronto", "fornitore": "mistral"}
