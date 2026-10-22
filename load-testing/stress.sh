#!/usr/bin/env bash
set -uo pipefail

URL="${URL:-http://localhost:18081/api/insumos}"
TOTAL="${TOTAL:-100}"
DELAY="${DELAY:-0.1}"

if ! [[ "$TOTAL" =~ ^[1-9][0-9]*$ ]]; then
  echo "Error: TOTAL debe ser un entero positivo."
  exit 1
fi

echo "Probando: $URL"
echo "Solicitudes: $TOTAL"

OK=0
ERROR=0

for ((i=1; i<=TOTAL; i++)); do
  CODE=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 5 "$URL" 2>/dev/null || true)

  if [[ "$CODE" == "200" ]]; then
    ((OK+=1))
  else
    ((ERROR+=1))
    echo "Solicitud $i: HTTP ${CODE:-sin respuesta}"
  fi

  sleep "$DELAY"
done

echo "== Resultado =="
echo "Correctas: $OK"
echo "Fallidas: $ERROR"
