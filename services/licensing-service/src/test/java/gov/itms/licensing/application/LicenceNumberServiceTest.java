package gov.itms.licensing.application;

import static org.assertj.core.api.Assertions.assertThat;

import gov.itms.licensing.application.LicenceNumberRepository.UsedNumber;
import gov.itms.licensing.domain.Actor;
import gov.itms.licensing.domain.Channel;
import gov.itms.licensing.domain.DomainException;
import gov.itms.licensing.domain.LicenceNumberRange;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.security.CurrentActor;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("DL-061")
class LicenceNumberServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-10T08:00:00Z");

    private final Ranges ranges = new Ranges();
    private final List<String> audit = new ArrayList<>();
    private final LicenceNumberService service = new LicenceNumberService(ranges, new AuditRecorder() {
        @Override
        public void record(String action, String entityType, String entityId, Object oldValue, Object newValue,
                           String reason) {
            audit.add(action);
        }

        @Override
        public void recordAs(CurrentActor actor, String action, String entityType, String entityId, Object oldValue,
                             Object newValue, String reason) {
            record(action, entityType, entityId, oldValue, newValue, reason);
        }
    }, Clock.fixed(NOW, ZoneOffset.UTC));

    private static final Caller MAKER = caller("u-maker", "NORTH", LicenceNumberService.SUBMIT,
            LicenceNumberService.READ);
    private static final Caller CHECKER = caller("u-checker", "NORTH", LicenceNumberService.APPROVE,
            LicenceNumberService.READ);

    private static Caller caller(String id, String region, String... permissions) {
        Set<String> held = Set.of(permissions);
        return new Caller(new Actor(id, id), Channel.BACKOFFICE, new Caller.Grants() {
            @Override
            public boolean allowsInRegion(String permission, String r) {
                return held.contains(permission) && region.equals(r);
            }

            @Override
            public boolean holds(String permission) {
                return held.contains(permission);
            }
        }, "corr-1");
    }

    private LicenceNumberRange active(String region, String prefix, long first, long last) {
        Caller maker = caller("u-maker", region, LicenceNumberService.SUBMIT);
        Caller checker = caller("u-checker", region, LicenceNumberService.APPROVE);
        LicenceNumberRange r = service.submit(new LicenceNumberRange.Spec(region, prefix, 6, first, last), "new range",
                maker);
        return service.approve(r.id(), null, checker);
    }

    private static String code(Runnable r) {
        try {
            r.run();
        } catch (DomainException e) {
            return e.code();
        }
        return null;
    }

    @Test
    @DisplayName("numbers come in order from the oldest active range, then the next; then the region is exhausted")
    void allocateInOrder() {
        active("NORTH", "N", 1, 2);
        active("NORTH", "N", 10, 10);
        assertThat(service.allocate("NORTH", "lic-1").licenceNumber()).isEqualTo("N000001");
        assertThat(service.allocate("NORTH", "lic-2").licenceNumber()).isEqualTo("N000002");
        assertThat(service.allocate("NORTH", "lic-3").licenceNumber()).isEqualTo("N000010");
        assertThat(code(() -> service.allocate("NORTH", "lic-4"))).isEqualTo("LICENCE_NUMBERS_EXHAUSTED");
        assertThat(code(() -> service.allocate("SOUTH", "lic-5"))).isEqualTo("LICENCE_NUMBERS_EXHAUSTED");
        assertThat(ranges.used).extracting(UsedNumber::allocatedTo).containsExactly("lic-1", "lic-2", "lic-3");
        assertThat(audit).contains("LICENCE_NUMBER_ALLOCATED");
    }

    @Test
    @DisplayName("the report shows total, used and available per range and for the region; pending ranges add nothing")
    void report() {
        LicenceNumberRange first = active("NORTH", "N", 1, 100);
        service.submit(new LicenceNumberRange.Spec("NORTH", "N", 6, 101, 200), "next range", MAKER);
        service.allocate("NORTH", "lic-1");
        service.allocate("NORTH", "lic-2");
        LicenceNumberService.Report report = service.report("NORTH", CHECKER);
        assertThat(report.ranges()).hasSize(2);
        assertThat(report.totals().total()).isEqualTo(100);
        assertThat(report.totals().used()).isEqualTo(2);
        assertThat(report.totals().available()).isEqualTo(98);
        assertThat(service.used(first.id(), 0, 10, CHECKER)).extracting(UsedNumber::licenceNumber)
                .containsExactly("N000001", "N000002");
        assertThat(code(() -> service.used(first.id(), 0, 501, CHECKER))).isEqualTo("PAGE_INVALID");
    }

    @Test
    @DisplayName("ranges with the same prefix never overlap, in any region; another prefix may reuse the numbers")
    void overlap() {
        active("NORTH", "N", 1, 100);
        Caller southMaker = caller("u-maker", "SOUTH", LicenceNumberService.SUBMIT);
        assertThat(code(() -> service.submit(new LicenceNumberRange.Spec("SOUTH", "N", 6, 50, 150), "overlap",
                southMaker))).isEqualTo("NUMBER_RANGE_OVERLAP");
        assertThat(service.submit(new LicenceNumberRange.Spec("SOUTH", "S", 6, 50, 150), "own prefix", southMaker)
                .status()).isEqualTo(LicenceNumberRange.Status.PENDING);
    }

    @Test
    @Tag("CC-002")
    @DisplayName("the maker cannot approve their own range but may withdraw it; a decided range stays decided")
    void makerChecker() {
        Caller both = caller("u-maker", "NORTH", LicenceNumberService.SUBMIT, LicenceNumberService.APPROVE);
        LicenceNumberRange r = service.submit(new LicenceNumberRange.Spec("NORTH", "N", 6, 1, 9), "new range", both);
        assertThat(code(() -> service.approve(r.id(), null, both))).isEqualTo("SAME_MAKER_CHECKER");
        assertThat(service.reject(r.id(), "not needed", MAKER).status()).isEqualTo(LicenceNumberRange.Status.REJECTED);
        assertThat(audit).contains("LICENCE_NUMBER_RANGE_WITHDRAWN");
        assertThat(code(() -> service.approve(r.id(), null, CHECKER))).isEqualTo("NUMBER_RANGE_DECIDED");
    }

    @Test
    @Tag("CC-001")
    @DisplayName("range commands and reads need a grant reaching the range's region")
    void regionScope() {
        assertThat(code(() -> service.submit(new LicenceNumberRange.Spec("SOUTH", "S", 6, 1, 9), "new range",
                MAKER))).isEqualTo("SCOPE_EXCEEDED");
        LicenceNumberRange south = active("SOUTH", "S", 1, 9);
        assertThat(code(() -> service.get(south.id(), CHECKER))).isEqualTo("SCOPE_EXCEEDED");
        assertThat(code(() -> service.report("SOUTH", CHECKER))).isEqualTo("SCOPE_EXCEEDED");
        assertThat(service.list(null, CHECKER)).isEmpty();
    }

    @Test
    @DisplayName("ranges are validated: bounds, width, prefix and region")
    void validation() {
        assertThat(code(() -> new LicenceNumberRange.Spec("NORTH", "N", 6, 0, 9))).isEqualTo("NUMBER_RANGE_INVALID");
        assertThat(code(() -> new LicenceNumberRange.Spec("NORTH", "N", 6, 9, 1))).isEqualTo("NUMBER_RANGE_INVALID");
        assertThat(code(() -> new LicenceNumberRange.Spec("NORTH", "N", 2, 1, 999))).isEqualTo("NUMBER_WIDTH_INVALID");
        assertThat(code(() -> new LicenceNumberRange.Spec("NORTH", "n-1", 6, 1, 9))).isEqualTo("FIELD_INVALID");
        assertThat(code(() -> new LicenceNumberRange.Spec("north", "N", 6, 1, 9))).isEqualTo("FIELD_INVALID");
        assertThat(new LicenceNumberRange.Spec("NORTH", null, 4, 1, 9).display(7)).isEqualTo("0007");
    }

    /** In-memory ranges. */
    private static final class Ranges implements LicenceNumberRepository {
        private final Map<UUID, LicenceNumberRange> rows = new LinkedHashMap<>();
        private final List<UsedNumber> used = new ArrayList<>();

        @Override
        public void lockPrefix(String prefix) {
        }

        @Override
        public void lockRegion(String region) {
        }

        @Override
        public void insert(LicenceNumberRange range) {
            rows.put(range.id(), range);
        }

        @Override
        public Optional<LicenceNumberRange> find(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<LicenceNumberRange> findForUpdate(UUID id) {
            return find(id);
        }

        @Override
        public List<LicenceNumberRange> openWithPrefix(String prefix) {
            return rows.values().stream().filter(r -> r.spec().prefix().equals(prefix)
                    && r.status() != LicenceNumberRange.Status.REJECTED).toList();
        }

        @Override
        public List<LicenceNumberRange> byRegion(String region) {
            return rows.values().stream().filter(r -> region == null || r.spec().region().equals(region)).toList();
        }

        @Override
        public List<LicenceNumberRange> activeInRegion(String region) {
            return rows.values().stream().filter(r -> r.spec().region().equals(region) && r.hasNumbersLeft())
                    .sorted(Comparator.comparing((LicenceNumberRange r) -> r.decided().at())
                            .thenComparing(r -> r.spec().firstNumber()))
                    .toList();
        }

        @Override
        public void saveDecision(LicenceNumberRange range) {
        }

        @Override
        public void saveNext(LicenceNumberRange range) {
        }

        @Override
        public void insertUsed(UsedNumber number) {
            used.add(number);
        }

        @Override
        public List<UsedNumber> usedInRange(UUID rangeId, int offset, int limit) {
            return used.stream().filter(u -> u.rangeId().equals(rangeId)).skip(offset).limit(limit).toList();
        }
    }
}
