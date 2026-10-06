package gov.itms.trafficfile.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Company files (DL-003, DL-004). */
@Tag("DL-003")
class CompanyTest {

    private static final Instant NOW = Instant.parse("2026-10-06T08:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);
    private static final Actor ACTOR = new Actor("u-1", "officer");
    private static final Company.Licence LICENCE = new Company.Licence("DXB_DED", "CN-1001234");

    private static Company company(LocalDate expiry, List<String> signatories, List<String> fleetManagers) {
        return new Company(LICENCE, "شركة", "Company LLC", List.of("LAND_TRANSPORT"), expiry, signatories,
                fleetManagers);
    }

    private static TrafficFile open(LocalDate expiry) {
        return TrafficFile.openCompany(UUID.randomUUID(), "10000000017",
                new TrafficFile.Classification("COMPANY", "LLC"), "DXB",
                company(expiry, List.of("10000000025"), List.of()), NOW, ACTOR, Channel.BACKOFFICE, NOW, TODAY);
    }

    @Test
    @Tag("DL-004")
    @DisplayName("a company needs a name, an expiry and at least one signatory; file numbers are checked")
    void companyRules() {
        assertThatThrownBy(() -> new Company(LICENCE, " ", null, List.of(), TODAY, List.of("1"), List.of()))
                .hasFieldOrPropertyWithValue("code", "NAME_REQUIRED");
        assertThatThrownBy(() -> company(null, List.of("1"), List.of()))
                .hasFieldOrPropertyWithValue("code", "FIELD_INVALID");
        assertThatThrownBy(() -> company(TODAY, List.of(), List.of()))
                .hasFieldOrPropertyWithValue("code", "SIGNATORY_REQUIRED");
        assertThatThrownBy(() -> company(TODAY, List.of("1", "1"), List.of()))
                .hasFieldOrPropertyWithValue("code", "LIST_DUPLICATES");
        assertThatThrownBy(() -> company(TODAY, List.of("bad number"), List.of()))
                .hasFieldOrPropertyWithValue("code", "FIELD_INVALID");
        assertThatThrownBy(() -> company(TODAY, Collections.nCopies(21, "1"), List.of()))
                .hasFieldOrPropertyWithValue("code", "FIELD_INVALID");
        assertThatThrownBy(() -> new Company.Licence("DXB_DED", "CN 1"))
                .hasFieldOrPropertyWithValue("code", "FIELD_INVALID");
    }

    @Test
    @DisplayName("representatives lists every named file once")
    void representatives() {
        Company c = company(TODAY, List.of("1", "2"), List.of("2", "3"));
        assertThat(c.representatives()).containsExactly("1", "2", "3");
    }

    @Test
    @DisplayName("an expired licence cannot open a file; one expiring today can")
    void expiredLicence() {
        assertThatThrownBy(() -> open(TODAY.minusDays(1)))
                .hasFieldOrPropertyWithValue("code", "COMPANY_LICENCE_EXPIRED");
        TrafficFile file = open(TODAY);
        assertThat(file.kind()).isEqualTo(TrafficFile.Kind.COMPANY);
        assertThat(file.identity()).isNull();
        assertThat(file.profile()).isNull();
        assertThat(file.identitySource()).isEqualTo(TrafficFile.IdentitySource.REGISTER);
    }

    @Test
    @Tag("DL-004")
    @DisplayName("representatives are replaced on a company file, keeping the licence; never on an individual's")
    void updateRepresentatives() {
        TrafficFile file = open(TODAY.plusYears(1));
        file.updateRepresentatives(List.of("10000000033"), List.of("10000000041"), ACTOR, Channel.BACKOFFICE,
                NOW.plusSeconds(5));
        assertThat(file.version()).isEqualTo(2);
        assertThat(file.company().signatories()).containsExactly("10000000033");
        assertThat(file.company().licence()).isEqualTo(LICENCE);
        assertThatThrownBy(() -> file.updateProfile(Profile.EMPTY, ACTOR, Channel.BACKOFFICE, NOW))
                .hasFieldOrPropertyWithValue("code", "WRONG_FILE_KIND");
    }
}
