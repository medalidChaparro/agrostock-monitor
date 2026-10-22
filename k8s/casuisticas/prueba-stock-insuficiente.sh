#!/usr/bin/env bash
set -euo pipefail

URL="${URL:-http://localhost:18082/api/pedidos}"

echo "Enviando un pedido que supera el stock disponible..."

curl -sS -i -X POST "$URL" \
  -H 'Content-Type: application/json' \
  -d '{"insumoId":"SUP003","cantidad":999}'

echo
echo "Consulta los contadores con:"
echo "kubectl port-forward -n agrostock service/gestion-pedidos 18082:8082"
echo "Luego revisa /actuator/prometheus para pedidos_rechazados_total."
