package gov.itms.violation.api;

import gov.itms.violation.application.ViolationOrders;
import gov.itms.violation.domain.PreservationOrder;
import gov.itms.violation.domain.ViolationPledge;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Pledges (VF-046) and administrative preservation orders (VF-047) recorded on violations. */
@RestController
class OrdersController {

    record PledgeBody(@NotBlank @Size(max = 200) String signedBy, @NotNull Instant signedAt,
                      @Size(max = 500) String note, @Size(max = 20) List<@NotBlank @Size(max = 64) String> documentIds) {
    }

    record OrderBody(@NotBlank @Size(max = 60) String reference, @NotBlank @Size(max = 200) String source,
                     @NotNull Instant startAt, Instant endAt, @Size(max = 500) String note) {
    }

    record PledgeDto(UUID id, UUID violationId, String violationNumber, String signedBy, Instant signedAt,
                     String note, List<String> documentIds, String recordedBy, Instant recordedAt) {
        static PledgeDto of(ViolationPledge p) {
            return new PledgeDto(p.id(), p.violationId(), p.violationNumber(), p.signedBy(), p.signedAt(), p.note(),
                    p.documentIds(), p.recordedBy(), p.recordedAt());
        }
    }

    record OrderDto(UUID id, UUID violationId, String violationNumber, String reference, String source,
                    Instant startAt, Instant endAt, String note, String recordedBy, Instant recordedAt) {
        static OrderDto of(PreservationOrder o) {
            return new OrderDto(o.id(), o.violationId(), o.violationNumber(), o.reference(), o.source(), o.startAt(),
                    o.endAt(), o.note(), o.recordedBy(), o.recordedAt());
        }
    }

    record Collection<T>(List<T> items) {
    }

    private final ViolationOrders records;
    private final Callers callers;

    OrdersController(ViolationOrders records, Callers callers) {
        this.records = records;
        this.callers = callers;
    }

    @PostMapping("/v1/violations/{number}/pledges")
    @PreAuthorize("hasAuthority('violation.pledge.create')")
    ResponseEntity<PledgeDto> pledge(@PathVariable String number, @Valid @RequestBody PledgeBody body) {
        ViolationPledge pledge = records.pledge(number, body.signedBy(), body.signedAt(), body.note(),
                body.documentIds() == null ? List.of() : body.documentIds(), callers.current());
        return ResponseEntity.created(URI.create("/v1/violations/" + number + "/pledges/" + pledge.id()))
                .body(PledgeDto.of(pledge));
    }

    @GetMapping("/v1/violations/{number}/pledges")
    @PreAuthorize("hasAuthority('violation.read')")
    Collection<PledgeDto> pledges(@PathVariable String number) {
        return new Collection<>(records.pledges(number, callers.current()).stream().map(PledgeDto::of).toList());
    }

    @PostMapping("/v1/violations/{number}/preservation-orders")
    @PreAuthorize("hasAuthority('violation.preservation.create')")
    ResponseEntity<OrderDto> order(@PathVariable String number, @Valid @RequestBody OrderBody body) {
        PreservationOrder order = records.preservationOrder(number, body.reference(), body.source(), body.startAt(),
                body.endAt(), body.note(), callers.current());
        return ResponseEntity.created(URI.create("/v1/violations/" + number + "/preservation-orders/" + order.id()))
                .body(OrderDto.of(order));
    }

    @GetMapping("/v1/violations/{number}/preservation-orders")
    @PreAuthorize("hasAuthority('violation.read')")
    Collection<OrderDto> orders(@PathVariable String number) {
        return new Collection<>(records.preservationOrders(number, callers.current()).stream().map(OrderDto::of)
                .toList());
    }
}
