package gov.itms.violation.api;

import gov.itms.violation.application.ObjectionService;
import gov.itms.violation.domain.Objection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Objection submission and review (VF-050..VF-052). */
@RestController
class ObjectionController {

    record SubmitBody(@NotBlank String reasonCode, @NotBlank @Size(max = 2000) String explanation,
                      @Size(max = 20) List<@NotBlank @Size(max = 64) String> evidenceIds) {
    }

    record DecisionBody(@NotNull Objection.Decision decision, @NotBlank @Size(min = 3, max = 500) String reason) {
    }

    record ObjectionDto(UUID id, UUID violationId, String violationNumber, String submittedBy, Instant submittedAt,
                        String reasonCode, String explanation, List<String> evidenceIds, Objection.Status status,
                        String decidedBy, Instant decidedAt, Objection.Decision decision, String decisionReason) {
        static ObjectionDto of(Objection o) {
            return new ObjectionDto(o.id(), o.violationId(), o.violationNumber(), o.submittedBy(), o.submittedAt(),
                    o.reasonCode(), o.explanation(), o.evidenceIds(), o.status(), o.decidedBy(), o.decidedAt(),
                    o.decision(), o.decisionReason());
        }
    }

    record Collection<T>(List<T> items) {
    }

    private final ObjectionService objections;
    private final Callers callers;

    ObjectionController(ObjectionService objections, Callers callers) {
        this.objections = objections;
        this.callers = callers;
    }

    @PostMapping("/v1/violations/{number}/objections")
    @PreAuthorize("hasAuthority('violation.objection.submit')")
    ResponseEntity<ObjectionDto> submit(@PathVariable String number, @Valid @RequestBody SubmitBody body) {
        Objection objection = objections.submit(number, body.reasonCode(), body.explanation(),
                body.evidenceIds() == null ? List.of() : body.evidenceIds(), callers.current());
        return ResponseEntity.created(URI.create("/v1/objections/" + objection.id())).body(ObjectionDto.of(objection));
    }

    @GetMapping("/v1/objections/queue")
    @PreAuthorize("hasAuthority('violation.objection.review')")
    Collection<ObjectionDto> queue(@RequestParam(defaultValue = "100") int limit) {
        return new Collection<>(objections.queue(limit, callers.current()).stream().map(ObjectionDto::of).toList());
    }

    @PostMapping("/v1/objections/{id}/decisions")
    @PreAuthorize("hasAuthority('violation.objection.review')")
    ObjectionDto decide(@PathVariable UUID id, @Valid @RequestBody DecisionBody body) {
        return ObjectionDto.of(objections.decide(id, body.decision(), body.reason(), callers.current()));
    }
}
