package gov.itms.violation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.platform.security.CurrentActor;
import gov.itms.violation.domain.Actor;
import gov.itms.violation.domain.BlackPoints;
import gov.itms.violation.domain.CatalogueEntry;
import gov.itms.violation.domain.CatalogueEntryTest;
import gov.itms.violation.domain.Channel;
import gov.itms.violation.domain.DomainException;
import gov.itms.violation.domain.UnpaidHold;
import gov.itms.violation.domain.Violation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Black points are added when a violation is <em>recorded</em> (ADR-056). UAT-1 says "when a violation ... is
 * confirmed"; the Q-001 interim answer agrees, but objections (and so a confirmation step) do not exist yet. Whether
 * recording may stand in for confirmation is open (Q-072 (a)); these tests exercise the current implementation.
 */
@Tag("VF-030")
class ViolationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T08:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);

    private final MutableClock clock = new MutableClock(NOW);
    private final Repo repo = new Repo();
    private final List<String> audit = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final List<String> payablesRegistered = new ArrayList<>();
    private final List<UUID> payablesCancelled = new ArrayList<>();
    private final List<String> holds = new ArrayList<>();
    private final List<String> unpaidPlaced = new ArrayList<>();
    private final List<String> unpaidLifted = new ArrayList<>();
    private boolean holdsDown;
    private final List<String> payablesHeld = new ArrayList<>();
    /** The rental contract rental-service reports for any plate and time; null = none covers it (TR-032). */
    private Ports.Rentals.Cover rentalCover;
    private boolean rentalsDown;
    private final ViolationService service = new ViolationService(repo, new Ports.Payables() {
        @Override
        public UUID register(String number, String file, String region, String currency, long amount,
                             String financialItem, String beneficiary, String description) {
            payablesRegistered.add(number + " " + file + " " + amount + " " + currency);
            return UUID.randomUUID();
        }

        @Override
        public void cancel(UUID payableId, String reason) {
            payablesCancelled.add(payableId);
        }

        @Override
        public void hold(UUID payableId, String sourceRef, String reason) {
            payablesHeld.add(payableId + " " + sourceRef);
        }

        @Override
        public void liftHold(UUID payableId, String sourceRef, String reason) {
            payablesHeld.remove(payableId + " " + sourceRef);
        }
    }, (file, from, until, sourceRef, reason) -> {
        holds.add(file + " " + from + ".." + until);
        return "HOLD-" + holds.size();
    }, new Ports.UnpaidFineHolds() {
        @Override
        public String place(UnpaidHold.Subject subject, String sourceRef, String reason) {
            if (holdsDown) {
                throw new Ports.UnavailableException("HOLDS_UNAVAILABLE", "down", null);
            }
            unpaidPlaced.add(subject.type() + " " + subject.id());
            return "UNPAID-HOLD-" + unpaidPlaced.size();
        }

        @Override
        public void lift(String holdId, String reason) {
            if (holdsDown) {
                throw new Ports.UnavailableException("HOLDS_UNAVAILABLE", "down", null);
            }
            unpaidLifted.add(holdId);
        }
    }, (plate, at) -> {
        if (rentalsDown) {
            throw new Ports.UnavailableException("RENTALS_UNAVAILABLE", "down", null);
        }
        return Optional.ofNullable(rentalCover);
    }, new AuditRecorder() {
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
    }, new ViolationService.Settings(new BlackPoints.Policy(12, 24, List.of(3, 6, 12), List.of(12, 18)),
            Set.of("DUPLICATE", "WRONG_PLATE"), "XTS", ZoneOffset.UTC, Duration.ofDays(30),
            new UnpaidHold.Policy(true, Duration.ofDays(60), 100)), clock);

    private static Caller caller(String id, Set<String> regions, String... permissions) {
        Set<String> held = Set.of(permissions);
        return new Caller(new Actor(id, id), Channel.FIELD, new Caller.Grants() {
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

    private static final Caller ADMIN = caller("u-admin", Set.of(), ViolationService.CATALOGUE_MANAGE);
    private static final Caller OFFICER = caller("u-officer", Set.of("NORTH", "SOUTH"), ViolationService.RECORD,
            ViolationService.READ);
    private static final Caller SUPERVISOR = caller("u-super", Set.of("NORTH"), ViolationService.CANCEL,
            ViolationService.READ);

    private CatalogueEntry publish(String code, String region, long fine, int points, Integer repeatDays,
                                   Long repeatFine) {
        return service.publish(CatalogueEntryTest.entry(code, region, fine, points, repeatDays, repeatFine, TODAY,
                null), "new catalogue entry", ADMIN);
    }

    private Violation record(String code, String region, String driver) {
        return service.record(new Violation.Report(code, clock.instant().minus(Duration.ofHours(1)), region, "Main road",
                "A 1", null, "TF-OWNER", driver, null, null, Violation.Source.OFFICER, null, List.of()), OFFICER);
    }

    @Test
    @Tag("VF-002")
    @DisplayName("VF-002: the regional version in force wins; other regions get the national one")
    void regionalVersion() {
        publish("PARKING", null, 20_000, 0, null, null);
        publish("PARKING", "NORTH", 30_000, 0, null, null);
        assertThat(record("PARKING", "NORTH", null).fineMinor()).isEqualTo(30_000);
        Violation south = record("PARKING", "SOUTH", null);
        assertThat(south.fineMinor()).isEqualTo(20_000);
        assertThat(south.catalogueVersion()).isEqualTo(1);
        assertThat(south.number()).matches("V-2026-\\d{9}");
        assertThatThrownBy(() -> record("UNKNOWN", "NORTH", null)).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("CATALOGUE_NOT_IN_FORCE");
        assertThatThrownBy(() -> service.publish(CatalogueEntryTest.entry("PARKING", null, 1, 0, null, null,
                TODAY.minusDays(1), null), "back-dated", ADMIN)).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("CATALOGUE_START_IN_PAST");
    }

    @Test
    @Tag("VF-003")
    @Tag("VF-015")
    @Tag("VF-070")
    @DisplayName("VF-003, VF-070: a repeat within the period costs the repeat fine; each fine becomes a payable")
    void repeatAndPayable() {
        publish("RED_LIGHT", null, 80_000, 0, 365, 160_000L);
        Violation first = record("RED_LIGHT", "NORTH", "TF-DRIVER");
        Violation second = record("RED_LIGHT", "NORTH", "TF-DRIVER");
        assertThat(first.repeat()).isFalse();
        assertThat(second.repeat()).isTrue();
        assertThat(second.fineMinor()).isEqualTo(160_000);
        assertThat(payablesRegistered).containsExactly(first.number() + " TF-DRIVER 80000 XTS",
                second.number() + " TF-DRIVER 160000 XTS");
        assertThat(first.payableId()).isNotNull();
        assertThat(events).contains("itms.violation.recorded " + first.number());
        publish("WARNING_ONLY", null, 0, 0, null, null);
        assertThat(record("WARNING_ONLY", "NORTH", null).payableId()).as("no fine, no payable").isNull();
    }

    @Test
    @Tag("VF-031")
    @Tag("VF-033")
    @DisplayName("VF-030, VF-033: points go to the responsible file and warn once per threshold crossed")
    void pointsAndWarnings() {
        publish("SEATBELT", null, 40_000, 6, null, null);
        record("SEATBELT", "NORTH", "TF-D");
        record("SEATBELT", "NORTH", "TF-D");
        assertThat(service.standing("TF-D", OFFICER).activePoints()).isEqualTo(12);
        assertThat(service.standing("TF-OWNER", OFFICER).activePoints()).as("the owner is not charged").isZero();
        assertThat(events.stream().filter(e -> e.equals("itms.violation.points-warning TF-D")).count()).isEqualTo(1);
        record("SEATBELT", "NORTH", "TF-D");
        assertThat(events.stream().filter(e -> e.equals("itms.violation.points-warning TF-D")).count()).isEqualTo(2);
        BlackPoints.Entry e = repo.points.getFirst();
        assertThat(e.expiresOn()).isEqualTo(e.occurredOn().plusMonths(12));
    }

    @Test
    @Tag("VF-032")
    @Tag("UAT-1")
    @DisplayName("UAT-1 (at recording, Q-072): 20 active + 6 = 26 suspends for 3 months with a hold; the next is 6 months")
    void uat1Suspension() {
        publish("SPEED_20", null, 30_000, 20, null, null);
        publish("SPEED_6", null, 60_000, 6, null, null);
        record("SPEED_20", "NORTH", "TF-UAT1");
        assertThat(service.standing("TF-UAT1", OFFICER).activePoints()).isEqualTo(20);
        assertThat(holds).isEmpty();

        Violation trigger = record("SPEED_6", "NORTH", "TF-UAT1");
        ViolationService.Standing standing = service.standing("TF-UAT1", OFFICER);
        assertThat(standing.activeSuspension()).isNotNull();
        assertThat(standing.activeSuspension().pointsAtOrder()).isEqualTo(26);
        assertThat(standing.activeSuspension().ordinal()).isEqualTo(1);
        assertThat(standing.activeSuspension().from()).isEqualTo(TODAY);
        assertThat(standing.activeSuspension().until()).isEqualTo(TODAY.plusMonths(3).minusDays(1));
        assertThat(standing.activeSuspension().holdId()).isEqualTo("HOLD-1");
        assertThat(standing.activeSuspension().triggeredBy()).isEqualTo(trigger.id());
        assertThat(holds).containsExactly("TF-UAT1 " + TODAY + ".." + TODAY.plusMonths(3).minusDays(1));
        assertThat(events).contains("itms.violation.licence-suspended TF-UAT1");
        assertThat(standing.activePoints()).as("the counted points are consumed").isZero();

        // While suspended, more points never order a second suspension.
        record("SPEED_20", "NORTH", "TF-UAT1");
        record("SPEED_6", "NORTH", "TF-UAT1");
        assertThat(holds).hasSize(1);

        // After the first suspension ends, reaching the threshold again gives the second length (6 months).
        clock.set(TODAY.plusMonths(3).atStartOfDay(ZoneOffset.UTC).toInstant().plus(Duration.ofHours(2)));
        record("SPEED_6", "NORTH", "TF-UAT1");
        LocalDate day = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        assertThat(holds).hasSize(2);
        assertThat(holds.getLast()).isEqualTo("TF-UAT1 " + day + ".." + day.plusMonths(6).minusDays(1));
        assertThat(repo.suspensions).extracting(BlackPoints.Suspension::ordinal).containsExactly(1, 2);
    }

    @Test
    @Tag("VF-021")
    @DisplayName("VF-021: a supervisor cancels with a listed reason; the payable is withdrawn and the points cancelled")
    void cancel() {
        publish("SEATBELT", null, 40_000, 6, null, null);
        Violation v = record("SEATBELT", "NORTH", "TF-C");
        assertThatThrownBy(() -> service.cancel(v.number(), "BECAUSE", null, SUPERVISOR))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("CANCEL_REASON_INVALID");
        assertThatThrownBy(() -> service.cancel(v.number(), "DUPLICATE", null, OFFICER))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("SCOPE_EXCEEDED");
        Violation cancelled = service.cancel(v.number(), "DUPLICATE", "entered twice", SUPERVISOR);
        assertThat(cancelled.status()).isEqualTo(Violation.Status.CANCELLED);
        assertThat(payablesCancelled).containsExactly(v.payableId());
        assertThat(service.standing("TF-C", OFFICER).activePoints()).isZero();
        assertThat(events).contains("itms.violation.cancelled " + v.number());
        assertThat(service.onPaid(v.number())).as("a cancelled violation is not paid").isFalse();
    }

    @Test
    @Tag("VF-070")
    @Tag("UAT-4")
    @DisplayName("VF-070, UAT-4: the payment confirmation marks the violation PAID once; a receipt cancellation reopens")
    void onPaidOnce() {
        publish("PARKING", null, 20_000, 0, null, null);
        Violation v = record("PARKING", "NORTH", null);
        assertThat(service.onPaid(v.number())).isTrue();
        assertThat(service.onPaid(v.number())).isFalse();
        assertThat(service.onPaid("V-2026-999999999")).isFalse();
        assertThat(events.stream().filter(e -> e.equals("itms.violation.paid " + v.number())).count()).isEqualTo(1);
        assertThat(service.onReceiptCancelled(v.number())).isTrue();
        assertThat(service.onReceiptCancelled(v.number())).isFalse();
        assertThat(repo.violations.get(v.number()).status()).isEqualTo(Violation.Status.OPEN);
    }

    @Test
    @Tag("VF-010")
    @Tag("CC-001")
    @DisplayName("violation times must be recent and not in the future; recording is scoped by region")
    void timesAndScope() {
        publish("PARKING", null, 20_000, 0, null, null);
        assertThatThrownBy(() -> service.record(new Violation.Report("PARKING", NOW.plusSeconds(120), "NORTH", "road",
                "A 1", null, "TF-1", null, null, null, Violation.Source.OFFICER, null, List.of()), OFFICER))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("OCCURRED_AT_INVALID");
        assertThatThrownBy(() -> service.record(new Violation.Report("PARKING", NOW.minus(Duration.ofDays(31)),
                "NORTH", "road", "A 1", null, "TF-1", null, null, null, Violation.Source.OFFICER, null, List.of()),
                OFFICER)).isInstanceOf(DomainException.class).extracting("code").isEqualTo("OCCURRED_AT_INVALID");
        assertThatThrownBy(() -> service.record(new Violation.Report("PARKING", NOW, "EAST", "road", "A 1", null,
                "TF-1", null, null, null, Violation.Source.OFFICER, null, List.of()), OFFICER))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("SCOPE_EXCEEDED");
        Violation v = record("PARKING", "SOUTH", null);
        assertThatThrownBy(() -> service.get(v.number(), SUPERVISOR)).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("SCOPE_EXCEEDED");
    }

    @Test
    @Tag("VF-023")
    @DisplayName("VF-023 (Q-012, Q-074): unpaid after the period, the vehicle or the driver's file is held; paying or "
            + "cancelling lifts it, a reopened violation is held again")
    void unpaidHolds() {
        publish("PARKING", null, 20_000, 0, null, null);
        Violation owners = service.record(new Violation.Report("PARKING", clock.instant().minus(Duration.ofHours(1)),
                "NORTH", "Main road", "A 1", "1HGCM82633A004352", "TF-OWNER", null, null, null,
                Violation.Source.OFFICER, null, List.of()), OFFICER);
        Violation drivers = record("PARKING", "NORTH", "TF-DRIVER");
        Violation noVin = record("PARKING", "NORTH", null);
        assertThat(service.overdueForHold()).as("not yet overdue").isEmpty();

        clock.set(NOW.plus(Duration.ofDays(60)).plus(Duration.ofHours(1)));
        assertThat(service.overdueForHold()).containsExactly(owners.number(), drivers.number(), noVin.number());
        service.overdueForHold().forEach(service::holdIfOverdue);
        assertThat(unpaidPlaced).containsExactly("VEHICLE 1HGCM82633A004352", "TRAFFIC_FILE TF-DRIVER",
                "TRAFFIC_FILE TF-OWNER");
        assertThat(service.holdIfOverdue(owners.number())).as("one active hold per violation").isFalse();
        assertThat(audit).contains("UNPAID_HOLD_PLACED " + owners.number());
        assertThat(events).contains("itms.violation.unpaid-hold-placed " + owners.number());

        assertThat(service.onPaid(owners.number())).isTrue();
        assertThat(unpaidLifted).as("paying lifts at once").containsExactly("UNPAID-HOLD-1");
        service.cancel(drivers.number(), "DUPLICATE", null, SUPERVISOR);
        assertThat(unpaidLifted).containsExactly("UNPAID-HOLD-1", "UNPAID-HOLD-2");

        // hold-eligibility-service down: the payment still counts and the job lifts the hold later
        holdsDown = true;
        assertThat(service.onPaid(noVin.number())).isTrue();
        assertThat(service.settledWithHold()).containsExactly(noVin.number());
        assertThatThrownBy(() -> service.liftIfSettled(noVin.number()))
                .isInstanceOf(Ports.UnavailableException.class);
        holdsDown = false;
        assertThat(service.liftIfSettled(noVin.number())).isTrue();
        assertThat(service.settledWithHold()).isEmpty();

        // a cancelled receipt reopens the violation; it is overdue at once, so it is held again
        assertThat(service.onReceiptCancelled(owners.number())).isTrue();
        assertThat(service.overdueForHold()).containsExactly(owners.number());
        assertThat(service.holdIfOverdue(owners.number())).isTrue();
        assertThat(repo.unpaid.stream().filter(h -> h.violationNumber().equals(owners.number()))).hasSize(2);
    }

    @Test
    @Tag("TR-032")
    @Tag("VF-014")
    @DisplayName("TR-032, VF-014, UAT-2: no identified driver and a rental contract covers the time: the renter is "
            + "responsible, gets the points, the payable and the unpaid hold; the company is notified")
    void rentalTransfer() {
        publish("SPEEDING", null, 30_000, 4, null, null);
        UUID contract = UUID.randomUUID();
        rentalCover = new Ports.Rentals.Cover(contract, "TF-RENTALCO", "TF-RENTER", false);
        Violation v = record("SPEEDING", "NORTH", null);
        assertThat(v.report().responsibleFileNumber()).isEqualTo("TF-RENTER");
        assertThat(v.report().rentalContractId()).isEqualTo(contract);
        assertThat(payablesRegistered).contains(v.number() + " TF-RENTER 30000 XTS");
        assertThat(repo.points("TF-RENTER")).hasSize(1);
        assertThat(repo.points("TF-OWNER")).isEmpty();
        assertThat(events).contains("itms.violation.recorded " + v.number(),
                "itms.violation.transferred-to-renter " + v.number());
        assertThat(audit).contains("VIOLATION_TRANSFERRED_TO_RENTER " + v.number());
        clock.set(NOW.plus(Duration.ofDays(61)));
        assertThat(service.holdIfOverdue(v.number())).isTrue();
        assertThat(unpaidPlaced).as("Q-016: the renter's file, not the company's vehicle")
                .containsExactly("TRAFFIC_FILE TF-RENTER");
    }

    @Test
    @Tag("TR-032")
    @Tag("VF-014")
    @DisplayName("VF-014: an identified driver wins over the rental contract; a visitor renter leaves the owner "
            + "responsible (Q-081); rental-service down refuses the recording (fail closed)")
    void rentalTransferLimits() {
        publish("PARKING", null, 20_000, 0, null, null);
        rentalCover = new Ports.Rentals.Cover(UUID.randomUUID(), "TF-RENTALCO", "TF-RENTER", false);
        Violation driven = record("PARKING", "NORTH", "TF-DRIVER");
        assertThat(driven.report().responsibleFileNumber()).isEqualTo("TF-DRIVER");
        assertThat(driven.report().rentalContractId()).as("not even asked").isNull();

        UUID visitorContract = UUID.randomUUID();
        rentalCover = new Ports.Rentals.Cover(visitorContract, "TF-RENTALCO", null, true);
        Violation visiting = record("PARKING", "NORTH", null);
        assertThat(visiting.report().responsibleFileNumber()).isEqualTo("TF-OWNER");
        assertThat(visiting.report().rentalContractId()).isEqualTo(visitorContract);
        assertThat(events).contains("itms.violation.renter-is-visitor " + visiting.number());

        rentalCover = null;
        assertThat(record("PARKING", "NORTH", null).report().rentalContractId()).isNull();

        rentalsDown = true;
        int before = repo.violations.size();
        assertThatThrownBy(() -> record("PARKING", "NORTH", null)).isInstanceOf(Ports.UnavailableException.class);
        assertThat(repo.violations).hasSize(before);
    }

    @Test
    @Tag("VF-052")
    @Tag("VF-023")
    @DisplayName("VF-052 (Q-074 f): no unpaid hold while an objection is pending; held once the objection is decided")
    void noUnpaidHoldUnderObjection() {
        publish("PARKING", null, 20_000, 0, null, null);
        Violation objected = record("PARKING", "NORTH", "TF-DRIVER");
        clock.set(NOW.plus(Duration.ofDays(60)).plus(Duration.ofHours(1)));
        repo.objected.add(objected.id());
        assertThat(service.overdueForHold()).as("payment is paused under objection").isEmpty();
        assertThat(service.holdIfOverdue(objected.number())).as("re-checked under the row lock").isFalse();
        assertThat(unpaidPlaced).isEmpty();

        repo.objected.remove(objected.id());
        assertThat(service.overdueForHold()).containsExactly(objected.number());
        assertThat(service.holdIfOverdue(objected.number())).isTrue();
        assertThat(unpaidPlaced).containsExactly("TRAFFIC_FILE TF-DRIVER");
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

    private static final class Repo implements Ports.ViolationRepository {
        final Map<String, ViolationBooks.Book> books = new HashMap<>();
        final Map<String, List<String>> bookReports = new HashMap<>();

        @Override
        public void insertPaperBook(ViolationBooks.Book book) {
            books.put(book.serial(), book);
        }

        @Override
        public Optional<ViolationBooks.Book> paperBook(String serial) {
            return Optional.ofNullable(books.get(serial));
        }

        @Override
        public void insertPaperBookEntry(String serial, String reportSerial, String violationNumber, Instant at) {
            bookReports.computeIfAbsent(serial, k -> new ArrayList<>()).add(reportSerial);
        }

        @Override
        public List<String> paperBookReports(String serial) {
            return bookReports.getOrDefault(serial, List.of());
        }

        @Override
        public void finishPaperBook(String serial, String status, String actor, Instant at) {
            ViolationBooks.Book b = books.get(serial);
            books.put(serial, new ViolationBooks.Book(b.serial(), b.region(), b.officerId(), status, b.issuedAt(), at,
                    actor));
        }

        final Map<String, List<CatalogueEntry>> catalogue = new HashMap<>();
        final Map<String, Violation> violations = new LinkedHashMap<>();
        final List<BlackPoints.Entry> points = new ArrayList<>();
        final List<BlackPoints.Suspension> suspensions = new ArrayList<>();
        long sequence;

        @Override
        public void lockCatalogue(String code) {
        }

        @Override
        public int nextCatalogueVersion(String code) {
            return catalogue.getOrDefault(code, List.of()).size() + 1;
        }

        @Override
        public void insertCatalogue(CatalogueEntry entry) {
            catalogue.computeIfAbsent(entry.code(), c -> new ArrayList<>()).add(entry);
        }

        @Override
        public List<CatalogueEntry> catalogueVersions(String code) {
            return catalogue.getOrDefault(code, List.of()).stream()
                    .sorted(Comparator.comparingInt(CatalogueEntry::versionNo).reversed()).toList();
        }

        @Override
        public List<CatalogueEntry> latestCatalogue() {
            return catalogue.values().stream().map(List::getLast).toList();
        }

        @Override
        public long nextViolationSequence() {
            return ++sequence;
        }

        @Override
        public void insertViolation(Violation violation) {
            violations.put(violation.number(), violation);
        }

        @Override
        public Optional<Violation> findByNumber(String number) {
            return Optional.ofNullable(violations.get(number));
        }

        @Override
        public Optional<Violation> findForUpdate(String number) {
            return findByNumber(number);
        }

        @Override
        public void updateViolation(Violation violation) {
        }

        @Override
        public List<Violation> byFile(String fileNumber, Violation.Status status, int limit) {
            return violations.values().stream().filter(v -> v.report().responsibleFileNumber().equals(fileNumber))
                    .filter(v -> status == null || v.status() == status).limit(limit).toList();
        }

        @Override
        public boolean hadViolationSince(String fileNumber, String catalogueCode, Instant since) {
            return violations.values().stream().anyMatch(v -> v.report().responsibleFileNumber().equals(fileNumber)
                    && v.catalogueCode().equals(catalogueCode) && v.status() != Violation.Status.CANCELLED
                    && !v.report().occurredAt().isBefore(since));
        }

        @Override
        public void lockFile(String fileNumber) {
        }

        @Override
        public void insertPoints(BlackPoints.Entry entry) {
            points.add(entry);
        }

        @Override
        public List<BlackPoints.Entry> points(String fileNumber) {
            return points.stream().filter(p -> p.fileNumber().equals(fileNumber)).toList();
        }

        @Override
        public void setPointsStatus(List<UUID> ids, BlackPoints.EntryStatus status) {
            points.replaceAll(p -> ids.contains(p.id()) ? new BlackPoints.Entry(p.id(), p.fileNumber(),
                    p.violationId(), p.points(), p.occurredOn(), p.expiresOn(), status) : p);
        }

        @Override
        public void cancelPointsOf(UUID violationId) {
            points.replaceAll(p -> p.violationId().equals(violationId) && p.status() == BlackPoints.EntryStatus.ACTIVE
                    ? new BlackPoints.Entry(p.id(), p.fileNumber(), p.violationId(), p.points(), p.occurredOn(),
                    p.expiresOn(), BlackPoints.EntryStatus.CANCELLED) : p);
        }

        @Override
        public List<BlackPoints.Suspension> suspensions(String fileNumber) {
            return suspensions.stream().filter(s -> s.fileNumber().equals(fileNumber)).toList();
        }

        @Override
        public void insertSuspension(BlackPoints.Suspension suspension) {
            suspensions.add(suspension);
        }

        final List<UnpaidHold> unpaid = new ArrayList<>();
        final Set<UUID> objected = new HashSet<>();

        @Override
        public List<Violation> overdueWithoutHold(Instant recordedBefore, int limit) {
            return violations.values().stream().filter(v -> v.status() == Violation.Status.OPEN)
                    .filter(v -> v.recordedAt().isBefore(recordedBefore))
                    .filter(v -> activeUnpaidHold(v.id()).isEmpty())
                    .filter(v -> !underObjection(v.id())).limit(limit).toList();
        }

        @Override
        public boolean underObjection(UUID violationId) {
            return objected.contains(violationId);
        }

        @Override
        public List<UnpaidHold> settledWithActiveHold(int limit) {
            return unpaid.stream().filter(UnpaidHold::active)
                    .filter(h -> violations.get(h.violationNumber()).status() != Violation.Status.OPEN)
                    .limit(limit).toList();
        }

        @Override
        public Optional<UnpaidHold> activeUnpaidHold(UUID violationId) {
            return unpaid.stream().filter(h -> h.violationId().equals(violationId) && h.active()).findFirst();
        }

        @Override
        public void insertUnpaidHold(UnpaidHold hold) {
            unpaid.add(hold);
        }

        @Override
        public void unpaidHoldLifted(UUID id, Instant at) {
            unpaid.replaceAll(h -> h.id().equals(id) ? new UnpaidHold(h.id(), h.violationId(), h.violationNumber(),
                    h.subject(), h.holdId(), h.placedAt(), at) : h);
        }
    }
}
