package gov.itms.rules.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One version of a rule set (CC-050, CC-051; ADR-048): a decision table with effective dates, nationally or for one
 * region (a regional variant). Submitted PENDING by a maker, decided once by a different checker (CC-002, as for
 * fees and reference data); never starts in the past; approving a successor of the same rule set and region ends the
 * previous version the day before. A version never changes otherwise and is never deleted, so a logged decision always
 * points at the exact rules it used (CC-053).
 */
public final class RuleVersion {

    public enum Status { PENDING, APPROVED, REJECTED }

    public static final String CODE = "^[A-Z][A-Z0-9_]{1,59}$";
    private static final Pattern CODE_PATTERN = Pattern.compile(CODE);
    private static final Pattern REGION = Pattern.compile("^[A-Z0-9_]{1,40}$");

    /** What a maker proposes. */
    public record Spec(String ruleSetCode, String nameNl, String nameEn, String region, DecisionTable table,
                       LocalDate effectiveFrom, LocalDate effectiveTo) {
        public Spec {
            if (ruleSetCode == null || !CODE_PATTERN.matcher(ruleSetCode).matches()) {
                throw invalid("ruleSetCode");
            }
            nameNl = name("nameNl", nameNl);
            nameEn = name("nameEn", nameEn);
            if (region != null && !REGION.matcher(region).matches()) {
                throw invalid("region");
            }
            Objects.requireNonNull(table, "table");
            if (effectiveFrom == null) {
                throw invalid("effectiveFrom");
            }
            if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
                throw DomainException.validation("RULE_DATES_INVALID", "the end date is before the start date",
                        List.of("CC-051"));
            }
        }
    }

    /** Who submitted or decided, when, through which channel, and why. */
    public record Decision(Actor actor, Channel channel, Instant at, String reason) {
        public Decision {
            Objects.requireNonNull(actor);
            Objects.requireNonNull(channel);
            Objects.requireNonNull(at);
        }
    }

    private final UUID id;
    private final int versionNo;
    private final Spec spec;
    private final Decision submitted;
    private Status status;
    private Decision decided;
    private LocalDate effectiveTo;

    private RuleVersion(UUID id, int versionNo, Spec spec, Decision submitted, Status status, Decision decided,
                        LocalDate effectiveTo) {
        this.id = Objects.requireNonNull(id);
        this.versionNo = versionNo;
        this.spec = Objects.requireNonNull(spec);
        this.submitted = Objects.requireNonNull(submitted);
        this.status = Objects.requireNonNull(status);
        this.decided = decided;
        this.effectiveTo = effectiveTo;
    }

    public static RuleVersion submit(UUID id, int versionNo, Spec spec, String reason, Actor maker, Channel channel,
                                     Instant now, LocalDate today) {
        requireNotPast(spec.effectiveFrom(), today);
        return new RuleVersion(id, versionNo, spec, new Decision(maker, channel, now, reason(reason)), Status.PENDING,
                null, spec.effectiveTo());
    }

    public static RuleVersion restore(UUID id, int versionNo, Spec spec, Decision submitted, Status status,
                                      Decision decided, LocalDate effectiveTo) {
        return new RuleVersion(id, versionNo, spec, submitted, status, decided, effectiveTo);
    }

    public void approve(Actor checker, Channel channel, String reason, Instant now, LocalDate today) {
        requirePending();
        if (checker.id().equals(submitted.actor().id())) {
            throw new DomainException(DomainException.Kind.SEGREGATION_OF_DUTIES, "SAME_MAKER_CHECKER",
                    "the person who submitted a rule change cannot approve it", List.of("CC-051", "CC-002"));
        }
        requireNotPast(spec.effectiveFrom(), today);
        status = Status.APPROVED;
        decided = new Decision(checker, channel, now, reason == null ? null : reason(reason));
    }

    public void reject(Actor checker, Channel channel, String reason, Instant now) {
        requirePending();
        status = Status.REJECTED;
        decided = new Decision(checker, channel, now, reason(reason));
    }

    public void endBefore(LocalDate successorStart) {
        if (status != Status.APPROVED || !spec.effectiveFrom().isBefore(successorStart)) {
            throw new IllegalStateException("only an approved version that started earlier can be ended");
        }
        LocalDate end = successorStart.minusDays(1);
        if (effectiveTo == null || effectiveTo.isAfter(end)) {
            effectiveTo = end;
        }
    }

    public boolean inForce(LocalDate day) {
        return status == Status.APPROVED && !spec.effectiveFrom().isAfter(day)
                && (effectiveTo == null || !effectiveTo.isBefore(day));
    }

    public boolean overlaps(LocalDate from, LocalDate to) {
        return (effectiveTo == null || !effectiveTo.isBefore(from)) && (to == null || !spec.effectiveFrom().isAfter(to));
    }

    private void requirePending() {
        if (status != Status.PENDING) {
            throw DomainException.conflict("RULE_VERSION_DECIDED", "this rule version is already " + status,
                    List.of("CC-051"), status.name());
        }
    }

    private static void requireNotPast(LocalDate from, LocalDate today) {
        if (from.isBefore(today)) {
            throw DomainException.validation("RULE_START_IN_PAST", "a rule version cannot start in the past",
                    List.of("CC-051"));
        }
    }

    static String reason(String value) {
        String v = value == null ? "" : value.strip();
        if (v.length() < 3 || v.length() > 500 || v.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n')) {
            throw DomainException.validation("REASON_INVALID", "give a reason of 3 to 500 characters",
                    List.of("CC-051"));
        }
        return v;
    }

    private static String name(String field, String value) {
        if (value == null || value.isBlank() || value.strip().length() > 200
                || value.chars().anyMatch(Character::isISOControl)) {
            throw invalid(field);
        }
        return value.strip();
    }

    static DomainException invalid(String field) {
        return DomainException.validation("FIELD_INVALID", field + " is missing or malformed", List.of("CC-050"),
                field);
    }

    public UUID id() {
        return id;
    }

    public int versionNo() {
        return versionNo;
    }

    public Spec spec() {
        return spec;
    }

    public Decision submitted() {
        return submitted;
    }

    public Status status() {
        return status;
    }

    public Decision decided() {
        return decided;
    }

    public LocalDate effectiveTo() {
        return effectiveTo;
    }
}
