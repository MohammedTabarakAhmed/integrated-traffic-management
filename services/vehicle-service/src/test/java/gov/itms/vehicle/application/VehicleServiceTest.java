package gov.itms.vehicle.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.platform.security.CurrentActor;
import gov.itms.vehicle.domain.Actor;
import gov.itms.vehicle.domain.Channel;
import gov.itms.vehicle.domain.DomainException;
import gov.itms.vehicle.domain.Plates;
import gov.itms.vehicle.domain.Registration;
import gov.itms.vehicle.domain.Vehicle;
import gov.itms.vehicle.domain.VehicleTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("VL-010")
class VehicleServiceTest {

    private static final String VIN = "1HGCM82633A004352";

    private final MutableClock clock = new MutableClock(VehicleTest.NOW);
    private final Repo repo = new Repo();
    private final List<String> audit = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final List<String> payablesRegistered = new ArrayList<>();
    private final List<String> quotes = new ArrayList<>();
    private final Map<String, LocalDate> policyEnds = new HashMap<>();
    private boolean insurerDown;
    private final Map<String, Ports.Eligibility.Block> heldSubjects = new HashMap<>();
    private final List<String> eligibilityChecks = new ArrayList<>();

    private final VehicleService service = new VehicleService(repo, (vin, asOf) -> {
        if (insurerDown) {
            throw new Ports.UnavailableException("INSURANCE_UNAVAILABLE", "down", null);
        }
        LocalDate end = policyEnds.get(vin);
        return end == null ? List.of() : List.of(new Ports.InsuranceRegister.Policy("POL-1", "INS1", "COMPREHENSIVE",
                end.minusYears(1), end, "ACTIVE"));
    }, (serviceCode, region, customerCategory, subjectCategory, expiryDate) -> {
        quotes.add(serviceCode + " " + subjectCategory + " " + expiryDate);
        return new Ports.FeeQuotes.Quote("Q-1", List.of(new Ports.FeeQuotes.Line("REG_FEE", "AUTHORITY", "fee",
                40000)), 40000, "XTS");
    }, (itemRef, file, region, currency, lines) -> {
        payablesRegistered.add(itemRef);
        return UUID.randomUUID();
    }, (serviceCode, subjects, channel) -> {
        eligibilityChecks.add(serviceCode + " " + subjects.stream().map(s -> s.type() + ":" + s.id()).toList());
        return subjects.stream().map(s -> heldSubjects.get(s.id())).filter(Objects::nonNull).toList();
    }, VehicleRulesTest.rules(), new AuditRecorder() {
        @Override
        public void record(String action, String entityType, String entityId, Object oldValue, Object newValue,
                           String reason) {
            audit.add(action + " " + entityId);
        }

        @Override
        public void recordAs(CurrentActor actor, String action, String entityType, String entityId, Object oldValue,
                             Object newValue, String reason) {
            record(action, entityType, entityId, oldValue, newValue, reason);
        }
    }, new DomainEventRecorder() {
        @Override
        public void record(String eventType, String subject, Object data) {
            events.add(eventType + " " + subject);
        }

        @Override
        public void recordAs(CurrentActor actor, String eventType, String subject, Object data) {
            record(eventType, subject, data);
        }
    }, clock);

    private static Caller caller(String id, Set<String> regions, String... permissions) {
        Set<String> held = Set.of(permissions);
        return new Caller(new Actor(id, id), Channel.BACKOFFICE, new Caller.Grants() {
            @Override
            public boolean allowsInRegion(String permission, String region) {
                return held.contains(permission) && regions.contains(region);
            }

            @Override
            public boolean holds(String permission) {
                return held.contains(permission);
            }
        });
    }

    private static final Caller OFFICER = caller("u-officer", Set.of("NORTH"), VehicleService.CREATE,
            VehicleService.READ, VehicleService.REGISTER, VehicleService.INSPECTION_RECORD,
            VehicleService.PLATE_MANAGE);

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    @BeforeEach
    void series() {
        service.saveSeries(new Plates.Series("NORTH_PRIVATE", "NORTH", "PRIVATE", "WHITE", "STANDARD", "A", 1, 3,
                "^1$", true), OFFICER);
        service.saveSeries(new Plates.Series("NORTH_TRADE", "NORTH", "TRADE", "RED", "STANDARD", "T", 1, 99, null,
                true), OFFICER);
    }

    private Vehicle registered(String vin, int modelYear) {
        service.create(vin, VehicleTest.spec("PRIVATE", modelYear), "TF-1", "NORTH", OFFICER);
        policyEnds.put(vin, today().plusMonths(6));
        Registration.Request r = service.startFirstRegistration(vin, "CITIZEN", OFFICER);
        assertThat(service.onPaid(VehicleService.itemRef(r.id()))).isTrue();
        return repo.vehicles.values().stream().filter(v -> v.vin().equals(vin)).findFirst().orElseThrow();
    }

    @Test
    @Tag("VL-032")
    @DisplayName("VL-010, VL-032: first registration reserves the next standard plate (skipping premium), pays, assigns")
    void firstRegistration() {
        service.create(VIN, VehicleTest.spec("PRIVATE", 2025), "TF-1", "NORTH", OFFICER);
        assertThatThrownBy(() -> service.create(VIN, VehicleTest.spec("PRIVATE", 2025), "TF-2", "NORTH", OFFICER))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("VIN_ACTIVE");
        policyEnds.put(VIN, today().plusMonths(6));
        Registration.Request r = service.startFirstRegistration(VIN, "CITIZEN", OFFICER);
        assertThat(r.status()).isEqualTo(Registration.Status.AWAITING_PAYMENT);
        assertThat(r.newExpiry()).isEqualTo(today().plusMonths(12).minusDays(1));
        Plates.Plate reserved = repo.plates.get(r.plateId());
        assertThat(reserved.number()).as("1 is premium").isEqualTo(2);
        assertThat(reserved.status()).isEqualTo(Plates.Status.RESERVED);
        assertThat(payablesRegistered).containsExactly(VehicleService.itemRef(r.id()));
        assertThat(quotes).containsExactly("VEH_FIRST_REG PRIVATE null");
        assertThatThrownBy(() -> service.startFirstRegistration(VIN, "CITIZEN", OFFICER))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("REQUEST_OPEN");

        assertThat(service.onPaid(VehicleService.itemRef(r.id()))).isTrue();
        assertThat(service.onPaid(VehicleService.itemRef(r.id()))).as("a repeat does nothing").isFalse();
        assertThat(service.onPaid("TXN-OTHER")).isFalse();
        assertThat(service.onPaid("VR-not-a-uuid")).isFalse();
        Vehicle v = service.get(VIN, OFFICER);
        assertThat(v.status()).isEqualTo(Vehicle.Status.REGISTERED);
        assertThat(v.plateNumber()).isEqualTo("A 2");
        assertThat(repo.plates.get(r.plateId()).status()).isEqualTo(Plates.Status.ASSIGNED);
        assertThat(events.stream().filter(e -> e.equals("itms.vehicle.registered " + VIN)).count()).isEqualTo(1);
        assertThat(repo.history.get(v.id())).contains("CREATED", "REGISTRATION_REQUESTED", "REGISTERED");
    }

    @Test
    @Tag("VL-024")
    @Tag("UAT-3")
    @DisplayName("UAT-3, VL-024: a policy that expired yesterday stops the renewal before any fee; no answer also stops")
    void insuranceBeforePayment() {
        Vehicle v = registered(VIN, 2025);
        clock.set(v.registrationExpiry().minusDays(10).atStartOfDay(ZoneOffset.UTC).toInstant());
        policyEnds.put(VIN, today().minusDays(1));
        int quotesBefore = quotes.size();
        int payablesBefore = payablesRegistered.size();
        assertThatThrownBy(() -> service.startRenewal(VIN, "CITIZEN", OFFICER)).isInstanceOf(DomainException.class)
                .hasMessage("Your vehicle insurance has expired. Renew it with your insurer, then try again.")
                .extracting("code").isEqualTo("INSURANCE_EXPIRED");
        assertThat(quotes).hasSize(quotesBefore);
        assertThat(payablesRegistered).as("stopped before payment").hasSize(payablesBefore);

        insurerDown = true;
        assertThatThrownBy(() -> service.startRenewal(VIN, "CITIZEN", OFFICER))
                .isInstanceOf(Ports.UnavailableException.class);
        insurerDown = false;
        policyEnds.put(VIN, today().plusMonths(3));
        Registration.Request renewal = service.startRenewal(VIN, "CITIZEN", OFFICER);
        assertThat(renewal.newExpiry()).isEqualTo(v.registrationExpiry().plusMonths(12));
        assertThat(quotes.getLast()).isEqualTo("VEH_RENEWAL PRIVATE " + v.registrationExpiry());
        service.onPaid(VehicleService.itemRef(renewal.id()));
        assertThat(service.get(VIN, OFFICER).registrationExpiry()).isEqualTo(renewal.newExpiry());
    }

    @Test
    @Tag("VL-011")
    @Tag("VF-023")
    @DisplayName("VL-011, VF-023: a hold on the vehicle or its owner's file stops the renewal before any fee")
    void holdsBlockRenewal() {
        Vehicle v = registered(VIN, 2025);
        clock.set(v.registrationExpiry().minusDays(10).atStartOfDay(ZoneOffset.UTC).toInstant());
        policyEnds.put(VIN, today().plusMonths(3));
        heldSubjects.put(VIN, new Ports.Eligibility.Block("UNPAID_FINES", "PAY_FINES", "Pay your unpaid fines."));
        int quotesBefore = quotes.size();
        assertThatThrownBy(() -> service.startRenewal(VIN, "CITIZEN", OFFICER))
                .isInstanceOf(EligibilityBlockedException.class).extracting("code").isEqualTo("ELIGIBILITY_BLOCKED");
        assertThat(eligibilityChecks.getLast()).isEqualTo("VEH_RENEWAL [VEHICLE:" + VIN + ", TRAFFIC_FILE:"
                + v.ownerFileNumber() + "]");
        assertThat(quotes).as("stopped before any fee").hasSize(quotesBefore);
        heldSubjects.clear();
        assertThat(service.startRenewal(VIN, "CITIZEN", OFFICER).kind()).isEqualTo(Registration.Kind.RENEWAL);
    }

    @Test
    @Tag("VL-011")
    @DisplayName("VL-011: renewal opens the configured days before expiry; a lapsed registration renews from today")
    void renewalWindow() {
        Vehicle v = registered(VIN, 2025);
        clock.set(v.registrationExpiry().minusDays(31).atStartOfDay(ZoneOffset.UTC).toInstant());
        policyEnds.put(VIN, today().plusYears(1));
        assertThatThrownBy(() -> service.startRenewal(VIN, "CITIZEN", OFFICER)).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("RENEWAL_NOT_OPEN");
        clock.set(v.registrationExpiry().plusDays(40).atStartOfDay(ZoneOffset.UTC).toInstant());
        policyEnds.put(VIN, today().plusYears(1));
        Registration.Request late = service.startRenewal(VIN, "CITIZEN", OFFICER);
        assertThat(late.newExpiry()).isEqualTo(today().minusDays(1).plusMonths(12));
    }

    @Test
    @Tag("VL-041")
    @Tag("VL-040")
    @DisplayName("VL-041: an old enough vehicle needs a recent passed inspection; a failed one blocks with its own code")
    void inspectionNeed() {
        Vehicle v = registered(VIN, 2024); // age 2 at registration, 3 at renewal
        clock.set(v.registrationExpiry().minusDays(5).atStartOfDay(ZoneOffset.UTC).toInstant());
        policyEnds.put(VIN, today().plusYears(1));
        assertThatThrownBy(() -> service.startRenewal(VIN, "CITIZEN", OFFICER)).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("INSPECTION_REQUIRED");
        service.recordInspection(VIN, "CENTRE_1", today(), 50_000, Map.of("BRAKES", "FAIL"), false, List.of(),
                OFFICER);
        assertThat(events).contains("itms.vehicle.inspection-failed " + VIN);
        assertThatThrownBy(() -> service.startRenewal(VIN, "CITIZEN", OFFICER)).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("INSPECTION_FAILED");
        assertThatThrownBy(() -> service.recordInspection(VIN, "CENTRE_1", today().plusDays(1), 50_000, Map.of(),
                true, List.of(), OFFICER)).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("INSPECTION_DATE_INVALID");
        service.recordInspection(VIN, "CENTRE_1", today().minusDays(31), 50_000, Map.of("BRAKES", "PASS"), true,
                List.of(), OFFICER);
        assertThatThrownBy(() -> service.startRenewal(VIN, "CITIZEN", OFFICER)).as("too old a pass")
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("INSPECTION_REQUIRED");
        service.recordInspection(VIN, "CENTRE_1", today(), 50_100, Map.of("BRAKES", "PASS"), true, List.of(),
                OFFICER);
        assertThat(service.startRenewal(VIN, "CITIZEN", OFFICER).status())
                .isEqualTo(Registration.Status.AWAITING_PAYMENT);
    }

    @Test
    @Tag("VL-032")
    @Tag("CC-001")
    @DisplayName("VL-032, CC-001: no free number blocks registration; other regions are out of scope")
    void platesRunOutAndScope() {
        registered("11111111111111111", 2025);
        registered("5GZCZ43D13S812715", 2025);
        service.create(VIN, VehicleTest.spec("PRIVATE", 2025), "TF-1", "NORTH", OFFICER);
        policyEnds.put(VIN, today().plusYears(1));
        assertThatThrownBy(() -> service.startFirstRegistration(VIN, "CITIZEN", OFFICER))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("NO_PLATE_AVAILABLE");
        Caller south = caller("u-south", Set.of("SOUTH"), VehicleService.READ, VehicleService.REGISTER);
        assertThatThrownBy(() -> service.get(VIN, south)).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("SCOPE_EXCEEDED");
        assertThatThrownBy(() -> service.startFirstRegistration(VIN, "CITIZEN", south))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("SCOPE_EXCEEDED");
        assertThatThrownBy(() -> service.saveSeries(new Plates.Series("NORTH_PRIVATE", "NORTH", "PRIVATE", "WHITE",
                "STANDARD", "B", 1, 3, null, true), OFFICER)).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("SERIES_FIXED");
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant at) {
            now = at;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class Repo implements Ports.VehicleRepository {
        final Map<UUID, Vehicle> vehicles = new LinkedHashMap<>();
        final Map<UUID, List<String>> history = new HashMap<>();
        final List<Registration.Inspection> inspections = new ArrayList<>();
        final Map<String, Plates.Series> series = new LinkedHashMap<>();
        final Map<UUID, Plates.Plate> plates = new LinkedHashMap<>();
        final Map<UUID, Registration.Request> requests = new LinkedHashMap<>();

        @Override
        public boolean activeVinExists(String vin) {
            return vehicles.values().stream().anyMatch(v -> v.vin().equals(vin));
        }

        @Override
        public void insertVehicle(Vehicle vehicle) {
            vehicles.put(vehicle.id(), vehicle);
        }

        @Override
        public Optional<Vehicle> findByVin(String vin) {
            return vehicles.values().stream().filter(v -> v.vin().equals(vin)).findFirst();
        }

        @Override
        public Optional<Vehicle> findForUpdate(UUID id) {
            return Optional.ofNullable(vehicles.get(id));
        }

        @Override
        public void updateVehicle(Vehicle vehicle) {
            vehicle.saved();
        }

        @Override
        public void history(UUID vehicleId, String event, String actorId, Map<String, Object> detail, Instant at) {
            history.computeIfAbsent(vehicleId, k -> new ArrayList<>()).add(event);
        }

        @Override
        public List<HistoryEntry> history(UUID vehicleId) {
            return history.getOrDefault(vehicleId, List.of()).stream()
                    .map(e -> new HistoryEntry(e, "x", Map.of(), Instant.EPOCH)).toList();
        }

        @Override
        public void insertInspection(Registration.Inspection inspection) {
            inspections.add(inspection);
        }

        @Override
        public Optional<Registration.Inspection> latestInspection(UUID vehicleId) {
            return inspections.stream().filter(i -> i.vehicleId().equals(vehicleId)).reduce((a, b) -> b);
        }

        @Override
        public void saveSeries(Plates.Series s) {
            series.put(s.code(), s);
        }

        @Override
        public Optional<Plates.Series> findSeries(String code) {
            return Optional.ofNullable(series.get(code));
        }

        @Override
        public List<Plates.Series> activeSeries(String region, String plateCategory) {
            return series.values().stream().filter(s -> s.active() && s.region().equals(region)
                    && s.plateCategory().equals(plateCategory)).toList();
        }

        @Override
        public Optional<Long> nextFreeNumber(Plates.Series s) {
            Set<Long> used = new HashSet<>();
            plates.values().stream().filter(p -> p.seriesCode().equals(s.code())).forEach(p -> used.add(p.number()));
            for (long n = s.firstNumber(); n <= s.lastNumber(); n++) {
                if (!used.contains(n) && !s.premium(n)) {
                    return Optional.of(n);
                }
            }
            return Optional.empty();
        }

        @Override
        public void insertPlate(Plates.Plate plate) {
            plates.put(plate.id(), plate);
        }

        @Override
        public Optional<Plates.Plate> findPlate(UUID id) {
            return Optional.ofNullable(plates.get(id));
        }

        @Override
        public void updatePlate(Plates.Plate plate) {
            plates.put(plate.id(), plate);
        }

        @Override
        public void insertRequest(Registration.Request request) {
            requests.put(request.id(), request);
        }

        @Override
        public Optional<Registration.Request> findRequestForUpdate(UUID id) {
            return Optional.ofNullable(requests.get(id));
        }

        @Override
        public Optional<Registration.Request> openRequest(UUID vehicleId) {
            return requests.values().stream().filter(r -> r.vehicleId().equals(vehicleId)
                    && r.status() == Registration.Status.AWAITING_PAYMENT).findFirst();
        }

        @Override
        public void updateRequest(Registration.Request request) {
            requests.put(request.id(), request);
        }
    }
}
