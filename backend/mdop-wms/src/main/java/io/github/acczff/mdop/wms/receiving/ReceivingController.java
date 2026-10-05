package io.github.acczff.mdop.wms.receiving;

import static io.github.acczff.mdop.wms.receiving.ReceivingModels.*;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/wms")
@PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:arrival:read')")
public class ReceivingController {
    private final ReceivingService service;

    public ReceivingController(ReceivingService service) {
        this.service = service;
    }

    @GetMapping("/arrival-notices")
    public List<Arrival> arrivals(@RequestParam long warehouseId) {
        return service.arrivals(warehouseId);
    }

    @GetMapping("/arrival-notices/{id}")
    public ArrivalDetail arrival(@PathVariable long id) {
        return service.arrival(id);
    }

    @GetMapping("/arrival-notices/{id}/receipts")
    public List<Receipt> receipts(@PathVariable long id) {
        return service.receipts(id);
    }

    @PostMapping("/arrival-notices/{id}/receipts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:receipt:create')")
    public ReceiptDetail create(@PathVariable long id, @Valid @RequestBody DraftInput input) {
        return service.createDraft(id, input);
    }

    @GetMapping("/receipts/{id}")
    public ReceiptDetail receipt(@PathVariable long id) {
        return service.receipt(id);
    }

    @PutMapping("/receipts/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:receipt:create')")
    public ReceiptDetail update(@PathVariable long id, @Valid @RequestBody UpdateDraft input) {
        return service.updateDraft(id, input);
    }

    @PostMapping("/receipts/{id}/submit")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:receipt:submit')")
    public ReceiptDetail submit(@PathVariable long id, @Valid @RequestBody SubmitInput input) {
        return service.submit(id, input);
    }

    @GetMapping("/inventory")
    public List<Balance> inventory(@RequestParam long warehouseId) {
        return service.inventory(warehouseId);
    }

    @GetMapping("/receipts/{id}/transactions")
    public List<Ledger> ledger(@PathVariable long id) {
        return service.ledger(id);
    }
}
