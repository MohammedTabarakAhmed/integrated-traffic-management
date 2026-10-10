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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vehicles, registration and plates — the Increment 1 core (ADR-055).
 * <ul>
 *   <li>vehicle records keyed by a checked VIN, one active record per VIN (VL-001, VL-003, VL-004), with a history
 *       (VL-005);</li>
 *   <li>inspection results from inspection centres (VL-025, VL-040); inspection need by category and age
 *       (VL-041);</li>
 *   <li>first registration (VL-010) and renewal (VL-011): every precondition is checked <em>before</em> any fee is
 *       asked — the renewal window, a valid insurance policy checked in real time with the insurer (VL-024, UAT-3:
 *       stop before payment with the plain message), a recent passed inspection when needed; then the fee is quoted
 *       by fees-service and registered as a payable at payment-service; payment-service's confirmation completes the
 *       registration (new expiry by category and age, VL-023);</li>
 *   <li>plate series per region, plate category, colour and design (VL-030); the next free standard number, skipping
 *       premium numbers, is reserved at first registration and assigned on payment (VL-031, VL-032).</li>
 * </ul>
 */
public class VehicleService {

    public static final String CREATE = "vehicle.create";
    public static final String READ = "vehicle.read";
    public static final String REGISTER = "vehicle.register";
    public static final String INSPECTION_RECORD = "vehicle.inspection.record";
    public static final String PLATE_MANAGE = "vehicle.plate.manage";
    static final String ENTITY = "Vehicle";

    private final Ports.VehicleRepository repo;
    private final Ports.InsuranceRegister insurance;
    private final Ports.FeeQuotes fees;
    private final Ports.Payables payables;
    private final Ports.Eligibility eligibility;
    private final Ports.RecordsRepository records;
    private final RequestDesk desk;
    private final List<PaidHandler> handlers = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final VehicleRules rules;
    private final AuditRecorder audit;
    private final DomainEventRecorder events;
    private final Clock clock;

    public VehicleService(Ports.VehicleRepository repo, Ports.InsuranceRegister insurance, Ports.FeeQuotes fees,
                          Ports.Payables payables, Ports.Eligibility eligibility, Ports.RecordsRepository records,
                          RequestDesk desk, VehicleRules rules, AuditRecorder audit, DomainEventRecorder events,
                          Clock clock) {
        this.repo = repo;
        this.insurance = insurance;
        this.fees = fees;
        this.payables = payables;
        this.eligibility = eligibility;
        this.records = records;
        this.desk = desk;
        this.rules = rules;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    /** Increment 2 transactions complete through their handlers (ADR-059). */
    public void addHandler(PaidHandler handler) {
        handlers.add(handler);
    }

    // ---- vehicle records

    @Transactional
    public Vehicle create(String vin, Vehicle.Spec spec, String ownerFileNumber, String region, Caller caller) {
        return create(vin, spec, ownerFileNumber, region, null, caller);
    }

    /**
     * With {@code certificateNo}, the vehicle is pre-filled from the customs clearance or dealer certificate received
     * from the feed (VL-002): the VIN must match, the certificate is used once, and its data is taken when no
     * {@code spec} is given.
     */
    @Transactional
    public Vehicle create(String vin, Vehicle.Spec spec, String ownerFileNumber, String region, String certificateNo,
                          Caller caller) {
        channelOf(caller);
        require(caller.grants().allowsInRegion(CREATE, region));
        Records.CustomsCertificate certificate = null;
        String givenVin = vin;
        Vehicle.Spec givenSpec = spec;
        if (certificateNo != null) {
            certificate = records.customs(certificateNo).orElseThrow(() -> new DomainException(
                    DomainException.Kind.NOT_FOUND, "CERTIFICATE_NOT_FOUND", "certificate not found",
                    List.of("VL-002")));
            if (certificate.usedBy() != null) {
                throw DomainException.conflict("CERTIFICATE_USED", "the certificate was already used for a vehicle",
                        List.of("VL-002"));
            }
            if (givenVin == null) {
                givenVin = certificate.vin();
            }
            if (givenSpec == null) {
                givenSpec = certificate.spec();
            }
        }
        if (givenSpec == null) {
            throw DomainException.validation("FIELD_INVALID", "spec is required without a certificate",
                    List.of("VL-001", "VL-002"), "spec");
        }
        String checked = Vin.check(givenVin, rules.checkDigitRegions());
        if (certificate != null && !certificate.vin().equals(checked)) {
            throw DomainException.conflict("CERTIFICATE_VIN_MISMATCH", "the certificate is for another VIN",
                    List.of("VL-002"));
        }
        if (repo.activeVinExists(checked)) {
            throw DomainException.conflict("VIN_ACTIVE", "a vehicle with this VIN is already in the register",
                    List.of("VL-003"));
        }
        Instant now = clock.instant();
        Vehicle v = Vehicle.create(UUID.randomUUID(), checked, givenSpec, ownerFileNumber, region, now);
        if (certificate != null) {
            v.linkCertificate(certificate.certificateNo());
        }
        repo.insertVehicle(v);
        if (certificate != null) {
            records.customsUsed(certificate.certificateNo(), v.id());
        }
        repo.history(v.id(), "CREATED", caller.actor().id(), Map.of("owner", ownerFileNumber, "category",
                givenSpec.category(), "certificate", certificateNo == null ? "" : certificateNo), now);
        audit.record("VEHICLE_CREATED", ENTITY, checked, null, snapshot(v), null);
        events.record("itms.vehicle.created", checked, snapshot(v));
        return v;
    }

    @Transactional
    public Vehicle get(String vin, Caller caller) {
        Vehicle v = find(vin);
        require(caller.grants().allowsInRegion(READ, v.region()));
        audit.record("VEHICLE_VIEWED", ENTITY, v.vin(), null, null, null);
        return v;
    }

    @Transactional
    public List<Ports.VehicleRepository.HistoryEntry> history(String vin, Caller caller) {
        Vehicle v = find(vin);
        require(caller.grants().allowsInRegion(READ, v.region()));
        audit.record("VEHICLE_HISTORY_VIEWED", ENTITY, v.vin(), null, null, null);
        return repo.history(v.id());
    }

    // ---- inspections (VL-025, VL-040)

    @Transactional
    public Registration.Inspection recordInspection(String vin, String centreCode, LocalDate inspectedOn,
                                                    long odometerKm, Map<String, String> results, boolean passed,
                                                    List<String> photos, Caller caller) {
        channelOf(caller);
        Vehicle v = find(vin);
        require(caller.grants().allowsInRegion(INSPECTION_RECORD, v.region()));
        Instant now = clock.instant();
        if (inspectedOn.isAfter(LocalDate.ofInstant(now, clock.getZone()))) {
            throw DomainException.validation("INSPECTION_DATE_INVALID", "an inspection cannot be in the future",
                    List.of("VL-040"));
        }
        Registration.Inspection i = new Registration.Inspection(UUID.randomUUID(), v.id(), centreCode, inspectedOn,
                odometerKm, results, passed, photos, caller.actor().id(), now);
        repo.insertInspection(i);
        repo.history(v.id(), passed ? "INSPECTION_PASSED" : "INSPECTION_FAILED", caller.actor().id(),
                Map.of("centre", centreCode, "date", inspectedOn.toString(), "odometerKm", odometerKm), now);
        audit.record("VEHICLE_INSPECTION_RECORDED", ENTITY, v.vin(), null,
                Map.of("passed", passed, "centre", centreCode), null);
        Map<String, Object> event = new LinkedHashMap<>(snapshot(v));
        event.put("passed", passed);
        event.put("centre", centreCode);
        // VL-025: a failed result blocks renewal here, and the event lets hold-eligibility-service place a hold.
        events.record(passed ? "itms.vehicle.inspection-passed" : "itms.vehicle.inspection-failed", v.vin(), event);
        return i;
    }

    // ---- registration (VL-010, VL-011, VL-023, VL-024)

    /** First registration: checks, reserves the next plate number, quotes the fee and registers the payable. */
    @Transactional
    public Registration.Request startFirstRegistration(String vin, String customerCategory, Caller caller) {
        return startFirstRegistration(vin, customerCategory, null, null, null, caller);
    }

    /**
     * First registration with the owner's own retained number ({@code heldPlateId}, VL-033/VL-034/VL-035) instead of
     * the next standard one, and the card's delivery (VL-022). Besides insurance and inspection: no ban (VL-006), a
     * customs or dealer certificate (VL-010, VL-002), the approvals the category needs (VL-018, VL-019).
     */
    @Transactional
    public Registration.Request startFirstRegistration(String vin, String customerCategory, UUID heldPlateId,
                                                       Records.Delivery delivery, String address, Caller caller) {
        String channel = channelOf(caller);
        Vehicle v = lockedVehicle(vin);
        require(caller.grants().allowsInRegion(REGISTER, v.region()));
        if (v.status() != Vehicle.Status.UNREGISTERED) {
            throw DomainException.conflict("ALREADY_REGISTERED", "the vehicle is already " + v.status(),
                    List.of("VL-010"), v.status().name());
        }
        requireNoOpenRequest(v);
        desk.requireNotBanned(v);
        if (desk.rules().requireCustomsCertificate() && v.customsCertificateNo() == null) {
            throw DomainException.conflict("CUSTOMS_CERTIFICATE_REQUIRED",
                    "first registration needs the customs clearance or dealer certificate", List.of("VL-010",
                            "VL-002"));
        }
        for (Records.ApprovalKind needed : desk.rules().approvalsFor(v.spec().category(), v.spec().bodyType())) {
            boolean approved = records.approvals(v.id()).stream().anyMatch(a -> a.kind() == needed
                    && a.status() == Records.ApprovalStatus.APPROVED);
            if (!approved) {
                throw DomainException.conflict(needed.name() + "_REQUIRED", "the vehicle needs an approved "
                        + needed.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ') + " before registration",
                        List.of(needed == Records.ApprovalKind.PUBLIC_DECLARATION ? "VL-018" : "VL-019"),
                        needed.name());
            }
        }
        if (delivery == Records.Delivery.COURIER && (address == null || address.isBlank())) {
            throw DomainException.validation("ADDRESS_REQUIRED", "courier delivery needs an address",
                    List.of("VL-022"));
        }
        LocalDate today = today();
        checkInsurance(v, today);
        checkInspection(v, today);
        Plates.Plate plate = heldPlateId == null ? desk.reserveNextPlate(v) : heldPlate(v, heldPlateId);
        LocalDate expiry = today.plusMonths(rules.validityMonths(v.spec().category(), v.ageYears(today)))
                .minusDays(1);
        Map<String, String> card = new LinkedHashMap<>();
        card.put("delivery", (delivery == null ? Records.Delivery.CENTRE : delivery).name());
        if (address != null) {
            card.put("address", address);
        }
        return request(v, Registration.Kind.FIRST, rules.firstRegistrationFee(), customerCategory, null, expiry,
                plate.id(), channel, caller, card);
    }

    /** The owner's own number, held without a vehicle and of the vehicle's plate category (VL-035). */
    private Plates.Plate heldPlate(Vehicle v, UUID plateId) {
        Plates.Plate p = repo.findPlate(plateId).orElseThrow(() -> new DomainException(
                DomainException.Kind.NOT_FOUND, "PLATE_NOT_FOUND", "plate not found", List.of("VL-031")));
        Plates.Series s = repo.findSeries(p.seriesCode()).orElseThrow();
        if (p.status() != Plates.Status.RETAINED || p.vehicleId() != null
                || !v.ownerFileNumber().equals(p.holderFileNumber())
                || !s.plateCategory().equals(rules.plateCategoryOf(v.spec().category()))) {
            throw DomainException.conflict("PLATE_NOT_OWNERS", "the number must be held by the owner without a"
                    + " vehicle, and fit the vehicle's plate category", List.of("VL-035"));
        }
        return p;
    }

    /** Renewal: within the window, valid insurance (UAT-3), inspection when needed; then the fee. */
    @Transactional
    public Registration.Request startRenewal(String vin, String customerCategory, Caller caller) {
        String channel = channelOf(caller);
        Vehicle v = lockedVehicle(vin);
        require(caller.grants().allowsInRegion(REGISTER, v.region()));
        if (v.status() != Vehicle.Status.REGISTERED) {
            throw DomainException.conflict("NOT_REGISTERED", "the vehicle is " + v.status(), List.of("VL-011"),
                    v.status().name());
        }
        requireNoOpenRequest(v);
        desk.requireNotBanned(v);
        LocalDate today = today();
        LocalDate opens = v.registrationExpiry().minusDays(rules.renewalWindowDays());
        if (today.isBefore(opens)) {
            throw DomainException.conflict("RENEWAL_NOT_OPEN", "renewal opens on " + opens, List.of("VL-011"),
                    opens.toString());
        }
        checkInsurance(v, today);
        checkInspection(v, today);
        checkHolds(v, rules.renewalFee(), channel);
        LocalDate base = v.registrationExpiry().isBefore(today) ? today.minusDays(1) : v.registrationExpiry();
        LocalDate expiry = base.plusMonths(rules.validityMonths(v.spec().category(), v.ageYears(today)));
        return request(v, Registration.Kind.RENEWAL, rules.renewalFee(), customerCategory, v.registrationExpiry(),
                expiry, null, channel, caller, Map.of("delivery", Records.Delivery.CENTRE.name()));
    }

    /**
     * No hold in force may block the renewal for the vehicle or its owner's traffic file (VL-011, VF-023): otherwise
     * 422 {@code ELIGIBILITY_BLOCKED} with the customer's reasons and next steps, before any fee is quoted. When the
     * check cannot be made the renewal is refused with 503 {@code ELIGIBILITY_UNAVAILABLE} (fail closed).
     */
    private void checkHolds(Vehicle v, String serviceCode, String channel) {
        List<Ports.Eligibility.Block> blocks = eligibility.blocks(serviceCode, List.of(
                new Ports.Eligibility.Subject("VEHICLE", v.vin()),
                new Ports.Eligibility.Subject("TRAFFIC_FILE", v.ownerFileNumber())), channel);
        if (!blocks.isEmpty()) {
            throw new EligibilityBlockedException(serviceCode, blocks);
        }
    }

    private Registration.Request request(Vehicle v, Registration.Kind kind, String feeService, String customerCategory,
                                         LocalDate currentExpiry, LocalDate newExpiry, UUID plateId, String channel,
                                         Caller caller, Map<String, String> payload) {
        Ports.FeeQuotes.Quote quote = fees.quote(feeService, v.region(), customerCategory, v.spec().category(),
                currentExpiry);
        UUID id = UUID.randomUUID();
        UUID payableId = payables.register(itemRef(id), v.ownerFileNumber(), v.region(), quote.currency(),
                quote.lines());
        Instant now = clock.instant();
        Registration.Request r = new Registration.Request(id, v.id(), kind, Registration.Status.AWAITING_PAYMENT,
                quote.id(), payableId, quote.total(), quote.currency(), newExpiry, plateId, caller.actor().id(),
                channel, now, null, payload, null, v.ownerFileNumber());
        repo.insertRequest(r);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("requestId", id.toString());
        detail.put("kind", kind.name());
        detail.put("amount", quote.total());
        detail.put("newExpiry", newExpiry.toString());
        repo.history(v.id(), kind == Registration.Kind.FIRST ? "REGISTRATION_REQUESTED" : "RENEWAL_REQUESTED",
                caller.actor().id(), detail, now);
        audit.record(kind == Registration.Kind.FIRST ? "VEHICLE_REGISTRATION_REQUESTED"
                : "VEHICLE_RENEWAL_REQUESTED", ENTITY, v.vin(), null, detail, null);
        return r;
    }

    /**
     * Completes a registration whose payable was paid (from {@code itms.payment.confirmed}). Items that are not
     * this service's are ignored; a repeat is harmless (the request is no longer awaiting payment).
     */
    @Transactional
    public boolean onPaid(String itemRef) {
        UUID requestId = requestIdOf(itemRef);
        if (requestId == null) {
            return false;
        }
        Optional<Registration.Request> found = repo.findRequestForUpdate(requestId);
        if (found.isEmpty() || found.get().status() != Registration.Status.AWAITING_PAYMENT) {
            return false;
        }
        Registration.Request r = found.get();
        Instant now = clock.instant();
        CurrentActor system = CurrentActor.system("vehicle-payment-consumer");
        if (r.kind() != Registration.Kind.FIRST && r.kind() != Registration.Kind.RENEWAL) {
            PaidHandler handler = handlers.stream().filter(h -> h.handles(r.kind())).findFirst()
                    .orElseThrow(() -> new IllegalStateException("no handler for " + r.kind()));
            repo.updateRequest(r.completed(now));
            handler.completed(r, now, system);
            return true;
        }
        Vehicle v = repo.findForUpdate(r.vehicleId()).orElseThrow();
        if (r.kind() == Registration.Kind.FIRST) {
            Plates.Plate plate = repo.findPlate(r.plateId()).orElseThrow();
            Plates.Plate assigned = plate.moveTo(Plates.Status.ASSIGNED, v.id(), v.ownerFileNumber(), now);
            repo.updatePlate(assigned);
            v.registered(assigned.id(), assigned.display(), r.newExpiry());
            desk.orderPlate(assigned.id(), "REGISTRATION");
        } else {
            v.renewed(r.newExpiry());
        }
        repo.updateVehicle(v);
        repo.updateRequest(r.completed(now));
        String delivery = r.value("delivery");
        desk.issueCard(v, delivery == null ? Records.Delivery.CENTRE : Records.Delivery.valueOf(delivery),
                r.value("address"));
        Map<String, Object> detail = new LinkedHashMap<>(snapshot(v));
        detail.put("requestId", r.id().toString());
        repo.history(v.id(), r.kind() == Registration.Kind.FIRST ? "REGISTERED" : "RENEWED", system.userId(),
                detail, now);
        audit.recordAs(system, r.kind() == Registration.Kind.FIRST ? "VEHICLE_REGISTERED" : "VEHICLE_RENEWED",
                ENTITY, v.vin(), null, detail, null);
        events.recordAs(system, r.kind() == Registration.Kind.FIRST ? "itms.vehicle.registered"
                : "itms.vehicle.renewed", v.vin(), detail);
        return true;
    }

    // ---- plates (VL-030..VL-032)

    @Transactional
    public Plates.Series saveSeries(Plates.Series series, Caller caller) {
        channelOf(caller);
        require(caller.grants().allowsInRegion(PLATE_MANAGE, series.region()));
        repo.findSeries(series.code()).ifPresent(old -> {
            if (!old.region().equals(series.region()) || old.firstNumber() != series.firstNumber()
                    || !old.prefix().equals(series.prefix())) {
                throw DomainException.validation("SERIES_FIXED", "a series keeps its region, prefix and start",
                        List.of("VL-030"));
            }
        });
        repo.saveSeries(series);
        audit.record("PLATE_SERIES_SAVED", "PlateSeries", series.code(), null,
                Map.of("region", series.region(), "plateCategory", series.plateCategory(), "last",
                        series.lastNumber(), "active", series.active()), null);
        return series;
    }

    // ---- checks

    /** VL-024 / UAT-3: a valid policy today, checked with the insurer now; no answer blocks (fail closed). */
    private void checkInsurance(Vehicle v, LocalDate today) {
        List<Ports.InsuranceRegister.Policy> policies = insurance.policies(v.vin(), today);
        if (policies.stream().noneMatch(p -> p.validOn(today))) {
            throw DomainException.conflict("INSURANCE_EXPIRED",
                    "Your vehicle insurance has expired. Renew it with your insurer, then try again.",
                    List.of("VL-024", "UAT-3"));
        }
    }

    private void checkInspection(Vehicle v, LocalDate today) {
        if (!rules.inspectionNeeded(v.spec().category(), v.ageYears(today))) {
            return;
        }
        Optional<Registration.Inspection> last = repo.latestInspection(v.id());
        boolean recentPass = last.isPresent() && last.get().passed()
                && !last.get().inspectedOn().plusDays(rules.inspectionValidityDays()).isBefore(today);
        if (!recentPass) {
            throw DomainException.conflict(last.isPresent() && !last.get().passed() ? "INSPECTION_FAILED"
                    : "INSPECTION_REQUIRED", "the vehicle needs a passed technical inspection",
                    List.of("VL-010", "VL-011", "VL-041"));
        }
    }

    private void requireNoOpenRequest(Vehicle v) {
        if (repo.openRequest(v.id()).isPresent()) {
            throw DomainException.conflict("REQUEST_OPEN", "a registration request is waiting for payment",
                    List.of("VL-010"));
        }
    }

    // ---- helpers

    static String itemRef(UUID requestId) {
        return "VR-" + requestId.toString().toUpperCase(java.util.Locale.ROOT);
    }

    static UUID requestIdOf(String itemRef) {
        if (itemRef == null || !itemRef.startsWith("VR-")) {
            return null;
        }
        try {
            return UUID.fromString(itemRef.substring(3));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Vehicle find(String vin) {
        String v = vin == null ? "" : vin.toUpperCase(java.util.Locale.ROOT);
        return repo.findByVin(v).orElseThrow(() -> new DomainException(DomainException.Kind.NOT_FOUND,
                "VEHICLE_NOT_FOUND", "vehicle not found", List.of("VL-001")));
    }

    private Vehicle lockedVehicle(String vin) {
        Vehicle v = find(vin);
        return repo.findForUpdate(v.id()).orElseThrow();
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), clock.getZone());
    }

    static Map<String, Object> snapshot(Vehicle v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("vin", v.vin());
        m.put("ownerFileNumber", v.ownerFileNumber());
        m.put("region", v.region());
        m.put("category", v.spec().category());
        m.put("status", v.status().name());
        m.put("registrationExpiry", v.registrationExpiry() == null ? null : v.registrationExpiry().toString());
        m.put("plate", v.plateNumber());
        return m;
    }

    private static void require(boolean allowed) {
        if (!allowed) {
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "SCOPE_EXCEEDED",
                    "you may not do this with this vehicle", List.of("CC-001"));
        }
    }

    static String channelOf(Caller caller) {
        if (caller.channel() == null) {
            throw DomainException.validation("CHANNEL_REQUIRED", "the request does not name its channel (X-Channel)",
                    List.of("CC-120"));
        }
        return caller.channel().name();
    }
}
