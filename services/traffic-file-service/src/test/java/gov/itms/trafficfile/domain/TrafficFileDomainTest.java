package gov.itms.trafficfile.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Traffic file rules (DL-001, DL-002, DL-005, DL-012). */
@Tag("DL-001")
class TrafficFileDomainTest {

    private static final Instant NOW = Instant.parse("2026-10-06T08:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);
    private static final Actor ACTOR = new Actor("u-1", "officer");

    private static Identity passportIdentity(LocalDate birthDate) {
        return new Identity(new Identity.Passport("AB12345", "GBR"), new PersonName(null, null, "Jane", "Doe"), "GBR",
                birthDate, "FEMALE", null, null);
    }

    private static TrafficFile visitor() {
        return TrafficFile.openIndividual(UUID.randomUUID(), "TF0000000001",
                new TrafficFile.Classification("INDIVIDUAL", "VISITOR"), "DXB", passportIdentity(LocalDate.of(1990, 1, 1)),
                TrafficFile.IdentitySource.DOCUMENT, null, null, ACTOR, Channel.BACKOFFICE, NOW, TODAY);
    }

    @Test
    @DisplayName("a name needs a complete pair in at least one language; parts are stripped")
    void names() {
        assertThat(new PersonName(" أحمد ", "المنصوري", null, null).givenNl()).isEqualTo("أحمد");
        assertThat(new PersonName(null, null, "A", "B").givenNl()).isNull();
        assertThatThrownBy(() -> new PersonName("أحمد", null, "Ahmed", null))
                .isInstanceOf(DomainException.class).hasFieldOrPropertyWithValue("code", "NAME_REQUIRED");
        assertThatThrownBy(() -> new PersonName(null, null, "A".repeat(101), "B"))
                .hasFieldOrPropertyWithValue("code", "FIELD_INVALID");
        assertThatThrownBy(() -> new PersonName(null, null, "A\u0000", "B"))
                .hasFieldOrPropertyWithValue("code", "FIELD_INVALID");
    }

    @Test
    @DisplayName("identity codes are checked; residency expiry needs a status")
    void identity() {
        assertThatThrownBy(() -> new Identity(new Identity.Passport("AB12345", "GB"), new PersonName(null, null, "A", "B"),
                "GBR", LocalDate.of(1990, 1, 1), "FEMALE", null, null)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new Identity(new Identity.NationalId("1234567"), new PersonName(null, null, "A", "B"),
                "GBR", LocalDate.of(1990, 1, 1), "FEMALE", null, LocalDate.of(2030, 1, 1)))
                .hasFieldOrPropertyWithValue("code", "FIELD_INVALID");
        assertThatThrownBy(() -> new Identity(new Identity.NationalId("1234567"), new PersonName(null, null, "A", "B"),
                "GBR", null, "FEMALE", null, null)).hasFieldOrPropertyWithValue("code", "FIELD_INVALID");
    }

    @Test
    @Tag("DL-005")
    @DisplayName("profile fields: contact formats, codes, no repeated list entries, blanks are absent")
    void profile() {
        Profile p = new Profile("+971501234567", "a@b.ae", " Street 1 ", "O_POS", "", null, List.of("AR", "EN"),
                null, null, "VISUAL");
        assertThat(p.address()).isEqualTo("Street 1");
        assertThat(p.educationLevel()).isNull();
        assertThat(p.physicalConditions()).isEmpty();
        assertThatThrownBy(() -> new Profile("0501234567", null, null, null, null, null, null, null, null, null))
                .hasFieldOrPropertyWithValue("code", "FIELD_INVALID");
        assertThatThrownBy(() -> new Profile(null, "not-an-email", null, null, null, null, null, null, null, null))
                .hasFieldOrPropertyWithValue("code", "FIELD_INVALID");
        assertThatThrownBy(() -> new Profile(null, null, null, null, null, null, List.of("AR", "AR"), null, null, null))
                .hasFieldOrPropertyWithValue("code", "LIST_DUPLICATES");
        assertThatThrownBy(() -> new Profile(null, null, null, "o+", null, null, null, null, null, null))
                .hasFieldOrPropertyWithValue("code", "FIELD_INVALID");
    }

    @Test
    @DisplayName("a file opens ACTIVE at version 1; the profile change moves the version and records who and how")
    void lifecycle() {
        TrafficFile file = visitor();
        assertThat(file.status()).isEqualTo(TrafficFile.Status.ACTIVE);
        assertThat(file.version()).isEqualTo(1);
        assertThat(file.profile()).isEqualTo(Profile.EMPTY);
        Actor other = new Actor("u-2", "other");
        file.updateProfile(new Profile("+971501234567", null, null, null, null, null, null, null, null, null), other,
                Channel.CALLCENTRE, NOW.plusSeconds(60));
        assertThat(file.version()).isEqualTo(2);
        assertThat(file.lastChange().actor()).isEqualTo(other);
        assertThat(file.lastChange().channel()).isEqualTo(Channel.CALLCENTRE);
        assertThat(file.opened().actor()).isEqualTo(ACTOR);
    }

    @Test
    @DisplayName("only an ACTIVE file can be changed")
    void inactiveFile() {
        TrafficFile merged = TrafficFile.restore(UUID.randomUUID(), "TF0000000002", TrafficFile.Kind.INDIVIDUAL,
                new TrafficFile.Classification("INDIVIDUAL", "VISITOR"), "DXB", passportIdentity(LocalDate.of(1990, 1, 1)),
                TrafficFile.IdentitySource.DOCUMENT, null, new TrafficFile.Change(ACTOR, Channel.BACKOFFICE, NOW),
                TrafficFile.Status.MERGED, Profile.EMPTY, null, new TrafficFile.Change(ACTOR, Channel.BACKOFFICE, NOW),
                3);
        assertThatThrownBy(() -> merged.updateProfile(Profile.EMPTY, ACTOR, Channel.BACKOFFICE, NOW))
                .hasFieldOrPropertyWithValue("code", "FILE_NOT_ACTIVE");
    }

    @Test
    @DisplayName("no birth date in the future; register identities are national ids with a sync time")
    void openingRules() {
        assertThatThrownBy(() -> TrafficFile.openIndividual(UUID.randomUUID(), "TF1",
                new TrafficFile.Classification("INDIVIDUAL", "VISITOR"), "DXB", passportIdentity(TODAY.plusDays(1)),
                TrafficFile.IdentitySource.DOCUMENT, null, null, ACTOR, Channel.BACKOFFICE, NOW, TODAY))
                .hasFieldOrPropertyWithValue("code", "BIRTH_DATE_IN_FUTURE");
        assertThatThrownBy(() -> TrafficFile.openIndividual(UUID.randomUUID(), "TF1",
                new TrafficFile.Classification("INDIVIDUAL", "VISITOR"), "DXB", passportIdentity(TODAY),
                TrafficFile.IdentitySource.REGISTER, NOW, null, ACTOR, Channel.BACKOFFICE, NOW, TODAY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrafficFile.Classification("individual", "X"))
                .hasFieldOrPropertyWithValue("code", "FIELD_INVALID");
    }
}
