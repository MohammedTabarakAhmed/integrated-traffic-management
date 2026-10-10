package gov.itms.vehicle.application;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.vehicle.domain.DomainException;
import gov.itms.vehicle.domain.Plates;
import gov.itms.vehicle.domain.Records;
import gov.itms.vehicle.domain.Registration;
import gov.itms.vehicle.domain.Vehicle;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What every paid vehicle and plate transaction shares (ADR-059): quote the fee at fees-service, register the payable
 * at payment-service, keep the request until payment-service confirms it; and the checks that stop a transaction
 * before any fee is asked (bans, mortgages, holds, open requests).
 */
public class RequestDesk {

    private final Ports.VehicleRepository repo;
    private final Ports.RecordsRepository records;
    private final Ports.FeeQuotes fees;
    private final Ports.Payables payables;
    private final Ports.Eligibility eligibility;
    private final VehicleRules vehicleRules;
    private final ServiceRules rules;
    private final AuditRecorder audit;
    private final Clock clock;

    public RequestDesk(Ports.VehicleRepository repo, Ports.RecordsRepository records, Ports.FeeQuotes fees,
                       Ports.Payables payables, Ports.Eligibility eligibility, VehicleRules vehicleRules,
                       ServiceRules rules, AuditRecorder audit, Clock clock) {
        this.repo = repo;
        this.records = records;
        this.fees = fees;
        this.payables = payables;
        this.eligibility = eligibility;
        this.vehicleRules = vehicleRules;
        this.rules = rules;
        this.audit = audit;
        this.clock = clock;
    }

    /** The details of a transaction to open: who pays, where, for what subject, and what it will change. */
    record Spec(Registration.Kind kind, Vehicle vehicle, String region, String payerFile, String customerCategory,
                String subjectCategory, UUID plateId, LocalDate newExpiry, Map<String, String> payload,
                String subjectRef) {
    }

    /** Quotes the kind's fee, registers the payable and keeps the request (VL-012…VL-039). */
    Registration.Request open(Spec s, String channel, Caller caller) {
        Ports.FeeQuotes.Quote quote = fees.quote(rules.fee(s.kind()), s.region(), s.customerCategory(),
                s.subjectCategory(), null);
        return keep(s, quote.id(), quote.lines(), quote.total(), quote.currency(), channel, caller);
    }

    /** A request whose price is not a fee (an auction price, VL-034). */
    Registration.Request openPriced(Spec s, long amountMinor, String description, String channel, Caller caller) {
        List<Ports.FeeQuotes.Line> lines = List.of(new Ports.FeeQuotes.Line(rules.auctionFinancialItem(),
                rules.auctionBeneficiary(), description, amountMinor));
        return keep(s, null, lines, amountMinor, rules.currency(), channel, caller);
    }

    /** A transfer waits for the buyer's consent before its fee is asked (VL-012). */
    Registration.Request awaitConsent(Spec s, String channel, Caller caller) {
        Instant now = clock.instant();
        Registration.Request r = new Registration.Request(UUID.randomUUID(), s.vehicle().id(), s.kind(),
                Registration.Status.AWAITING_CONSENT, null, null, 0, null, s.newExpiry(), s.plateId(),
                caller.actor().id(), channel, now, null, s.payload(), s.subjectRef(), s.payerFile());
        repo.insertRequest(r);
        trace(s, r, "REQUESTED", caller.actor().id(), now);
        return r;
    }

    /** The buyer consented: the fee is quoted and the payable registered now (VL-012). */
    Registration.Request priceConsented(Registration.Request r, Spec s, Caller caller) {
        Ports.FeeQuotes.Quote quote = fees.quote(rules.fee(r.kind()), s.region(), s.customerCategory(),
                s.subjectCategory(), null);
        UUID payableId = payables.register(VehicleService.itemRef(r.id()), s.payerFile(), s.region(),
                quote.currency(), quote.lines());
        Registration.Request priced = r.priced(quote.id(), payableId, quote.total(), quote.currency(), s.plateId(),
                s.payload());
        repo.updateRequest(priced);
        return priced;
    }

    private Registration.Request keep(Spec s, String quoteId, List<Ports.FeeQuotes.Line> lines, long total,
                                      String currency, String channel, Caller caller) {
        UUID id = UUID.randomUUID();
        UUID payableId = payables.register(VehicleService.itemRef(id), s.payerFile(), s.region(), currency, lines);
        Instant now = clock.instant();
        Registration.Request r = new Registration.Request(id, s.vehicle() == null ? null : s.vehicle().id(),
                s.kind(), Registration.Status.AWAITING_PAYMENT, quoteId, payableId, total, currency, s.newExpiry(),
                s.plateId(), caller.actor().id(), channel, now, null, s.payload(), s.subjectRef(), s.payerFile());
        repo.insertRequest(r);
        trace(s, r, "REQUESTED", caller.actor().id(), now);
        return r;
    }

    private void trace(Spec s, Registration.Request r, String what, String actor, Instant now) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("requestId", r.id().toString());
        detail.put("kind", r.kind().name());
        detail.put("amount", r.amountMinor());
        detail.putAll(s.payload());
        if (s.vehicle() != null) {
            repo.history(s.vehicle().id(), r.kind().name() + "_" + what, actor, detail, now);
        }
        audit.record("VEHICLE_" + r.kind().name() + "_" + what, s.vehicle() == null ? "Plate" : VehicleService.ENTITY,
                s.vehicle() == null ? s.subjectRef() : s.vehicle().vin(), null, detail, null);
    }

    // ---- checks made before any fee

    /** VL-006: a banned vehicle may not be used, registered, renewed, transferred or taken abroad. */
    void requireNotBanned(Vehicle v) {
        List<Records.Ban> active = records.bans(v.id()).stream().filter(Records.Ban::active).toList();
        if (!active.isEmpty()) {
            throw DomainException.conflict("VEHICLE_BANNED", "the vehicle is banned from use ("
                    + active.get(0).reasonCode() + ")", List.of("VL-006"), active.get(0).reasonCode());
        }
    }

    /** VL-021: an active mortgage blocks transfer and de-registration unless the bank consented to {@code transfer}. */
    void requireNoMortgage(Vehicle v, UUID transfer) {
        for (Records.Mortgage m : records.activeMortgages(v.id())) {
            if (transfer == null || !transfer.equals(m.transferConsent())) {
                throw DomainException.conflict("MORTGAGE_ACTIVE", "the vehicle is mortgaged to " + m.bankCode()
                        + "; the bank must release it or consent to its transfer", List.of("VL-021", "VL-012"),
                        m.bankCode());
            }
        }
    }

    /** CC-032: holds in force on the subjects that block {@code serviceCode}; 422 with the customer's reasons. */
    void requireNoHolds(String serviceCode, List<Ports.Eligibility.Subject> subjects, String channel) {
        List<Ports.Eligibility.Block> blocks = eligibility.blocks(serviceCode, subjects, channel);
        if (!blocks.isEmpty()) {
            throw new EligibilityBlockedException(serviceCode, blocks);
        }
    }

    void requireNoOpenRequest(Vehicle v) {
        if (repo.openRequest(v.id()).isPresent()) {
            throw DomainException.conflict("REQUEST_OPEN", "another transaction of this vehicle waits for payment",
                    List.of("VL-010"));
        }
    }

    /** VL-024: a valid policy today, checked with the insurer now (fail closed). */
    void requireInsurance(Vehicle v, Ports.InsuranceRegister insurance, LocalDate today) {
        if (insurance.policies(v.vin(), today).stream().noneMatch(p -> p.validOn(today))) {
            throw DomainException.conflict("INSURANCE_EXPIRED",
                    "Your vehicle insurance has expired. Renew it with your insurer, then try again.",
                    List.of("VL-024", "UAT-3"));
        }
    }

    /** The next free standard number of the vehicle's region and plate category, reserved for it (VL-032). */
    Plates.Plate reserveNextPlate(Vehicle v, String plateCategory) {
        for (Plates.Series s : repo.activeSeries(v.region(), plateCategory)) {
            Optional<Long> next = repo.nextFreeNumber(s);
            if (next.isPresent()) {
                Instant now = clock.instant();
                Plates.Plate plate = new Plates.Plate(UUID.randomUUID(), s.code(), next.get(),
                        s.display(next.get()), Plates.Status.AVAILABLE, null, null, now)
                        .moveTo(Plates.Status.RESERVED, v.id(), v.ownerFileNumber(), now);
                repo.insertPlate(plate);
                return plate;
            }
        }
        throw DomainException.conflict("NO_PLATE_AVAILABLE", "no standard plate number is free for this category",
                List.of("VL-032"), plateCategory);
    }

    Plates.Plate reserveNextPlate(Vehicle v) {
        return reserveNextPlate(v, vehicleRules.plateCategoryOf(v.spec().category()));
    }

    /** Queues a physical plate for the next manufacturing order (VL-036). */
    void orderPlate(UUID plateId, String reason) {
        records.insertOrderItem(new Plates.OrderItem(UUID.randomUUID(), plateId, null, reason, null,
                clock.instant()));
    }

    /** Issues a registration card; earlier cards are cancelled (VL-014, VL-022). */
    Records.Card issueCard(Vehicle v, Records.Delivery delivery, String address) {
        Instant now = clock.instant();
        for (Records.Card c : records.cards(v.id())) {
            if (c.status() != Records.CardStatus.CANCELLED) {
                records.updateCard(c.moveTo(Records.CardStatus.CANCELLED, null, now));
            }
        }
        Records.Card card = new Records.Card(UUID.randomUUID(), v.id(), records.nextCardSerial(v.id()),
                Records.Card.first(delivery), delivery, address, now, now, null);
        records.insertCard(card);
        return card;
    }

    LocalDate retainedUntil(LocalDate from) {
        return from.plusMonths(rules.retentionMonths());
    }

    ServiceRules rules() {
        return rules;
    }

    VehicleRules vehicleRules() {
        return vehicleRules;
    }

    LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), clock.getZone());
    }
}
