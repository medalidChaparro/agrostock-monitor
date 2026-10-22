# AgroStock Monitor

Sistema de microservicios Java para gestionar insumos agrícolas y pedidos, con monitoreo en Kubernetes mediante Prometheus y Grafana.

## Microservicios

- **catalogo-insumos:** registra y consulta insumos, actualiza existencias y muestra productos con stock bajo.
- **gestion-pedidos:** registra pedidos en memoria y valida el stock consultando el catálogo mediante Spring WebClient.

Los datos se almacenan en memoria; no se utiliza una base de datos.

## Tecnologías

Java 17, Spring Boot 3, Maven, WebClient, Micrometer, Prometheus, Grafana, Docker, Kubernetes, Minikube y Helm.

## Estructura

- `microservices/`: código fuente y Dockerfiles.
- `k8s/`: despliegues, servicios, monitoreo, alertas y casuísticas.
- `load-testing/`: prueba de carga.
- `scripts/`: preparación y limpieza del entorno.
- `docs/`: documentación técnica.

## Acceso a la aplicación

Después de ejecutar `./scripts/setup.sh`, consulta la IP de Minikube con `minikube ip`.

- Grafana: `http://IP_DE_MINIKUBE:30300`
- Prometheus: `http://IP_DE_MINIKUBE:30090`

Para acceder a las API, abre una terminal para cada comando:

```bash
kubectl port-forward -n agrostock service/catalogo-insumos 18081:8081
```

```bash
kubectl port-forward -n agrostock service/gestion-pedidos 18082:8082
```

Con los túneles activos, consulta `http://localhost:18081/api/insumos` y `http://localhost:18082/api/pedidos`.

## Requisitos previos

Se requiere Java 17, Maven, Docker, Minikube, kubectl y Helm. Consulta las instrucciones oficiales: [Java](https://ubuntu.com/server/docs/how-to-install-java), [Docker](https://docs.docker.com/engine/install/), [Minikube](https://minikube.sigs.k8s.io/docs/start/), [kubectl](https://kubernetes.io/docs/tasks/tools/) y [Helm](https://helm.sh/docs/intro/install/).

Verifica las herramientas con `java -version`, `mvn -version`, `docker --version`, `minikube version`, `kubectl version --client` y `helm version`.

## Comandos principales

Consultar los recursos desplegados:

```bash
kubectl get pods -n agrostock
kubectl get services -n agrostock
kubectl get servicemonitor -n agrostock
```

Preparar el entorno:

```bash
chmod +x scripts/setup.sh scripts/teardown.sh load-testing/stress.sh
./scripts/setup.sh
```

Ejecutar la prueba de carga con el port-forward del catálogo activo en el puerto local `18081`:

```bash
./load-testing/stress.sh
```

Retirar los recursos de la aplicación:

```bash
./scripts/teardown.sh
```

El proyecto incluye alertas de Prometheus, un dashboard de Grafana y tres casuísticas: fallos de contenedor, lentitud del catálogo y pedidos con stock insuficiente.