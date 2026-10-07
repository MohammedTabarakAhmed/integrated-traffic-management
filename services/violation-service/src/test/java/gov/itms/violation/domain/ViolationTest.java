package gov.itms.violation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("VF-014")
public class ViolationTest {

    static final Instant AT = Instant.parse("2026-10-06T08:00:00Z");

    public static Violation.Report report(String code, String owner, String driver, Integer limit, Integer measured) {
        return new Violation.Report(code, AT, "NORTH", "Main road km 12", "A 1234", null, owner, driver, limit,
                measured, Violation.Source.OFFICER, null, List.of("doc-1"));
    }

    private static Violation open() {
        return new Violation(UUID.randomUUID(), "V-2026-000000001", report("RED_LIGHT", "TF-OWNER", null, null, null),
                "RED_LIGHT", 1, 80_000, 6, false, "u-officer", AT, Violation.Status.OPEN, null, null, null);
    }

    @Test
    @DisplayName("VF-014: the identified driver is responsible; otherwise the registered owner")
    void responsibleParty() {
        assertThat(report("X", "TF-OWNER", "TF-DRIVER", null, null).responsibleFileNumber()).isEqualTo("TF-DRIVER");
        assertThat(report("X", "TF-OWNER", null, null, null).responsibleFileNumber()).isEqualTo("TF-OWNER");
        assertThat(report("X", null, "TF-DRIVER", null, null).responsibleFileNumber()).isEqualTo("TF-DRIVER");
        assertThatThrownBy(() -> report("X", null, null, null, null)).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("RESPONSIBLE_UNKNOWN");
    }

    @Test
    @Tag("VF-004")
    @DisplayName("VF-004, VF-014: speed over the limit is measured minus limit, never negative; reports are validated")
    void reportValidation() {
        assertThat(report("X", "TF-1", null, 80, 125).overKmh()).isEqualTo(45);
        assertThat(report("X", "TF-1", null, 80, 70).overKmh()).isZero();
        assertThat(report("X", "TF-1", null, null, null).overKmh()).isNull();
        assertThatThrownBy(() -> report("X", "TF-1", null, 80, null)).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("FIELD_INVALID");
        assertThatThrownBy(() -> new Violation.Report("X", AT, "NORTH", "road", null, null, "TF-1", null, null, null,
                Violation.Source.OFFICER, null, List.of())).as("no vehicle and no driver")
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("SUBJECT_REQUIRED");
        assertThatThrownBy(() -> new Violation.Report("X", AT, "NORTH", "road", "A 1", null, "TF-1", null, null, null,
                Violation.Source.DEVICE, null, List.of())).as("a device report names its device")
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("FIELD_INVALID");
        assertThatThrownBy(() -> new Violation.Report("X", AT, "NORTH", "road", "A 1", "1M8GDM9AXKP04278I", "TF-1",
                null, null, null, Violation.Source.OFFICER, null, List.of())).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("FIELD_INVALID");
        assertThatThrownBy(() -> new Violation.Report("X", AT, "NORTH", " ", "A 1", null, "TF-1", null, null, null,
                Violation.Source.OFFICER, null, List.of())).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("FIELD_INVALID");
    }

    @Test
    @Tag("VF-020")
    @Tag("VF-021")
    @DisplayName("VF-020, VF-021: OPEN → PAID → OPEN on receipt cancellation; cancel only while OPEN")
    void transitions() {
        Violation v = open();
        v.paid(AT.plusSeconds(60));
        assertThat(v.status()).isEqualTo(Violation.Status.PAID);
        assertThat(v.closedAt()).isEqualTo(AT.plusSeconds(60));
        assertThatThrownBy(() -> v.paid(AT)).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("VIOLATION_CLOSED");
        assertThatThrownBy(() -> v.cancel("DUPLICATE", AT)).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("VIOLATION_CLOSED");
        v.reopened();
        assertThat(v.status()).isEqualTo(Violation.Status.OPEN);
        assertThat(v.closedAt()).isNull();
        assertThatThrownBy(v::reopened).isInstanceOf(IllegalStateException.class);
        v.cancel("DUPLICATE", AT.plusSeconds(120));
        assertThat(v.status()).isEqualTo(Violation.Status.CANCELLED);
        assertThat(v.cancelReason()).isEqualTo("DUPLICATE");
    }
}
