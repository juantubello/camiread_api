package net.casapipis.camireads.web.controller;

import lombok.RequiredArgsConstructor;
import net.casapipis.camireads.dto.StatsResponse;
import net.casapipis.camireads.service.StatsService;
import org.springframework.web.bind.annotation.*;

/**
 * GET /stats — metricas de lectura para la pantalla de perfil.
 *
 * Solo lectura: no hay POST/PUT/DELETE. El @CrossOrigin es el mismo que ya
 * usan ReviewController y TagController.
 */
@CrossOrigin(
        origins = "*",
        allowedHeaders = "*",
        methods = {RequestMethod.GET, RequestMethod.OPTIONS}
)
@RestController
@RequestMapping("/stats")
@RequiredArgsConstructor
public class StatsController {

    private final StatsService statsService;

    @GetMapping
    public StatsResponse getStats() {
        return statsService.getStats();
    }
}
