"""I corpi delle richieste. Campi in più vengono ignorati (le app più nuove possono
mandarne); i tipi sbagliati diventano un 422 in italiano (errori.py)."""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, StrictBool, StrictInt, field_validator

from . import clock

Colore = Literal["ambra", "rosa", "petrolio", "viola", "blu", "verde", "arancio", "oliva"]
TipoMateriale = Literal["verifica", "interrogazione", "appunti", "libro", "altro"]
TipoEvento = Literal["scritta", "orale"]
TipoDomanda = Literal["aperta", "definizione", "date", "esercizio", "vero_falso", "scelta_multipla", "collegamento",
                      "traduzione", "altro"]


class Corpo(BaseModel):
    model_config = ConfigDict(extra="ignore")


def _data(v):
    if v is None:
        return v
    if not clock.data_valida(v):
        raise ValueError("data non valida")
    return v


class AbbinaIn(Corpo):
    codice: str = Field(min_length=1, max_length=20)
    nome: str = Field(min_length=1, max_length=60)
    tipo: Literal["android", "pc"]


class MateriaIn(Corpo):
    nome: str = Field(min_length=1, max_length=40)
    prof: str = Field(default="", max_length=40)
    colore: Colore | None = None


class MateriaPatch(Corpo):
    nome: str | None = Field(default=None, min_length=1, max_length=40)
    prof: str | None = Field(default=None, max_length=40)
    colore: Colore | None = None


class ConfermaIn(Corpo):
    conferma: StrictBool | None = None


class ArgomentoIn(Corpo):
    nome: str = Field(min_length=1, max_length=80)
    volte: int = Field(default=0, ge=0, le=1000)
    esempio: str = Field(default="", max_length=200)


class TipoDomandaIn(Corpo):
    tipo: TipoDomanda
    quota: float = Field(ge=0, le=1)


class SchedaPut(Corpo):
    argomenti: list[ArgomentoIn] | None = Field(default=None, max_length=30)
    tipi_domanda: list[TipoDomandaIn] | None = Field(default=None, max_length=9)
    cosa_corregge: list[str] | None = Field(default=None, max_length=20)
    cosa_premia: list[str] | None = Field(default=None, max_length=20)
    lunghezza_risposte: str | None = Field(default=None, max_length=200)
    consigli: list[str] | None = Field(default=None, max_length=20)
    note_mie: str | None = Field(default=None, max_length=5000)

    @field_validator("cosa_corregge", "cosa_premia", "consigli")
    @classmethod
    def _voci_brevi(cls, v):
        if v is not None and any(len(x) > 200 for x in v):
            raise ValueError("voce troppo lunga")
        return v


class MaterialeTestoIn(Corpo):
    tipo: TipoMateriale | None = None
    titolo: str | None = Field(default=None, max_length=120)
    data: str | None = None
    testo: str = Field(min_length=1)

    _d = field_validator("data")(classmethod(lambda cls, v: _data(v)))


class EventoIn(Corpo):
    tipo: TipoEvento
    argomento: str = Field(default="", max_length=200)
    data: str

    _d = field_validator("data")(classmethod(lambda cls, v: _data(v)))


class EventoPatch(Corpo):
    tipo: TipoEvento | None = None
    argomento: str | None = Field(default=None, max_length=200)
    data: str | None = None

    _d = field_validator("data")(classmethod(lambda cls, v: _data(v)))


class EsitoIn(Corpo):
    domande_uscite: list[StrictInt] = Field(default_factory=list, max_length=200)
    altre_domande: str = Field(default="", max_length=4000)
    voto: str | None = Field(default=None, max_length=20)


class StimaIn(Corpo):
    materia_id: StrictInt
    durata_min: StrictInt = Field(ge=15, le=60)
    voce: str | None = Field(default=None, max_length=120)
    materiali: list[StrictInt] | None = Field(default=None, max_length=500)


class LezioneIn(Corpo):
    materia_id: StrictInt
    evento_id: StrictInt | None = None
    argomento: str = Field(default="", max_length=200)
    # Manca = tutto il materiale della materia; una lista = solo quello; [] = errore (contratto 15.1).
    materiali: list[StrictInt] | None = Field(default=None, max_length=500)
    durata_min: StrictInt | None = Field(default=None, ge=15, le=60)
    voce: str | None = Field(default=None, max_length=120)
    domande_saltabili: StrictBool | None = None


class LezionePatch(Corpo):
    domande_saltabili: StrictBool | None = None


class RispostaIn(Corpo):
    testo: str = Field(default="", max_length=2000)
    saltata: StrictBool = False


class AscoltoIn(Corpo):
    posizione_s: float = Field(ge=0, le=100_000)
    finita: StrictBool = False
    aggiornato_il: str | None = Field(default=None, max_length=40)


class ImpostazioniPatch(Corpo):
    tetto_eur: float | None = Field(default=None, ge=0.5, le=10)
    voce_predefinita: str | None = Field(default=None, min_length=3, max_length=120)
    durata_predefinita: StrictInt | None = Field(default=None, ge=15, le=60)
    fermati_alle_domande: StrictBool | None = None
    velocita: float | None = Field(default=None, ge=0.5, le=2.0)
    nome: str | None = Field(default=None, max_length=40)
    risposte_scritte: StrictBool | None = None
    domande_saltabili: StrictBool | None = None
