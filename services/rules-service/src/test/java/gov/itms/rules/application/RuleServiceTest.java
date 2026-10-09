package gov.itms.rules.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.platform.security.CurrentActor;
import gov.itms.rules.domain.Actor;
import gov.itms.rules.domain.Channel;
import gov.itms.rules.domain.DecisionRecord;
import gov.itms.rules.domain.DecisionTable;
import gov.itms.rules.domain.DomainException;
import gov.itms.rules.domain.RuleVersion;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("CC-050")
class RuleServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T08:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);
    private static final String RULE_SET = "LICENCE_AGE_MINIMUM";

    private final Rules rules = new Rules();
    private final List<String> audit = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final RuleService service = new RuleService(rules, new AuditRecorder() {
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
    }, Clock.fixed(NOW, ZoneOffset.UTC));

    private static Caller caller(String id, Channel channel, String... permissions) {
        Set<String> held = Set.of(permissions);
        return new Caller(new Actor(id, id), channel, new Caller.Grants() {
            @Override
            public boolean allowsInRegion(String permission, String region) {
                return held.contains(permission);
            }

            @Override
            public boolean holds(String permission) {
                return held.contains(permission);
            }
        }, "corr-1");
    }

    private static final Caller MAKER = caller("u-maker", Channel.BACKOFFICE, RuleService.SUBMIT, RuleService.READ,
            RuleService.TEST);
    private static final Caller CHECKER = caller("u-checker", Channel.BACKOFFICE, RuleService.APPROVE,
            RuleService.READ, RuleService.TEST, RuleService.DECISION_READ);
    private static final Caller EVALUATOR = caller("svc-licensing", Channel.PARTNER, RuleService.EVALUATE);

    /** Minimum age by category; {@code age} is the minimum for B, so regional variants differ by value. */
    private static DecisionTable table(int age) {
        return new DecisionTable(
                List.of(new DecisionTable.Column("category", DecisionTable.Type.TEXT),
                        new DecisionTable.Column("age", DecisionTable.Type.NUMBER)),
                List.of(new DecisionTable.Column("eligible", DecisionTable.Type.BOOLEAN)),
                DecisionTable.HitPolicy.FIRST,
                List.of(new DecisionTable.Rule(Map.of(
                        "category", new DecisionTable.Condition(DecisionTable.Op.EQ, List.of("B")),
                        "age", new DecisionTable.Condition(DecisionTable.Op.GE, List.of(age))),
                        Map.of("eligible", true), "car from " + age)),
                Map.of("eligible", false));
    }

    private static RuleVersion.Spec spec(String region, int age, LocalDate from) {
        return new RuleVersion.Spec(RULE_SET, "الحد الأدنى للعمر", "Minimum age", region, table(age), from, null);
    }

    private RuleVersion approved(String region, int age, LocalDate from) {
        RuleVersion v = service.submit(spec(region, age, from), "new rule version", MAKER);
        return service.approve(v.id(), "checked", CHECKER);
    }

    @Test
    @Tag("CC-051")
    @DisplayName("CC-051: the regional variant in force wins over the national version; other regions get national")
    void regionalPrecedence() {
        RuleVersion national = approved(null, 18, TODAY);
        RuleVersion north = approved("NORTH", 21, TODAY);

        DecisionRecord inNorth = service.evaluate(RULE_SET, "NORTH", TODAY, Map.of("category", "B", "age", 19),
                "APP-1", EVALUATOR);
        assertThat(inNorth.versionId()).isEqualTo(north.id());
        assertThat(inNorth.outputs()).containsExactly(Map.of("eligible", false));
        assertThat(inNorth.matchedRules()).isEmpty();

        DecisionRecord inSouth = service.evaluate(RULE_SET, "SOUTH", TODAY, Map.of("category", "B", "age", 19),
                "APP-2", EVALUATOR);
        assertThat(inSouth.versionId()).isEqualTo(national.id());
        assertThat(inSouth.matchedRules()).containsExactly(1);
        assertThat(inSouth.outputs()).containsExactly(Map.of("eligible", true));

        assertThat(rules.decisions).hasSize(2);
        assertThatThrownBy(() -> service.evaluate(RULE_SET, "NORTH", TODAY.minusDays(1), Map.of(), null, EVALUATOR))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("RULE_NOT_IN_FORCE");
        assertThatThrownBy(() -> service.evaluate("UNKNOWN_SET", null, TODAY, Map.of(), null, EVALUATOR))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("RULE_NOT_IN_FORCE");
    }

    @Test
    @Tag("CC-053")
    @DisplayName("CC-053: a decision is logged with the exact version, inputs, matched rules, outputs and requester")
    void decisionLogged() {
        RuleVersion national = approved(null, 18, TODAY);
        DecisionRecord d = service.evaluate(RULE_SET, null, null, Map.of("category", "B", "age", 30), "APP-9",
                EVALUATOR);
        assertThat(d.versionNo()).isEqualTo(national.versionNo());
        assertThat(d.date()).isEqualTo(TODAY);
        assertThat(d.inputs()).containsEntry("age", 30);
        assertThat(d.reference()).isEqualTo("APP-9");
        assertThat(d.actorId()).isEqualTo("svc-licensing");
        assertThat(d.channel()).isEqualTo(Channel.PARTNER);
        assertThat(d.correlationId()).isEqualTo("corr-1");

        assertThat(service.decision(d.id(), CHECKER).id()).isEqualTo(d.id());
        assertThat(service.decisions(RULE_SET, null, 10, CHECKER)).extracting(DecisionRecord::id).containsExactly(d.id());
        assertThat(audit).contains("RULE_DECISION_VIEWED " + d.id(), "RULE_DECISIONS_LISTED " + RULE_SET);
        assertThatThrownBy(() -> service.decisions(RULE_SET, null, 0, CHECKER))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("FIELD_INVALID");
        assertThatThrownBy(() -> service.decision(UUID.randomUUID(), CHECKER))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("DECISION_NOT_FOUND");
    }

    @Test
    @Tag("CC-051")
    @DisplayName("CC-051: the maker withdraws their own pending version without approve rights; others need them")
    void withdrawal() {
        RuleVersion v = service.submit(spec(null, 18, TODAY), "new rule version", MAKER);
        Caller otherMaker = caller("u-other", Channel.BACKOFFICE, RuleService.SUBMIT);
        assertThatThrownBy(() -> service.reject(v.id(), "not mine", otherMaker))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("SCOPE_EXCEEDED");

        RuleVersion withdrawn = service.reject(v.id(), "submitted by mistake", MAKER);
        assertThat(withdrawn.status()).isEqualTo(RuleVersion.Status.REJECTED);
        assertThat(audit).contains("RULE_VERSION_WITHDRAWN " + v.id());
        assertThatThrownBy(() -> service.approve(v.id(), "late", CHECKER))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("RULE_VERSION_DECIDED");

        RuleVersion second = service.submit(spec(null, 18, TODAY), "second attempt", MAKER);
        service.reject(second.id(), "table is wrong", CHECKER);
        assertThat(audit).contains("RULE_VERSION_REJECTED " + second.id());
    }

    @Test
    @Tag("CC-051")
    @DisplayName("CC-051: an approved successor ends its predecessor; a version starting before an approved one overlaps")
    void successorAndOverlap() {
        RuleVersion first = approved(null, 18, TODAY);
        approved(null, 17, TODAY.plusDays(30));
        assertThat(rules.stored.get(first.id()).effectiveTo()).isEqualTo(TODAY.plusDays(29));
        assertThat(audit).contains("RULE_VERSION_ENDED " + first.id());
        assertThat(events).contains("itms.rule.version-approved " + RULE_SET);

        // A regional version never overlaps the national ones.
        approved("NORTH", 21, TODAY.plusDays(10));
        assertThat(rules.stored.get(first.id()).effectiveTo()).isEqualTo(TODAY.plusDays(29));

        // Last: a refused approval rolls back in the database, which this in-memory store does not.
        RuleVersion earlier = service.submit(spec(null, 16, TODAY.plusDays(10)), "earlier change", MAKER);
        assertThatThrownBy(() -> service.approve(earlier.id(), null, CHECKER))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("RULE_VERSION_OVERLAP");
    }

    @Test
    @Tag("CC-052")
    @DisplayName("CC-052: a pending version is tested; each case reports matches, outputs, pass/fail or the input error")
    void testRun() {
        RuleVersion pending = service.submit(spec(null, 18, TODAY), "new rule version", MAKER);
        List<RuleService.TestResult> results = service.testRun(pending.id(), List.of(
                new RuleService.TestCase(Map.of("category", "B", "age", 18), Map.of("eligible", true)),
                new RuleService.TestCase(Map.of("category", "B", "age", 17), Map.of("eligible", true)),
                new RuleService.TestCase(Map.of("category", "C", "age", 40), null),
                new RuleService.TestCase(Map.of("colour", "red"), Map.of("eligible", false))), MAKER);

        assertThat(results).extracting(RuleService.TestResult::passed).containsExactly(true, false, null, false);
        assertThat(results.get(0).matchedRules()).containsExactly(1);
        assertThat(results.get(1).outputs()).containsExactly(Map.of("eligible", false));
        assertThat(results.get(3).error()).isEqualTo("RULE_INPUT_UNKNOWN");
        assertThat(pending.status()).as("a test run decides nothing").isEqualTo(RuleVersion.Status.PENDING);
        assertThat(rules.decisions).as("test runs are not logged decisions").isEmpty();
        assertThat(audit).contains("RULE_VERSION_TESTED " + pending.id());

        List<RuleService.TestCase> tooMany = IntStream.range(0, 101)
                .mapToObj(i -> new RuleService.TestCase(Map.of(), null)).toList();
        assertThatThrownBy(() -> service.testRun(pending.id(), tooMany, MAKER))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("TEST_CASES_INVALID");
        assertThatThrownBy(() -> service.testRun(pending.id(), List.of(), MAKER))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("TEST_CASES_INVALID");
    }

    @Test
    @Tag("CC-001")
    @Tag("CC-120")
    @DisplayName("CC-001, CC-120: every command needs its permission and a named channel")
    void permissionsAndChannel() {
        assertThatThrownBy(() -> service.submit(spec(null, 18, TODAY), "new rule version", CHECKER))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("SCOPE_EXCEEDED");
        assertThatThrownBy(() -> service.evaluate(RULE_SET, null, TODAY, Map.of(), null, MAKER))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("SCOPE_EXCEEDED");
        assertThatThrownBy(() -> service.submit(spec(null, 18, TODAY), "new rule version",
                caller("u-maker", null, RuleService.SUBMIT)))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("CHANNEL_REQUIRED");
        RuleVersion v = service.submit(spec(null, 18, TODAY), "new rule version", MAKER);
        assertThatThrownBy(() -> service.approve(v.id(), null,
                caller("u-maker", Channel.BACKOFFICE, RuleService.APPROVE)))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("SAME_MAKER_CHECKER");
        assertThatThrownBy(() -> service.get(UUID.randomUUID(), MAKER))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("RULE_VERSION_NOT_FOUND");
        assertThat(audit).contains("RULE_VERSION_SUBMITTED " + v.id());
    }

    /** In-memory repository; versions are kept as the same mutable objects the service changes. */
    private static final class Rules implements RuleRepository {
        /** What the service last handed in (its objects) and what is stored (copies, as rows). */
        final Map<UUID, RuleVersion> versions = new LinkedHashMap<>();
        final Map<UUID, RuleVersion> stored = new LinkedHashMap<>();
        final Map<UUID, DecisionRecord> decisions = new LinkedHashMap<>();

        @Override
        public int nextVersionNo(String ruleSetCode) {
            return (int) versions.values().stream().filter(v -> v.spec().ruleSetCode().equals(ruleSetCode)).count()
                    + 1;
        }

        @Override
        public void lockRuleSet(String ruleSetCode) {
        }

        @Override
        public void insert(RuleVersion version) {
            store(version);
        }

        @Override
        public Optional<RuleVersion> find(UUID id) {
            return Optional.ofNullable(stored.get(id)).map(Rules::copy);
        }

        @Override
        public Optional<RuleVersion> findForUpdate(UUID id) {
            return find(id);
        }

        @Override
        public List<RuleVersion> byRuleSet(String ruleSetCode) {
            return stored.values().stream().filter(v -> v.spec().ruleSetCode().equals(ruleSetCode))
                    .sorted(Comparator.comparingInt(RuleVersion::versionNo)).map(Rules::copy).toList();
        }

        private static RuleVersion copy(RuleVersion v) {
            return RuleVersion.restore(v.id(), v.versionNo(), v.spec(), v.submitted(), v.status(), v.decided(),
                    v.effectiveTo());
        }

        private void store(RuleVersion v) {
            stored.put(v.id(), copy(v));
            versions.put(v.id(), v);
        }

        @Override
        public void saveDecision(RuleVersion version) {
            store(version);
        }

        @Override
        public void saveEnd(RuleVersion version) {
            store(version);
        }

        @Override
        public void insertDecision(DecisionRecord decision) {
            decisions.put(decision.id(), decision);
        }

        @Override
        public Optional<DecisionRecord> findDecision(UUID id) {
            return Optional.ofNullable(decisions.get(id));
        }

        @Override
        public List<DecisionRecord> decisions(String ruleSetCode, Instant since, int limit) {
            return decisions.values().stream().filter(d -> d.ruleSetCode().equals(ruleSetCode))
                    .filter(d -> !d.at().isBefore(since))
                    .sorted(Comparator.comparing(DecisionRecord::at).reversed()).limit(limit).toList();
        }
    }
}
