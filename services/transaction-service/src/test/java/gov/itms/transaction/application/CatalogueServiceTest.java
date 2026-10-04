package gov.itms.transaction.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.itms.transaction.domain.Actor;
import gov.itms.transaction.domain.Availability;
import gov.itms.transaction.domain.AvailabilityRule;
import gov.itms.transaction.domain.AvailabilityRuleSpec;
import gov.itms.transaction.domain.BilingualName;
import gov.itms.transaction.domain.CatalogueChange;
import gov.itms.transaction.domain.CatalogueChange.Type;
import gov.itms.transaction.domain.Channel;
import gov.itms.transaction.domain.DomainException;
import gov.itms.transaction.domain.ServiceSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class CatalogueServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 5);
    private static final Actor MAKER = new Actor("u-maker", "maker");
    private static final Actor CHECKER = new Actor("u-checker", "checker");

    private InMemoryRepositories.Services services;
    private InMemoryRepositories.Rules rules;
    private InMemoryRepositories.Changes changes;
    private InMemoryRepositories.Audit audit;
    private CatalogueService service;

    @BeforeEach
    void setUp() {
        services = new InMemoryRepositories.Services();
        rules = new InMemoryRepositories.Rules();
        changes = new InMemoryRepositories.Changes();
        audit = new InMemoryRepositories.Audit();
        service = new CatalogueService(services, rules, changes, audit, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ServiceSpec spec(String code, List<String> regions, List<Channel> channels) {
        return new ServiceSpec(code, new BilingualName("خدمة " + code, "Service " + code), "VEHICLE",
                List.of("INDIVIDUAL"), channels, List.of(), null, null, null, "P5D", regions, true, false);
    }

    private static Consumer<Throwable> code(String expected) {
        return t -> assertThat(((DomainException) t).code()).isEqualTo(expected);
    }

    private CatalogueChange submitAndApprove(Type type, String code, ServiceSpec spec, Long base,
                                             AvailabilityRuleSpec rule, UUID ruleId, LocalDate end) {
        CatalogueChange c = service.submit(CatalogueChange.submit(type, code, spec, base, rule, ruleId, end,
                "test change", MAKER, NOW));
        return service.approve(c.id(), CHECKER);
    }

    private void create(String code, List<String> regions, List<Channel> channels) {
        submitAndApprove(Type.CREATE_SERVICE, code, spec(code, regions, channels), null, null, null, null);
    }

    private AvailabilityRule addRule(String code, AvailabilityRuleSpec rule) {
        CatalogueChange c = submitAndApprove(Type.ADD_AVAILABILITY_RULE, code, null, null, rule, null, null);
        return rules.rows.values().stream().filter(r -> r.changeId().equals(c.id())).findFirst().orElseThrow();
    }

    @Test
    @Tag("CC-020")
    @Tag("CC-114")
    @Tag("CC-120")
    @DisplayName("CC-020/CC-114/CC-120: a service exists only after a second person approves it; every step is audited")
    void createServiceThroughMakerChecker() {
        CatalogueChange c = service.submit(CatalogueChange.submit(Type.CREATE_SERVICE, "VL_RENEW",
                spec("VL_RENEW", List.of("*"), List.of(Channel.PORTAL)), null, null, null, null, "new service",
                MAKER, NOW));
        assertThat(services.rows).isEmpty();
        assertThatThrownBy(() -> service.service("VL_RENEW")).satisfies(code("SERVICE_NOT_FOUND"));

        service.approve(c.id(), CHECKER);
        assertThat(service.service("VL_RENEW").version()).isEqualTo(1);
        assertThat(service.change(c.id()).status()).isEqualTo(CatalogueChange.Status.APPROVED);
        assertThat(audit.actions()).containsExactly("CATALOGUE_CHANGE_SUBMITTED", "SERVICE_DEFINITION_CREATED",
                "CATALOGUE_CHANGE_APPROVED");
        @SuppressWarnings("unchecked")
        Map<String, Object> created = (Map<String, Object>) audit.entries.get(1).newValue();
        assertThat(created).containsEntry("code", "VL_RENEW").containsEntry("version", 1L)
                .containsEntry("sla", "P5D");
    }

    @Test
    @Tag("CC-002")
    @Tag("CC-114")
    @DisplayName("CC-002/CC-114: the maker cannot approve; one pending change per service; rejection applies nothing")
    void makerCheckerRules() {
        CatalogueChange c = service.submit(CatalogueChange.submit(Type.CREATE_SERVICE, "VL_RENEW",
                spec("VL_RENEW", List.of("*"), List.of(Channel.PORTAL)), null, null, null, null, "new service",
                MAKER, NOW));
        assertThatThrownBy(() -> service.approve(c.id(), MAKER)).satisfies(code("SEGREGATION_OF_DUTIES"));
        assertThatThrownBy(() -> service.submit(CatalogueChange.submit(Type.CREATE_SERVICE, "VL_RENEW",
                spec("VL_RENEW", List.of("R1"), List.of(Channel.APP)), null, null, null, null, "second", MAKER, NOW)))
                .satisfies(code("CHANGE_ALREADY_PENDING"));
        service.reject(c.id(), CHECKER, "not this year");
        assertThat(services.rows).isEmpty();
        assertThat(service.changes(CatalogueChange.Status.REJECTED)).extracting(CatalogueChange::id)
                .containsExactly(c.id());
        assertThat(audit.actions()).last().isEqualTo("CATALOGUE_CHANGE_REJECTED");
        assertThatThrownBy(() -> service.approve(c.id(), CHECKER)).satisfies(code("CHANGE_NOT_PENDING"));
        assertThatThrownBy(() -> service.change(UUID.randomUUID())).satisfies(code("CHANGE_NOT_FOUND"));
        assertThatThrownBy(() -> service.approve(UUID.randomUUID(), CHECKER)).satisfies(code("CHANGE_NOT_FOUND"));
        assertThatThrownBy(() -> service.reject(UUID.randomUUID(), CHECKER, "nope"))
                .satisfies(code("CHANGE_NOT_FOUND"));
    }

    @Test
    @Tag("CC-020")
    @Tag("DAT-002")
    @Tag("CC-121")
    @DisplayName("DAT-002/CC-121: an update needs the current version and keeps every version in history")
    void updateKeepsHistory() {
        create("VL_RENEW", List.of("*"), List.of(Channel.PORTAL));
        assertThatThrownBy(() -> service.submit(CatalogueChange.submit(Type.CREATE_SERVICE, "VL_RENEW",
                spec("VL_RENEW", List.of("*"), List.of(Channel.PORTAL)), null, null, null, null, "again", MAKER, NOW)))
                .satisfies(code("SERVICE_EXISTS"));
        assertThatThrownBy(() -> service.submit(CatalogueChange.submit(Type.UPDATE_SERVICE, "VL_RENEW",
                spec("VL_RENEW", List.of("R1"), List.of(Channel.PORTAL)), 2L, null, null, null, "stale", MAKER, NOW)))
                .satisfies(code("STALE_CHANGE"));
        assertThatThrownBy(() -> service.submit(CatalogueChange.submit(Type.UPDATE_SERVICE, "NOPE",
                spec("NOPE", List.of("R1"), List.of(Channel.PORTAL)), 1L, null, null, null, "missing", MAKER, NOW)))
                .satisfies(code("SERVICE_NOT_FOUND"));

        // A change prepared against version 1 goes stale if another update is approved first.
        CatalogueChange first = service.submit(CatalogueChange.submit(Type.UPDATE_SERVICE, "VL_RENEW",
                spec("VL_RENEW", List.of("R1"), List.of(Channel.PORTAL)), 1L, null, null, null, "regions", MAKER, NOW));
        service.approve(first.id(), CHECKER);
        assertThat(service.service("VL_RENEW").version()).isEqualTo(2);
        assertThat(service.history("VL_RENEW")).extracting(ServiceDefinitionRepository.ServiceVersion::version)
                .containsExactly(1L, 2L);
        assertThat(service.history("VL_RENEW").get(0).validTo()).isEqualTo(NOW);
        assertThatThrownBy(() -> service.history("NOPE")).satisfies(code("SERVICE_NOT_FOUND"));
        assertThat(audit.actions()).contains("SERVICE_DEFINITION_UPDATED");
    }

    @Test
    @Tag("CC-026")
    @Tag("CC-112")
    @DisplayName("CC-026/CC-112: rules are added and ended through maker-checker and change availability")
    void availabilityRules() {
        create("VL_RENEW", List.of("R1", "R2"), List.of(Channel.PORTAL, Channel.KIOSK));
        assertThat(service.availability("VL_RENEW", "R1", Channel.KIOSK, null).available()).isTrue();

        AvailabilityRule kioskOff = addRule("VL_RENEW", new AvailabilityRuleSpec("*", Channel.KIOSK, false, TODAY,
                null));
        Availability off = service.availability("VL_RENEW", "R1", Channel.KIOSK, null);
        assertThat(off.available()).isFalse();
        assertThat(off.ruleId()).isEqualTo(kioskOff.id());
        assertThat(off.date()).isEqualTo(TODAY);
        assertThat(service.rules("VL_RENEW")).hasSize(1);
        assertThat(audit.actions()).contains("AVAILABILITY_RULE_ADDED");

        submitAndApprove(Type.END_AVAILABILITY_RULE, "VL_RENEW", null, null, null, kioskOff.id(), TODAY.plusDays(1));
        assertThat(service.availability("VL_RENEW", "R1", Channel.KIOSK, TODAY.plusDays(1)).available()).isFalse();
        assertThat(service.availability("VL_RENEW", "R1", Channel.KIOSK, TODAY.plusDays(2)).available()).isTrue();
        assertThat(audit.actions()).contains("AVAILABILITY_RULE_ENDED");

        // Listing by region and channel shows only what is available there.
        create("DL_ISSUE", List.of("R1"), List.of(Channel.KIOSK));
        addRule("DL_ISSUE", new AvailabilityRuleSpec("R1", null, false, TODAY, TODAY));
        assertThat(service.services("R1", Channel.KIOSK, TODAY.plusDays(2))).extracting(d -> d.code())
                .containsExactly("DL_ISSUE", "VL_RENEW");
        assertThat(service.services("R1", Channel.KIOSK, TODAY)).isEmpty();
        assertThat(service.services("R2", Channel.KIOSK, TODAY.plusDays(2))).extracting(d -> d.code())
                .containsExactly("VL_RENEW");
        assertThat(service.services(null, null, null)).hasSize(2);
        assertThatThrownBy(() -> service.services("R1", null, null)).satisfies(code("AVAILABILITY_QUERY_INVALID"));
        assertThatThrownBy(() -> service.services(null, Channel.APP, null))
                .satisfies(code("AVAILABILITY_QUERY_INVALID"));
    }

    @Test
    @Tag("CC-026")
    @DisplayName("CC-026: rules must fit the offer, start today or later, not overlap, and belong to the service")
    void ruleValidation() {
        create("VL_RENEW", List.of("R1"), List.of(Channel.PORTAL));
        create("DL_ISSUE", List.of("*"), List.of(Channel.PORTAL));
        assertThatThrownBy(() -> addRule("VL_RENEW", new AvailabilityRuleSpec("R2", null, false, TODAY, null)))
                .satisfies(code("RULE_OUTSIDE_OFFER"));
        assertThatThrownBy(() -> addRule("VL_RENEW", new AvailabilityRuleSpec("*", Channel.APP, false, TODAY, null)))
                .satisfies(code("RULE_OUTSIDE_OFFER"));
        assertThatThrownBy(() -> addRule("VL_RENEW",
                new AvailabilityRuleSpec("R1", null, false, TODAY.minusDays(1), null)))
                .satisfies(code("RULE_START_IN_PAST"));
        AvailabilityRule r = addRule("VL_RENEW", new AvailabilityRuleSpec("R1", null, false, TODAY, TODAY.plusDays(9)));
        assertThatThrownBy(() -> addRule("VL_RENEW",
                new AvailabilityRuleSpec("R1", null, true, TODAY.plusDays(9), null)))
                .satisfies(code("RULE_OVERLAP"));
        addRule("VL_RENEW", new AvailabilityRuleSpec("R1", null, true, TODAY.plusDays(10), null));
        assertThatThrownBy(() -> addRule("NOPE", new AvailabilityRuleSpec("*", null, false, TODAY, null)))
                .satisfies(code("SERVICE_NOT_FOUND"));
        // A rule id of another service is not found, and ending checks run when submitted.
        assertThatThrownBy(() -> submitAndApprove(Type.END_AVAILABILITY_RULE, "DL_ISSUE", null, null, null, r.id(),
                TODAY)).satisfies(code("RULE_NOT_FOUND"));
        assertThatThrownBy(() -> submitAndApprove(Type.END_AVAILABILITY_RULE, "VL_RENEW", null, null, null,
                UUID.randomUUID(), TODAY)).satisfies(code("RULE_NOT_FOUND"));
        assertThatThrownBy(() -> submitAndApprove(Type.END_AVAILABILITY_RULE, "VL_RENEW", null, null, null, r.id(),
                TODAY.minusDays(1))).satisfies(code("RULE_END_INVALID")); // before its start (today)
        assertThatThrownBy(() -> service.rules("NOPE")).satisfies(code("SERVICE_NOT_FOUND"));
        assertThatThrownBy(() -> service.availability("NOPE", "R1", Channel.APP, null))
                .satisfies(code("SERVICE_NOT_FOUND"));
        assertThatThrownBy(() -> service.service("bad code")).satisfies(code("INVALID_SERVICE_CODE"));
    }

    @Test
    @Tag("CC-114")
    @DisplayName("CC-114: a change is validated again when approved (the state may have changed since)")
    void revalidatedOnApproval() {
        create("VL_RENEW", List.of("R1"), List.of(Channel.PORTAL));
        // Submitted today, approved tomorrow: the rule would start in the past by then.
        CatalogueChange c = service.submit(CatalogueChange.submit(Type.ADD_AVAILABILITY_RULE, "VL_RENEW", null, null,
                new AvailabilityRuleSpec("R1", null, false, TODAY, null), null, null, "today only", MAKER, NOW));
        CatalogueService tomorrow = new CatalogueService(services, rules, changes, audit,
                Clock.fixed(NOW.plusSeconds(86_400), ZoneOffset.UTC));
        assertThatThrownBy(() -> tomorrow.approve(c.id(), CHECKER)).satisfies(code("RULE_START_IN_PAST"));
        assertThat(rules.rows).isEmpty();
    }
}
