package gov.itms.crossborder.api;

import gov.itms.crossborder.application.CrossborderService;
import gov.itms.crossborder.application.ExchangeDelivery;
import gov.itms.crossborder.application.Ports;
import gov.itms.platform.security.CurrentActorProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CrossborderController {

    private final CrossborderService exchange;
    private final ExchangeDelivery delivery;
    private final CurrentActorProvider actors;

    CrossborderController(CrossborderService exchange, ExchangeDelivery delivery, CurrentActorProvider actors) {
        this.exchange = exchange;
        this.delivery = delivery;
        this.actors = actors;
    }

    record PartnerRequest(@NotBlank @Size(max = 12) String code, @NotBlank @Size(max = 120) String name,
                          @NotBlank String mode, @Min(0) @Max(10000) int commissionBasisPoints,
                          @Size(max = 500) String endpointUrl, boolean allowsAccident, boolean allowsPermit,
                          boolean allowsLicence, String status, @NotBlank @Size(min = 3, max = 500) String reason) { }
    record MappingRequest(@NotBlank @Size(max = 40) String domain, @NotBlank @Size(max = 80) String localCode,
                          @NotBlank @Size(max = 80) String externalCode,
                          @NotBlank @Size(min = 3, max = 500) String reason) { }
    record DecisionRequest(boolean approve, @NotBlank @Size(min = 3, max = 500) String reason) { }
    record ViolationRequest(@NotBlank @Size(max = 120) String reference, @NotBlank String code,
                            @NotBlank @Size(max = 40) String vehiclePlate, @Size(max = 40) String ownerFile,
                            @NotBlank @Size(min = 3, max = 3) String currency, @Min(0) long amountMinor,
                            @NotNull Instant occurredAt, @Size(max = 300) String location) { }
    record ReceivedViolationRequest(@NotBlank @Size(max = 120) String externalId, @NotBlank String code,
                                    @NotBlank @Size(max = 40) String vehiclePlate, @Size(max = 300) String location,
                                    @NotBlank @Size(min = 3, max = 3) String currency, @Min(0) long amountMinor,
                                    @NotNull Instant occurredAt) { }
    record PaymentRequest(@NotBlank @Size(max = 120) String externalId,
                          @NotBlank @Size(max = 120) String paymentReference, @Min(0) long amountMinor,
                          @NotBlank @Size(min = 3, max = 3) String currency) { }
    record ObjectionRequest(@Size(max = 120) String externalId, @Size(max = 30) String violationNumber,
                            @NotBlank @Size(max = 1000) String reason) { }
    record PartnerObjectionRequest(@NotBlank @Size(max = 120) String externalId,
                                   @NotBlank @Size(max = 1000) String reason) { }
    record PartnerDecisionRequest(@NotBlank @Size(max = 120) String externalId, @NotBlank String decision,
                                  @Size(max = 500) String reason) { }
    record DataRequest(@NotBlank String type, @NotBlank @Size(max = 120) String reference,
                       @NotNull @Size(min = 1, max = 100) Map<String, Object> content) { }
    record ReceivedDataRequest(@NotBlank @Size(max = 120) String externalId, @NotBlank String type,
                               @NotBlank @Size(max = 120) String reference,
                               @NotNull @Size(min = 1, max = 100) Map<String, Object> content) { }
    record AckRequest(boolean accepted, @Size(max = 500) String response, @Size(max = 120) String partnerReference) { }
    record Window(@NotNull Instant from, @NotNull Instant to) { }
    record Delivered(int accepted) { }
    record Collection<T>(List<T> items) { }

    // ---- maker-checker configuration (XB-007, XB-008)

    @PostMapping("/v1/partner-states")
    @PreAuthorize("hasAuthority('crossborder.partner.manage')")
    ResponseEntity<Ports.Proposal> proposePartner(@Valid @RequestBody PartnerRequest b) {
        var p = exchange.proposePartner(b.code(), b.name(), b.mode(), b.commissionBasisPoints(), b.endpointUrl(),
                b.allowsAccident(), b.allowsPermit(), b.allowsLicence(), b.status(), b.reason(), actor());
        return ResponseEntity.accepted().location(URI.create("/v1/config-proposals/" + p.id())).body(p);
    }

    @GetMapping("/v1/partner-states/{code}")
    @PreAuthorize("hasAuthority('crossborder.message.read')")
    ResponseEntity<CrossborderService.Partner> partner(@PathVariable String code) {
        return ResponseEntity.of(exchange.partner(code));
    }

    @PostMapping("/v1/partner-states/{code}/mappings")
    @PreAuthorize("hasAuthority('crossborder.mapping.manage')")
    ResponseEntity<Ports.Proposal> proposeMapping(@PathVariable String code, @Valid @RequestBody MappingRequest b) {
        var p = exchange.proposeMapping(code, b.domain(), b.localCode(), b.externalCode(), b.reason(), actor());
        return ResponseEntity.accepted().location(URI.create("/v1/config-proposals/" + p.id())).body(p);
    }

    @GetMapping("/v1/config-proposals")
    @PreAuthorize("hasAuthority('crossborder.proposal.approve')")
    Collection<Ports.Proposal> proposals(@RequestParam(defaultValue = "50") int limit) {
        return new Collection<>(exchange.openProposals(limit));
    }

    @PostMapping("/v1/config-proposals/{id}/decisions")
    @PreAuthorize("hasAuthority('crossborder.proposal.approve')")
    Ports.Proposal decide(@PathVariable UUID id, @Valid @RequestBody DecisionRequest b) {
        return exchange.decideProposal(id, b.approve(), b.reason(), actor());
    }

    // ---- violations and payments (XB-001..XB-003)

    @PostMapping("/v1/partner-states/{code}/violations")
    @PreAuthorize("hasAuthority('crossborder.exchange')")
    ResponseEntity<CrossborderService.Message> send(@PathVariable String code, @Valid @RequestBody ViolationRequest b) {
        var m = exchange.sendViolation(code, b.reference(), b.code(), b.vehiclePlate(), b.ownerFile(), b.currency(),
                b.amountMinor(), b.occurredAt(), b.location(), actor());
        return ResponseEntity.created(URI.create("/v1/exchange-messages/" + m.id())).body(m);
    }

    @PostMapping("/v1/partner-states/{code}/inbound-violations")
    @PreAuthorize("hasAuthority('crossborder.exchange')")
    ResponseEntity<CrossborderService.Message> receive(@PathVariable String code,
                                                       @Valid @RequestBody ReceivedViolationRequest b) {
        var m = exchange.receiveViolation(code, b.externalId(), b.code(), b.vehiclePlate(), b.location(),
                b.currency(), b.amountMinor(), b.occurredAt(), actor());
        return ResponseEntity.created(URI.create("/v1/exchange-messages/" + m.id())).body(m);
    }

    @PostMapping("/v1/partner-states/{code}/payment-notices")
    @PreAuthorize("hasAuthority('crossborder.exchange')")
    CrossborderService.Message payment(@PathVariable String code, @Valid @RequestBody PaymentRequest b) {
        return exchange.paymentNotice(code, b.externalId(), b.paymentReference(), b.amountMinor(), b.currency(),
                actor());
    }

    // ---- objections (XB-005)

    /** An officer forwards a customer's objection to a partner violation; payment pauses meanwhile. */
    @PostMapping("/v1/partner-states/{code}/objections")
    @PreAuthorize("hasAuthority('crossborder.objection.submit')")
    CrossborderService.Message object(@PathVariable String code, @Valid @RequestBody ObjectionRequest b) {
        return exchange.forwardObjection(code, b.externalId(), b.violationNumber(), b.reason(), true, actor());
    }

    /** The issuing state's decision on an objection forwarded to it. */
    @PostMapping("/v1/partner-states/{code}/objection-decisions")
    @PreAuthorize("hasAuthority('crossborder.exchange')")
    CrossborderService.Message partnerDecision(@PathVariable String code,
                                               @Valid @RequestBody PartnerDecisionRequest b) {
        return exchange.partnerDecision(code, b.externalId(), b.decision(), b.reason(), actor());
    }

    /** A partner forwards its customer's objection to a violation sent from here. */
    @PostMapping("/v1/partner-states/{code}/inbound-objections")
    @PreAuthorize("hasAuthority('crossborder.exchange')")
    CrossborderService.Message inboundObjection(@PathVariable String code,
                                                @Valid @RequestBody PartnerObjectionRequest b) {
        return exchange.receiveObjection(code, b.externalId(), b.reason(), actor());
    }

    // ---- accident, permit and licence data (XB-006, DL-064)

    @PostMapping("/v1/partner-states/{code}/data-exchanges")
    @PreAuthorize("hasAuthority('crossborder.data.exchange')")
    ResponseEntity<CrossborderService.Message> sendData(@PathVariable String code, @Valid @RequestBody DataRequest b) {
        var m = exchange.sendData(code, b.type(), b.reference(), b.content(), actor());
        return ResponseEntity.created(URI.create("/v1/exchange-messages/" + m.id())).body(m);
    }

    @PostMapping("/v1/partner-states/{code}/inbound-data")
    @PreAuthorize("hasAuthority('crossborder.exchange')")
    ResponseEntity<CrossborderService.Message> receiveData(@PathVariable String code,
                                                           @Valid @RequestBody ReceivedDataRequest b) {
        var m = exchange.receiveData(code, b.type(), b.externalId(), b.reference(), b.content(), actor());
        return ResponseEntity.created(URI.create("/v1/exchange-messages/" + m.id())).body(m);
    }

    // ---- delivery (XB-008)

    /** Delivers what is due now for the partner, optionally only messages that occurred in the window. */
    @PostMapping("/v1/partner-states/{code}/batches")
    @PreAuthorize("hasAuthority('crossborder.exchange')")
    Delivered batch(@PathVariable String code, @RequestBody(required = false) Window w) {
        return new Delivered(delivery.deliver(code, w == null ? null : w.from(), w == null ? null : w.to()));
    }

    /** A partner that acknowledges later confirms or refuses a delivery. */
    @PostMapping("/v1/exchange-messages/{id}/acknowledgements")
    @PreAuthorize("hasAuthority('crossborder.exchange')")
    CrossborderService.Message acknowledge(@PathVariable UUID id, @Valid @RequestBody AckRequest b) {
        return exchange.delivered(id, new Ports.PartnerTransport.Outcome(b.accepted(), !b.accepted(), b.response(),
                b.partnerReference()), actor());
    }

    // ---- settlement (XB-004)

    @GetMapping("/v1/partner-states/{code}/settlements")
    @PreAuthorize("hasAuthority('crossborder.settlement.read')")
    Collection<CrossborderService.Settlement> settlement(@PathVariable String code, @RequestParam Instant from,
                                                         @RequestParam Instant to) {
        return new Collection<>(exchange.settlement(code, from, to));
    }

    @PostMapping("/v1/partner-states/{code}/settlement-statements")
    @PreAuthorize("hasAuthority('crossborder.settlement.issue')")
    Collection<CrossborderService.StatementView> issue(@PathVariable String code, @Valid @RequestBody Window w) {
        return new Collection<>(exchange.issueStatements(code, w.from(), w.to(), actor()));
    }

    @GetMapping("/v1/partner-states/{code}/settlement-statements")
    @PreAuthorize("hasAuthority('crossborder.settlement.read')")
    Collection<Ports.Statement> statements(@PathVariable String code, @RequestParam(defaultValue = "50") int limit) {
        return new Collection<>(exchange.statements(code, limit));
    }

    @GetMapping("/v1/settlement-statements/{id}")
    @PreAuthorize("hasAuthority('crossborder.settlement.read')")
    CrossborderService.StatementView statement(@PathVariable UUID id) {
        return exchange.statement(id);
    }

    // ---- message log (XB-007)

    @GetMapping("/v1/exchange-messages")
    @PreAuthorize("hasAuthority('crossborder.message.read')")
    Collection<CrossborderService.Message> messages(@RequestParam(required = false) String partner,
                                                    @RequestParam(required = false) String type,
                                                    @RequestParam(required = false) String status,
                                                    @RequestParam(defaultValue = "50") int limit) {
        return new Collection<>(exchange.messages(partner, type, status, limit));
    }

    @GetMapping("/v1/exchange-messages/{id}")
    @PreAuthorize("hasAuthority('crossborder.message.read')")
    CrossborderService.Message message(@PathVariable UUID id) {
        return exchange.message(id);
    }

    @GetMapping("/v1/exchange-messages/{id}/history")
    @PreAuthorize("hasAuthority('crossborder.message.read')")
    Collection<Ports.History> history(@PathVariable UUID id) {
        return new Collection<>(exchange.history(id));
    }

    private String actor() {
        return actors.current().userId();
    }
}
