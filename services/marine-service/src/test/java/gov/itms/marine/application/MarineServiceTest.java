package gov.itms.marine.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import gov.itms.marine.domain.DomainException;
import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("RE-030")
class MarineServiceTest {

    private final Map<String, MarineService.Craft> crafts = new HashMap<>();
    private final Map<String, String> lastRenewal = new HashMap<>();
    private final MarineService marine = new MarineService(new Ports.MarineRepository() {
        @Override
        public void insertCraft(MarineService.Craft craft, String actor, Instant at) {
            crafts.put(craft.hullId(), craft);
        }

        @Override
        public Optional<MarineService.Craft> craft(String hullId) {
            return Optional.ofNullable(crafts.get(hullId));
        }

        @Override
        public void insertInspection(MarineService.Inspection i, String actor) {
            if ("RENEWAL".equals(i.purpose())) {
                lastRenewal.put(i.hullId(), i.result());
            }
        }

        @Override
        public Optional<String> latestRenewalResult(String hullId) {
            return Optional.ofNullable(lastRenewal.get(hullId));
        }

        @Override
        public void insertLicence(MarineService.OperatorLicence licence, String actor) {
        }

        @Override
        public void transfer(String hullId, String owner, Instant at) {
            MarineService.Craft c = crafts.get(hullId);
            crafts.put(hullId, new MarineService.Craft(c.id(), c.hullId(), c.type(), c.lengthMetres(),
                    c.engineCount(), c.capacity(), c.homePort(), owner, c.status()));
        }

        @Override
        public void renew(String hullId, Instant expiresAt, Instant at) {
        }
    }, mock(AuditRecorder.class), mock(DomainEventRecorder.class),
            Clock.fixed(Instant.parse("2026-10-10T08:00:00Z"), ZoneOffset.UTC));

    @Test
    void registrationRules() {
        assertThatThrownBy(() -> marine.register("H", "T", 0, 1, 1, "P", "O", "u")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> marine.register("H", "T", 5, 11, 1, "P", "O", "u")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> marine.register(" ", "T", 5, 1, 1, "P", "O", "u")).isInstanceOf(DomainException.class);
        assertThat(marine.register("H1", "T", 5, 1, 1, "P", "O", "u").status()).isEqualTo("ACTIVE");
    }

    @Test
    @Tag("RE-031")
    void inspectionAndRenewal() {
        marine.register("H2", "T", 5, 1, 1, "P", "O", "u");
        assertThatThrownBy(() -> marine.inspect("H2", "PAINT", "PASS", "W", null, "u"))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> marine.inspect("NONE", "SAFETY", "PASS", "W", null, "u"))
                .satisfies(e -> assertThat(((DomainException) e).kind()).isEqualTo(DomainException.Kind.NOT_FOUND));
        assertThatThrownBy(() -> marine.renew("H2", 365, "u")).isInstanceOf(DomainException.class);
        marine.inspect("H2", "RENEWAL", "PASS", "W", "D", "u");
        assertThatThrownBy(() -> marine.renew("H2", 0, "u")).isInstanceOf(DomainException.class);
        assertThat(marine.renew("H2", 365, "u").hullId()).isEqualTo("H2");
        assertThat(marine.transfer("H2", "O2", "u").owner()).isEqualTo("O2");
    }

    @Test
    @Tag("RE-032")
    void operatorLicence() {
        assertThatThrownBy(() -> marine.issueLicence("F", "T", 0, "u")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> marine.issueLicence(null, "T", 10, "u")).isInstanceOf(DomainException.class);
        MarineService.OperatorLicence l = marine.issueLicence("F", "T", 10, "u");
        assertThat(l.expiresAt()).isEqualTo(Instant.parse("2026-10-20T08:00:00Z"));
        assertThat(l.number()).startsWith("MOL-2026-");
    }
}
