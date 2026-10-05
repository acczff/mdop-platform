package io.github.acczff.mdop.integration.messaging;

import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/integration/messages")
@PreAuthorize("hasRole('ADMIN')")
public class DeliveryController {
    private final DeliveryService service;

    public DeliveryController(DeliveryService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestParam DeliveryService.Direction direction,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "0") int page) {
        return service.list(direction, status, page);
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return service.summary();
    }

    @GetMapping("/results")
    public List<Map<String, Object>> results() {
        return service.results();
    }

    @GetMapping("/rejections")
    public List<Map<String, Object>> rejections() {
        return service.rejections();
    }

    @GetMapping("/{direction}/{id}/history")
    public List<Map<String, Object>> history(
            @PathVariable DeliveryService.Direction direction, @PathVariable String id) {
        return service.history(direction, id);
    }

    public record Replay(String reason) {}

    @PostMapping("/{direction}/{id}/replay")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void replay(
            @PathVariable DeliveryService.Direction direction,
            @PathVariable String id,
            @RequestBody Replay input) {
        service.replay(direction, id, input.reason());
    }
}
