package gov.itms.violation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("VF-046")
class ViolationPledgeTest {

    private static Instant at(long offsetMinutes) {
        return Instant.parse("2026-01-01T00:00:00Z").plusSeconds(60 * offsetMinutes);
    }

    private static ViolationPledge pledge(String signedBy, Instant signedAt, String note, List<String> documentIds) {
        return new ViolationPledge(UUID.randomUUID(), UUID.randomUUID(), "V-2026-000000001", signedBy, signedAt,
                note, documentIds, "u-officer", at(5));
    }

    @Test
    void aPledgeRecordsWhoSignedAndWhen() {
        ViolationPledge p = pledge("Ahmed Khan", at(4), " undertakes to appear ", List.of("DOC-1", "DOC-2"));
        assertThat(p.documentIds()).containsExactly("DOC-1", "DOC-2");
        assertThat(p.signedAt()).isEqualTo(at(4));
        assertThat(p.recordedAt()).isEqualTo(at(5));
        // a blank note is dropped
        assertThat(pledge("Ahmed Khan", at(4), "   ", List.of()).note()).isNull();
        assertThat(pledge("Ahmed Khan", at(4), null, List.of()).note()).isNull();
    }

    @Test
    void pledgeDetailsAreChecked() {
        assertThatThrownBy(() -> pledge(" ", at(4), null, List.of())).isInstanceOf(DomainException.class)
                .extracting("code", "kind").containsExactly("PLEDGE_INVALID", DomainException.Kind.VALIDATION);
        assertThatThrownBy(() -> pledge("u".repeat(201), at(4), null, List.of()))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("PLEDGE_INVALID");
        assertThatThrownBy(() -> pledge("Ahmed Khan", at(4), "x".repeat(501), List.of()))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("PLEDGE_INVALID");
        assertThatThrownBy(() -> pledge("Ahmed Khan", at(4), null, List.of("bad id")))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("PLEDGE_INVALID");
        assertThatThrownBy(() -> pledge("Ahmed Khan", at(4), null, Collections.nCopies(21, "DOC-1")))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("PLEDGE_INVALID");
    }
}
