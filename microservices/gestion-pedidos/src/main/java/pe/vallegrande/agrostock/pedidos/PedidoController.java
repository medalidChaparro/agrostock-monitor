package pe.vallegrande.agrostock.pedidos;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api/pedidos")
public class PedidoController {

    public record Insumo(String id, String nombre, int stock, double precio) {}
    public record SolicitudPedido(String insumoId, int cantidad) {}
    public record Pedido(String id, String insumoId, String nombreInsumo,
                         int cantidad, double total, String estado) {}

    private final ConcurrentHashMap<String, Pedido> pedidos = new ConcurrentHashMap<>();
    private final AtomicLong secuencia = new AtomicLong();
    private final WebClient webClient;
    private final Counter pedidosAceptados;
    private final Counter pedidosRechazados;
    private final Counter erroresCatalogo;
    private final Timer tiempoValidacion;

    @Value("${pedidos.timeout-ms:2000}")
    private long timeoutMs;

    public PedidoController(WebClient.Builder builder, MeterRegistry registry,
                            @Value("${catalogo.url:http://localhost:8081}") String catalogoUrl) {
        this.webClient = builder.baseUrl(catalogoUrl).build();

        pedidosAceptados = Counter.builder("pedidos_aceptados_total")
                .description("Cantidad de pedidos aceptados").register(registry);
        pedidosRechazados = Counter.builder("pedidos_rechazados_total")
                .description("Pedidos rechazados por falta de stock o datos inválidos")
                .register(registry);
        erroresCatalogo = Counter.builder("pedidos_errores_catalogo_total")
                .description("Errores al comunicarse con el catálogo").register(registry);
        tiempoValidacion = Timer.builder("pedidos_validacion_catalogo_duracion")
                .description("Tiempo de validación del catálogo").register(registry);

        Gauge.builder("pedidos_en_memoria", pedidos, mapa -> mapa.size())
                .description("Pedidos almacenados en memoria").register(registry);
    }

    @GetMapping
    public List<Pedido> listar() {
        return List.copyOf(pedidos.values());
    }

    @GetMapping("/{id}")
    public Pedido buscar(@PathVariable String id) {
        Pedido pedido = pedidos.get(id);
        if (pedido == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pedido no encontrado");
        }
        return pedido;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Pedido crear(@RequestBody SolicitudPedido solicitud) {
        if (solicitud.insumoId() == null || solicitud.insumoId().isBlank()
                || solicitud.cantidad() <= 0) {
            pedidosRechazados.increment();
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Datos del pedido inválidos");
        }

        Insumo insumo;
        try {
            insumo = tiempoValidacion.record(() ->
                    webClient.get()
                            .uri("/api/insumos/{id}", solicitud.insumoId())
                            .retrieve()
                            .bodyToMono(Insumo.class)
                            .timeout(Duration.ofMillis(timeoutMs))
                            .block()
            );
        } catch (WebClientResponseException.NotFound e) {
            pedidosRechazados.increment();
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El insumo no existe");
        } catch (WebClientResponseException | WebClientRequestException e) {
            erroresCatalogo.increment();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "No se pudo consultar el catálogo");
        } catch (RuntimeException e) {
            if (esTimeout(e)) {
                erroresCatalogo.increment();
                throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                        "El catálogo tardó demasiado en responder");
            }
            erroresCatalogo.increment();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Error al validar el insumo");
        }

        if (insumo == null) {
            erroresCatalogo.increment();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "El catálogo devolvió una respuesta vacía");
        }

        if (insumo.stock() < solicitud.cantidad()) {
            pedidosRechazados.increment();
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Stock insuficiente");
        }

        String id = "PED" + String.format("%03d", secuencia.incrementAndGet());
        Pedido pedido = new Pedido(id, insumo.id(), insumo.nombre(),
                solicitud.cantidad(), insumo.precio() * solicitud.cantidad(), "ACEPTADO");
        pedidos.put(id, pedido);
        pedidosAceptados.increment();
        return pedido;
    }

    @GetMapping("/estadisticas")
    public java.util.Map<String, Object> estadisticas() {
        return java.util.Map.of(
                "totalPedidos", pedidos.size(),
                "aceptados", pedidosAceptados.count(),
                "rechazados", pedidosRechazados.count(),
                "erroresCatalogo", erroresCatalogo.count()
        );
    }

    private boolean esTimeout(Throwable error) {
        Throwable actual = error;
        while (actual != null) {
            if (actual instanceof TimeoutException) {
                return true;
            }
            actual = actual.getCause();
        }
        return false;
    }
}
