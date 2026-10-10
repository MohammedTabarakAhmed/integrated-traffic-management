package gov.itms.payment.api;

import gov.itms.payment.application.PaymentService;
import gov.itms.payment.application.FinanceOperations;
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
    private final FinanceOperations finance;

    PaymentController(PaymentService payments, Callers callers, FinanceOperations finance) {
        this.payments = payments;
        this.callers = callers;
        this.finance = finance;
    }

    record LineDto(@NotBlank String financialItem, @NotBlank String beneficiary, String costCentre,
                   @Size(max = 200) String description, @Min(0) long amountMinor) {
        LineDto(String financialItem, String beneficiary, String description, long amountMinor) {
            this(financialItem, beneficiary, beneficiary, description, amountMinor);
        }
    }

    record RegisterRequest(@NotNull Payable.ItemType itemType, @NotBlank String itemRef, String trafficFileNumber,
                           @NotBlank String region, @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
                           @NotNull @Size(min = 1, max = 50) List<@NotNull @Valid LineDto> lines) {
    }

    record PayableDto(UUID id, Payable.ItemType itemType, String itemRef, String trafficFileNumber, String region,
                      String currency, List<LineDto> lines, long total, Payable.Status status, Instant createdAt) {
        static PayableDto of(Payable p) {
            return new PayableDto(p.id(), p.itemType(), p.itemRef(), p.trafficFileNumber(), p.region(), p.currency(),
                    p.lines().stream().map(l -> new LineDto(l.financialItem(), l.beneficiary(), l.costCentre(),
                            l.description(), l.amountMinor())).toList(), p.total(), p.status(), p.createdAt());
        }
    }

    record ReasonRequest(@NotBlank @Size(min = 3, max = 500) String reason) {
    }

    record PayableHoldRequest(@NotBlank @Size(max = 80) String sourceRef,
                              @NotBlank @Size(min = 3, max = 500) String reason) { }

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
                          String financialItem, String beneficiary, String costCentre, String description,
                          long amountMinor) {
    }

    record ReceiptDto(String number, UUID paymentId, Receipt.Type type, String region, String channel,
                      Payment.Method method, String currency, long totalMinor, List<ReceiptLineDto> lines,
                      Instant issuedAt, LocalDate businessDay, Receipt.Status status, Instant cancelledAt,
                      String cancellationReason) {
        static ReceiptDto of(Receipt r) {
            return r == null ? null : new ReceiptDto(r.number(), r.paymentId(), r.type(), r.region(), r.channel(),
                    r.method(), r.currency(), r.totalMinor(), r.lines().stream().map(l -> new ReceiptLineDto(
                    l.payableId(), l.itemType(), l.itemRef(), l.trafficFileNumber(), l.financialItem(),
                    l.beneficiary(), l.costCentre(), l.description(), l.amountMinor())).toList(), r.issuedAt(),
                    r.businessDay(),
                    r.status(), r.cancelledAt(), r.cancellationReason());
        }
    }

    record Collection<T>(List<T> items) {
    }

    record RefundRequest(@Min(1) long amountMinor, @NotBlank @Size(min = 3, max = 500) String reason) { }

    record RefundDecision(@NotBlank @Size(min = 3, max = 500) String reason) { }

    record RefundDto(UUID id, UUID paymentId, String receiptNumber, String region, String currency, long amountMinor,
                     String reason, String status, String requestedBy, Instant requestedAt, String decidedBy,
                     Instant decidedAt, String remoteRef) {
        static RefundDto of(FinanceOperations.Refund r) {
            return new RefundDto(r.id(), r.paymentId(), r.receiptNumber(), r.region(), r.currency(), r.amountMinor(),
                    r.reason(), r.status(), r.requestedBy(), r.requestedAt(), r.decidedBy(), r.decidedAt(),
                    r.remoteRef());
        }
    }

    record SettlementLineDto(@NotBlank String source, @NotBlank @Size(max = 200) String externalRef, Instant paidAt,
                             @NotBlank String region, @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
                             @Min(0) long amountMinor) {
        FinanceOperations.SettlementLine toDomain() {
            return new FinanceOperations.SettlementLine(source, externalRef, paidAt, region, currency, amountMinor);
        }
    }

    record SettlementBatch(@NotNull @Size(min = 1, max = 10000) List<@NotNull @Valid SettlementLineDto> lines) { }

    record ReconciliationDto(UUID id, String source, String externalRef, Instant paidAt, String region, String currency,
                             long amountMinor, UUID paymentId, String status, String reason, Instant receivedAt) {
        static ReconciliationDto of(FinanceOperations.Reconciliation r) {
            return new ReconciliationDto(r.id(), r.source(), r.externalRef(), r.paidAt(), r.region(), r.currency(),
                    r.amountMinor(), r.paymentId(), r.status(), r.reason(), r.receivedAt());
        }
    }

    record ResolveRequest(@NotNull UUID paymentId, @NotBlank @Size(min = 3, max = 500) String reason) { }

    record TillOpenRequest(@NotBlank String region, @Min(0) long openingAmountMinor) { }
    record TillAmount(@NotNull Payment.Method method, @Min(0) long actualMinor) { }
    record TillCloseRequest(@NotNull @Size(min = 1, max = 3) List<@NotNull @Valid TillAmount> totals,
                            @NotBlank @Size(min = 3, max = 500) String reason) { }
    record TillDto(UUID id, String cashierId, String region, long openingAmountMinor, Instant openedAt,
                   String status, Instant closedAt, List<FinanceOperations.TillTotal> totals) {
        static TillDto of(FinanceOperations.Till t) {
            return new TillDto(t.id(), t.cashierId(), t.region(), t.openingAmountMinor(), t.openedAt(), t.status(),
                    t.closedAt(), List.of());
        }
        static TillDto of(FinanceOperations.ClosedTill c) {
            FinanceOperations.Till t = c.till();
            return new TillDto(t.id(), t.cashierId(), t.region(), t.openingAmountMinor(), t.openedAt(), t.status(),
                    t.closedAt(), c.totals());
        }
    }

    record BankDepositRequest(@NotBlank @Size(max = 120) String depositRef, @NotBlank @Size(max = 120) String bank,
                              @NotNull @Size(min = 1, max = 500) List<@NotBlank String> receiptNumbers) { }
    record BankDepositDto(UUID id, String depositRef, String bank, String region, String currency, long totalMinor,
                          String status, String createdBy, Instant createdAt, Instant matchedAt, String reason) {
        static BankDepositDto of(FinanceOperations.BankDeposit d) {
            return new BankDepositDto(d.id(), d.depositRef(), d.bank(), d.region(), d.currency(), d.totalMinor(),
                    d.status(), d.createdBy(), d.createdAt(), d.matchedAt(), d.reason());
        }
    }
    record BankStatementLineDto(@NotBlank String source, @NotBlank @Size(max = 200) String externalRef,
                                @NotBlank @Size(max = 120) String bank, Instant bookedAt, @NotBlank String region,
                                @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency, @Min(0) long amountMinor) {
        FinanceOperations.BankStatementLine toDomain() {
            return new FinanceOperations.BankStatementLine(source, externalRef, bank, bookedAt, region, currency,
                    amountMinor);
        }
    }
    record BankStatementBatch(@NotNull @Size(min = 1, max = 10000)
                              List<@NotNull @Valid BankStatementLineDto> lines) { }
    record BankDepositResolution(@NotNull UUID depositId, @NotBlank @Size(min = 3, max = 500) String reason) { }
    record BankStatementDto(UUID id, String source, String externalRef, String bank, String region, String currency,
                            long amountMinor, UUID depositId, String status, String reason, Instant receivedAt) {
        static BankStatementDto of(FinanceOperations.BankStatementResult r) {
            return new BankStatementDto(r.id(), r.source(), r.externalRef(), r.bank(), r.region(), r.currency(),
                    r.amountMinor(), r.depositId(), r.status(), r.reason(), r.receivedAt());
        }
    }

    // ---- payables

    @PostMapping("/v1/payables")
    @PreAuthorize("hasAuthority('payment.payable.register')")
    ResponseEntity<PayableDto> register(@Valid @RequestBody RegisterRequest body) {
        Payable p = payments.register(body.itemType(), body.itemRef(), body.trafficFileNumber(), body.region(),
                body.currency(), body.lines().stream().map(l -> new Payable.Line(l.financialItem(), l.beneficiary(),
                        l.costCentre() == null ? l.beneficiary() : l.costCentre(), l.description(), l.amountMinor()))
                        .toList(), callers.current());
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

    @PostMapping("/v1/payables/{id}/holds")
    @PreAuthorize("hasAuthority('payment.payable.hold')")
    ResponseEntity<Void> holdPayable(@PathVariable UUID id, @Valid @RequestBody PayableHoldRequest body) {
        payments.placePayableHold(id, body.sourceRef(), body.reason(), callers.current());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/v1/payables/{id}/hold-lifts")
    @PreAuthorize("hasAuthority('payment.payable.hold')")
    ResponseEntity<Void> liftPayableHold(@PathVariable UUID id, @Valid @RequestBody PayableHoldRequest body) {
        payments.liftPayableHold(id, body.sourceRef(), body.reason(), callers.current());
        return ResponseEntity.noContent().build();
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

    @PostMapping("/v1/payments/{id}/refund-requests")
    @PreAuthorize("hasAuthority('payment.refund.request')")
    ResponseEntity<RefundDto> requestRefund(@PathVariable UUID id, @Valid @RequestBody RefundRequest body) {
        FinanceOperations.Refund r = finance.requestRefund(id, body.amountMinor(), body.reason(), callers.current());
        return ResponseEntity.created(URI.create("/v1/refund-requests/" + r.id())).body(RefundDto.of(r));
    }

    @PostMapping("/v1/refund-requests/{id}/approvals")
    @PreAuthorize("hasAuthority('payment.refund.approve')")
    RefundDto approveRefund(@PathVariable UUID id, @Valid @RequestBody RefundDecision body) {
        return RefundDto.of(finance.decideRefund(id, true, body.reason(), callers.current()));
    }

    @PostMapping("/v1/refund-requests/{id}/rejections")
    @PreAuthorize("hasAuthority('payment.refund.approve')")
    RefundDto rejectRefund(@PathVariable UUID id, @Valid @RequestBody RefundDecision body) {
        return RefundDto.of(finance.decideRefund(id, false, body.reason(), callers.current()));
    }

    @PostMapping("/v1/settlement-imports")
    @PreAuthorize("hasAuthority('payment.settlement.import')")
    Collection<ReconciliationDto> importSettlement(@Valid @RequestBody SettlementBatch body) {
        return new Collection<>(finance.importSettlements(body.lines().stream().map(SettlementLineDto::toDomain)
                .toList(), callers.current()).stream().map(ReconciliationDto::of).toList());
    }

    @GetMapping("/v1/reconciliation-exceptions")
    @PreAuthorize("hasAuthority('payment.finance.read')")
    Collection<ReconciliationDto> exceptions(@RequestParam(defaultValue = "100") @Min(1) @Max(1000) int limit) {
        return new Collection<>(finance.exceptions(limit, callers.current()).stream().map(ReconciliationDto::of)
                .toList());
    }

    @PostMapping("/v1/reconciliation-exceptions/{id}/resolutions")
    @PreAuthorize("hasAuthority('payment.finance.resolve')")
    ReconciliationDto resolve(@PathVariable UUID id, @Valid @RequestBody ResolveRequest body) {
        return ReconciliationDto.of(finance.resolve(id, body.paymentId(), body.reason(), callers.current()));
    }

    @PostMapping("/v1/tills")
    @PreAuthorize("hasAuthority('payment.till.manage')")
    ResponseEntity<TillDto> openTill(@Valid @RequestBody TillOpenRequest body) {
        FinanceOperations.Till till = finance.openTill(body.region(), body.openingAmountMinor(), callers.current());
        return ResponseEntity.created(URI.create("/v1/tills/" + till.id())).body(TillDto.of(till));
    }

    @PostMapping("/v1/tills/{id}/closures")
    @PreAuthorize("hasAuthority('payment.till.manage')")
    TillDto closeTill(@PathVariable UUID id, @Valid @RequestBody TillCloseRequest body) {
        List<FinanceOperations.TillTotal> totals = body.totals().stream()
                .map(t -> new FinanceOperations.TillTotal(t.method(), 0, t.actualMinor(), 0)).toList();
        return TillDto.of(finance.closeTill(id, totals, body.reason(), callers.current()));
    }

    @PostMapping("/v1/bank-deposits")
    @PreAuthorize("hasAuthority('payment.settlement.import')")
    ResponseEntity<BankDepositDto> recordBankDeposit(@Valid @RequestBody BankDepositRequest body) {
        FinanceOperations.BankDeposit deposit = finance.recordBankDeposit(body.depositRef(), body.bank(),
                body.receiptNumbers(), callers.current());
        return ResponseEntity.created(URI.create("/v1/bank-deposits/" + deposit.id())).body(BankDepositDto.of(deposit));
    }

    @PostMapping("/v1/bank-statement-imports")
    @PreAuthorize("hasAuthority('payment.settlement.import')")
    Collection<BankStatementDto> importBankStatement(@Valid @RequestBody BankStatementBatch body) {
        return new Collection<>(finance.importBankStatement(body.lines().stream().map(BankStatementLineDto::toDomain)
                .toList(), callers.current()).stream().map(BankStatementDto::of).toList());
    }

    @GetMapping("/v1/bank-deposit-exceptions")
    @PreAuthorize("hasAuthority('payment.finance.read')")
    Collection<BankStatementDto> bankDepositExceptions(@RequestParam(defaultValue = "100") @Min(1) @Max(1000) int limit) {
        return new Collection<>(finance.bankDepositExceptions(limit, callers.current()).stream()
                .map(BankStatementDto::of).toList());
    }

    @PostMapping("/v1/bank-deposit-exceptions/{id}/resolutions")
    @PreAuthorize("hasAuthority('payment.finance.resolve')")
    BankStatementDto resolveBankDepositException(@PathVariable UUID id,
                                                 @Valid @RequestBody BankDepositResolution body) {
        return BankStatementDto.of(finance.resolveBankDepositException(id, body.depositId(), body.reason(),
                callers.current()));
    }
}
