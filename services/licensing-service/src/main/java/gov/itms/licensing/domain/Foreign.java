package gov.itms.licensing.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Foreign licences and permits (DL-054…DL-056, DL-063): the exchange rule of each issuing country, the master file of
 * foreign licences, temporary driving permits and international driving permits.
 */
public final class Foreign {

    private Foreign() {
    }

    private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2,3}$");
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,39}$");
    private static final Pattern REF = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_./:-]{0,59}$");
    private static final Pattern LANGUAGE = Pattern.compile("^[a-z]{2,3}$");

    // ---- exchange rule per country (DL-054)

    /** DL-054: a country's licences are exchanged directly or only after the tests. */
    public enum ExchangeMode { DIRECT_EXCHANGE, TEST_REQUIRED }

    public enum RuleStatus { PENDING, APPROVED, REJECTED, SUPERSEDED }

    /**
     * The exchange rule of one issuing country, under maker-checker (CC-002): its mode and, for direct exchange, which
     * local class each foreign class becomes.
     */
    public record CountryRule(UUID id, String countryCode, ExchangeMode mode, Map<String, String> classMap,
                              RuleStatus status, String submittedBy, Instant submittedAt, String submitReason,
                              String decidedBy, Instant decidedAt, String decisionReason) {
        public CountryRule {
            Objects.requireNonNull(id);
            if (countryCode == null || !COUNTRY.matcher(countryCode).matches()) {
                throw invalid("countryCode", "DL-054");
            }
            if (mode == null) {
                throw invalid("mode", "DL-054");
            }
            classMap = classMap == null ? Map.of() : Map.copyOf(classMap);
            if (classMap.size() > 30 || classMap.entrySet().stream().anyMatch(e -> !CODE.matcher(e.getKey()).matches()
                    || e.getValue() == null || !CODE.matcher(e.getValue()).matches())) {
                throw invalid("classMap", "DL-054");
            }
            if (mode == ExchangeMode.DIRECT_EXCHANGE && classMap.isEmpty()) {
                throw DomainException.validation("CLASS_MAP_REQUIRED", "a direct exchange needs the local class of each"
                        + " foreign class", List.of("DL-054"));
            }
            Objects.requireNonNull(status);
        }

        public CountryRule decided(RuleStatus next, String by, Instant at, String reason) {
            if (status != RuleStatus.PENDING) {
                throw DomainException.conflict("COUNTRY_RULE_DECIDED", "this country rule is already " + status,
                        List.of("DL-054"), status.name());
            }
            if (next == RuleStatus.APPROVED && by.equals(submittedBy)) {
                throw new DomainException(DomainException.Kind.SEGREGATION_OF_DUTIES, "SAME_MAKER_CHECKER",
                        "the person who submitted a country rule cannot approve it", List.of("DL-054", "CC-002"));
            }
            return new CountryRule(id, countryCode, mode, classMap, next, submittedBy, submittedAt, submitReason, by,
                    at, reason);
        }

        public CountryRule superseded() {
            return new CountryRule(id, countryCode, mode, classMap, RuleStatus.SUPERSEDED, submittedBy, submittedAt,
                    submitReason, decidedBy, decidedAt, decisionReason);
        }
    }

    // ---- foreign licence master file (DL-063)

    public enum ForeignStatus { RECORDED, EXCHANGED, TEST_REQUIRED }

    /** A foreign licence as recorded from the holder's documents (DL-063). */
    public record ForeignLicence(UUID id, String trafficFileNumber, String countryCode, String foreignNumber,
                                 List<String> classes, LocalDate issuedOn, LocalDate expiresOn, String language,
                                 String documentId, String translationDocumentId, ForeignStatus status,
                                 UUID licenceId, String recordedBy, Instant recordedAt) {
        public ForeignLicence {
            Objects.requireNonNull(id);
            if (trafficFileNumber == null || !REF.matcher(trafficFileNumber).matches()) {
                throw invalid("trafficFileNumber", "DL-063");
            }
            if (countryCode == null || !COUNTRY.matcher(countryCode).matches()) {
                throw invalid("countryCode", "DL-063");
            }
            if (foreignNumber == null || !REF.matcher(foreignNumber).matches()) {
                throw invalid("foreignNumber", "DL-063");
            }
            classes = classes == null ? List.of() : List.copyOf(classes);
            if (classes.isEmpty() || classes.size() > 30 || classes.stream().anyMatch(c -> c == null
                    || !CODE.matcher(c).matches()) || new HashSet<>(classes).size() != classes.size()) {
                throw invalid("classes", "DL-063");
            }
            if (expiresOn == null || (issuedOn != null && !expiresOn.isAfter(issuedOn))) {
                throw invalid("expiresOn", "DL-063");
            }
            if (language == null || !LANGUAGE.matcher(language).matches()) {
                throw invalid("language", "DL-054");
            }
            if (documentId == null || !REF.matcher(documentId).matches()) {
                throw invalid("documentId", "DL-063");
            }
            if (translationDocumentId != null && !REF.matcher(translationDocumentId).matches()) {
                throw invalid("translationDocumentId", "DL-054");
            }
            Objects.requireNonNull(status);
        }

        public boolean validOn(LocalDate day) {
            return !expiresOn.isBefore(day);
        }

        public ForeignLicence withStatus(ForeignStatus next, UUID licence) {
            return new ForeignLicence(id, trafficFileNumber, countryCode, foreignNumber, classes, issuedOn, expiresOn,
                    language, documentId, translationDocumentId, next, licence, recordedBy, recordedAt);
        }
    }

    // ---- temporary driving permit (DL-055)

    /** DL-055: for visitors (on a valid foreign licence) and for holders whose card is pending. */
    public enum PermitKind { VISITOR, CARD_PENDING }

    public enum PermitStatus { ACTIVE, CANCELLED }

    public record TemporaryPermit(UUID id, String permitNumber, PermitKind kind, String trafficFileNumber,
                                  String region, UUID licenceId, UUID foreignLicenceId, List<String> classes,
                                  LocalDate validFrom, LocalDate validUntil, PermitStatus status, UUID requestId,
                                  Instant issuedAt) {
        public TemporaryPermit {
            Objects.requireNonNull(id);
            Objects.requireNonNull(permitNumber);
            Objects.requireNonNull(kind);
            classes = classes == null ? List.of() : List.copyOf(classes);
            if (validUntil.isBefore(validFrom)) {
                throw DomainException.validation("PERMIT_DATES_INVALID", "the permit would end before it starts",
                        List.of("DL-055"));
            }
            Objects.requireNonNull(status);
        }

        /** ACTIVE, EXPIRED (past its end) or CANCELLED on {@code day}. */
        public String statusOn(LocalDate day) {
            return status == PermitStatus.ACTIVE && validUntil.isBefore(day) ? "EXPIRED" : status.name();
        }

        public TemporaryPermit cancelled() {
            if (status != PermitStatus.ACTIVE) {
                throw DomainException.conflict("PERMIT_NOT_ACTIVE", "the permit is already " + status,
                        List.of("DL-055"));
            }
            return new TemporaryPermit(id, permitNumber, kind, trafficFileNumber, region, licenceId, foreignLicenceId,
                    classes, validFrom, validUntil, PermitStatus.CANCELLED, requestId, issuedAt);
        }
    }

    // ---- international driving permit (DL-056)

    public enum IdpStatus { ISSUED, PRINTED, CANCELLED }

    /** DL-056: issued against a valid national licence; printed at a centre or by an approved partner. */
    public record InternationalPermit(UUID id, String permitNumber, UUID licenceId, LocalDate issuedOn,
                                      LocalDate validUntil, String printer, IdpStatus status, String printedBy,
                                      Instant printedAt, UUID requestId, Instant issuedAt) {
        public InternationalPermit {
            Objects.requireNonNull(id);
            Objects.requireNonNull(permitNumber);
            Objects.requireNonNull(licenceId);
            Objects.requireNonNull(issuedOn);
            Objects.requireNonNull(validUntil);
            if (printer == null || !CODE.matcher(printer).matches()) {
                throw invalid("printer", "DL-056");
            }
            Objects.requireNonNull(status);
        }

        public InternationalPermit printed(String by, Instant at) {
            if (status != IdpStatus.ISSUED) {
                throw DomainException.conflict("IDP_NOT_ISSUED", "the permit is already " + status,
                        List.of("DL-056"));
            }
            return new InternationalPermit(id, permitNumber, licenceId, issuedOn, validUntil, printer,
                    IdpStatus.PRINTED, by, at, requestId, issuedAt);
        }
    }

    private static DomainException invalid(String field, String ref) {
        return DomainException.validation("FIELD_INVALID", field + " is missing or malformed", List.of(ref), field);
    }
}
