"""Da dove arriva una richiesta: da casa o da internet (per i tentativi di abbinamento).

Come si distingue, in modo che da internet non ci si possa fingere "di casa":
- le richieste da internet passano TUTTE da Tailscale Funnel, cioè dal container
  `lectio-tailscale`, che le gira a lectio dalla rete Docker del progetto: l'indirizzo che
  lectio vede è quello del container. Lo si chiede al DNS di Docker per nome
  (LECTIO_TAILSCALE_HOST, base "lectio-tailscale") a ogni abbinamento, così vale anche se il
  container riparte con un indirizzo nuovo;
- un indirizzo pubblico (non di una rete privata) è internet;
- tutto il resto che viene da una rete privata (192.168.0.0/16, 10.0.0.0/8, 172.16.0.0/12,
  il NAS stesso) è casa: la porta 8500 si apre solo nella rete di casa.
Le intestazioni come X-Forwarded-For non contano: le può scrivere chiunque.
Nel dubbio (indirizzo che non si capisce) si conta come internet."""

import ipaddress
import logging
import socket

log = logging.getLogger("uvicorn.error")

CASA, INTERNET = "casa", "internet"
RETI_PRIVATE = [ipaddress.ip_network(r) for r in (
    "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8", "::1/128", "fc00::/7", "fe80::/10")]


def _risolvi_dns(nome: str) -> set[str]:
    try:
        return {r[4][0] for r in socket.getaddrinfo(nome, None)}
    except OSError:
        return set()


class Origine:
    def __init__(self, nome_tailscale: str = "lectio-tailscale", risolvi=_risolvi_dns):
        self.nome_tailscale = nome_tailscale
        self._risolvi = risolvi

    def gruppo(self, ip: str | None) -> str:
        try:
            indirizzo = ipaddress.ip_address((ip or "").split("%")[0])
        except ValueError:
            return INTERNET
        if getattr(indirizzo, "ipv4_mapped", None):
            indirizzo = indirizzo.ipv4_mapped
        if not any(indirizzo in rete for rete in RETI_PRIVATE):
            return INTERNET
        # Indirizzo privato: è casa, a meno che sia il container di Tailscale (= internet).
        if str(indirizzo) in self._risolvi(self.nome_tailscale):
            return INTERNET
        return CASA
