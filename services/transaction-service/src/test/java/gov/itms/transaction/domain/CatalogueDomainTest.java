package gov.itms.transaction.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class CatalogueDomainTest {

    private static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 5);
    private static final Actor MAKER = new Actor("u-maker", "maker");
    private static final Actor CHECKER = new Actor("u-checker", "checker");
    private static final BilingualName NAME = new BilingualName("تجديد ترخيص المركبة", "Vehicle licence renewal");

    static ServiceSpec spec(List<String> regions, List<Channel> channels) {
        return new ServiceSpec("VL_RENEW", NAME, "VEHICLE", List.of("INDIVIDUAL", "COMPANY"), channels,
                List.of("INSURANCE_CERT"), "VL_RENEW_FEE", null, null, "P5D", regions, true, false);
    }

    static ServiceSpec spec() {
        return spec(List.of("*"), List.of(Channel.PORTAL, Channel.BACKOFFICE));
    }

    private static Consumer<Throwable> code(String expected) {
        return t -> assertThat(((DomainException) t).code()).isEqualTo(expected);
    }

    private static AvailabilityRule rule(String region, Channel channel, boolean enabled, LocalDate from,
                                         LocalDate to) {
        return AvailabilityRule.add("VL_RENEW", new AvailabilityRuleSpec(region, channel, enabled, from, to),
                UUID.randomUUID());
    }

    // ------------------------------------------------------------------ service definition (CC-020)

    @Test
    @Tag("CC-020")
    @DisplayName("CC-020: a service definition records every attribute the BRD lists")
    void definitionRecordsEveryAttribute() {
        ServiceSpec s = new ServiceSpec("DL_ISSUE", NAME, "LICENSING", List.of("INDIVIDUAL"),
                List.of(Channel.BACKOFFICE), List.of("EYE_TEST", "PHOTO"), "DL_FEE", "DL_ELIGIBILITY", "DL_REVIEW",
                "PT4H", List.of("R1", "R2"), false, true);
        assertThat(s.code()).isEqualTo("DL_ISSUE");
        assertThat(s.name()).isEqualTo(NAME);
        assertThat(s.module()).isEqualTo("LICENSING");
        assertThat(s.customerTypes()).containsExactly("INDIVIDUAL");
        assertThat(s.channels()).containsExactly(Channel.BACKOFFICE);
        assertThat(s.requiredDocuments()).containsExactly("EYE_TEST", "PHOTO");
        assertThat(List.of(s.feeCode(), s.ruleSetCode(), s.workflowCode()))
                .containsExactly("DL_FEE", "DL_ELIGIBILITY", "DL_REVIEW");
        assertThat(s.slaDuration()).isEqualTo(Duration.ofHours(4));
        assertThat(s.regions()).containsExactly("R1", "R2");
        assertThat(s.anyRegion()).isFalse();
        assertThat(s.allowsConcurrentOpen()).isTrue();
        assertThat(s.offersRegion("R1")).isTrue();
        assertThat(s.offersRegion("R3")).isFalse();
        assertThat(spec().offersRegion("ANY")).isTrue();
    }

    @Test
    @Tag("CC-020")
    @Tag("DAT-004")
    @DisplayName("CC-020/DAT-004: invalid service definitions are refused with a specific code")
    void definitionValidation() {
        assertThatThrownBy(() -> new ServiceSpec("vl-renew", NAME, "VEHICLE", List.of("I"), List.of(Channel.APP),
                List.of(), null, null, null, "P1D", List.of("*"), true, false)).satisfies(code("INVALID_SERVICE_CODE"));
        assertThatThrownBy(() -> new ServiceSpec("A", NAME, "VEHICLE", List.of("I"), List.of(Channel.APP),
                List.of(), null, null, null, "P1D", List.of("*"), true, false)).satisfies(code("INVALID_SERVICE_CODE"));
        assertThatThrownBy(() -> new ServiceSpec("VL", null, "VEHICLE", List.of("I"), List.of(Channel.APP),
                List.of(), null, null, null, "P1D", List.of("*"), true, false)).satisfies(code("NAME_REQUIRED"));
        assertThatThrownBy(() -> new ServiceSpec("VL", NAME, "vehicle", List.of("I"), List.of(Channel.APP),
                List.of(), null, null, null, "P1D", List.of("*"), true, false)).satisfies(code("INVALID_CODE"));
        assertThatThrownBy(() -> new ServiceSpec("VL", NAME, "VEHICLE", List.of(), List.of(Channel.APP),
                List.of(), null, null, null, "P1D", List.of("*"), true, false)).satisfies(code("LIST_SIZE_INVALID"));
        assertThatThrownBy(() -> new ServiceSpec("VL", NAME, "VEHICLE", List.of("I", "I"), List.of(Channel.APP),
                List.of(), null, null, null, "P1D", List.of("*"), true, false)).satisfies(code("LIST_DUPLICATES"));
        List<Channel> withNull = new ArrayList<>(Collections.singletonList(null));
        assertThatThrownBy(() -> new ServiceSpec("VL", NAME, "VEHICLE", List.of("I"), withNull,
                List.of(), null, null, null, "P1D", List.of("*"), true, false)).satisfies(code("LIST_DUPLICATES"));
        assertThatThrownBy(() -> new ServiceSpec("VL", NAME, "VEHICLE", List.of("I"), List.of(Channel.APP),
                List.of("bad doc"), null, null, null, "P1D", List.of("*"), true, false))
                .satisfies(code("INVALID_CODE"));
        assertThatThrownBy(() -> new ServiceSpec("VL", NAME, "VEHICLE", List.of("I"), List.of(Channel.APP),
                List.of(), "fee", null, null, "P1D", List.of("*"), true, false)).satisfies(code("INVALID_CODE"));
        for (String sla : new String[] {"P0D", "PT0H", "P", "PT", "5D", "P1W", "P1Y", "P1DT", "PT1S", "", null}) {
            assertThatThrownBy(() -> new ServiceSpec("VL", NAME, "VEHICLE", List.of("I"), List.of(Channel.APP),
                    List.of(), null, null, null, sla, List.of("*"), true, false)).as(String.valueOf(sla))
                    .satisfies(code("SLA_INVALID"));
        }
        assertThat(new ServiceSpec("VL", NAME, "VEHICLE", List.of("I"), List.of(Channel.APP), List.of(), null, null,
                null, "P2DT3H30M", List.of("*"), true, false).slaDuration())
                .isEqualTo(Duration.ofDays(2).plusHours(3).plusMinutes(30));
        assertThatThrownBy(() -> new ServiceSpec("VL", NAME, "VEHICLE", List.of("I"), List.of(Channel.APP),
                List.of(), null, null, null, "P1D", List.of("*", "R1"), true, false))
                .satisfies(code("REGIONS_ALL_MIXED"));
        assertThatThrownBy(() -> new ServiceSpec("VL", NAME, "VEHICLE", List.of("I"), List.of(Channel.APP),
                List.of(), null, null, null, "P1D", List.of(), true, false)).satisfies(code("LIST_SIZE_INVALID"));
        assertThatThrownBy(() -> new ServiceSpec("VL", NAME, "VEHICLE", List.of("I"), List.of(Channel.APP),
                List.of(), null, null, null, "P1D", List.of("r1"), true, false)).satisfies(code("INVALID_CODE"));
    }

    @Test
    @Tag("CC-020")
    @Tag("DAT-002")
    @DisplayName("DAT-002: each update creates the next version; the code never changes")
    void definitionVersions() {
        ServiceDefinition v1 = ServiceDefinition.create(spec(), UUID.randomUUID(), CHECKER, NOW);
        assertThat(v1.version()).isEqualTo(1);
        ServiceDefinition v2 = v1.update(spec(List.of("R1"), List.of(Channel.PORTAL)), UUID.randomUUID(), CHECKER,
                NOW.plusSeconds(60));
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.spec().regions()).containsExactly("R1");
        ServiceSpec other = new ServiceSpec("OTHER", NAME, "VEHICLE", List.of("I"), List.of(Channel.APP), List.of(),
                null, null, null, "P1D", List.of("*"), true, false);
        assertThatThrownBy(() -> v2.update(other, UUID.randomUUID(), CHECKER, NOW))
                .satisfies(code("SERVICE_CODE_IMMUTABLE"));
        assertThatThrownBy(() -> new ServiceDefinition(spec(), 0, UUID.randomUUID(), NOW, CHECKER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ availability (CC-026)

    @Test
    @Tag("CC-026")
    @Tag("CC-112")
    @DisplayName("CC-026/CC-112: a service is available only where its definition offers it")
    void availabilityFollowsTheOffer() {
        ServiceSpec s = spec(List.of("R1"), List.of(Channel.PORTAL));
        assertThat(Availability.of(s, List.of(), "R1", Channel.PORTAL, TODAY))
                .extracting(Availability::available, Availability::reason)
                .containsExactly(true, Availability.Reason.OFFERED);
        assertThat(Availability.of(s, List.of(), "R2", Channel.PORTAL, TODAY).reason())
                .isEqualTo(Availability.Reason.NOT_OFFERED_IN_REGION);
        assertThat(Availability.of(s, List.of(), "R1", Channel.APP, TODAY).reason())
                .isEqualTo(Availability.Reason.NOT_OFFERED_ON_CHANNEL);
        // A rule never makes a service available where the definition does not offer it.
        AvailabilityRule enableAll = rule("*", null, true, TODAY, null);
        assertThat(Availability.of(s, List.of(enableAll), "R2", Channel.PORTAL, TODAY).available()).isFalse();
        assertThatThrownBy(() -> Availability.of(s, List.of(), "r1", Channel.PORTAL, TODAY))
                .satisfies(code("INVALID_CODE"));
        assertThatThrownBy(() -> Availability.of(s, List.of(), "R1", null, TODAY))
                .satisfies(code("AVAILABILITY_QUERY_INVALID"));
    }

    @Test
    @Tag("CC-026")
    @DisplayName("CC-026: the most specific rule in force decides, with effective dates")
    void mostSpecificRuleInForceDecides() {
        ServiceSpec s = spec(List.of("*"), List.of(Channel.PORTAL, Channel.KIOSK));
        AvailabilityRule offEverywhere = rule("*", null, false, TODAY.plusDays(10), TODAY.plusDays(20));
        AvailabilityRule kioskOff = rule("*", Channel.KIOSK, false, TODAY, null);
        AvailabilityRule r1On = rule("R1", null, true, TODAY.plusDays(10), TODAY.plusDays(15));
        AvailabilityRule r1KioskOn = rule("R1", Channel.KIOSK, true, TODAY, TODAY.plusDays(3));
        List<AvailabilityRule> rules = List.of(offEverywhere, kioskOff, r1On, r1KioskOn);

        // Today: kiosks are off everywhere except in R1 (region + channel beats channel only).
        assertThat(Availability.of(s, rules, "R2", Channel.KIOSK, TODAY))
                .extracting(Availability::available, Availability::reason, Availability::ruleId)
                .containsExactly(false, Availability.Reason.DISABLED_BY_RULE, kioskOff.id());
        assertThat(Availability.of(s, rules, "R1", Channel.KIOSK, TODAY))
                .extracting(Availability::available, Availability::reason, Availability::ruleId)
                .containsExactly(true, Availability.Reason.ENABLED_BY_RULE, r1KioskOn.id());
        assertThat(Availability.of(s, rules, "R2", Channel.PORTAL, TODAY).reason())
                .isEqualTo(Availability.Reason.OFFERED);
        // Day 3 is the last day of r1KioskOn (inclusive); day 4 falls back to kioskOff.
        assertThat(Availability.of(s, rules, "R1", Channel.KIOSK, TODAY.plusDays(3)).available()).isTrue();
        assertThat(Availability.of(s, rules, "R1", Channel.KIOSK, TODAY.plusDays(4)).available()).isFalse();
        // Days 10-20: off everywhere, but R1 (region beats all) is on for days 10-15.
        assertThat(Availability.of(s, rules, "R2", Channel.PORTAL, TODAY.plusDays(12)).available()).isFalse();
        assertThat(Availability.of(s, rules, "R1", Channel.PORTAL, TODAY.plusDays(12)).available()).isTrue();
        assertThat(Availability.of(s, rules, "R1", Channel.PORTAL, TODAY.plusDays(16)).available()).isFalse();
        assertThat(Availability.of(s, rules, "R1", Channel.PORTAL, TODAY.plusDays(21)).available()).isTrue();
        // Region only (2) beats channel only (1): R1 + KIOSK on day 12 -> r1On (enabled), not kioskOff.
        assertThat(Availability.of(s, rules, "R1", Channel.KIOSK, TODAY.plusDays(12)).ruleId()).isEqualTo(r1On.id());
        // Rules of other services are ignored.
        AvailabilityRule otherService = AvailabilityRule.add("OTHER",
                new AvailabilityRuleSpec("*", null, false, TODAY, null), UUID.randomUUID());
        assertThat(Availability.of(s, List.of(otherService), "R2", Channel.PORTAL, TODAY).available()).isTrue();
    }

    @Test
    @Tag("CC-026")
    @DisplayName("CC-026: rule dates, overlap and ending")
    void ruleRules() {
        assertThatThrownBy(() -> new AvailabilityRuleSpec("*", null, true, null, null))
                .satisfies(code("RULE_START_REQUIRED"));
        assertThatThrownBy(() -> new AvailabilityRuleSpec("*", null, true, TODAY, TODAY.minusDays(1)))
                .satisfies(code("RULE_DATES_INVALID"));
        assertThatThrownBy(() -> new AvailabilityRuleSpec("r1", null, true, TODAY, null))
                .satisfies(code("INVALID_CODE"));
        AvailabilityRuleSpec a = new AvailabilityRuleSpec("R1", Channel.APP, false, TODAY, TODAY.plusDays(5));
        assertThat(a.overlaps(new AvailabilityRuleSpec("R1", Channel.APP, true, TODAY.plusDays(5), null))).isTrue();
        assertThat(a.overlaps(new AvailabilityRuleSpec("R1", Channel.APP, true, TODAY.plusDays(6), null))).isFalse();
        assertThat(a.overlaps(new AvailabilityRuleSpec("R1", null, true, TODAY, null))).isFalse();
        assertThat(a.overlaps(new AvailabilityRuleSpec("R2", Channel.APP, true, TODAY, null))).isFalse();
        assertThat(new AvailabilityRuleSpec("R1", Channel.APP, true, TODAY, null)
                .overlaps(new AvailabilityRuleSpec("R1", Channel.APP, true, TODAY.plusYears(5), null))).isTrue();
        assertThat(List.of(a.specificity(), new AvailabilityRuleSpec("R1", null, true, TODAY, null).specificity(),
                new AvailabilityRuleSpec("*", Channel.APP, true, TODAY, null).specificity(),
                new AvailabilityRuleSpec("*", null, true, TODAY, null).specificity())).containsExactly(3, 2, 1, 0);

        AvailabilityRule open = rule("R1", null, false, TODAY.minusDays(10), null);
        assertThatThrownBy(() -> open.end(TODAY.minusDays(1), UUID.randomUUID(), TODAY))
                .satisfies(code("RULE_END_IN_PAST"));
        assertThatThrownBy(() -> open.end(TODAY.minusDays(11), UUID.randomUUID(), TODAY))
                .satisfies(code("RULE_END_INVALID"));
        UUID change = UUID.randomUUID();
        open.end(TODAY.plusDays(2), change, TODAY);
        assertThat(open.spec().effectiveTo()).isEqualTo(TODAY.plusDays(2));
        assertThat(open.endedByChange()).isEqualTo(change);
        assertThatThrownBy(() -> open.end(TODAY.plusDays(2), UUID.randomUUID(), TODAY))
                .satisfies(code("RULE_END_NOT_EARLIER"));
        open.end(TODAY, UUID.randomUUID(), TODAY);
        assertThat(open.spec().effectiveTo()).isEqualTo(TODAY);
        assertThatThrownBy(() -> open.end(null, UUID.randomUUID(), TODAY)).satisfies(code("RULE_END_INVALID"));
    }

    // ------------------------------------------------------------------ maker-checker (CC-114, CC-002)

    @Test
    @Tag("CC-114")
    @Tag("CC-002")
    @DisplayName("CC-114/CC-002: a change is decided once, never by its maker; a rejection needs a reason")
    void changeDecisions() {
        CatalogueChange c = CatalogueChange.submit(CatalogueChange.Type.CREATE_SERVICE, "VL_RENEW", spec(), null,
                null, null, null, "new service", MAKER, NOW);
        assertThat(c.status()).isEqualTo(CatalogueChange.Status.PENDING_APPROVAL);
        assertThatThrownBy(() -> c.approve(new Actor("u-maker", "same person"), NOW))
                .satisfies(code("SEGREGATION_OF_DUTIES"));
        assertThatThrownBy(() -> c.reject(CHECKER, " x ", NOW)).satisfies(code("REASON_REQUIRED"));
        c.approve(CHECKER, NOW);
        assertThat(c.status()).isEqualTo(CatalogueChange.Status.APPROVED);
        assertThat(c.checker()).isEqualTo(CHECKER);
        assertThatThrownBy(() -> c.reject(CHECKER, "too late", NOW)).satisfies(code("CHANGE_NOT_PENDING"));

        CatalogueChange r = CatalogueChange.submit(CatalogueChange.Type.CREATE_SERVICE, "VL_RENEW", spec(), null,
                null, null, null, "new service", MAKER, NOW);
        r.reject(CHECKER, "  not needed  ", NOW);
        assertThat(r.decisionReason()).isEqualTo("not needed");
        assertThat(r.status()).isEqualTo(CatalogueChange.Status.REJECTED);
    }

    @Test
    @Tag("CC-114")
    @DisplayName("CC-114: each change type carries exactly its own content")
    void changeShapes() {
        AvailabilityRuleSpec rule = new AvailabilityRuleSpec("*", null, false, TODAY, null);
        UUID id = UUID.randomUUID();
        assertThat(CatalogueChange.submit(CatalogueChange.Type.UPDATE_SERVICE, "VL_RENEW", spec(), 3L, null, null,
                null, "update", MAKER, NOW).baseVersion()).isEqualTo(3L);
        assertThat(CatalogueChange.submit(CatalogueChange.Type.ADD_AVAILABILITY_RULE, "VL_RENEW", null, null, rule,
                null, null, "rule", MAKER, NOW).rule()).isEqualTo(rule);
        assertThat(CatalogueChange.submit(CatalogueChange.Type.END_AVAILABILITY_RULE, "VL_RENEW", null, null, null,
                id, TODAY, "end", MAKER, NOW).ruleId()).isEqualTo(id);

        assertThatThrownBy(() -> CatalogueChange.submit(CatalogueChange.Type.CREATE_SERVICE, "VL_RENEW", null, null,
                null, null, null, "missing", MAKER, NOW)).satisfies(code("CHANGE_SHAPE_INVALID"));
        assertThatThrownBy(() -> CatalogueChange.submit(CatalogueChange.Type.CREATE_SERVICE, "VL_RENEW", spec(), 1L,
                null, null, null, "extra", MAKER, NOW)).satisfies(code("CHANGE_SHAPE_INVALID"));
        assertThatThrownBy(() -> CatalogueChange.submit(CatalogueChange.Type.UPDATE_SERVICE, "VL_RENEW", spec(), null,
                null, null, null, "no base", MAKER, NOW)).satisfies(code("CHANGE_SHAPE_INVALID"));
        assertThatThrownBy(() -> CatalogueChange.submit(CatalogueChange.Type.UPDATE_SERVICE, "VL_RENEW", spec(), 0L,
                null, null, null, "base 0", MAKER, NOW)).satisfies(code("CHANGE_SHAPE_INVALID"));
        assertThatThrownBy(() -> CatalogueChange.submit(CatalogueChange.Type.ADD_AVAILABILITY_RULE, "VL_RENEW",
                spec(), null, rule, null, null, "both", MAKER, NOW)).satisfies(code("CHANGE_SHAPE_INVALID"));
        assertThatThrownBy(() -> CatalogueChange.submit(CatalogueChange.Type.END_AVAILABILITY_RULE, "VL_RENEW", null,
                null, null, id, null, "no date", MAKER, NOW)).satisfies(code("CHANGE_SHAPE_INVALID"));
        assertThatThrownBy(() -> CatalogueChange.submit(CatalogueChange.Type.CREATE_SERVICE, "OTHER", spec(), null,
                null, null, null, "mismatch", MAKER, NOW)).satisfies(code("SERVICE_CODE_MISMATCH"));
        assertThatThrownBy(() -> CatalogueChange.submit(null, "VL_RENEW", spec(), null, null, null, null, "no type",
                MAKER, NOW)).satisfies(code("CHANGE_TYPE_REQUIRED"));
        assertThatThrownBy(() -> CatalogueChange.submit(CatalogueChange.Type.CREATE_SERVICE, "VL_RENEW", spec(), null,
                null, null, null, "\t\t", MAKER, NOW)).satisfies(code("REASON_REQUIRED"));
        assertThatThrownBy(() -> CatalogueChange.submit(CatalogueChange.Type.CREATE_SERVICE, "VL_RENEW", spec(), null,
                null, null, null, "x".repeat(501), MAKER, NOW)).satisfies(code("REASON_TOO_LONG"));
    }

    @Test
    @DisplayName("names and actors are required")
    void namesAndActors() {
        assertThatThrownBy(() -> new BilingualName(" ", "x")).satisfies(code("NAME_REQUIRED"));
        assertThatThrownBy(() -> new BilingualName("x", "y".repeat(201))).satisfies(code("NAME_TOO_LONG"));
        assertThat(new BilingualName("  أ  ", " a ")).isEqualTo(new BilingualName("أ", "a"));
        assertThatThrownBy(() -> new Actor(" ", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Codes.reason(null)).satisfies(code("REASON_REQUIRED"));
        assertThat(new DomainException(DomainException.Kind.CONFLICT, "C", "m", List.of("CC-1"), 1).args())
                .containsExactly(1);
    }
}
