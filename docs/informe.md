# Informe técnico: AgroStock Monitor

## 1. Introducción

AgroStock Monitor es una solución basada en microservicios para consultar insumos, validar pedidos y supervisar el comportamiento de ambos servicios. El proyecto utiliza Spring Boot, Micrometer, Prometheus, Grafana y Kubernetes con Minikube.

El objetivo es identificar problemas de disponibilidad, lentitud y rechazo de pedidos mediante métricas, consultas PromQL y alertas.

## 2. Arquitectura de la solución

### 2.1. Microservicio catalogo-insumos

Se ejecuta en el puerto 8081 y administra un catálogo de insumos en memoria. Permite listar y consultar insumos, registrar insumos, actualizar existencias y consultar productos con stock bajo.

### 2.2. Microservicio gestion-pedidos

Se ejecuta en el puerto 8082. Permite consultar y registrar pedidos. Antes de aceptar un pedido, utiliza WebClient para validar las existencias en el catálogo. La solución contempla respuestas para pedidos inválidos, stock insuficiente, errores del catálogo y tiempos de espera agotados.

Los datos se mantienen en memoria; no se utiliza una base de datos.

### 2.3. Monitoreo

Micrometer registra métricas de la aplicación y las expone mediante Spring Boot Actuator en `/actuator/prometheus`. Prometheus recopila estas métricas mediante dos recursos ServiceMonitor, y Grafana permite visualizar el comportamiento de los servicios mediante un dashboard.

La infraestructura se ejecuta en Kubernetes utilizando Minikube.

## 3. Conceptos de monitoreo

### 3.1. Micrometer y Prometheus

Micrometer proporciona una interfaz para instrumentar las aplicaciones Java con métricas. Prometheus recopila y almacena esas métricas y permite consultarlas mediante PromQL.

Se utiliza la integración de Micrometer con Prometheus en lugar de implementar manualmente un cliente de métricas.

### 3.2. Tipos de métricas

- **Counter:** contabiliza eventos acumulativos, como pedidos aceptados, pedidos rechazados y consultas realizadas.
- **Gauge:** representa un valor actual, como la cantidad de insumos en memoria o el retraso configurado para el catálogo.
- **Timer:** mide la duración y frecuencia de una operación, como las consultas del catálogo o la validación del pedido.

### 3.3. ServiceMonitor

ServiceMonitor es un recurso de Prometheus Operator que define qué servicios de Kubernetes se deben supervisar, qué puerto consultar y qué ruta recopilar. En este proyecto se configuraron recursos para catalogo-insumos y gestion-pedidos, con la ruta `/actuator/prometheus`.

### 3.4. Liveness y readiness

Liveness permite identificar si un contenedor necesita reiniciarse. Readiness indica si una aplicación está preparada para recibir tráfico. Estas comprobaciones ayudan a Kubernetes a gestionar el estado de las aplicaciones durante su ejecución.

### 3.5. Imágenes en Minikube

Las imágenes locales deben cargarse en Minikube para que los despliegues puedan utilizarlas. En este proyecto se emplean imágenes etiquetadas como `catalogo-insumos:1.0.0` y `gestion-pedidos:1.0.0`, con `imagePullPolicy: Never`.

## 4. Consultas PromQL

### 4.1. Disponibilidad de los servicios

```promql
up{namespace="agrostock"}
```

Un valor de 1 indica que Prometheus recopila correctamente las métricas del objetivo. Un valor de 0 indica que no puede recopilar sus métricas.

### 4.2. Solicitudes por minuto

```promql
sum by (service) (rate(http_server_requests_seconds_count{namespace="agrostock"}[5m])) * 60
```

Permite observar las solicitudes HTTP por minuto, siempre que la métrica esté disponible para los servicios consultados.

### 4.3. Percentil 95 de duración de consultas al catálogo

```promql
histogram_quantile(0.95, sum by (le) (rate(catalogo_consulta_duracion_seconds_bucket[5m])))
```

Esta consulta necesita una métrica de tipo histograma con buckets. Si el temporizador se expone como summary, la consulta no producirá resultados y será necesario ajustar la instrumentación o utilizar las métricas disponibles.

### 4.4. Pedidos rechazados en cinco minutos

```promql
increase(pedidos_rechazados_total[5m])
```

Muestra el incremento del contador de pedidos rechazados durante cinco minutos.

### 4.5. Retraso configurado del catálogo

```promql
catalogo_retraso_configurado_ms
```

Muestra el retraso configurado en milisegundos. Un valor de 0 indica que no se configuró retraso artificial.

## 5. Alertas configuradas

Se configuraron cuatro reglas de alerta en Prometheus:

1. **CatalogoInsumosDown:** detecta que el objetivo del catálogo aparece caído.
2. **GestionPedidosDown:** detecta que el objetivo de gestión de pedidos aparece caído.
3. **PedidosRechazadosAltos:** se activa cuando se registran al menos tres rechazos durante la ventana de cinco minutos.
4. **CatalogoConsultaLenta:** detecta cuando el percentil 95 de duración de las consultas supera los 0.5 segundos, siempre que existan los buckets necesarios.

Las alertas dependen de que las métricas existan y tengan datos suficientes.

## 6. Casuísticas de prueba

### 6.1. Casuística 1: pod con CrashLoopBackOff

**Contexto:** se utiliza un despliegue de prueba cuyo contenedor termina con error para observar el comportamiento de Kubernetes.

**Activación:**

```bash
kubectl apply -f k8s/casuisticas/crashloop.yaml
kubectl get pods -n agrostock -w
```

**Diagnóstico:**

```bash
kubectl describe pod -n agrostock -l app=caso-crashloop
kubectl logs -n agrostock deployment/caso-crashloop --previous
```

Si el pod entra en CrashLoopBackOff, Kubernetes está intentando reiniciar un contenedor que termina repetidamente. Esta casuística no garantiza por sí sola que una alerta basada en los objetivos ServiceMonitor se active.

**Resolución:**

```bash
kubectl delete -f k8s/casuisticas/crashloop.yaml
```

**Aprendizaje:** los estados de los pods y las métricas de disponibilidad de los servicios son señales complementarias y deben analizarse juntas.

### 6.2. Casuística 2: lentitud del catálogo

**Contexto:** se configura un retraso artificial de cinco segundos en las consultas de listado del catálogo. Esto permite observar el efecto sobre las solicitudes que dependen de este servicio.

**Activación:**

```bash
kubectl apply -f k8s/casuisticas/lentitud-catalogo.yaml
```

**Diagnóstico:** consultar el valor de la métrica:

```promql
catalogo_retraso_configurado_ms
```

También se puede revisar la duración de las operaciones y los errores de timeout de gestión de pedidos. El retraso afecta al listado del catálogo; no debe suponerse que afecta a todos sus endpoints.

**Resolución:**

```bash
kubectl set env deployment/catalogo-insumos -n agrostock CATALOGO_DELAY_MS=0
kubectl rollout status deployment/catalogo-insumos -n agrostock --timeout=180s
```

**Aprendizaje:** un servicio lento puede provocar timeouts en los servicios que dependen de él, aunque el contenedor siga apareciendo como disponible.

### 6.3. Casuística 3: rechazo por stock insuficiente

**Contexto:** se envía un pedido de una cantidad superior a las existencias disponibles para comprobar la validación del catálogo.

**Activación:**

```bash
bash k8s/casuisticas/prueba-stock-insuficiente.sh
```

**Diagnóstico:** consultar el contador de pedidos rechazados:

```promql
increase(pedidos_rechazados_total[5m])
```

También se puede revisar el estado HTTP de la respuesta y los logs del microservicio de pedidos.

**Resolución:** ajustar la cantidad solicitada a las existencias disponibles y volver a enviar el pedido.

**Aprendizaje:** validar el stock antes de aceptar un pedido evita registrar solicitudes que no se pueden atender. El rechazo debe diferenciarse de un error de comunicación con el catálogo.

## 7. Verificación del entorno

Durante la validación se comprobó que los dos microservicios estuvieran ejecutándose en Kubernetes y que Prometheus recibiera sus métricas.

La consulta:

```promql
up{namespace="agrostock"}
```

devolvió dos series, una para cada microservicio, con valor 1.

También se comprobó la métrica:

```promql
catalogo_retraso_configurado_ms
```

con valor 0, y la métrica `catalogo_insumos_en_memoria` con valor 3.

Estos resultados confirman que ambos objetivos estaban disponibles durante la comprobación y que el catálogo exponía la métrica de retraso configurado.

## 8. Evidencias

Incorporar las capturas ya realizadas, con una breve descripción de cada una:

- **Evidencia 1:** objetivos de Prometheus con los dos servicios en estado UP.
- **Evidencia 2:** dashboard de Grafana con los paneles de monitoreo.
- **Evidencia 3:** estado de los pods de AgroStock en Kubernetes.
- **Evidencia 4:** prueba de la casuística de lentitud del catálogo.
- **Evidencia 5:** prueba de rechazo de pedidos por stock insuficiente.
- **Evidencia 6:** estado de una alerta en FIRING, si se dispone de esa captura.

Las evidencias deben corresponder a pruebas realmente ejecutadas. No se debe afirmar que una alerta se activó si no se verificó su estado.

## 9. Conclusiones

La implementación permitió integrar dos microservicios con un sistema de monitoreo basado en Micrometer, Prometheus y Grafana. Las métricas ayudan a observar la disponibilidad, las operaciones del negocio y los tiempos de respuesta.

Las casuísticas permiten estudiar fallos de contenedores, lentitud y rechazos por stock insuficiente. Además, el uso de Kubernetes facilita observar y administrar los despliegues. Como mejora futura, se recomienda persistir la información de negocio y ampliar las pruebas automatizadas.
