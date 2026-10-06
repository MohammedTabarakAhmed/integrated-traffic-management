package gov.itms.rules.application;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.rules.domain.Channel;
import gov.itms.rules.domain.DecisionRecord;
import gov.itms.rules.domain.DecisionTable;
import gov.itms.rules.domain.DomainException;
import gov.itms.rules.domain.RuleVersion;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business rules (CC-050..CC-053; ADR-048):
 * <ul>
 *   <li>versions of a rule set are submitted ({@value #SUBMIT}) and approved or rejected once by someone else
 *       ({@value #APPROVE}); a version is national or for one region (CC-051) and has effective dates;</li>
 *   <li>any version, also a pending one, can be tested against sample inputs before it is approved ({@value #TEST},
 *       CC-052); test runs are not decisions and are not logged as such, only audited;</li>
 *   <li>services evaluate a rule set ({@value #EVALUATE}): the regional variant in force on the date wins over the
 *       national one; every decision is logged with the exact version, inputs, matched rules and outputs (CC-053);</li>
 *   <li>rules are national configuration, so the permissions apply in any grant.</li>
 * </ul>
 */
public class RuleService {

    public static final String SUBMIT = "rules.version.submit";
    public static final String APPROVE = "rules.version.approve";
    public static final String READ = "rules.version.read";
    public static final String TEST = "rules.version.test";
    public static final String EVALUATE = "rules.evaluate";
    public static final String DECISION_READ = "rules.decision.read";
    static final String ENTITY = "RuleVersion";

    private final RuleRepository rules;
    private final AuditRecorder audit;
    private final DomainEventRecorder events;
    private final Clock clock;

    public RuleService(RuleRepository rules, AuditRecorder audit, DomainEventRecorder events, Clock clock) {
        this.rules = rules;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    /** One sample case of a test run, with an optional expected output (the first output row for COLLECT). */
    public record TestCase(Map<String, Object> inputs, Map<String, Object> expected) {
    }

    public record TestResult(int caseNo, List<Integer> matchedRules, List<Map<String, Object>> outputs, Boolean passed,
                             String error) {
    }

    @Transactional
    public RuleVersion submit(RuleVersion.Spec spec, String reason, Caller caller) {
        Channel channel = channelOf(caller);
        require(caller, SUBMIT);
        rules.lockRuleSet(spec.ruleSetCode());
        Instant now = clock.instant();
        RuleVersion version = RuleVersion.submit(UUID.randomUUID(), rules.nextVersionNo(spec.ruleSetCode()), spec,
                reason, caller.actor(), channel, now, today(now));
        rules.insert(version);
        audit.record("RULE_VERSION_SUBMITTED", ENTITY, version.id().toString(), null, snapshot(version),
                version.submitted().reason());
        return version;
    }

    @Transactional
    public RuleVersion approve(UUID id, String reason, Caller caller) {
        Channel channel = channelOf(caller);
        require(caller, APPROVE);
        RuleVersion found = rules.find(id).orElseThrow(() -> notFound(id));
        rules.lockRuleSet(found.spec().ruleSetCode());
        RuleVersion version = rules.findForUpdate(id).orElseThrow(() -> notFound(id));
        Instant now = clock.instant();
        version.approve(caller.actor(), channel, reason, now, today(now));
        for (RuleVersion other : rules.byRuleSet(version.spec().ruleSetCode())) {
            boolean sameScope = other.status() == RuleVersion.Status.APPROVED
                    && Objects.equals(other.spec().region(), version.spec().region());
            if (!sameScope || !other.overlaps(version.spec().effectiveFrom(), version.effectiveTo())) {
                continue;
            }
            if (!other.spec().effectiveFrom().isBefore(version.spec().effectiveFrom())) {
                throw DomainException.conflict("RULE_VERSION_OVERLAP",
                        "an approved version (v" + other.versionNo() + ") already covers this period",
                        List.of("CC-051"), other.versionNo());
            }
            Map<String, Object> before = snapshot(other);
            other.endBefore(version.spec().effectiveFrom());
            rules.saveEnd(other);
            audit.record("RULE_VERSION_ENDED", ENTITY, other.id().toString(), before, snapshot(other),
                    "superseded by v" + version.versionNo());
        }
        rules.saveDecision(version);
        audit.record("RULE_VERSION_APPROVED", ENTITY, id.toString(), null, snapshot(version), reason);
        events.record("itms.rule.version-approved", version.spec().ruleSetCode(), snapshot(version));
        return version;
    }

    @Transactional
    public RuleVersion reject(UUID id, String reason, Caller caller) {
        Channel channel = channelOf(caller);
        RuleVersion found = rules.find(id).orElseThrow(() -> notFound(id));
        boolean withdrawal = found.submitted().actor().id().equals(caller.actor().id());
        if (!withdrawal) {
            require(caller, APPROVE);
        }
        RuleVersion version = rules.findForUpdate(id).orElseThrow(() -> notFound(id));
        version.reject(caller.actor(), channel, reason, clock.instant());
        rules.saveDecision(version);
        audit.record(withdrawal ? "RULE_VERSION_WITHDRAWN" : "RULE_VERSION_REJECTED", ENTITY, id.toString(), null,
                snapshot(version), version.decided().reason());
        return version;
    }

    @Transactional(readOnly = true)
    public RuleVersion get(UUID id, Caller caller) {
        require(caller, READ);
        return rules.find(id).orElseThrow(() -> notFound(id));
    }

    @Transactional(readOnly = true)
    public List<RuleVersion> byRuleSet(String ruleSetCode, Caller caller) {
        require(caller, READ);
        return rules.byRuleSet(ruleSetCode);
    }

    /** Runs sample cases against a version, approved or not (CC-052). Audited; no decision is logged. */
    @Transactional
    public List<TestResult> testRun(UUID id, List<TestCase> cases, Caller caller) {
        require(caller, TEST);
        if (cases == null || cases.isEmpty() || cases.size() > 100) {
            throw DomainException.validation("TEST_CASES_INVALID", "give 1 to 100 test cases", List.of("CC-052"));
        }
        RuleVersion version = rules.find(id).orElseThrow(() -> notFound(id));
        DecisionTable table = version.spec().table();
        List<TestResult> results = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            TestCase c = cases.get(i);
            try {
                Map<String, Object> inputs = table.checkInputs(c.inputs() == null ? Map.of() : c.inputs());
                List<Integer> matched = table.matches(inputs);
                List<Map<String, Object>> outputs = table.outputsOf(matched);
                Boolean passed = c.expected() == null ? null
                        : sameValues(c.expected(), outputs.isEmpty() ? Map.of() : outputs.getFirst());
                results.add(new TestResult(i + 1, oneBased(matched), outputs, passed, null));
            } catch (DomainException e) {
                results.add(new TestResult(i + 1, List.of(), List.of(), c.expected() == null ? null : false,
                        e.code()));
            }
        }
        long failed = results.stream().filter(r -> Boolean.FALSE.equals(r.passed())).count();
        audit.record("RULE_VERSION_TESTED", ENTITY, id.toString(), null,
                Map.of("cases", cases.size(), "failed", failed), null);
        return results;
    }

    /**
     * Evaluates a rule set for a region and date (CC-050, CC-051) and logs the decision (CC-053). The regional
     * variant in force wins over the national version; with neither, 404 {@code RULE_NOT_IN_FORCE}.
     */
    @Transactional
    public DecisionRecord evaluate(String ruleSetCode, String region, LocalDate date, Map<String, Object> inputs,
                                   String reference, Caller caller) {
        Channel channel = channelOf(caller);
        require(caller, EVALUATE);
        Instant now = clock.instant();
        LocalDate on = date == null ? today(now) : date;
        List<RuleVersion> inForce = rules.byRuleSet(ruleSetCode).stream().filter(v -> v.inForce(on)).toList();
        RuleVersion version = inForce.stream().filter(v -> region != null && region.equals(v.spec().region()))
                .findFirst()
                .or(() -> inForce.stream().filter(v -> v.spec().region() == null)
                        .max(Comparator.comparing(v -> v.spec().effectiveFrom())))
                .orElseThrow(() -> new DomainException(DomainException.Kind.NOT_FOUND, "RULE_NOT_IN_FORCE",
                        "no version of " + ruleSetCode + " is in force on " + on, List.of("CC-051"), ruleSetCode,
                        on.toString()));
        DecisionTable table = version.spec().table();
        Map<String, Object> checked = table.checkInputs(inputs == null ? Map.of() : inputs);
        List<Integer> matched = table.matches(checked);
        DecisionRecord decision = new DecisionRecord(UUID.randomUUID(), ruleSetCode, version.id(),
                version.versionNo(), region, on, inputs == null ? Map.of() : inputs, oneBased(matched),
                table.outputsOf(matched), reference, caller.actor().id(), channel, caller.correlationId(), now);
        rules.insertDecision(decision);
        return decision;
    }

    @Transactional
    public DecisionRecord decision(UUID id, Caller caller) {
        require(caller, DECISION_READ);
        DecisionRecord d = rules.findDecision(id).orElseThrow(() -> new DomainException(
                DomainException.Kind.NOT_FOUND, "DECISION_NOT_FOUND", "decision " + id + " not found",
                List.of("CC-053")));
        audit.record("RULE_DECISION_VIEWED", "RuleDecision", id.toString(), null, null, null);
        return d;
    }

    @Transactional
    public List<DecisionRecord> decisions(String ruleSetCode, Instant since, int limit, Caller caller) {
        require(caller, DECISION_READ);
        if (limit < 1 || limit > 500) {
            throw DomainException.validation("FIELD_INVALID", "limit must be 1..500", List.of("CC-053"), "limit");
        }
        List<DecisionRecord> found = rules.decisions(ruleSetCode, since == null ? Instant.EPOCH : since, limit);
        audit.record("RULE_DECISIONS_LISTED", "RuleDecision", ruleSetCode, null, Map.of("count", found.size()), null);
        return found;
    }

    private static boolean sameValues(Map<String, Object> expected, Map<String, Object> actual) {
        for (Map.Entry<String, Object> e : expected.entrySet()) {
            Object a = actual.get(e.getKey());
            if (a instanceof Number an && e.getValue() instanceof Number en) {
                if (new java.math.BigDecimal(an.toString()).compareTo(new java.math.BigDecimal(en.toString())) != 0) {
                    return false;
                }
            } else if (!Objects.equals(a, e.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static List<Integer> oneBased(List<Integer> indexes) {
        return indexes.stream().map(i -> i + 1).toList();
    }

    private LocalDate today(Instant now) {
        return LocalDate.ofInstant(now, clock.getZone());
    }

    static void require(Caller caller, String permission) {
        if (!caller.grants().holds(permission)) {
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "SCOPE_EXCEEDED",
                    "you may not do this with rules", List.of("CC-001"));
        }
    }

    static Channel channelOf(Caller caller) {
        if (caller.channel() == null) {
            throw DomainException.validation("CHANNEL_REQUIRED", "the request does not name its channel (X-Channel)",
                    List.of("CC-120"));
        }
        return caller.channel();
    }

    private static DomainException notFound(UUID id) {
        return new DomainException(DomainException.Kind.NOT_FOUND, "RULE_VERSION_NOT_FOUND",
                "rule version " + id + " does not exist", List.of("CC-050"));
    }

    private static Map<String, Object> snapshot(RuleVersion v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ruleSetCode", v.spec().ruleSetCode());
        m.put("versionNo", v.versionNo());
        m.put("region", v.spec().region());
        m.put("hitPolicy", v.spec().table().hitPolicy().name());
        m.put("rules", v.spec().table().rules().size());
        m.put("effectiveFrom", v.spec().effectiveFrom().toString());
        m.put("effectiveTo", v.effectiveTo() == null ? null : v.effectiveTo().toString());
        m.put("status", v.status().name());
        return m;
    }
}
