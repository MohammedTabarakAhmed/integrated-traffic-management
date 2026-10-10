package gov.itms.violation.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A customer's challenge to a recorded violation (VF-050..VF-052). */
public record Objection(UUID id, UUID violationId, String violationNumber, String submittedBy, Instant submittedAt,
                        String reasonCode, String explanation, List<String> evidenceIds, Status status,
                        String decidedBy, Instant decidedAt, Decision decision, String decisionReason) {

    public enum Status { PENDING, ACCEPTED, REJECTED, COURT }

    public enum Decision { ACCEPT, REJECT, REFER_TO_COURT }

    public Objection {
        Objects.requireNonNull(id);
        Objects.requireNonNull(violationId);
        Objects.requireNonNull(violationNumber);
        Objects.requireNonNull(submittedBy);
        Objects.requireNonNull(submittedAt);
        Objects.requireNonNull(status);
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        if (reasonCode == null || !reasonCode.matches("^[A-Z][A-Z0-9_]{1,39}$")
                || explanation == null || explanation.isBlank() || explanation.length() > 2000
                || evidenceIds.size() > 20 || evidenceIds.stream().anyMatch(s -> s == null || s.isBlank()
                || s.length() > 64)) {
            throw DomainException.validation("OBJECTION_INVALID", "objection details are invalid",
                    List.of("VF-050"));
        }
    }

    public Objection decide(Decision outcome, String actor, Instant at, String reason) {
        if (status != Status.PENDING) {
            throw DomainException.conflict("OBJECTION_DECIDED", "the objection has already been decided",
                    List.of("VF-051"));
        }
        if (outcome == null || actor == null || reason == null || reason.isBlank()) {
            throw DomainException.validation("DECISION_INVALID", "a decision, actor and reason are required",
                    List.of("VF-051"));
        }
        Status next = switch (outcome) {
            case ACCEPT -> Status.ACCEPTED;
            case REJECT -> Status.REJECTED;
            case REFER_TO_COURT -> Status.COURT;
        };
        return new Objection(id, violationId, violationNumber, submittedBy, submittedAt, reasonCode, explanation,
                evidenceIds, next, actor, at, outcome, reason);
    }
}
