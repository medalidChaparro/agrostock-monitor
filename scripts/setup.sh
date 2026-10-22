#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "== Verificando requisitos =="
for herramienta in java mvn docker minikube kubectl helm; do
  if ! command -v "$herramienta" >/dev/null 2>&1; then
    echo "ERROR: falta instalar o configurar: $herramienta"
    exit 1
  fi
done

java -version
mvn -version | head -n 2
docker --version
minikube version
kubectl version --client
helm version --short


export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export PATH="$JAVA_HOME/bin:$PATH"

echo "== Iniciando AgroStock =="

# Iniciar Minikube si no está activo
if ! minikube status >/dev/null 2>&1; then
  minikube start --driver=docker --cpus=2 --memory=4096
fi

echo "== Compilando microservicios =="
mvn -B -f microservices/catalogo-insumos/pom.xml clean package -DskipTests
mvn -B -f microservices/gestion-pedidos/pom.xml clean package -DskipTests

echo "== Construyendo imágenes =="
docker build -t catalogo-insumos:1.0.0 microservices/catalogo-insumos
docker build -t gestion-pedidos:1.0.0 microservices/gestion-pedidos

echo "== Cargando imágenes en Minikube =="
minikube image load catalogo-insumos:1.0.0
minikube image load gestion-pedidos:1.0.0

echo "== Habilitando métricas =="
minikube addons enable metrics-server

echo "== Preparando namespaces =="
kubectl create namespace agrostock --dry-run=client -o yaml | kubectl apply -f -

echo "== Instalando monitoreo =="
helm repo add prometheus-community \
  https://prometheus-community.github.io/helm-charts --force-update
helm repo update

helm upgrade --install monitoring prometheus-community/kube-prometheus-stack \
  --namespace monitoring \
  --create-namespace \
  --values k8s/monitoring/values.yaml \
  --timeout 10m

echo "== Aplicando manifiestos =="
kubectl apply -f k8s/catalogo-insumos.yaml
kubectl apply -f k8s/gestion-pedidos.yaml
kubectl apply -f k8s/monitoring/servicemonitors.yaml
kubectl apply -f k8s/monitoring/alerts.yaml

echo "== Esperando los despliegues =="
kubectl rollout status deployment/catalogo-insumos \
  -n agrostock --timeout=180s
kubectl rollout status deployment/gestion-pedidos \
  -n agrostock --timeout=180s

echo "== Estado de AgroStock =="
kubectl get pods -n agrostock
kubectl get services -n agrostock
kubectl get servicemonitor -n agrostock

echo "== AgroStock preparado correctamente =="

echo
echo "== Acceso a AgroStock Monitor =="
echo "Grafana:    http://$(minikube ip):30300"
echo "Prometheus: http://$(minikube ip):30090"
echo
echo "Para consultar los microservicios:"
echo "kubectl port-forward -n agrostock service/catalogo-insumos 18081:8081"
echo "kubectl port-forward -n agrostock service/gestion-pedidos 18082:8082"
