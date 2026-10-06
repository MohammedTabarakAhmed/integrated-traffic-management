package gov.itms.payment.api;

import gov.itms.payment.application.PaymentService;
import gov.itms.payment.application.Ports;
import gov.itms.payment.domain.Basket;
import gov.itms.payment.domain.Payable;
import gov.itms.payment.domain.Payment;
import gov.itms.payment.domain.Receipt;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Payables, baskets, payments, gateway callbacks and receipts of {@code payment-service.v1.yaml}. */
@RestController
class PaymentController {

    private final PaymentService payments;
    private final Callers callers;

    PaymentController(PaymentService payments, Callers callers) {
        this.payments = payments;
        this.callers = callers;
    }

    record LineDto(@NotBlank String financialItem, @NotBlank String beneficiary, @Size(max = 200) String description,
                   @Min(0) long amountMinor) {
    }

    record RegisterRequest(@NotNull Payable.ItemType itemType, @NotBlank String itemRef, String trafficFileNumber,
                           @NotBlank String region, @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
                           @NotNull @Size(min = 1, max = 50) List<@NotNull @Valid LineDto> lines) {
    }

    record PayableDto(UUID id, Payable.ItemType itemType, String itemRef, String trafficFileNumber, String region,
                      String currency, List<LineDto> lines, long total, Payable.Status status, Instant createdAt) {
        static PayableDto of(Payable p) {
            return new PayableDto(p.id(), p.itemType(), p.itemRef(), p.trafficFileNumber(), p.region(), p.currency(),
                    p.lines().stream().map(l -> new LineDto(l.financialItem(), l.beneficiary(), l.description(),
                            l.amountMinor())).toList(), p.total(), p.status(), p.createdAt());
        }
    }

    record ReasonRequest(@NotBlank @Size(min = 3, max = 500) String reason) {
    }

    record BasketRequest(@NotNull @Size(min = 1, max = 100) List<@NotNull UUID> payableIds, String region) {
    }

    record BasketDto(UUID id, List<UUID> payableIds, String region, String currency, long totalMinor,
                     Basket.Status status, Instant createdAt) {
        static BasketDto of(Basket b) {
            return new BasketDto(b.id(), b.payableIds(), b.region(), b.currency(), b.totalMinor(), b.status(),
                    b.createdAt());
        }
    }

    record PayRequest(@NotNull Payment.Method method, @Size(max = 500) String returnUrl,
                      @Size(max = 60) String counterReference) {
    }

    record PaymentDto(UUID id, UUID basketId, Payment.Method method, long amountMinor, String currency,
                      String channel, String region, Payment.Status status, String gatewayTxnId, Instant createdAt,
                      Instant confirmedAt, String exceptionReason) {
        static PaymentDto of(Payment p) {
            return new PaymentDto(p.id(), p.basketId(), p.method(), p.amountMinor(), p.currency(), p.channel(),
                    p.region(), p.status(), p.gatewayTxnId(), p.createdAt(), p.confirmedAt(), p.exceptionReason());
        }
    }

    record StartedDto(PaymentDto payment, String redirectUrl, ReceiptDto receipt) {
    }

    record CallbackRequest(@NotBlank @Size(max = 200) String gatewayTxnId,
                           @NotBlank @Pattern(regexp = "^[0-9a-fA-F-]{36}$") String merchantRef,
                           @NotBlank @Pattern(regexp = "^(SUCCESS|FAILED)$") String status,
                           @Min(0) long amountMinor, @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
                           @Size(max = 40) String paidAt, @NotBlank @Size(max = 128) String signature) {
    }

    record CallbackDto(UUID paymentId, Payment.Status status, String receiptNumber, boolean duplicate) {
    }

    record ReceiptLineDto(UUID payableId, Payable.ItemType itemType, String itemRef, String trafficFileNumber,
                          String financialItem, String beneficiary, String description, long amountMinor) {
    }

    record ReceiptDto(String number, UUID paymentId, Receipt.Type type, String region, String channel,
                      Payment.Method method, String currency, long totalMinor, List<ReceiptLineDto> lines,
                      Instant issuedAt, LocalDate businessDay, Receipt.Status status, Instant cancelledAt,
                      String cancellationReason) {
        static ReceiptDto of(Receipt r) {
            return r == null ? null : new ReceiptDto(r.number(), r.paymentId(), r.type(), r.region(), r.channel(),
                    r.method(), r.currency(), r.totalMinor(), r.lines().stream().map(l -> new ReceiptLineDto(
                    l.payableId(), l.itemType(), l.itemRef(), l.trafficFileNumber(), l.financialItem(),
                    l.beneficiary(), l.description(), l.amountMinor())).toList(), r.issuedAt(), r.businessDay(),
                    r.status(), r.cancelledAt(), r.cancellationReason());
        }
    }

    record Collection<T>(List<T> items) {
    }

    // ---- payables

    @PostMapping("/v1/payables")
    @PreAuthorize("hasAuthority('payment.payable.register')")
    ResponseEntity<PayableDto> register(@Valid @RequestBody RegisterRequest body) {
        Payable p = payments.register(body.itemType(), body.itemRef(), body.trafficFileNumber(), body.region(),
                body.currency(), body.lines().stream().map(l -> new Payable.Line(l.financialItem(), l.beneficiary(),
                        l.description(), l.amountMinor())).toList(), callers.current());
        return ResponseEntity.created(URI.create("/v1/payables/" + p.id())).body(PayableDto.of(p));
    }

    @GetMapping("/v1/payables/{id}")
    @PreAuthorize("isAuthenticated()")
    PayableDto payable(@PathVariable UUID id) {
        return PayableDto.of(payments.payable(id));
    }

    @PostMapping("/v1/payables/{id}/cancellations")
    @PreAuthorize("hasAuthority('payment.payable.register')")
    PayableDto cancelPayable(@PathVariable UUID id, @Valid @RequestBody ReasonRequest body) {
        return PayableDto.of(payments.cancelPayable(id, body.reason(), callers.current()));
    }

    // ---- baskets and payments

    @PostMapping("/v1/baskets")
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<BasketDto> basket(@Valid @RequestBody BasketRequest body) {
        Basket b = payments.basket(body.payableIds(), body.region(), callers.current());
        return ResponseEntity.created(URI.create("/v1/baskets/" + b.id())).body(BasketDto.of(b));
    }

    @GetMapping("/v1/baskets/{id}")
    @PreAuthorize("isAuthenticated()")
    BasketDto getBasket(@PathVariable UUID id) {
        return BasketDto.of(payments.getBasket(id, callers.current()));
    }

    @PostMapping("/v1/baskets/{id}/payments")
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<StartedDto> pay(@PathVariable UUID id, @Valid @RequestBody PayRequest body) {
        PaymentService.Started s = payments.pay(id, body.method(), body.returnUrl(), body.counterReference(),
                callers.current());
        return ResponseEntity.created(URI.create("/v1/payments/" + s.payment().id()))
                .body(new StartedDto(PaymentDto.of(s.payment()), s.redirectUrl(), ReceiptDto.of(s.receipt())));
    }

    @GetMapping("/v1/payments/{id}")
    @PreAuthorize("isAuthenticated()")
    PaymentDto payment(@PathVariable UUID id) {
        return PaymentDto.of(payments.payment(id, callers.current()));
    }

    /** ICD-03 callback through the partner ingress (FN-004): duplicates answer 200 with the same receipt. */
    @PostMapping("/v1/payments/{id}/callbacks")
    @PreAuthorize("hasAuthority('payment.gateway.callback')")
    CallbackDto callback(@PathVariable UUID id, @Valid @RequestBody CallbackRequest body) {
        if (!id.toString().equalsIgnoreCase(body.merchantRef())) {
            throw gov.itms.payment.domain.DomainException.validation("FIELD_INVALID",
                    "merchantRef is not this payment", List.of("FN-004"), "merchantRef");
        }
        PaymentService.CallbackOutcome o = payments.callback(id, body.gatewayTxnId(), body.status(),
                body.amountMinor(), body.currency(), body.paidAt(), body.signature(), callers.current());
        return new CallbackDto(o.payment().id(), o.payment().status(),
                o.receipt() == null ? null : o.receipt().number(), o.duplicate());
    }

    // ---- receipts

    @GetMapping("/v1/receipts/{number}")
    @PreAuthorize("isAuthenticated()")
    ReceiptDto receipt(@PathVariable String number) {
        return ReceiptDto.of(payments.receipt(number, callers.current()));
    }

    @GetMapping("/v1/receipts")
    @PreAuthorize("hasAuthority('payment.receipt.read')")
    Collection<ReceiptDto> searchReceipts(@RequestParam(required = false) String trafficFileNumber,
                                          @RequestParam(required = false) String payerId,
                                          @RequestParam(required = false) LocalDate from,
                                          @RequestParam(required = false) LocalDate to,
                                          @RequestParam(required = false) Long amountMinor,
                                          @RequestParam(required = false) String channel,
                                          @RequestParam(required = false) String financialItem,
                                          @RequestParam(required = false) String region,
                                          @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {
        return new Collection<>(payments.searchReceipts(new Ports.PaymentRepository.ReceiptQuery(trafficFileNumber,
                payerId, from, to, amountMinor, channel, financialItem, region, limit), callers.current()).stream()
                .map(ReceiptDto::of).toList());
    }

    @PostMapping("/v1/receipts/{number}/cancellations")
    @PreAuthorize("hasAuthority('payment.receipt.cancel')")
    ReceiptDto cancelReceipt(@PathVariable String number, @Valid @RequestBody ReasonRequest body) {
        return ReceiptDto.of(payments.cancelReceipt(number, body.reason(), callers.current()));
    }
}
