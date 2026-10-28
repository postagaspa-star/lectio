#!/bin/bash
# Inserisce una chiave nel file giusto di Lectio sul NAS e fa ripartire il container che la usa.
# Si lancia dal PC, nel Prompt dei comandi:
#   ssh -t "<utente>"@<ip-del-nas> "bash /volume1/docker/Lectio-NAS/imposta-chiave.sh"
# La chiave si incolla a un prompt che non la mostra: non finisce nella cronologia dei comandi,
# non passa da nessun programma di chat e non viene mai stampata.
#
# Due file separati:
#   server/.env     -> le chiavi di Lectio (Mistral, Azure): le vede solo il container lectio
#   tailscale/.env  -> la chiave di Tailscale: la vede solo il container lectio-tailscale
set -e
CARTELLA="${LECTIO_CARTELLA:-/volume1/docker/Lectio-NAS}"
FILE_LECTIO="${LECTIO_FILE_CHIAVI:-$CARTELLA/server/.env}"
FILE_TAILSCALE="${LECTIO_FILE_TAILSCALE:-$CARTELLA/tailscale/.env}"
cd "$CARTELLA"

mkdir -p "$(dirname "$FILE_LECTIO")" "$(dirname "$FILE_TAILSCALE")"
touch "$FILE_LECTIO" "$FILE_TAILSCALE"
chmod 600 "$FILE_LECTIO" "$FILE_TAILSCALE"

# Le installazioni di prima tenevano TS_AUTHKEY in server/.env: la si sposta nel suo file.
if grep -q '^TS_AUTHKEY=' "$FILE_LECTIO"; then
  if ! grep -q '^TS_AUTHKEY=' "$FILE_TAILSCALE"; then
    grep '^TS_AUTHKEY=' "$FILE_LECTIO" >> "$FILE_TAILSCALE"
  fi
  sed -i '/^TS_AUTHKEY=/d' "$FILE_LECTIO"
  echo "Ho spostato la chiave di Tailscale nel suo file (tailscale/.env)."
fi

echo "Quale chiave vuoi inserire?"
echo "  1) Mistral (testo delle lezioni, voce espressiva, lettura delle foto)"
echo "  2) Azure: chiave della voce gratis"
echo "  3) Azure: regione (per esempio westeurope)"
echo "  4) Tailscale: chiave di accesso"
read -r -p "Numero: " N
case "$N" in
  1) NOME=LECTIO_MISTRAL_KEY; FILE="$FILE_LECTIO" ;;
  2) NOME=LECTIO_AZURE_KEY; FILE="$FILE_LECTIO" ;;
  3) NOME=LECTIO_AZURE_REGIONE; FILE="$FILE_LECTIO" ;;
  4) NOME=TS_AUTHKEY; FILE="$FILE_TAILSCALE" ;;
  *) echo "Numero non valido: non ho cambiato niente."; exit 1 ;;
esac

read -r -s -p "Incolla il valore (non si vede mentre lo incolli) e premi Invio: " VALORE
echo
VALORE="$(printf '%s' "$VALORE" | tr -d '[:space:]')"
if [ -z "$VALORE" ]; then
  echo "Era vuoto: non ho cambiato niente."; exit 1
fi
# Le chiavi vere usano solo lettere, numeri e - _ . : — qualsiasi altro carattere vuol dire
# che si è incollato qualcosa di sbagliato (o che romperebbe il file).
if ! printf '%s' "$VALORE" | grep -Eq '^[A-Za-z0-9_.:-]+$'; then
  unset VALORE
  echo "Il valore contiene caratteri che una chiave non ha: forse si è copiato qualcosa in più. Non l'ho salvato."
  exit 1
fi

if grep -q "^$NOME=" "$FILE"; then
  sed -i "s|^$NOME=.*|$NOME=$VALORE|" "$FILE"
else
  printf '%s=%s\n' "$NOME" "$VALORE" >> "$FILE"
fi
unset VALORE
echo "Salvata."

if [ -n "$LECTIO_SENZA_RIAVVIO" ]; then exit 0; fi
if [ "$NOME" = "TS_AUTHKEY" ]; then
  echo "Faccio ripartire Tailscale..."
  docker compose -p lectio --profile tailscale up -d --force-recreate lectio-tailscale >/dev/null 2>&1 \
    && echo "Tailscale è partito." || echo "Tailscale non è partito: scrivilo a chi ti segue con Lectio."
else
  echo "Faccio ripartire Lectio con la chiave nuova..."
  docker compose -p lectio up -d --no-deps --force-recreate lectio >/dev/null 2>&1 \
    && echo "Fatto: Lectio è ripartito." || echo "Lectio non è ripartito: scrivilo a chi ti segue con Lectio."
fi
