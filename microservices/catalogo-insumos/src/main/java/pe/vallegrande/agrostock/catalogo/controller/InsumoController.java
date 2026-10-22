package pe.vallegrande.agrostock.catalogo.controller;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api/insumos")
public class InsumoController {

    public record Insumo(String id, String nombre, int stock, double precio) {}
    public record NuevoInsumo(String nombre, int stock, double precio) {}

    private final ConcurrentHashMap<String, Insumo> insumos = new ConcurrentHashMap<>();
    private final AtomicLong secuencia = new AtomicLong(3);
    private final Counter consultas;
    private final Counter registros;
    private final Counter actualizaciones;
    private final Timer tiempoConsulta;

    @Value("${catalogo.delay-ms:0}")
    private long retrasoMs;

    public InsumoController(MeterRegistry registry) {
        insumos.put("SUP001", new Insumo("SUP001", "Fertilizante orgánico", 50, 35.0));
        insumos.put("SUP002", new Insumo("SUP002", "Abono foliar", 30, 28.5));
        insumos.put("SUP003", new Insumo("SUP003", "Bioestimulante", 8, 42.0));

        consultas = Counter.builder("catalogo_consultas_total")
                .description("Consultas realizadas al catálogo")
                .register(registry);
        registros = Counter.builder("catalogo_insumos_registrados_total")
                .description("Insumos registrados")
                .register(registry);
        actualizaciones = Counter.builder("catalogo_stock_actualizaciones_total")
                .description("Actualizaciones de stock")
                .register(registry);
        tiempoConsulta = Timer.builder("catalogo_consulta_duracion")
                .description("Duración de consultas al catálogo")
                .register(registry);

        Gauge.builder("catalogo_insumos_en_memoria", insumos, mapa -> mapa.size())
                .description("Cantidad de insumos en memoria")
                .register(registry);

        Gauge.builder("catalogo_retraso_configurado_ms", this, controller -> controller.retrasoMs)
                .description("Retraso configurado para las consultas del catálogo en milisegundos")
                .register(registry);
    }

    @GetMapping
    public List<Insumo> listar() {
        return tiempoConsulta.record(() -> {
            esperarSiCorresponde();
            consultas.increment();
            return List.copyOf(insumos.values());
        });
    }

    @GetMapping("/{id}")
    public Insumo buscar(@PathVariable String id) {
        consultas.increment();
        Insumo insumo = insumos.get(id);
        if (insumo == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Insumo no encontrado");
        }
        return insumo;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Insumo registrar(@RequestBody NuevoInsumo nuevo) {
        if (nuevo.nombre() == null || nuevo.nombre().isBlank()
                || nuevo.stock() < 0 || nuevo.precio() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Datos de insumo inválidos");
        }

        String id = "SUP" + String.format("%03d", secuencia.incrementAndGet());
        Insumo insumo = new Insumo(id, nuevo.nombre(), nuevo.stock(), nuevo.precio());
        insumos.put(id, insumo);
        registros.increment();
        return insumo;
    }

    @PatchMapping("/{id}/stock")
    public Insumo actualizarStock(@PathVariable String id, @RequestParam int cantidad) {
        Insumo actualizado = insumos.compute(id, (clave, anterior) -> {
            if (anterior == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Insumo no encontrado");
            }
            int stockNuevo = anterior.stock() + cantidad;
            if (stockNuevo < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Stock insuficiente");
            }
            return new Insumo(anterior.id(), anterior.nombre(), stockNuevo, anterior.precio());
        });
        actualizaciones.increment();
        return actualizado;
    }

    @GetMapping("/stock-bajo")
    public List<Insumo> stockBajo(@RequestParam(defaultValue = "10") int minimo) {
        consultas.increment();
        return insumos.values().stream()
                .filter(insumo -> insumo.stock() <= minimo)
                .toList();
    }

    private void esperarSiCorresponde() {
        if (retrasoMs > 0) {
            try {
                Thread.sleep(retrasoMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Consulta interrumpida", e);
            }
        }
    }
}
