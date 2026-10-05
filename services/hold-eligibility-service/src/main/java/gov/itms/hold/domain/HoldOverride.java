package gov.itms.hold.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A supervisor's override of a hold for one transaction (CC-034; LLD §2.2 "Active → Active"): the hold stays active
 * for every other transaction and is never changed; only the eligibility check for this transaction ignores it. An
 * override is recorded once, with a reason and a supporting document reference, and is never changed or deleted.
 *
 * @param transactionReference the one transaction it applies to (transaction-service's reference, CC-021)
 * @param serviceCode          the transaction's service, taken from its reference
 * @param document             the supporting document (a reference until the document service exists, Q-055)
 */
public record HoldOverride(UUID id, UUID holdId, String transactionReference, String serviceCode, String reason,
                           Document document, Actor actor, Channel channel, Instant at) {

    /** Transaction-service's reference {@code <ServiceCode>-<Region>-<YYYY>-<Sequence>} (CC-021). */
    public static final String TRANSACTION_REFERENCE = "^([A-Z][A-Z0-9_]{1,19})-[A-Z0-9_]{1,40}-[0-9]{4}-[0-9]{7}$";
    private static final Pattern REFERENCE = Pattern.compile(TRANSACTION_REFERENCE);

    /**
     * The supporting document (CC-034): its type (a code of the document-type list) and its number. Only a reference
     * until the document service (CC-080) exists; the file itself is not stored here (Q-055).
     */
    public record Document(String type, String number) {

        public Document {
            type = Codes.code("documentType", type);
            if (number == null || !Pattern.matches(Codes.SOURCE_REF, number)) {
                throw new DomainException(DomainException.Kind.VALIDATION, "INVALID_DOCUMENT_NUMBER",
                        "the document number must be 1-100 characters of letters, digits, '.', '_', ':', '/' or '-'",
                        List.of("CC-034"));
            }
        }
    }

    public HoldOverride {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(holdId, "holdId");
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(at, "at");
    }

    /** The service code in a transaction reference; anything that is not a reference is refused. */
    public static String serviceOf(String transactionReference) {
        Matcher m = transactionReference == null ? null : REFERENCE.matcher(transactionReference);
        if (m == null || !m.matches()) {
            throw new DomainException(DomainException.Kind.VALIDATION, "INVALID_TRANSACTION_REFERENCE",
                    "the transaction reference must look like SERVICE-REGION-YYYY-0000001", List.of("CC-034", "CC-021"));
        }
        return m.group(1);
    }

    /**
     * Overrides {@code hold} for one transaction. The hold must still be Active (not lifted or expired) and must block
     * the transaction's service, a reason is mandatory, and the supervisor may not be the officer who placed the hold
     * (segregation of duties, Q-055).
     */
    public static HoldOverride grant(UUID id, Hold hold, String transactionReference, String reason,
                                     Document document, Actor actor, Channel channel, Instant now, LocalDate today) {
        Objects.requireNonNull(channel, "channel");
        String serviceCode = serviceOf(transactionReference);
        String cleanReason = Codes.reason(reason);
        HoldStatus status = hold.status(today);
        if (status != HoldStatus.ACTIVE) {
            throw new DomainException(DomainException.Kind.CONFLICT, "OVERRIDE_HOLD_NOT_ACTIVE",
                    "this hold is " + status + ", so there is nothing to override", List.of("CC-034"), status);
        }
        List<String> blocked = hold.spec().blockedServices();
        if (!blocked.contains(Codes.ALL) && !blocked.contains(serviceCode)) {
            throw new DomainException(DomainException.Kind.CONFLICT, "OVERRIDE_NOT_NEEDED",
                    "this hold does not block service " + serviceCode, List.of("CC-034"), serviceCode);
        }
        if (hold.placed().actor().id().equals(actor.id())) {
            throw new DomainException(DomainException.Kind.SEGREGATION_OF_DUTIES, "OVERRIDE_BY_PLACER",
                    "the officer who placed a hold cannot override it", List.of("CC-034", "CC-002"));
        }
        return new HoldOverride(id, hold.id(), transactionReference, serviceCode, cleanReason, document, actor,
                channel, now);
    }
}
