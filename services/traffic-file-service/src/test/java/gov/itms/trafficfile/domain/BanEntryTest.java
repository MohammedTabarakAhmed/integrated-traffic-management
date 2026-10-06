package gov.itms.trafficfile.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Ban list entries (DL-013). */
@Tag("DL-013")
class BanEntryTest {

    private static final Instant NOW = Instant.parse("2026-10-06T08:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);
    private static final Actor ACTOR = new Actor("u-1", "officer");
    private static final Identity.IdentityDocument PERSON = new Identity.NationalId("784199012345671");

    private static BanEntry place(LocalDate start, LocalDate end) {
        return BanEntry.place(UUID.randomUUID(), new BanEntry.Spec(PERSON, "court order 12/2026", "COURT", "CO-12",
                start, end), null, ACTOR, Channel.BACKOFFICE, NOW, TODAY);
    }

    @Test
    @DisplayName("a ban starts today by default and is in force until lifted or past its end date")
    void lifecycle() {
        BanEntry ban = place(null, TODAY.plusDays(10));
        assertThat(ban.startDate()).isEqualTo(TODAY);
        assertThat(ban.inForce(TODAY)).isTrue();
        assertThat(ban.inForce(TODAY.plusDays(10))).as("the end date is the last day").isTrue();
        assertThat(ban.status(TODAY.plusDays(11))).isEqualTo(BanEntry.Status.EXPIRED);
        assertThat(ban.inForce(TODAY.plusDays(11))).isFalse();
        ban.lift(ACTOR, Channel.BACKOFFICE, "order revoked", NOW.plusSeconds(60), TODAY);
        assertThat(ban.status(TODAY)).isEqualTo(BanEntry.Status.LIFTED);
        assertThat(ban.version()).isEqualTo(2);
        assertThat(ban.lifted().reason()).isEqualTo("order revoked");
        assertThatThrownBy(() -> ban.lift(ACTOR, Channel.BACKOFFICE, "again", NOW, TODAY))
                .hasFieldOrPropertyWithValue("code", "BAN_NOT_ACTIVE");
    }

    @Test
    @DisplayName("a future start is not yet in force")
    void futureStart() {
        BanEntry ban = place(TODAY.plusDays(3), null);
        assertThat(ban.status(TODAY)).isEqualTo(BanEntry.Status.ACTIVE);
        assertThat(ban.inForce(TODAY)).isFalse();
        assertThat(ban.inForce(TODAY.plusDays(3))).isTrue();
    }

    @Test
    @DisplayName("dates, reason, source and reference are checked")
    void validation() {
        assertThatThrownBy(() -> place(TODAY, TODAY.minusDays(1)))
                .hasFieldOrPropertyWithValue("code", "BAN_DATES_INVALID");
        assertThatThrownBy(() -> place(TODAY.minusDays(10), TODAY.minusDays(1)))
                .hasFieldOrPropertyWithValue("code", "BAN_END_IN_PAST");
        assertThatThrownBy(() -> new BanEntry.Spec(PERSON, "no", "COURT", null, null, null))
                .hasFieldOrPropertyWithValue("code", "REASON_INVALID");
        assertThatThrownBy(() -> new BanEntry.Spec(PERSON, "valid reason", "court", null, null, null))
                .hasFieldOrPropertyWithValue("code", "FIELD_INVALID");
        assertThatThrownBy(() -> new BanEntry.Spec(PERSON, "valid reason", "COURT", "bad ref", null, null))
                .hasFieldOrPropertyWithValue("code", "FIELD_INVALID");
        BanEntry ban = place(null, null);
        assertThatThrownBy(() -> ban.lift(ACTOR, Channel.BACKOFFICE, " ", NOW, TODAY))
                .hasFieldOrPropertyWithValue("code", "REASON_INVALID");
    }
}
