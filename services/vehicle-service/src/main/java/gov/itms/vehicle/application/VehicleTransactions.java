package gov.itms.vehicle.application;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.platform.security.CurrentActor;
import gov.itms.vehicle.domain.DomainException;
import gov.itms.vehicle.domain.Plates;
import gov.itms.vehicle.domain.Records;
import gov.itms.vehicle.domain.Registration;
import gov.itms.vehicle.domain.Vehicle;
import gov.itms.vehicle.domain.Vin;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * The vehicle file's transactions after registration (Increment 2; ADR-059):
 * <ul>
 *   <li>customs/dealer certificates that pre-fill vehicles (VL-002); banned vehicles (VL-006);</li>
 *   <li>transfer of ownership with both parties' consent, fines, mortgage and buyer insurance (VL-012); change of
 *       details after an inspection (VL-013); card replacement (VL-014) and card print and delivery (VL-022);
 *       de-registration with plates returned or retained and an export certificate (VL-015);</li>
 *   <li>temporary entry and exit permits (VL-016); insurance recovery (VL-017), ownership, no-objection,
 *       registration history and clearance certificates (VL-020); public vehicle declarations (VL-018) and
 *       cash-transport security inspections (VL-019); mortgages registered and released by banks (VL-021);</li>
 *   <li>company fleets: list, bulk renewal, driver assignment (VL-026); dealers: bulk first registration and
 *       handover to buyers (VL-027).</li>
 * </ul>
 * Every paid transaction is checked completely before its fee is asked and completes on payment-service's
 * confirmation, like registration.
 */
public class VehicleTransactions implements PaidHandler {

    public static final String SERVICE = "vehicle.service";
    public static final String APPROVE = "vehicle.approve";
    public static final String BAN = "vehicle.ban.manage";
    public static final String MORTGAGE = "vehicle.mortgage.manage";
    public static final String CUSTOMS = "vehicle.customs.feed";
    public static final String CARD = "vehicle.card.manage";
    public static final String INSURANCE_RECOVERY = "vehicle.insurance.recovery";
    private static final int MAX_BULK = 200;

    private final Ports.VehicleRepository repo;
    private final Ports.RecordsRepository records;
    private final Ports.InsuranceRegister insurance;
    private final RequestDesk desk;
    private final VehicleService vehicles;
    private final AuditRecorder audit;
    private final DomainEventRecorder events;
    private final Clock clock;

    public VehicleTransactions(Ports.VehicleRepository repo, Ports.RecordsRepository records,
                               Ports.InsuranceRegister insurance, RequestDesk desk, VehicleService vehicles,
                               AuditRecorder audit, DomainEventRecorder events, Clock clock) {
        this.repo = repo;
        this.records = records;
        this.insurance = insurance;
        this.desk = desk;
        this.vehicles = vehicles;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    // ---- customs and dealer certificates (VL-002)

    @Transactional
    public Records.CustomsCertificate receiveCustoms(String certificateNo, Records.CustomsSource source,
                                                     String issuerCode, String vin, Vehicle.Spec spec,
                                                     LocalDate issuedOn, Caller caller) {
        VehicleService.channelOf(caller);
        require(caller.grants().holds(CUSTOMS));
        String checked = Vin.check(vin, desk.vehicleRules().checkDigitRegions());
        Records.CustomsCertificate c = new Records.CustomsCertificate(certificateNo, source, issuerCode, checked, spec,
                issuedOn, caller.actor().id(), clock.instant(), null);
        if (!records.insertCustoms(c)) {
            throw DomainException.conflict("CERTIFICATE_EXISTS", "this certificate was already received",
                    List.of("VL-002"));
        }
        audit.record("CUSTOMS_CERTIFICATE_RECEIVED", "CustomsCertificate", certificateNo, null,
                Map.of("source", source.name(), "issuer", issuerCode, "vin", checked), null);
        return c;
    }

    @Transactional
    public Records.CustomsCertificate customs(String certificateNo, Caller caller) {
        require(caller.grants().holds(VehicleService.READ) || caller.grants().holds(VehicleService.CREATE));
        Records.CustomsCertificate c = records.customs(certificateNo).orElseThrow(() -> notFound(
                "CERTIFICATE_NOT_FOUND", "VL-002"));
        audit.record("CUSTOMS_CERTIFICATE_VIEWED", "CustomsCertificate", certificateNo, null, null, null);
        return c;
    }

    // ---- bans (VL-006)

    @Transactional
    public Records.Ban ban(String vin, String reasonCode, String reference, Caller caller) {
        VehicleService.channelOf(caller);
        Vehicle v = find(vin);
        require(caller.grants().allowsInRegion(BAN, v.region()));
        Instant now = clock.instant();
        Records.Ban b = new Records.Ban(UUID.randomUUID(), v.id(), reasonCode, reference, caller.actor().id(), now,
                null, null, null);
        records.insertBan(b);
        Map<String, Object> detail = Map.of("banId", b.id().toString(), "reason", reasonCode, "reference", reference);
        repo.history(v.id(), "BANNED", caller.actor().id(), detail, now);
        audit.record("VEHICLE_BANNED", VehicleService.ENTITY, v.vin(), null, detail, null);
        events.record("itms.vehicle.banned", v.vin(), event(v, detail));
        return b;
    }

    @Transactional
    public Records.Ban liftBan(String vin, UUID banId, String reason, Caller caller) {
        VehicleService.channelOf(caller);
        Vehicle v = find(vin);
        require(caller.grants().allowsInRegion(BAN, v.region()));
        Records.Ban b = records.ban(banId).filter(x -> x.vehicleId().equals(v.id()))
                .orElseThrow(() -> notFound("BAN_NOT_FOUND", "VL-006"));
        Instant now = clock.instant();
        Records.Ban lifted = b.lifted(caller.actor().id(), reason, now);
        records.updateBan(lifted);
        Map<String, Object> detail = Map.of("banId", b.id().toString(), "reason", reason);
        repo.history(v.id(), "BAN_LIFTED", caller.actor().id(), detail, now);
        audit.record("VEHICLE_BAN_LIFTED", VehicleService.ENTITY, v.vin(), Map.of("active", true),
                Map.of("active", false), reason);
        events.record("itms.vehicle.ban-lifted", v.vin(), event(v, detail));
        return lifted;
    }

    // ---- approvals (VL-018, VL-019)

    @Transactional
    public Records.Approval submitApproval(String vin, Records.ApprovalKind kind, Map<String, String> detail,
                                           Caller caller) {
        VehicleService.channelOf(caller);
        Vehicle v = find(vin);
        require(caller.grants().allowsInRegion(SERVICE, v.region()));
        Instant now = clock.instant();
        Records.Approval a = new Records.Approval(UUID.randomUUID(), v.id(), kind, Records.ApprovalStatus.SUBMITTED,
                detail, caller.actor().id(), now, null, null, null);
        records.insertApproval(a);
        repo.history(v.id(), kind.name() + "_SUBMITTED", caller.actor().id(), Map.of("approvalId",
                a.id().toString()), now);
        audit.record("VEHICLE_APPROVAL_SUBMITTED", VehicleService.ENTITY, v.vin(), null,
                Map.of("approvalId", a.id().toString(), "kind", kind.name()), null);
        return a;
    }

    @Transactional
    public Records.Approval decideApproval(String vin, UUID approvalId, boolean approve, String note, Caller caller) {
        VehicleService.channelOf(caller);
        Vehicle v = find(vin);
        require(caller.grants().allowsInRegion(APPROVE, v.region()));
        Records.Approval a = records.approval(approvalId).filter(x -> x.vehicleId().equals(v.id()))
                .orElseThrow(() -> notFound("APPROVAL_NOT_FOUND", "VL-018"));
        Instant now = clock.instant();
        Records.Approval decided = a.decided(approve, caller.actor().id(), note, now);
        records.updateApproval(decided);
        repo.history(v.id(), a.kind().name() + "_" + decided.status().name(), caller.actor().id(),
                Map.of("approvalId", a.id().toString()), now);
        audit.record("VEHICLE_APPROVAL_DECIDED", VehicleService.ENTITY, v.vin(), Map.of("status", "SUBMITTED"),
                Map.of("approvalId", a.id().toString(), "status", decided.status().name()), note);
        return decided;
    }

    // ---- transfer of ownership (VL-012)

    /**
     * The seller's side: names the buyer, whether the buyer takes over unpaid fines and whether the seller keeps the
     * plate number. Waits for the buyer's consent; no fee yet.
     */
    @Transactional
    public Registration.Request startTransfer(String vin, String buyerFile, boolean buyerAcceptsFines,
                                              boolean sellerRetainsPlate, String customerCategory, Caller caller) {
        String channel = VehicleService.channelOf(caller);
        Vehicle v = locked(vin);
        require(caller.grants().allowsInRegion(SERVICE, v.region()));
        requireRegistered(v, "VL-012");
        desk.requireNotBanned(v);
        desk.requireNoOpenRequest(v);
        String buyer = checkedRef("buyerFileNumber", buyerFile);
        if (buyer.equals(v.ownerFileNumber())) {
            throw DomainException.validation("BUYER_IS_OWNER", "the buyer already owns the vehicle",
                    List.of("VL-012"));
        }
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("seller", v.ownerFileNumber());
        payload.put("buyer", buyer);
        payload.put("buyerAcceptsFines", Boolean.toString(buyerAcceptsFines));
        payload.put("sellerRetainsPlate", Boolean.toString(sellerRetainsPlate));
        payload.put("customerCategory", customerCategory);
        payload.put("sellerConsentBy", caller.actor().id());
        return desk.awaitConsent(spec(Registration.Kind.TRANSFER, v, buyer, customerCategory, null, payload, null),
                channel, caller);
    }

    /**
     * The buyer's consent. Now everything is checked before the fee: no ban, the mortgage released or the bank's
     * consent to this transfer, no holds (the seller's fines, unless the buyer accepts them), the buyer's insurance.
     */
    @Transactional
    public Registration.Request consentTransfer(String vin, UUID requestId, Caller caller) {
        String channel = VehicleService.channelOf(caller);
        Vehicle v = locked(vin);
        require(caller.grants().allowsInRegion(SERVICE, v.region()));
        Registration.Request r = repo.findRequestForUpdate(requestId)
                .filter(x -> v.id().equals(x.vehicleId()) && x.kind() == Registration.Kind.TRANSFER)
                .orElseThrow(() -> notFound("TRANSFER_NOT_FOUND", "VL-012"));
        if (r.status() != Registration.Status.AWAITING_CONSENT) {
            throw DomainException.conflict("TRANSFER_NOT_AWAITING_CONSENT", "the transfer is " + r.status(),
                    List.of("VL-012"), r.status().name());
        }
        desk.requireNotBanned(v);
        desk.requireNoMortgage(v, r.id());
        List<Ports.Eligibility.Subject> subjects = new ArrayList<>();
        subjects.add(new Ports.Eligibility.Subject("TRAFFIC_FILE", v.ownerFileNumber()));
        if (!Boolean.parseBoolean(r.value("buyerAcceptsFines"))) {
            subjects.add(new Ports.Eligibility.Subject("VEHICLE", v.vin()));
        }
        String fee = desk.rules().fee(Registration.Kind.TRANSFER);
        desk.requireNoHolds(fee, subjects, channel);
        desk.requireInsurance(v, insurance, desk.today());
        UUID newPlate = null;
        if (Boolean.parseBoolean(r.value("sellerRetainsPlate"))) {
            Vehicle forBuyer = Vehicle.restore(v.id(), v.vin(), v.spec(), v.createdAt(), r.value("buyer"),
                    v.region(), v.status(), v.registrationExpiry(), v.plateId(), v.plateNumber(), v.version());
            newPlate = desk.reserveNextPlate(forBuyer).id();
        }
        Map<String, String> payload = new LinkedHashMap<>(r.payload());
        payload.put("buyerConsentBy", caller.actor().id());
        Registration.Request priced = desk.priceConsented(r, spec(Registration.Kind.TRANSFER, v, r.value("buyer"),
                r.value("customerCategory"), newPlate, payload, null), caller);
        Map<String, Object> detail = new LinkedHashMap<>(payload);
        detail.put("requestId", r.id().toString());
        detail.put("amount", priced.amountMinor());
        repo.history(v.id(), "TRANSFER_CONSENTED", caller.actor().id(), detail, clock.instant());
        audit.record("VEHICLE_TRANSFER_CONSENTED", VehicleService.ENTITY, v.vin(), null, detail, null);
        return priced;
    }

    // ---- change of details (VL-013)

    /** Colour, engine, body or use type; needs a passed inspection report after the change. */
    @Transactional
    public Registration.Request startChange(String vin, List<String> colours, String engineNo, String bodyType,
                                            String category, String customerCategory, Caller caller) {
        String channel = VehicleService.channelOf(caller);
        Vehicle v = locked(vin);
        require(caller.grants().allowsInRegion(SERVICE, v.region()));
        if (v.status() == Vehicle.Status.DEREGISTERED) {
            throw DomainException.conflict("VEHICLE_DEREGISTERED", "the vehicle is de-registered", List.of("VL-013"));
        }
        desk.requireNotBanned(v);
        desk.requireNoOpenRequest(v);
        Vehicle.Spec s = v.spec();
        Vehicle.Spec changed = new Vehicle.Spec(engineNo == null ? s.engineNo() : engineNo, s.make(), s.model(),
                s.modelYear(), bodyType == null ? s.bodyType() : bodyType, colours == null ? s.colours() : colours,
                s.fuelType(), s.steering(), s.seats(), s.emptyWeightKg(), s.grossWeightKg(), s.axles(),
                s.cylinders(), category == null ? s.category() : category);
        if (changed.equals(s)) {
            throw DomainException.validation("NOTHING_CHANGED", "the request changes nothing", List.of("VL-013"));
        }
        LocalDate today = desk.today();
        Optional<Registration.Inspection> last = repo.latestInspection(v.id());
        if (last.isEmpty() || !last.get().passed() || last.get().inspectedOn()
                .plusDays(desk.vehicleRules().inspectionValidityDays()).isBefore(today)) {
            throw DomainException.conflict("INSPECTION_REQUIRED",
                    "a change of details needs a recent passed inspection report", List.of("VL-013"));
        }
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("engineNo", changed.engineNo());
        payload.put("bodyType", changed.bodyType());
        payload.put("colours", String.join(",", changed.colours()));
        payload.put("category", changed.category());
        payload.put("inspectionId", last.get().id().toString());
        return desk.open(spec(Registration.Kind.CHANGE, v, v.ownerFileNumber(), customerCategory, null, payload,
                null), channel, caller);
    }

    // ---- cards (VL-014, VL-022)

    @Transactional
    public Registration.Request startCardReplacement(String vin, String reason, Records.Delivery delivery,
                                                     String address, String customerCategory, Caller caller) {
        String channel = VehicleService.channelOf(caller);
        Vehicle v = locked(vin);
        require(caller.grants().allowsInRegion(SERVICE, v.region()));
        requireRegistered(v, "VL-014");
        desk.requireNoOpenRequest(v);
        if (!"LOST".equals(reason) && !"DAMAGED".equals(reason)) {
            throw DomainException.validation("FIELD_INVALID", "reason is LOST or DAMAGED", List.of("VL-014"),
                    "reason");
        }
        if (delivery == Records.Delivery.COURIER && (address == null || address.isBlank() || address.length() > 300)) {
            throw DomainException.validation("ADDRESS_REQUIRED", "courier delivery needs an address",
                    List.of("VL-022"));
        }
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("reason", reason);
        payload.put("delivery", delivery.name());
        if (address != null) {
            payload.put("address", address);
        }
        return desk.open(spec(Registration.Kind.CARD, v, v.ownerFileNumber(), customerCategory, null, payload, null),
                channel, caller);
    }

    @Transactional
    public List<Records.Card> cards(String vin, Caller caller) {
        Vehicle v = find(vin);
        require(caller.grants().allowsInRegion(VehicleService.READ, v.region())
                || caller.grants().allowsInRegion(CARD, v.region()));
        return records.cards(v.id());
    }

    /** The print centre or courier moves a card on: printed, dispatched, delivered or collected (VL-022). */
    @Transactional
    public Records.Card moveCard(String vin, UUID cardId, Records.CardStatus next, String trackingRef,
                                 Caller caller) {
        VehicleService.channelOf(caller);
        Vehicle v = find(vin);
        require(caller.grants().allowsInRegion(CARD, v.region()));
        Records.Card c = records.card(cardId).filter(x -> x.vehicleId().equals(v.id()))
                .orElseThrow(() -> notFound("CARD_NOT_FOUND", "VL-022"));
        if (trackingRef != null && !trackingRef.matches("^[A-Za-z0-9-]{1,60}$")) {
            throw DomainException.validation("FIELD_INVALID", "trackingRef is malformed", List.of("VL-022"),
                    "trackingRef");
        }
        Records.Card moved = c.moveTo(next, trackingRef, clock.instant());
        records.updateCard(moved);
        audit.record("VEHICLE_CARD_" + next.name(), VehicleService.ENTITY, v.vin(), Map.of("status",
                c.status().name()), Map.of("cardId", c.id().toString(), "status", next.name()), null);
        if (next == Records.CardStatus.DISPATCHED || next == Records.CardStatus.PRINTED) {
            events.record("itms.vehicle.card-" + next.name().toLowerCase(Locale.ROOT), v.vin(),
                    event(v, Map.of("cardId", c.id().toString(), "delivery", c.delivery().name())));
        }
        return moved;
    }

    // ---- de-registration (VL-015)

    @Transactional
    public Registration.Request startDeregistration(String vin, String reason, boolean retainPlate,
                                                    String destinationCountry, String customerCategory,
                                                    Caller caller) {
        String channel = VehicleService.channelOf(caller);
        Vehicle v = locked(vin);
        require(caller.grants().allowsInRegion(SERVICE, v.region()));
        if (v.status() == Vehicle.Status.DEREGISTERED) {
            throw DomainException.conflict("VEHICLE_DEREGISTERED", "the vehicle is already de-registered",
                    List.of("VL-015"));
        }
        if (!List.of("SCRAP", "EXPORT", "TOTAL_LOSS").contains(reason)) {
            throw DomainException.validation("FIELD_INVALID", "reason is SCRAP, EXPORT or TOTAL_LOSS",
                    List.of("VL-015"), "reason");
        }
        if ("EXPORT".equals(reason) && (destinationCountry == null || !destinationCountry.matches("^[A-Z]{3}$"))) {
            throw DomainException.validation("FIELD_INVALID", "an export names the destination country",
                    List.of("VL-015"), "destinationCountry");
        }
        desk.requireNoOpenRequest(v);
        desk.requireNoMortgage(v, null);
        desk.requireNoHolds(desk.rules().fee(Registration.Kind.DEREGISTER), List.of(
                new Ports.Eligibility.Subject("VEHICLE", v.vin()),
                new Ports.Eligibility.Subject("TRAFFIC_FILE", v.ownerFileNumber())), channel);
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("reason", reason);
        payload.put("retainPlate", Boolean.toString(retainPlate && v.plateId() != null));
        if (destinationCountry != null) {
            payload.put("destinationCountry", destinationCountry);
        }
        return desk.open(spec(Registration.Kind.DEREGISTER, v, v.ownerFileNumber(), customerCategory, null, payload,
                null), channel, caller);
    }

    // ---- certificates (VL-017, VL-020)

    @Transactional
    public Registration.Request requestCertificate(String vin, Records.CertificateKind kind,
                                                   String customerCategory, Caller caller) {
        String channel = VehicleService.channelOf(caller);
        Vehicle v = locked(vin);
        require(caller.grants().allowsInRegion(SERVICE, v.region()));
        if (kind == Records.CertificateKind.EXPORT || kind == Records.CertificateKind.INSURANCE_RECOVERY) {
            throw DomainException.validation("CERTIFICATE_KIND_INVALID", kind + " is issued by its own transaction",
                    List.of("VL-020"), kind.name());
        }
        desk.requireNoOpenRequest(v);
        if (kind == Records.CertificateKind.NO_OBJECTION || kind == Records.CertificateKind.CLEARANCE) {
            desk.requireNoHolds(desk.rules().fee(Registration.Kind.CERTIFICATE), List.of(
                    new Ports.Eligibility.Subject("VEHICLE", v.vin()),
                    new Ports.Eligibility.Subject("TRAFFIC_FILE", v.ownerFileNumber())), channel);
        }
        if (kind == Records.CertificateKind.CLEARANCE) {
            desk.requireNoMortgage(v, null);
        }
        return desk.open(spec(Registration.Kind.CERTIFICATE, v, v.ownerFileNumber(), customerCategory, null,
                Map.of("certificate", kind.name()), null), channel, caller);
    }

    /** An insurer's recovery certificate for a vehicle it recovered after total loss or theft (VL-017). */
    @Transactional
    public Records.Certificate insuranceRecovery(String vin, String insurerCode, String claimRef, String reason,
                                                 Caller caller) {
        VehicleService.channelOf(caller);
        Vehicle v = locked(vin);
        require(caller.grants().allowsInRegion(INSURANCE_RECOVERY, v.region()));
        if (!"TOTAL_LOSS".equals(reason) && !"THEFT".equals(reason)) {
            throw DomainException.validation("FIELD_INVALID", "reason is TOTAL_LOSS or THEFT", List.of("VL-017"),
                    "reason");
        }
        Map<String, Object> content = new LinkedHashMap<>(VehicleService.snapshot(v));
        content.put("insurer", checkedCode("insurerCode", insurerCode));
        content.put("claimRef", checkedRef("claimRef", claimRef));
        content.put("reason", reason);
        return issue(v, Records.CertificateKind.INSURANCE_RECOVERY, content, caller.actor().id(), null, clock.instant());
    }

    @Transactional
    public List<Records.Certificate> certificates(String vin, Caller caller) {
        Vehicle v = find(vin);
        require(caller.grants().allowsInRegion(VehicleService.READ, v.region()));
        audit.record("VEHICLE_CERTIFICATES_VIEWED", VehicleService.ENTITY, v.vin(), null, null, null);
        return records.certificates(v.id());
    }

    // ---- temporary entry and exit (VL-016)

    @Transactional
    @SuppressWarnings("checkstyle:ParameterNumber")
    public Registration.Request startEntryPermit(String vin, String foreignPlate, String country, String holderFile,
                                                 String borderPoint, LocalDate from, LocalDate to, String region,
                                                 String customerCategory, Caller caller) {
        String channel = VehicleService.channelOf(caller);
        require(caller.grants().allowsInRegion(SERVICE, region));
        String checked = vin == null ? "" : vin.toUpperCase(Locale.ROOT);
        if (!checked.matches("^[A-HJ-NPR-Z0-9]{17}$")) {
            throw DomainException.validation("VIN_INVALID", "the VIN is not 17 valid characters", List.of("VL-016"));
        }
        checkPermitDates(from, to);
        Records.TemporaryPermit p = new Records.TemporaryPermit(UUID.randomUUID(),
                records.nextPermitNumber(from.getYear()), Records.PermitKind.ENTRY,
                Records.PermitStatus.AWAITING_PAYMENT, null, checked, foreignPlate, country, holderFile, borderPoint,
                from, to, checkedCode("region", region), caller.actor().id(), clock.instant(), null);
        records.insertPermit(p);
        return desk.open(new RequestDesk.Spec(Registration.Kind.TEMP_PERMIT, null, region, holderFile,
                customerCategory, "FOREIGN", null, null, Map.of("permit", p.number(), "kind", "ENTRY"),
                p.id().toString()), channel, caller);
    }

    @Transactional
    public Registration.Request startExitPermit(String vin, String country, String borderPoint, LocalDate from,
                                                LocalDate to, String customerCategory, Caller caller) {
        String channel = VehicleService.channelOf(caller);
        Vehicle v = locked(vin);
        require(caller.grants().allowsInRegion(SERVICE, v.region()));
        requireRegistered(v, "VL-016");
        if (!v.registrationValid(to)) {
            throw DomainException.conflict("REGISTRATION_EXPIRES", "the registration must be valid until the vehicle"
                    + " returns", List.of("VL-016"));
        }
        desk.requireNotBanned(v);
        checkPermitDates(from, to);
        desk.requireNoHolds(desk.rules().fee(Registration.Kind.TEMP_PERMIT), List.of(
                new Ports.Eligibility.Subject("VEHICLE", v.vin()),
                new Ports.Eligibility.Subject("TRAFFIC_FILE", v.ownerFileNumber())), channel);
        Records.TemporaryPermit p = new Records.TemporaryPermit(UUID.randomUUID(),
                records.nextPermitNumber(from.getYear()), Records.PermitKind.EXIT,
                Records.PermitStatus.AWAITING_PAYMENT, v.id(), v.vin(), null, country, v.ownerFileNumber(),
                borderPoint, from, to, v.region(), caller.actor().id(), clock.instant(), null);
        records.insertPermit(p);
        return desk.open(new RequestDesk.Spec(Registration.Kind.TEMP_PERMIT, v, v.region(), v.ownerFileNumber(),
                customerCategory, v.spec().category(), null, null, Map.of("permit", p.number(), "kind", "EXIT"),
                p.id().toString()), channel, caller);
    }

    @Transactional
    public Records.TemporaryPermit permit(UUID id, Caller caller) {
        Records.TemporaryPermit p = records.permit(id).orElseThrow(() -> notFound("PERMIT_NOT_FOUND", "VL-016"));
        require(caller.grants().allowsInRegion(VehicleService.READ, p.region())
                || caller.grants().allowsInRegion(SERVICE, p.region()));
        return p;
    }

    /** The vehicle left (entry permit) or came back (exit permit). */
    @Transactional
    public Records.TemporaryPermit closePermit(UUID id, Caller caller) {
        VehicleService.channelOf(caller);
        Records.TemporaryPermit p = records.permit(id).orElseThrow(() -> notFound("PERMIT_NOT_FOUND", "VL-016"));
        require(caller.grants().allowsInRegion(SERVICE, p.region()));
        Records.TemporaryPermit closed = p.moveTo(Records.PermitStatus.CLOSED, clock.instant());
        records.updatePermit(closed);
        audit.record("TEMPORARY_PERMIT_CLOSED", "TemporaryPermit", p.number(), Map.of("status", p.status().name()),
                Map.of("status", "CLOSED"), null);
        return closed;
    }

    private void checkPermitDates(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from) || from.isBefore(desk.today())
                || ChronoUnit.DAYS.between(from, to) + 1 > desk.rules().permitMaxDays()) {
            throw DomainException.validation("PERMIT_DATES_INVALID", "a permit starts today or later and lasts at most "
                    + desk.rules().permitMaxDays() + " days", List.of("VL-016"));
        }
    }

    // ---- mortgages (VL-021)

    @Transactional
    public Records.Mortgage registerMortgage(String vin, String bankCode, String contractRef, Caller caller) {
        VehicleService.channelOf(caller);
        Vehicle v = locked(vin);
        require(caller.grants().allowsInRegion(MORTGAGE, v.region()));
        if (v.status() == Vehicle.Status.DEREGISTERED) {
            throw DomainException.conflict("VEHICLE_DEREGISTERED", "the vehicle is de-registered", List.of("VL-021"));
        }
        Instant now = clock.instant();
        Records.Mortgage m = new Records.Mortgage(UUID.randomUUID(), v.id(), bankCode, contractRef,
                v.ownerFileNumber(), Records.MortgageStatus.ACTIVE, null, caller.actor().id(), now, null, null);
        records.insertMortgage(m);
        Map<String, Object> detail = Map.of("mortgageId", m.id().toString(), "bank", bankCode, "contract",
                contractRef);
        repo.history(v.id(), "MORTGAGE_REGISTERED", caller.actor().id(), detail, now);
        audit.record("VEHICLE_MORTGAGE_REGISTERED", VehicleService.ENTITY, v.vin(), null, detail, null);
        events.record("itms.vehicle.mortgage-registered", v.vin(), event(v, detail));
        return m;
    }

    @Transactional
    public Records.Mortgage releaseMortgage(String vin, UUID mortgageId, Caller caller) {
        VehicleService.channelOf(caller);
        Vehicle v = locked(vin);
        require(caller.grants().allowsInRegion(MORTGAGE, v.region()));
        Records.Mortgage m = mortgageOf(v, mortgageId);
        Instant now = clock.instant();
        Records.Mortgage released = m.released(caller.actor().id(), now);
        records.updateMortgage(released);
        Map<String, Object> detail = Map.of("mortgageId", m.id().toString(), "bank", m.bankCode());
        repo.history(v.id(), "MORTGAGE_RELEASED", caller.actor().id(), detail, now);
        audit.record("VEHICLE_MORTGAGE_RELEASED", VehicleService.ENTITY, v.vin(), Map.of("status", "ACTIVE"),
                Map.of("status", "RELEASED", "mortgageId", m.id().toString()), null);
        events.record("itms.vehicle.mortgage-released", v.vin(), event(v, detail));
        return released;
    }

    /** The bank consents to its mortgage moving to the buyer with this transfer (VL-012, VL-021). */
    @Transactional
    public Records.Mortgage consentMortgageTransfer(String vin, UUID mortgageId, UUID transferId, Caller caller) {
        VehicleService.channelOf(caller);
        Vehicle v = locked(vin);
        require(caller.grants().allowsInRegion(MORTGAGE, v.region()));
        Records.Mortgage m = mortgageOf(v, mortgageId);
        Records.Mortgage consented = m.consentTo(Objects2.require(transferId));
        records.updateMortgage(consented);
        audit.record("VEHICLE_MORTGAGE_TRANSFER_CONSENTED", VehicleService.ENTITY, v.vin(), null,
                Map.of("mortgageId", m.id().toString(), "transferId", transferId.toString()), null);
        return consented;
    }

    private Records.Mortgage mortgageOf(Vehicle v, UUID id) {
        return records.mortgage(id).filter(x -> x.vehicleId().equals(v.id()))
                .orElseThrow(() -> notFound("MORTGAGE_NOT_FOUND", "VL-021"));
    }

    // ---- the vehicle file's records (VL-005)

    public record FileRecords(List<Records.Ban> bans, List<Records.Approval> approvals,
                              List<Records.Mortgage> mortgages, List<Records.Card> cards,
                              List<Records.Certificate> certificates) {
        public FileRecords {
            bans = bans == null ? null : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(bans));
            approvals = approvals == null ? null : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(approvals));
            mortgages = mortgages == null ? null : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(mortgages));
            cards = cards == null ? null : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(cards));
            certificates = certificates == null ? null : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(certificates));
        }
    }

    @Transactional
    public FileRecords records(String vin, Caller caller) {
        Vehicle v = find(vin);
        require(caller.grants().allowsInRegion(VehicleService.READ, v.region()));
        audit.record("VEHICLE_RECORDS_VIEWED", VehicleService.ENTITY, v.vin(), null, null, null);
        return new FileRecords(records.bans(v.id()), records.approvals(v.id()), records.activeMortgages(v.id()),
                records.cards(v.id()), records.certificates(v.id()));
    }

    // ---- fleets (VL-026) and dealers (VL-027)

    @Transactional
    public List<Vehicle> fleet(String companyFile, Caller caller) {
        checkedRef("fileNumber", companyFile);
        require(caller.grants().holds(VehicleService.READ));
        List<Vehicle> all = records.byOwner(companyFile, 5_000).stream()
                .filter(v -> caller.grants().allowsInRegion(VehicleService.READ, v.region())).toList();
        audit.record("FLEET_LISTED", "Fleet", companyFile, null, Map.of("vehicles", all.size()), null);
        return all;
    }

    /** One line of a bulk run: the request made, or why this vehicle was refused. */
    public record BulkOutcome(String vin, UUID requestId, long amountMinor, String refusedCode, String refusedReason) {
    }

    /** Renews each listed vehicle of the company on its own; one refusal does not stop the others (VL-026). */
    public List<BulkOutcome> bulkRenewal(String companyFile, List<String> vins, String customerCategory,
                                         Caller caller) {
        checkBulk(vins);
        List<BulkOutcome> out = new ArrayList<>();
        for (String vin : vins) {
            out.add(attempt(vin, () -> {
                Vehicle v = find(vin);
                if (!v.ownerFileNumber().equals(companyFile)) {
                    throw DomainException.conflict("NOT_IN_FLEET", "the vehicle is not the company's",
                            List.of("VL-026"));
                }
                return vehicles.startRenewal(vin, customerCategory, caller);
            }));
        }
        return out;
    }

    /** Starts first registration of each listed new vehicle on its own (VL-027). */
    public List<BulkOutcome> bulkFirstRegistration(String dealerFile, List<String> vins, String customerCategory,
                                                   Caller caller) {
        checkedRef("dealerFileNumber", dealerFile);
        checkBulk(vins);
        List<BulkOutcome> out = new ArrayList<>();
        for (String vin : vins) {
            out.add(attempt(vin, () -> vehicles.startFirstRegistration(vin, customerCategory, null, null, null,
                    caller)));
        }
        return out;
    }

    /** The dealer hands a new, unregistered vehicle to its buyer (VL-027). */
    @Transactional
    public Vehicle handover(String dealerFile, String vin, String buyerFile, Caller caller) {
        VehicleService.channelOf(caller);
        Vehicle v = locked(vin);
        require(caller.grants().allowsInRegion(SERVICE, v.region()));
        if (!v.ownerFileNumber().equals(dealerFile)) {
            throw DomainException.conflict("NOT_DEALERS", "the vehicle is not the dealer's", List.of("VL-027"));
        }
        desk.requireNoOpenRequest(v);
        v.handedOver(buyerFile);
        repo.updateVehicle(v);
        Map<String, Object> detail = Map.of("dealer", dealerFile, "buyer", buyerFile);
        repo.history(v.id(), "HANDED_OVER", caller.actor().id(), detail, clock.instant());
        audit.record("VEHICLE_HANDED_OVER", VehicleService.ENTITY, v.vin(), Map.of("owner", dealerFile),
                Map.of("owner", buyerFile), null);
        events.record("itms.vehicle.handed-over", v.vin(), event(v, detail));
        return v;
    }

    @Transactional
    public Records.DriverAssignment assignDriver(String companyFile, String vin, String driverFile, LocalDate from,
                                                 LocalDate to, Caller caller) {
        VehicleService.channelOf(caller);
        Vehicle v = locked(vin);
        require(caller.grants().allowsInRegion(SERVICE, v.region()));
        if (!v.ownerFileNumber().equals(companyFile)) {
            throw DomainException.conflict("NOT_IN_FLEET", "the vehicle is not the company's", List.of("VL-026"));
        }
        Instant now = clock.instant();
        records.endAssignment(v.id(), caller.actor().id(), now);
        Records.DriverAssignment a = new Records.DriverAssignment(UUID.randomUUID(), v.id(), companyFile, driverFile,
                from == null ? desk.today() : from, to, caller.actor().id(), now, null, null);
        records.insertAssignment(a);
        repo.history(v.id(), "DRIVER_ASSIGNED", caller.actor().id(), Map.of("driver", driverFile), now);
        audit.record("FLEET_DRIVER_ASSIGNED", VehicleService.ENTITY, v.vin(), null,
                Map.of("company", companyFile, "driver", driverFile), null);
        return a;
    }

    @Transactional
    public List<Records.DriverAssignment> assignments(String companyFile, Caller caller) {
        require(caller.grants().holds(VehicleService.READ));
        return records.assignments(checkedRef("fileNumber", companyFile));
    }

    private BulkOutcome attempt(String vin, java.util.function.Supplier<Registration.Request> start) {
        try {
            Registration.Request r = start.get();
            return new BulkOutcome(vin, r.id(), r.amountMinor(), null, null);
        } catch (DomainException e) {
            return new BulkOutcome(vin, null, 0, e.code(), e.getMessage());
        } catch (Ports.UnavailableException e) {
            return new BulkOutcome(vin, null, 0, e.code(), e.getMessage());
        }
    }

    private static void checkBulk(List<String> vins) {
        if (vins == null || vins.isEmpty() || vins.size() > MAX_BULK) {
            throw DomainException.validation("BULK_SIZE", "a bulk run takes 1 to " + MAX_BULK + " vehicles",
                    List.of("VL-026", "VL-027"));
        }
    }

    // ---- completion on payment

    @Override
    public boolean handles(Registration.Kind kind) {
        return switch (kind) {
            case TRANSFER, CHANGE, CARD, DEREGISTER, CERTIFICATE, TEMP_PERMIT -> true;
            default -> false;
        };
    }

    @Override
    public void completed(Registration.Request r, Instant now, CurrentActor system) {
        if (r.kind() == Registration.Kind.TEMP_PERMIT) {
            Records.TemporaryPermit p = records.permit(UUID.fromString(r.subjectRef())).orElseThrow();
            records.updatePermit(p.moveTo(Records.PermitStatus.ACTIVE, now));
            Map<String, Object> detail = Map.of("permit", p.number(), "kind", p.kind().name(), "vin", p.vin(),
                    "validTo", p.validTo().toString());
            audit.recordAs(system, "TEMPORARY_PERMIT_ISSUED", "TemporaryPermit", p.number(), null, detail, null);
            events.recordAs(system, "itms.vehicle.temporary-permit-issued", p.vin(), detail);
            return;
        }
        Vehicle v = repo.findForUpdate(r.vehicleId()).orElseThrow();
        Map<String, Object> detail = new LinkedHashMap<>(r.payload());
        detail.put("requestId", r.id().toString());
        String event = switch (r.kind()) {
            case TRANSFER -> transferred(v, r, now);
            case CHANGE -> {
                Vehicle.Spec s = v.spec();
                v.changed(new Vehicle.Spec(r.value("engineNo"), s.make(), s.model(), s.modelYear(),
                        r.value("bodyType"), List.of(r.value("colours").split(",")), s.fuelType(), s.steering(),
                        s.seats(), s.emptyWeightKg(), s.grossWeightKg(), s.axles(), s.cylinders(),
                        r.value("category")));
                repo.updateVehicle(v);
                desk.issueCard(v, Records.Delivery.CENTRE, null);
                yield "CHANGED";
            }
            case CARD -> {
                desk.issueCard(v, Records.Delivery.valueOf(r.value("delivery")), r.value("address"));
                yield "CARD_REPLACED";
            }
            case DEREGISTER -> deregistered(v, r, now, system);
            case CERTIFICATE -> {
                Records.CertificateKind kind = Records.CertificateKind.valueOf(r.value("certificate"));
                Records.Certificate c = issue(v, kind, content(v, kind), system.userId(), system, now);
                detail.put("certificate", c.number());
                yield "CERTIFICATE_ISSUED";
            }
            default -> throw new IllegalStateException("not a vehicle transaction: " + r.kind());
        };
        detail.putAll(VehicleService.snapshot(v));
        repo.history(v.id(), event, system.userId(), detail, now);
        audit.recordAs(system, "VEHICLE_" + event, VehicleService.ENTITY, v.vin(), null, detail, null);
        events.recordAs(system, "itms.vehicle." + event.toLowerCase(Locale.ROOT).replace('_', '-'), v.vin(), detail);
    }

    private String transferred(Vehicle v, Registration.Request r, Instant now) {
        String seller = r.value("seller");
        String buyer = r.value("buyer");
        LocalDate today = LocalDate.ofInstant(now, clock.getZone());
        UUID newPlate = null;
        String newDisplay = null;
        if (r.plateId() != null) {
            Plates.Plate old = repo.findPlate(v.plateId()).orElseThrow();
            repo.updatePlate(old.moveTo(Plates.Status.RETAINED, null, seller, now, desk.retainedUntil(today)));
            Plates.Plate fresh = repo.findPlate(r.plateId()).orElseThrow()
                    .moveTo(Plates.Status.ASSIGNED, v.id(), buyer, now);
            repo.updatePlate(fresh);
            desk.orderPlate(fresh.id(), "TRANSFER");
            newPlate = fresh.id();
            newDisplay = fresh.display();
        } else if (v.plateId() != null) {
            Plates.Plate same = repo.findPlate(v.plateId()).orElseThrow();
            repo.updatePlate(new Plates.Plate(same.id(), same.seriesCode(), same.number(), same.display(),
                    same.status(), same.vehicleId(), buyer, now, same.holdUntil(), same.serialNo()));
        }
        v.transferred(buyer, newPlate, newDisplay);
        repo.updateVehicle(v);
        for (Records.Mortgage m : records.activeMortgages(v.id())) {
            if (r.id().equals(m.transferConsent())) {
                records.updateMortgage(m.movedTo(buyer));
            }
        }
        records.endAssignment(v.id(), "SYSTEM", now);
        desk.issueCard(v, Records.Delivery.CENTRE, null);
        return "TRANSFERRED";
    }

    private String deregistered(Vehicle v, Registration.Request r, Instant now, CurrentActor system) {
        LocalDate today = LocalDate.ofInstant(now, clock.getZone());
        if (v.plateId() != null) {
            Plates.Plate p = repo.findPlate(v.plateId()).orElseThrow();
            repo.updatePlate(Boolean.parseBoolean(r.value("retainPlate"))
                    ? p.moveTo(Plates.Status.RETAINED, null, v.ownerFileNumber(), now, desk.retainedUntil(today))
                    : p.moveTo(Plates.Status.RETURNED, null, null, now));
        }
        for (Records.Card c : records.cards(v.id())) {
            if (c.status() != Records.CardStatus.CANCELLED) {
                records.updateCard(c.moveTo(Records.CardStatus.CANCELLED, null, now));
            }
        }
        if ("EXPORT".equals(r.value("reason"))) {
            Map<String, Object> content = new LinkedHashMap<>(VehicleService.snapshot(v));
            content.put("destinationCountry", r.value("destinationCountry"));
            issue(v, Records.CertificateKind.EXPORT, content, system.userId(), system, now);
        }
        v.deregistered();
        repo.updateVehicle(v);
        return "DEREGISTERED";
    }

    private Map<String, Object> content(Vehicle v, Records.CertificateKind kind) {
        Map<String, Object> content = new LinkedHashMap<>(VehicleService.snapshot(v));
        content.put("make", v.spec().make());
        content.put("model", v.spec().model());
        content.put("modelYear", v.spec().modelYear());
        if (kind == Records.CertificateKind.REGISTRATION_HISTORY) {
            content.put("events", repo.history(v.id()).stream()
                    .map(e -> e.at().toString() + " " + e.event()).toList());
        }
        if (kind == Records.CertificateKind.CLEARANCE || kind == Records.CertificateKind.NO_OBJECTION) {
            content.put("mortgages", records.activeMortgages(v.id()).size());
        }
        return content;
    }

    /** {@code as} is the system actor when a payment completes the request; null for a caller's own request. */
    private Records.Certificate issue(Vehicle v, Records.CertificateKind kind, Map<String, Object> content,
                                      String by, CurrentActor as, Instant now) {
        Records.Certificate c = new Records.Certificate(UUID.randomUUID(),
                records.nextCertificateNumber(LocalDate.ofInstant(now, clock.getZone()).getYear()), v.id(), kind,
                content, by, now);
        records.insertCertificate(c);
        Map<String, Object> issued = Map.of("number", c.number(), "kind", kind.name());
        if (as == null) {
            audit.record("VEHICLE_CERTIFICATE_ISSUED", VehicleService.ENTITY, v.vin(), null, issued, null);
        } else {
            audit.recordAs(as, "VEHICLE_CERTIFICATE_ISSUED", VehicleService.ENTITY, v.vin(), null, issued, null);
        }
        return c;
    }

    // ---- helpers

    private RequestDesk.Spec spec(Registration.Kind kind, Vehicle v, String payer, String customerCategory,
                                  UUID plateId, Map<String, String> payload, LocalDate newExpiry) {
        return new RequestDesk.Spec(kind, v, v.region(), payer, customerCategory, v.spec().category(), plateId,
                newExpiry, payload, null);
    }

    private static Map<String, Object> event(Vehicle v, Map<String, Object> detail) {
        Map<String, Object> m = new LinkedHashMap<>(VehicleService.snapshot(v));
        m.putAll(detail);
        return m;
    }

    private static void requireRegistered(Vehicle v, String requirement) {
        if (v.status() != Vehicle.Status.REGISTERED) {
            throw DomainException.conflict("NOT_REGISTERED", "the vehicle is " + v.status(), List.of(requirement),
                    v.status().name());
        }
    }

    private Vehicle find(String vin) {
        String key = vin == null ? "" : vin.toUpperCase(Locale.ROOT);
        return repo.findByVin(key).orElseThrow(() -> notFound("VEHICLE_NOT_FOUND", "VL-001"));
    }

    private Vehicle locked(String vin) {
        return repo.findForUpdate(find(vin).id()).orElseThrow();
    }

    static String checkedRef(String field, String value) {
        if (value == null || !value.matches("^[A-Z0-9][A-Z0-9-]{0,59}$")) {
            throw DomainException.validation("FIELD_INVALID", field + " is missing or malformed", List.of("VL-001"),
                    field);
        }
        return value;
    }

    static String checkedCode(String field, String value) {
        if (value == null || !value.matches(Vehicle.CODE)) {
            throw DomainException.validation("FIELD_INVALID", field + " is missing or malformed", List.of("VL-001"),
                    field);
        }
        return value;
    }

    static DomainException notFound(String code, String requirement) {
        return new DomainException(DomainException.Kind.NOT_FOUND, code, "not found", List.of(requirement));
    }

    private static void require(boolean allowed) {
        if (!allowed) {
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "SCOPE_EXCEEDED",
                    "you may not do this with this vehicle", List.of("CC-001"));
        }
    }

    /** Null checks that read as validation errors. */
    private static final class Objects2 {
        static UUID require(UUID id) {
            if (id == null) {
                throw DomainException.validation("FIELD_INVALID", "transferId is required", List.of("VL-021"),
                        "transferId");
            }
            return id;
        }
    }
}
