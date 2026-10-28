"""I fornitori dietro le interfacce: richieste ben fatte, ritenti, frasi d'errore in italiano.
Tutto con httpx.MockTransport: nessuna chiamata vera."""

import base64
import json

import httpx
import pytest

from app import ai, prompt
from app.ocr import OcrMistral
from app.rete import ErroreFornitore, ForseElaborata, NonElaborata
from app.testo_ai import TestoMistral as ChatMistral
from app.voci import Catalogo, VoceAzure, VoceMistral, VoceProva, senza_segni


def trasporto(risposte: list, registro: list):
    def gestisci(richiesta: httpx.Request):
        registro.append(richiesta)
        r = risposte.pop(0)
        return r(richiesta) if callable(r) else r

    return httpx.MockTransport(gestisci)


def chat_ok(testo='{"ok": true}'):
    return httpx.Response(200, json={"model": "mistral-small-latest", "choices": [{"message": {"content": testo}}],
                                     "usage": {"prompt_tokens": 120, "completion_tokens": 30}})


def test_chat_mistral_richiesta_e_numeri_veri():
    registro = []
    t = ChatMistral("chiave", transport=trasporto([chat_ok()], registro))
    r = t.genera(compito="piano", messaggi=[{"role": "user", "content": "ciao"}], modello="mistral-small-latest",
                 max_token=500)
    assert r.testo == '{"ok": true}' and r.token_entrata == 120 and r.token_uscita == 30
    corpo = json.loads(registro[0].content)
    assert registro[0].url == "https://api.mistral.ai/v1/chat/completions"
    assert registro[0].headers["authorization"] == "Bearer chiave"
    assert corpo["response_format"] == {"type": "json_object"} and corpo["max_tokens"] == 500


def test_un_tentativo_e_errori_classificati():
    """Revisione, punto 2: i fornitori fanno UN tentativo e dicono se hanno forse lavorato."""
    casi = [(httpx.Response(429), NonElaborata), (httpx.Response(503), NonElaborata),
            (httpx.Response(408), NonElaborata), (httpx.Response(500), ForseElaborata),
            (httpx.Response(502), ForseElaborata), (httpx.Response(504), ForseElaborata)]

    def connessione_rifiutata(richiesta):
        raise httpx.ConnectError("rifiutata", request=richiesta)

    def tempo_scaduto(richiesta):
        raise httpx.ReadTimeout("lento", request=richiesta)

    def chiusa_a_meta(richiesta):
        raise httpx.RemoteProtocolError("chiusa", request=richiesta)

    casi += [(connessione_rifiutata, NonElaborata), (tempo_scaduto, ForseElaborata), (chiusa_a_meta, ForseElaborata)]
    for risposta, atteso in casi:
        registro = []
        t = ChatMistral("chiave", transport=trasporto([risposta], registro))
        with pytest.raises(atteso):
            t.genera(compito="x", messaggi=[], modello="m")
        assert len(registro) == 1  # nessun ritento dentro il fornitore


def test_errori_definitivi_senza_ritenti():
    for codice, pezzo in ((401, "non accetta la chiave"), (402, "credito di Mistral è finito"),
                          (404, "non trova il modello"), (400, "ha rifiutato la richiesta")):
        registro = []
        t = ChatMistral("chiave", transport=trasporto([httpx.Response(codice)], registro))
        with pytest.raises(ErroreFornitore) as e:
            t.genera(compito="x", messaggi=[], modello="m")
        assert pezzo in e.value.frase and len(registro) == 1


def test_voce_mistral_segni_e_audio():
    registro = []
    risposta = httpx.Response(200, json={"audio_data": base64.b64encode(b"ID3audio").decode()})
    v = VoceMistral("chiave", "voxtral-mini-tts-3-0", "voice_482", transport=trasporto([risposta], registro))
    a = v.sintetizza("La data è il 27 avanti Cristo.", emozione="calm", lento=True)
    corpo = json.loads(registro[0].content)
    assert registro[0].url == "https://api.mistral.ai/v1/audio/speech"
    assert corpo == {"model": "voxtral-mini-tts-3-0", "input": "[emotion=calm][pace=slow] La data è il 27 avanti Cristo.",
                     "voice_id": "voice_482", "response_format": "mp3"}
    assert a.contenuto == b"ID3audio" and a.estensione == "mp3" and a.caratteri == len(corpo["input"])
    assert VoceMistral.con_segni("Ciao", "nessuna", False) == "Ciao"


def test_voce_azure_ssml_e_elenco():
    registro = []
    elenco = httpx.Response(200, json=[
        {"ShortName": "it-IT-DiegoNeural", "DisplayName": "Diego", "Gender": "Male", "Locale": "it-IT"},
        {"ShortName": "it-IT-GiuseppeMultilingualNeural", "DisplayName": "Giuseppe Multilingual", "Gender": "Male",
         "Locale": "it-IT"},
        {"ShortName": "it-IT-IsabellaMultilingualNeural", "DisplayName": "Isabella Multilingual", "Gender": "Female",
         "Locale": "it-IT"},
        {"ShortName": "fr-FR-DeniseNeural", "DisplayName": "Denise", "Gender": "Female", "Locale": "fr-FR"}])
    orologio = [0.0]
    v = VoceAzure("chiave", "westeurope", transport=trasporto([elenco, httpx.Response(200, content=b"mp3")], registro),
                  dormi=lambda s: None, orologio=lambda: orologio[0])
    assert [x["ShortName"] for x in v.elenco()] == ["it-IT-DiegoNeural", "it-IT-GiuseppeMultilingualNeural",
                                                     "it-IT-IsabellaMultilingualNeural"]
    assert v.predefinita() == "it-IT-IsabellaMultilingualNeural"
    assert len(registro) == 1  # in memoria per un giorno
    a = v.sintetizza("[emotion=calm][pace=slow] Roma & <Cartagine>", lento=True, voce="it-IT-DiegoNeural")
    r = registro[1]
    assert str(r.url) == "https://westeurope.tts.speech.microsoft.com/cognitiveservices/v1"
    assert r.headers["x-microsoft-outputformat"] == "audio-24khz-48kbitrate-mono-mp3"
    assert r.headers["ocp-apim-subscription-key"] == "chiave"
    ssml = r.content.decode()
    assert '<voice name="it-IT-DiegoNeural"><prosody rate="-10%">Roma &amp; &lt;Cartagine&gt;</prosody></voice>' in ssml
    assert "[emotion" not in ssml
    assert a.caratteri == len("Roma & <Cartagine>")


def test_azure_senza_elenco_usa_la_voce_sicura():
    def giu(richiesta):
        raise httpx.ConnectError("giù", request=richiesta)

    v = VoceAzure("chiave", "westeurope", transport=httpx.MockTransport(giu), dormi=lambda s: None)
    assert v.predefinita() == "it-IT-IsabellaNeural"


def test_ocr_mistral_pagine_dal_fornitore():
    registro = []
    risposta = httpx.Response(200, json={"pages": [{"markdown": "Pagina uno"}, {"markdown": "Pagina due"}],
                                         "usage_info": {"pages_processed": 2}})
    o = OcrMistral("chiave", transport=trasporto([risposta], registro))
    r = o.leggi(b"%PDF", "application/pdf", "mistral-ocr-latest")
    assert r.testo == "Pagina uno\n\nPagina due" and r.pagine == 2
    corpo = json.loads(registro[0].content)
    assert corpo["model"] == "mistral-ocr-latest" and corpo["document"]["type"] == "document_url"


def test_catalogo():
    c = Catalogo(prova=True, euro_al_minuto_mistral=0.0126, voce_mistral_id="voice_482", mistral=None, azure=None,
                 voce_prova=VoceProva(programma="non-esiste"))
    assert c.risolvi("mistral:voice_482") is None
    assert c.risolvi("prova:espeak").tipo == "prova"
    assert c.ripiego("mistral:voice_482") == "prova:espeak"
    elenco = c.elenco()
    assert elenco[-1]["id"] == "prova:espeak" and elenco[-1]["disponibile"] is False
    assert c.risolvi("azure:it-IT-IsabellaNeural") is None and c.risolvi("rotta") is None
    assert senza_segni("[emotion=warm] Ciao [pace=slow]a tutti") == "Ciao a tutti"


class SequenzaDiTesti:
    nome = "mistral"
    a_pagamento = True

    def __init__(self, testi):
        self.testi = list(testi)
        self.messaggi = []

    def genera(self, *, messaggi, **kw):
        from app.testo_ai import RispostaTesto

        self.messaggi.append(messaggi)
        return RispostaTesto(self.testi.pop(0), 100, 20, "m")


def test_json_validato_con_una_correzione(app):
    cassa = app.state.servizi.cassa
    f = SequenzaDiTesti(['```json\n{"a": 1}\n```'])
    assert ai.chiedi_json(cassa=cassa, fornitore=f, compito="x", messaggi=[{"role": "user", "content": "x"}],
                          modello="mistral-small-latest", valida=lambda o: [], max_token=100, cosa="prova") == {"a": 1}
    f = SequenzaDiTesti(["non json", '{"titolo": "ok"}'])
    valida = lambda o: [] if "titolo" in o else ['manca "titolo"']  # noqa: E731
    assert ai.chiedi_json(cassa=cassa, fornitore=f, compito="x", messaggi=[{"role": "user", "content": "x"}],
                          modello="mistral-small-latest", valida=valida, max_token=100, cosa="prova")["titolo"] == "ok"
    correzione = f.messaggi[1][-1]["content"]
    assert correzione.startswith("La tua risposta non va bene: non è un oggetto JSON")
    f = SequenzaDiTesti(['{"x": 1}', '{"y": 2}'])
    with pytest.raises(ai.RispostaNonUsabile) as e:
        ai.chiedi_json(cassa=cassa, fornitore=f, compito="x", messaggi=[{"role": "user", "content": "x"}],
                       modello="mistral-small-latest", valida=valida, max_token=100, cosa="prova")
    assert "Tocca Riprova" in e.value.frase
    assert 'manca "titolo"' in f.messaggi[1][-1]["content"]
    assert prompt.correzione(["a", "b"]).endswith("senza altro testo.")
