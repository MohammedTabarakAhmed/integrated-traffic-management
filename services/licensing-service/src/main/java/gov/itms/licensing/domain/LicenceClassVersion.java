package gov.itms.licensing.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One version of a licence class (DL-020, DL-062; BRD §6.2: "administrators can add classes and change ages and
 * validity"). A class has rules per customer category: minimum age, optional maximum age, required tests, minimum
 * training hours, medical need and validity; a rule without a category applies to every category not listed. A class
 * may record the gearbox of the test vehicle (DL-022).
 * <p>
 * Submitted PENDING by a maker and decided once by a different checker (CC-002, as for rules and fees); never starts in
 * the past; approving a successor ends the previous approved version the day before. A version never changes otherwise
 * and is never deleted, so a licence always points at the exact class rules it was issued under.
 */
public final class LicenceClassVersion {

    public enum Status { PENDING, APPROVED, REJECTED }

    public static final String CODE = "^[A-Z][A-Z0-9_]{0,19}$";
    private static final Pattern CODE_PATTERN = Pattern.compile(CODE);
    private static final Pattern CATEGORY = Pattern.compile("^[A-Z][A-Z0-9_]{0,39}$");
    private static final Pattern TEST = Pattern.compile("^[A-Z][A-Z0-9_]{0,29}$");
    static final int MIN_AGE = 10;
    static final int MAX_AGE = 120;
    static final int MAX_TRAINING_HOURS = 1000;
    static final int MAX_VALIDITY_MONTHS = 240;
    static final int MAX_RULES = 50;
    static final int MAX_TESTS = 10;

    /**
     * The rule of one customer category ({@code null} = every category not listed).
     *
     * @param requiredTests   test type codes of examination-service (DL-043), in the order they are taken
     * @param residencyLinked for residents: the licence never outlives the residency (DL-062)
     */
    public record CategoryRule(String customerCategory, int minAgeYears, Integer maxAgeYears,
                               List<String> requiredTests, int minTrainingHours, boolean medicalRequired,
                               int validityMonths, boolean residencyLinked) {
        public CategoryRule {
            if (customerCategory != null && !CATEGORY.matcher(customerCategory).matches()) {
                throw invalid("customerCategory");
            }
            if (minAgeYears < MIN_AGE || minAgeYears > MAX_AGE) {
                throw invalid("minAgeYears");
            }
            if (maxAgeYears != null && (maxAgeYears < minAgeYears || maxAgeYears > MAX_AGE)) {
                throw DomainException.validation("CLASS_AGES_INVALID",
                        "the maximum age must lie between the minimum age and " + MAX_AGE, List.of("DL-020"));
            }
            requiredTests = requiredTests == null ? List.of() : List.copyOf(requiredTests);
            if (requiredTests.size() > MAX_TESTS
                    || requiredTests.stream().anyMatch(t -> t == null || !TEST.matcher(t).matches())) {
                throw invalid("requiredTests");
            }
            if (new HashSet<>(requiredTests).size() != requiredTests.size()) {
                throw DomainException.validation("LIST_DUPLICATES", "requiredTests lists a test twice",
                        List.of("DL-020"), "requiredTests");
            }
            if (minTrainingHours < 0 || minTrainingHours > MAX_TRAINING_HOURS) {
                throw invalid("minTrainingHours");
            }
            if (validityMonths < 1 || validityMonths > MAX_VALIDITY_MONTHS) {
                throw invalid("validityMonths");
            }
        }

        /** Whether someone of this age (in whole years) may hold or apply for the class. */
        public boolean ageAllowed(int ageYears) {
            return ageYears >= minAgeYears && (maxAgeYears == null || ageYears <= maxAgeYears);
        }
    }

    /** What a maker proposes. */
    public record Spec(String classCode, String nameNl, String nameEn, String vehicleTypeNl, String vehicleTypeEn,
                       boolean gearboxRecorded, List<CategoryRule> rules, LocalDate effectiveFrom,
                       LocalDate effectiveTo) {
        public Spec {
            if (classCode == null || !CODE_PATTERN.matcher(classCode).matches()) {
                throw invalid("classCode");
            }
            nameNl = text("nameNl", nameNl);
            nameEn = text("nameEn", nameEn);
            vehicleTypeNl = text("vehicleTypeNl", vehicleTypeNl);
            vehicleTypeEn = text("vehicleTypeEn", vehicleTypeEn);
            if (rules == null || rules.isEmpty() || rules.size() > MAX_RULES
                    || rules.stream().anyMatch(Objects::isNull)) {
                throw DomainException.validation("LIST_SIZE_INVALID", "rules needs 1 to " + MAX_RULES + " entries",
                        List.of("DL-020"), "rules", 1, MAX_RULES);
            }
            rules = List.copyOf(rules);
            Set<String> categories = new HashSet<>();
            for (CategoryRule r : rules) {
                if (!categories.add(r.customerCategory() == null ? "" : r.customerCategory())) {
                    throw DomainException.validation("LIST_DUPLICATES", "rules lists a customer category twice",
                            List.of("DL-020"), "rules");
                }
            }
            if (effectiveFrom == null) {
                throw invalid("effectiveFrom");
            }
            if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
                throw DomainException.validation("CLASS_DATES_INVALID", "the end date is before the start date",
                        List.of("DL-020"));
            }
        }

        /** The rule of a customer category, or the rule for every other category; empty if neither exists. */
        public Optional<CategoryRule> ruleFor(String customerCategory) {
            return rules.stream().filter(r -> Objects.equals(r.customerCategory(), customerCategory)).findFirst()
                    .or(() -> rules.stream().filter(r -> r.customerCategory() == null).findFirst());
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

    private LicenceClassVersion(UUID id, int versionNo, Spec spec, Decision submitted, Status status,
                                Decision decided, LocalDate effectiveTo) {
        this.id = Objects.requireNonNull(id);
        this.versionNo = versionNo;
        this.spec = Objects.requireNonNull(spec);
        this.submitted = Objects.requireNonNull(submitted);
        this.status = Objects.requireNonNull(status);
        this.decided = decided;
        this.effectiveTo = effectiveTo;
    }

    public static LicenceClassVersion submit(UUID id, int versionNo, Spec spec, String reason, Actor maker,
                                             Channel channel, Instant now, LocalDate today) {
        requireNotPast(spec.effectiveFrom(), today);
        return new LicenceClassVersion(id, versionNo, spec, new Decision(maker, channel, now, reason(reason)),
                Status.PENDING, null, spec.effectiveTo());
    }

    public static LicenceClassVersion restore(UUID id, int versionNo, Spec spec, Decision submitted, Status status,
                                              Decision decided, LocalDate effectiveTo) {
        return new LicenceClassVersion(id, versionNo, spec, submitted, status, decided, effectiveTo);
    }

    public void approve(Actor checker, Channel channel, String reason, Instant now, LocalDate today) {
        requirePending();
        if (checker.id().equals(submitted.actor().id())) {
            throw new DomainException(DomainException.Kind.SEGREGATION_OF_DUTIES, "SAME_MAKER_CHECKER",
                    "the person who submitted a licence class change cannot approve it", List.of("DL-020", "CC-002"));
        }
        requireNotPast(spec.effectiveFrom(), today);
        Decision decision = new Decision(checker, channel, now, reason == null ? null : reason(reason));
        status = Status.APPROVED;
        decided = decision;
    }

    public void reject(Actor checker, Channel channel, String reason, Instant now) {
        requirePending();
        Decision decision = new Decision(checker, channel, now, reason(reason));
        status = Status.REJECTED;
        decided = decision;
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
        return (effectiveTo == null || !effectiveTo.isBefore(from))
                && (to == null || !spec.effectiveFrom().isAfter(to));
    }

    private void requirePending() {
        if (status != Status.PENDING) {
            throw DomainException.conflict("CLASS_VERSION_DECIDED", "this licence class version is already " + status,
                    List.of("DL-020"), status.name());
        }
    }

    private static void requireNotPast(LocalDate from, LocalDate today) {
        if (from.isBefore(today)) {
            throw DomainException.validation("CLASS_START_IN_PAST", "a licence class version cannot start in the past",
                    List.of("DL-020"));
        }
    }

    static String reason(String value) {
        String v = value == null ? "" : value.strip();
        if (v.length() < 3 || v.length() > 500 || v.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n')) {
            throw DomainException.validation("REASON_INVALID", "give a reason of 3 to 500 characters",
                    List.of("CC-120"));
        }
        return v;
    }

    private static String text(String field, String value) {
        if (value == null || value.isBlank() || value.strip().length() > 200
                || value.chars().anyMatch(Character::isISOControl)) {
            throw invalid(field);
        }
        return value.strip();
    }

    static DomainException invalid(String field) {
        return DomainException.validation("FIELD_INVALID", field + " is missing or malformed", List.of("DL-020"),
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
