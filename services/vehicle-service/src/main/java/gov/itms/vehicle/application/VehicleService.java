package gov.itms.vehicle.application;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.platform.security.CurrentActor;
import gov.itms.vehicle.domain.DomainException;
import gov.itms.vehicle.domain.Plates;
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
    private final VehicleRules rules;
    private final AuditRecorder audit;
    private final DomainEventRecorder events;
    private final Clock clock;

    public VehicleService(Ports.VehicleRepository repo, Ports.InsuranceRegister insurance, Ports.FeeQuotes fees,
                          Ports.Payables payables, Ports.Eligibility eligibility, VehicleRules rules,
                          AuditRecorder audit, DomainEventRecorder events, Clock clock) {
        this.repo = repo;
        this.insurance = insurance;
        this.fees = fees;
        this.payables = payables;
        this.eligibility = eligibility;
        this.rules = rules;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    // ---- vehicle records

    @Transactional
    public Vehicle create(String vin, Vehicle.Spec spec, String ownerFileNumber, String region, Caller caller) {
        channelOf(caller);
        require(caller.grants().allowsInRegion(CREATE, region));
        String checked = Vin.check(vin, rules.checkDigitRegions());
        if (repo.activeVinExists(checked)) {
            throw DomainException.conflict("VIN_ACTIVE", "a vehicle with this VIN is already in the register",
                    List.of("VL-003"));
        }
        Instant now = clock.instant();
        Vehicle v = Vehicle.create(UUID.randomUUID(), checked, spec, ownerFileNumber, region, now);
        repo.insertVehicle(v);
        repo.history(v.id(), "CREATED", caller.actor().id(), Map.of("owner", ownerFileNumber, "category",
                spec.category()), now);
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
        String channel = channelOf(caller);
        Vehicle v = lockedVehicle(vin);
        require(caller.grants().allowsInRegion(REGISTER, v.region()));
        if (v.status() != Vehicle.Status.UNREGISTERED) {
            throw DomainException.conflict("ALREADY_REGISTERED", "the vehicle is already " + v.status(),
                    List.of("VL-010"), v.status().name());
        }
        requireNoOpenRequest(v);
        LocalDate today = today();
        checkInsurance(v, today);
        checkInspection(v, today);
        Plates.Plate plate = reservePlate(v);
        LocalDate expiry = today.plusMonths(rules.validityMonths(v.spec().category(), v.ageYears(today)))
                .minusDays(1);
        return request(v, Registration.Kind.FIRST, rules.firstRegistrationFee(), customerCategory, null, expiry,
                plate.id(), channel, caller);
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
                expiry, null, channel, caller);
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
                                         Caller caller) {
        Ports.FeeQuotes.Quote quote = fees.quote(feeService, v.region(), customerCategory, v.spec().category(),
                currentExpiry);
        UUID id = UUID.randomUUID();
        UUID payableId = payables.register(itemRef(id), v.ownerFileNumber(), v.region(), quote.currency(),
                quote.lines());
        Instant now = clock.instant();
        Registration.Request r = new Registration.Request(id, v.id(), kind, Registration.Status.AWAITING_PAYMENT,
                quote.id(), payableId, quote.total(), quote.currency(), newExpiry, plateId, caller.actor().id(),
                channel, now, null);
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
        Vehicle v = repo.findForUpdate(r.vehicleId()).orElseThrow();
        if (r.kind() == Registration.Kind.FIRST) {
            Plates.Plate plate = repo.findPlate(r.plateId()).orElseThrow();
            Plates.Plate assigned = plate.moveTo(Plates.Status.ASSIGNED, v.id(), v.ownerFileNumber(), now);
            repo.updatePlate(assigned);
            v.registered(assigned.id(), assigned.display(), r.newExpiry());
        } else {
            v.renewed(r.newExpiry());
        }
        repo.updateVehicle(v);
        repo.updateRequest(r.completed(now));
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

    private Plates.Plate reservePlate(Vehicle v) {
        String plateCategory = rules.plateCategoryOf(v.spec().category());
        for (Plates.Series s : repo.activeSeries(v.region(), plateCategory)) {
            Optional<Long> next = repo.nextFreeNumber(s);
            if (next.isPresent()) {
                Plates.Plate plate = new Plates.Plate(UUID.randomUUID(), s.code(), next.get(),
                        s.display(next.get()), Plates.Status.AVAILABLE, null, null, clock.instant())
                        .moveTo(Plates.Status.RESERVED, v.id(), v.ownerFileNumber(), clock.instant());
                repo.insertPlate(plate);
                return plate;
            }
        }
        throw DomainException.conflict("NO_PLATE_AVAILABLE", "no standard plate number is free for this category",
                List.of("VL-032"), plateCategory);
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
