package gov.itms.transaction.application;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.transaction.domain.Actor;
import gov.itms.transaction.domain.Availability;
import gov.itms.transaction.domain.AvailabilityRule;
import gov.itms.transaction.domain.AvailabilityRuleSpec;
import gov.itms.transaction.domain.CatalogueChange;
import gov.itms.transaction.domain.Channel;
import gov.itms.transaction.domain.Codes;
import gov.itms.transaction.domain.DomainException;
import gov.itms.transaction.domain.ServiceDefinition;
import gov.itms.transaction.domain.ServiceSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service catalogue use cases (CC-020, CC-026, CC-112, CC-113, CC-114, CC-121, DAT-002). Reads return what is in
 * force; every write is a maker-checker change, validated when submitted and again when approved, and audited in the
 * same transaction as the data it changes (CC-120, NFR-015).
 */
public class CatalogueService {

    static final String ENTITY_CHANGE = "CatalogueChange";
    static final String ENTITY_SERVICE = "ServiceDefinition";
    static final String ENTITY_RULE = "AvailabilityRule";

    private final ServiceDefinitionRepository services;
    private final AvailabilityRuleRepository rules;
    private final CatalogueChangeRepository changes;
    private final AuditRecorder audit;
    private final Clock clock;

    public CatalogueService(ServiceDefinitionRepository services, AvailabilityRuleRepository rules,
                            CatalogueChangeRepository changes, AuditRecorder audit, Clock clock) {
        this.services = services;
        this.rules = rules;
        this.changes = changes;
        this.audit = audit;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- queries

    /**
     * Every definition; with a region and a channel, only those available there on {@code date} (default today).
     * Giving only one of region and channel is refused.
     */
    @Transactional(readOnly = true)
    public List<ServiceDefinition> services(String region, Channel channel, LocalDate date) {
        if (region == null && channel == null) {
            return services.findAll();
        }
        if (region == null || channel == null) {
            throw DomainException.validation("AVAILABILITY_QUERY_INVALID",
                    "give both a region and a channel to list the services available there");
        }
        LocalDate day = date != null ? date : today();
        Map<String, List<AvailabilityRule>> byService = rules.findAll().stream()
                .collect(Collectors.groupingBy(AvailabilityRule::serviceCode));
        return services.findAll().stream()
                .filter(s -> Availability.of(s.spec(), byService.getOrDefault(s.code(), List.of()), region, channel,
                        day).available())
                .toList();
    }

    @Transactional(readOnly = true)
    public ServiceDefinition service(String code) {
        return services.find(Codes.serviceCode(code)).orElseThrow(() -> serviceNotFound(code));
    }

    /** Version history, oldest first (DAT-002, CC-121). */
    @Transactional(readOnly = true)
    public List<ServiceDefinitionRepository.ServiceVersion> history(String code) {
        service(code);
        return services.history(code);
    }

    @Transactional(readOnly = true)
    public List<AvailabilityRule> rules(String code) {
        service(code);
        return rules.findByService(code);
    }

    /** Availability of a service in a region on a channel on {@code date} (default today) (CC-026). */
    @Transactional(readOnly = true)
    public Availability availability(String code, String region, Channel channel, LocalDate date) {
        ServiceDefinition definition = service(code);
        return Availability.of(definition.spec(), rules.findByService(code), region, channel,
                date != null ? date : today());
    }

    @Transactional(readOnly = true)
    public List<CatalogueChange> changes(CatalogueChange.Status status) {
        return changes.findByStatus(status);
    }

    @Transactional(readOnly = true)
    public CatalogueChange change(UUID id) {
        return changes.find(id).orElseThrow(() -> changeNotFound(id));
    }

    // ---------------------------------------------------------------- maker-checker commands

    /** Maker submits a change (CC-114). It is validated now and again when approved. */
    @Transactional
    public CatalogueChange submit(CatalogueChange change) {
        validateAgainstCurrentState(change);
        if (changes.pendingExistsFor(change.serviceCode())) {
            throw DomainException.conflict("CHANGE_ALREADY_PENDING",
                    "another change to service " + change.serviceCode() + " is waiting for approval",
                    change.serviceCode());
        }
        changes.insert(change);
        audit.record("CATALOGUE_CHANGE_SUBMITTED", ENTITY_CHANGE, change.id().toString(), null, snapshot(change),
                change.reason());
        return change;
    }

    /** Checker approves; the change is applied in the same transaction (CC-114, CC-002). */
    @Transactional
    public CatalogueChange approve(UUID id, Actor checker) {
        CatalogueChange change = changes.findForDecision(id).orElseThrow(() -> changeNotFound(id));
        Map<String, Object> before = snapshot(change);
        Instant now = clock.instant();
        change.approve(checker, now);
        validateAgainstCurrentState(change);
        apply(change, checker, now);
        changes.saveDecision(change);
        audit.record("CATALOGUE_CHANGE_APPROVED", ENTITY_CHANGE, id.toString(), before, snapshot(change), null);
        return change;
    }

    @Transactional
    public CatalogueChange reject(UUID id, Actor checker, String reason) {
        CatalogueChange change = changes.findForDecision(id).orElseThrow(() -> changeNotFound(id));
        Map<String, Object> before = snapshot(change);
        change.reject(checker, reason, clock.instant());
        changes.saveDecision(change);
        audit.record("CATALOGUE_CHANGE_REJECTED", ENTITY_CHANGE, id.toString(), before, snapshot(change),
                change.decisionReason());
        return change;
    }

    // ---------------------------------------------------------------- internals

    private void validateAgainstCurrentState(CatalogueChange change) {
        String code = change.serviceCode();
        switch (change.type()) {
            case CREATE_SERVICE -> {
                if (services.find(code).isPresent()) {
                    throw DomainException.conflict("SERVICE_EXISTS", "service " + code + " already exists", code);
                }
            }
            case UPDATE_SERVICE -> {
                ServiceDefinition current = service(code);
                if (current.version() != change.baseVersion()) {
                    throw DomainException.conflict("STALE_CHANGE",
                            "the service was changed after this change was prepared; prepare it again");
                }
            }
            case ADD_AVAILABILITY_RULE -> checkNewRule(service(code).spec(), change.rule());
            case END_AVAILABILITY_RULE -> {
                service(code);
                ruleOf(code, change.ruleId()).checkEnd(change.endDate(), today());
            }
        }
    }

    private void checkNewRule(ServiceSpec service, AvailabilityRuleSpec rule) {
        if (!rule.allRegions() && !service.offersRegion(rule.region())) {
            throw DomainException.validation("RULE_OUTSIDE_OFFER",
                    "service " + service.code() + " is not offered in region " + rule.region(), rule.region());
        }
        if (!rule.allChannels() && !service.offersChannel(rule.channel())) {
            throw DomainException.validation("RULE_OUTSIDE_OFFER",
                    "service " + service.code() + " is not offered on channel " + rule.channel(), rule.channel());
        }
        if (rule.effectiveFrom().isBefore(today())) {
            throw DomainException.validation("RULE_START_IN_PAST",
                    "a rule cannot start on a day that has passed; availability on past days is never rewritten");
        }
        boolean overlap = rules.findByService(service.code()).stream().anyMatch(r -> r.spec().overlaps(rule));
        if (overlap) {
            throw DomainException.conflict("RULE_OVERLAP",
                    "another rule for the same region and channel is in force on some of these days");
        }
    }

    private AvailabilityRule ruleOf(String serviceCode, UUID ruleId) {
        AvailabilityRule rule = rules.find(ruleId).orElseThrow(() -> ruleNotFound(ruleId));
        if (!rule.serviceCode().equals(serviceCode)) {
            throw ruleNotFound(ruleId);
        }
        return rule;
    }

    private void apply(CatalogueChange change, Actor checker, Instant now) {
        String code = change.serviceCode();
        switch (change.type()) {
            case CREATE_SERVICE -> {
                ServiceDefinition created = ServiceDefinition.create(change.service(), change.id(), checker, now);
                services.insert(created);
                audit.record("SERVICE_DEFINITION_CREATED", ENTITY_SERVICE, code, null, snapshot(created),
                        change.reason());
            }
            case UPDATE_SERVICE -> {
                ServiceDefinition current = services.findForUpdate(code).orElseThrow(() -> serviceNotFound(code));
                if (current.version() != change.baseVersion()) {
                    throw DomainException.conflict("STALE_CHANGE",
                            "the service was changed after this change was prepared; prepare it again");
                }
                ServiceDefinition next = current.update(change.service(), change.id(), checker, now);
                services.update(next);
                audit.record("SERVICE_DEFINITION_UPDATED", ENTITY_SERVICE, code, snapshot(current), snapshot(next),
                        change.reason());
            }
            case ADD_AVAILABILITY_RULE -> {
                AvailabilityRule rule = AvailabilityRule.add(code, change.rule(), change.id());
                rules.insert(rule, checker, now);
                audit.record("AVAILABILITY_RULE_ADDED", ENTITY_RULE, rule.id().toString(), null, snapshot(rule),
                        change.reason());
            }
            case END_AVAILABILITY_RULE -> {
                AvailabilityRule rule = ruleOf(code, change.ruleId());
                Map<String, Object> before = snapshot(rule);
                rule.end(change.endDate(), change.id(), today());
                rules.saveEnd(rule);
                audit.record("AVAILABILITY_RULE_ENDED", ENTITY_RULE, rule.id().toString(), before, snapshot(rule),
                        change.reason());
            }
        }
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    // ---------------------------------------------------------------- audit snapshots (CC-120 old/new values)

    static Map<String, Object> snapshot(ServiceSpec s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", s.code());
        m.put("name", Map.of("nl", s.name().nl(), "en", s.name().en()));
        m.put("module", s.module());
        m.put("customerTypes", s.customerTypes());
        m.put("channels", s.channels().stream().map(Enum::name).toList());
        m.put("requiredDocuments", s.requiredDocuments());
        m.put("feeCode", s.feeCode());
        m.put("ruleSetCode", s.ruleSetCode());
        m.put("workflowCode", s.workflowCode());
        m.put("sla", s.sla());
        m.put("regions", s.regions());
        m.put("anyRegion", s.anyRegion());
        m.put("allowsConcurrentOpen", s.allowsConcurrentOpen());
        return m;
    }

    static Map<String, Object> snapshot(AvailabilityRuleSpec r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("region", r.region());
        m.put("channel", r.allChannels() ? Codes.ALL : r.channel().name());
        m.put("enabled", r.enabled());
        m.put("effectiveFrom", r.effectiveFrom().toString());
        m.put("effectiveTo", r.effectiveTo() == null ? null : r.effectiveTo().toString());
        return m;
    }

    private static Map<String, Object> snapshot(ServiceDefinition d) {
        Map<String, Object> m = new LinkedHashMap<>(snapshot(d.spec()));
        m.put("version", d.version());
        m.put("lastChangeId", d.lastChangeId().toString());
        return m;
    }

    private static Map<String, Object> snapshot(AvailabilityRule r) {
        Map<String, Object> m = new LinkedHashMap<>(snapshot(r.spec()));
        m.put("id", r.id().toString());
        m.put("serviceCode", r.serviceCode());
        m.put("changeId", r.changeId().toString());
        m.put("endedByChange", r.endedByChange() == null ? null : r.endedByChange().toString());
        return m;
    }

    private static Map<String, Object> snapshot(CatalogueChange c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("changeType", c.type().name());
        m.put("status", c.status().name());
        m.put("serviceCode", c.serviceCode());
        m.put("service", c.service() == null ? null : snapshot(c.service()));
        m.put("baseVersion", c.baseVersion());
        m.put("rule", c.rule() == null ? null : snapshot(c.rule()));
        m.put("ruleId", c.ruleId() == null ? null : c.ruleId().toString());
        m.put("endDate", c.endDate() == null ? null : c.endDate().toString());
        m.put("maker", c.maker().id());
        m.put("checker", c.checker() == null ? null : c.checker().id());
        m.put("decisionReason", c.decisionReason());
        return m;
    }

    private static DomainException serviceNotFound(String code) {
        return DomainException.notFound("SERVICE_NOT_FOUND", "service " + code + " does not exist", code);
    }

    private static DomainException ruleNotFound(UUID id) {
        return DomainException.notFound("RULE_NOT_FOUND", "availability rule " + id + " does not exist", id);
    }

    private static DomainException changeNotFound(UUID id) {
        return DomainException.notFound("CHANGE_NOT_FOUND", "catalogue change " + id + " does not exist", id);
    }
}
