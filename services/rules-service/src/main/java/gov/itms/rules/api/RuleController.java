package gov.itms.rules.api;

import gov.itms.rules.api.ApiModels.Collection;
import gov.itms.rules.api.ApiModels.DecisionRecordDto;
import gov.itms.rules.api.ApiModels.DecisionRequest;
import gov.itms.rules.api.ApiModels.EvaluationRequest;
import gov.itms.rules.api.ApiModels.RejectRequest;
import gov.itms.rules.api.ApiModels.RuleVersionDto;
import gov.itms.rules.api.ApiModels.SubmitVersionRequest;
import gov.itms.rules.api.ApiModels.TestRunDto;
import gov.itms.rules.api.ApiModels.TestRunRequest;
import gov.itms.rules.application.RuleService;
import gov.itms.rules.domain.DecisionRecord;
import gov.itms.rules.domain.RuleVersion;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Rule versions, test runs, evaluations and the decision log of {@code rules-service.v1.yaml} (CC-050..CC-053). */
@RestController
class RuleController {

    private final RuleService rules;
    private final Callers callers;

    RuleController(RuleService rules, Callers callers) {
        this.rules = rules;
        this.callers = callers;
    }

    @PostMapping("/v1/rule-versions")
    @PreAuthorize("hasAuthority('rules.version.submit')")
    ResponseEntity<RuleVersionDto> submit(@Valid @RequestBody SubmitVersionRequest body) {
        RuleVersion v = rules.submit(body.toDomain(), body.reason(), callers.current());
        return ResponseEntity.created(URI.create("/v1/rule-versions/" + v.id())).body(RuleVersionDto.of(v));
    }

    @GetMapping("/v1/rule-versions")
    @PreAuthorize("hasAuthority('rules.version.read')")
    Collection<RuleVersionDto> list(@RequestParam String ruleSetCode,
                                    @RequestParam(required = false) RuleVersion.Status status) {
        return new Collection<>(rules.byRuleSet(ruleSetCode, callers.current()).stream()
                .filter(v -> status == null || v.status() == status).map(RuleVersionDto::of).toList());
    }

    @GetMapping("/v1/rule-versions/{id}")
    @PreAuthorize("hasAuthority('rules.version.read')")
    RuleVersionDto get(@PathVariable UUID id) {
        return RuleVersionDto.of(rules.get(id, callers.current()));
    }

    @PostMapping("/v1/rule-versions/{id}/approvals")
    @PreAuthorize("hasAuthority('rules.version.approve')")
    RuleVersionDto approve(@PathVariable UUID id, @Valid @RequestBody DecisionRequest body) {
        return RuleVersionDto.of(rules.approve(id, body.reason(), callers.current()));
    }

    /** A checker rejects; the maker may withdraw their own pending version. */
    @PostMapping("/v1/rule-versions/{id}/rejections")
    @PreAuthorize("hasAuthority('rules.version.approve') or hasAuthority('rules.version.submit')")
    RuleVersionDto reject(@PathVariable UUID id, @Valid @RequestBody RejectRequest body) {
        return RuleVersionDto.of(rules.reject(id, body.reason(), callers.current()));
    }

    @PostMapping("/v1/rule-versions/{id}/test-runs")
    @PreAuthorize("hasAuthority('rules.version.test')")
    TestRunDto test(@PathVariable UUID id, @Valid @RequestBody TestRunRequest body) {
        return TestRunDto.of(rules.testRun(id, body.toDomain(), callers.current()));
    }

    @PostMapping("/v1/rule-sets/{ruleSetCode}/evaluations")
    @PreAuthorize("hasAuthority('rules.evaluate')")
    ResponseEntity<DecisionRecordDto> evaluate(@PathVariable String ruleSetCode,
                                               @Valid @RequestBody EvaluationRequest body) {
        DecisionRecord d = rules.evaluate(ruleSetCode, body.region(), body.date(), body.inputs(), body.reference(),
                callers.current());
        return ResponseEntity.created(URI.create("/v1/rule-decisions/" + d.id())).body(DecisionRecordDto.of(d));
    }

    @GetMapping("/v1/rule-decisions")
    @PreAuthorize("hasAuthority('rules.decision.read')")
    Collection<DecisionRecordDto> decisions(@RequestParam String ruleSetCode,
                                            @RequestParam(required = false) Instant since,
                                            @RequestParam(defaultValue = "50") int limit) {
        return new Collection<>(rules.decisions(ruleSetCode, since, limit, callers.current()).stream()
                .map(DecisionRecordDto::of).toList());
    }

    @GetMapping("/v1/rule-decisions/{id}")
    @PreAuthorize("hasAuthority('rules.decision.read')")
    DecisionRecordDto decision(@PathVariable UUID id) {
        return DecisionRecordDto.of(rules.decision(id, callers.current()));
    }
}
