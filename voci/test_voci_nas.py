"""Le parti del servizio delle voci che non hanno bisogno dei modelli: python -m pytest voci"""

import json
import threading
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

import pytest

import voci_nas


def test_pulisci_toglie_i_segni_della_voce_espressiva():
    assert voci_nas.pulisci("[emotion=warm][pace=slow] Ciao,   come   stai?") == "Ciao, come stai?"


def test_dividi_frasi_intere_entro_il_massimo():
    testo = "Prima frase. Seconda frase un po' più lunga! Terza? Quarta."
    pezzi = voci_nas.dividi(testo, 30)
    assert all(len(p) <= 30 for p in pezzi)
    assert " ".join(pezzi) == testo


def test_dividi_taglia_una_frase_troppo_lunga():
    frase = ", ".join(["una parola"] * 40) + "."
    pezzi = voci_nas.dividi(frase, 60)
    assert all(len(p) <= 60 for p in pezzi)
    assert " ".join(pezzi).replace("  ", " ") == frase


def test_leggi_controlla_motore_e_testo():
    with pytest.raises(voci_nas.Errore):
        voci_nas.leggi("lorenzo", "Ciao.")
    with pytest.raises(voci_nas.Errore):
        voci_nas.leggi("giovanni", "  [pace=slow] ")
    with pytest.raises(voci_nas.Errore):
        voci_nas.leggi("giovanni", "a" * (voci_nas.TESTO_MASSIMO + 1))


@pytest.fixture
def servizio(monkeypatch):
    monkeypatch.setattr(voci_nas, "pocket", lambda testo, lingua, voce: f"RIFF{lingua}:{testo}".encode())
    server = ThreadingHTTPServer(("127.0.0.1", 0), voci_nas.Gestore)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{server.server_address[1]}"
    server.shutdown()


def _post(url, oggetto):
    richiesta = urllib.request.Request(url + "/voce", data=json.dumps(oggetto).encode(), method="POST",
                                       headers={"Content-Type": "application/json"})
    return urllib.request.urlopen(richiesta, timeout=5)


def test_http_salute_e_voce(servizio):
    salute = json.load(urllib.request.urlopen(servizio + "/salute", timeout=5))
    assert salute["ok"] and "giovanni_grande" in salute["motori"]
    r = _post(servizio, {"motore": "giovanni_grande", "testo": "[emotion=calm] Ciao."})
    assert r.headers["Content-Type"] == "audio/wav"
    assert r.read() == b"RIFFitalian_24l:Ciao."
    with pytest.raises(urllib.error.HTTPError) as e:
        _post(servizio, {"motore": "nessuna", "testo": "Ciao."})
    assert e.value.code == 400 and json.load(e.value)["errore"] == "Questa voce non c'è."
