#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "== Retirando los recursos de AgroStock =="

kubectl delete -f k8s/monitoring/alerts.yaml --ignore-not-found
kubectl delete -f k8s/monitoring/servicemonitors.yaml --ignore-not-found
kubectl delete -f k8s/casuisticas/crashloop.yaml --ignore-not-found
kubectl delete -f k8s/catalogo-insumos.yaml --ignore-not-found
kubectl delete -f k8s/gestion-pedidos.yaml --ignore-not-found

if [[ "${1:-}" == "--cluster" ]]; then
  echo "== Eliminando el clúster Minikube =="
  minikube delete
else
  echo "Se conservan Minikube y el stack de monitoreo."
  echo "Para eliminar también Minikube, ejecuta: ./scripts/teardown.sh --cluster"
fi

echo "== Proceso finalizado =="
