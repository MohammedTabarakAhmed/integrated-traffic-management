package gov.itms.rules.api;

import gov.itms.rules.application.RuleService;
import gov.itms.rules.domain.Channel;
import gov.itms.rules.domain.DecisionRecord;
import gov.itms.rules.domain.DecisionTable;
import gov.itms.rules.domain.RuleVersion;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Request/response shapes of {@code rules-service.v1.yaml}. */
final class ApiModels {

    private ApiModels() {
    }

    record ColumnDto(@NotBlank String name, @NotNull DecisionTable.Type type) {
    }

    record ConditionDto(@NotNull DecisionTable.Op op, @Size(max = 100) List<Object> values) {
    }

    record RuleDto(@Size(max = 30) Map<String, @NotNull @Valid ConditionDto> when,
                   @Size(max = 30) Map<String, Object> then,
                   @Size(max = 200) String description) {
    }

    record TableDto(@NotNull DecisionTable.HitPolicy hitPolicy,
                    @NotNull @Size(min = 1, max = 30) List<@NotNull @Valid ColumnDto> inputs,
                    @NotNull @Size(min = 1, max = 30) List<@NotNull @Valid ColumnDto> outputs,
                    @NotNull @Size(min = 1, max = DecisionTable.MAX_RULES) List<@NotNull @Valid RuleDto> rules,
                    @Size(max = 30) Map<String, Object> defaults) {

        DecisionTable toDomain() {
            return new DecisionTable(
                    inputs.stream().map(c -> new DecisionTable.Column(c.name(), c.type())).toList(),
                    outputs.stream().map(c -> new DecisionTable.Column(c.name(), c.type())).toList(),
                    hitPolicy,
                    rules.stream().map(r -> {
                        Map<String, DecisionTable.Condition> when = new LinkedHashMap<>();
                        if (r.when() != null) {
                            r.when().forEach((k, c) -> when.put(k, new DecisionTable.Condition(c.op(), c.values())));
                        }
                        return new DecisionTable.Rule(when, r.then(), r.description());
                    }).toList(),
                    defaults);
        }

        static TableDto of(DecisionTable t) {
            return new TableDto(t.hitPolicy(),
                    t.inputs().stream().map(c -> new ColumnDto(c.name(), c.type())).toList(),
                    t.outputs().stream().map(c -> new ColumnDto(c.name(), c.type())).toList(),
                    t.rules().stream().map(r -> {
                        Map<String, ConditionDto> when = new LinkedHashMap<>();
                        r.when().forEach((k, c) -> when.put(k, new ConditionDto(c.op(), c.values())));
                        return new RuleDto(when, r.then(), r.description());
                    }).toList(),
                    t.defaults());
        }
    }

    record SubmitVersionRequest(
            @NotBlank String ruleSetCode,
            @NotBlank @Size(max = 200) String nameNl,
            @NotBlank @Size(max = 200) String nameEn,
            String region,
            @NotNull @Valid TableDto table,
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo,
            @NotBlank @Size(min = 3, max = 500) String reason) {

        RuleVersion.Spec toDomain() {
            return new RuleVersion.Spec(ruleSetCode, nameNl, nameEn, region, table.toDomain(), effectiveFrom,
                    effectiveTo);
        }
    }

    record DecisionRequest(@Size(min = 3, max = 500) String reason) {
    }

    record RejectRequest(@NotBlank @Size(min = 3, max = 500) String reason) {
    }

    record DecisionDto(String actorId, String actorUsername, Channel channel, Instant at, String reason) {
        static DecisionDto of(RuleVersion.Decision d) {
            return d == null ? null
                    : new DecisionDto(d.actor().id(), d.actor().username(), d.channel(), d.at(), d.reason());
        }
    }

    record RuleVersionDto(UUID id, String ruleSetCode, int versionNo, String nameNl, String nameEn, String region,
                          TableDto table, LocalDate effectiveFrom, LocalDate effectiveTo, RuleVersion.Status status,
                          DecisionDto submitted, DecisionDto decided) {
        static RuleVersionDto of(RuleVersion v) {
            RuleVersion.Spec s = v.spec();
            return new RuleVersionDto(v.id(), s.ruleSetCode(), v.versionNo(), s.nameNl(), s.nameEn(), s.region(),
                    TableDto.of(s.table()), s.effectiveFrom(), v.effectiveTo(), v.status(),
                    DecisionDto.of(v.submitted()), DecisionDto.of(v.decided()));
        }
    }

    record TestCaseDto(@Size(max = 30) Map<String, Object> inputs, @Size(max = 30) Map<String, Object> expected) {
    }

    record TestRunRequest(@NotNull @Size(min = 1, max = 100) List<@NotNull @Valid TestCaseDto> cases) {
        List<RuleService.TestCase> toDomain() {
            return cases.stream().map(c -> new RuleService.TestCase(c.inputs(), c.expected())).toList();
        }
    }

    record TestRunDto(int cases, long passed, long failed, List<RuleService.TestResult> results) {
        static TestRunDto of(List<RuleService.TestResult> results) {
            return new TestRunDto(results.size(),
                    results.stream().filter(r -> Boolean.TRUE.equals(r.passed())).count(),
                    results.stream().filter(r -> Boolean.FALSE.equals(r.passed())).count(), results);
        }
    }

    record EvaluationRequest(String region, LocalDate date, @NotNull @Size(max = 30) Map<String, Object> inputs,
                             @Size(max = 100) String reference) {
    }

    record DecisionRecordDto(UUID id, String ruleSetCode, UUID versionId, int versionNo, String region,
                             LocalDate date, Map<String, Object> inputs, List<Integer> matchedRules,
                             List<Map<String, Object>> outputs, String reference, String actorId, Channel channel,
                             String correlationId, Instant at) {
        static DecisionRecordDto of(DecisionRecord d) {
            return new DecisionRecordDto(d.id(), d.ruleSetCode(), d.versionId(), d.versionNo(), d.region(), d.date(),
                    d.inputs(), d.matchedRules(), d.outputs(), d.reference(), d.actorId(), d.channel(),
                    d.correlationId(), d.at());
        }
    }

    record Collection<T>(List<T> items) {
    }
}
