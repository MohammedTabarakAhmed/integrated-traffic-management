package gov.itms.licensing.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** The licence's other records: cards (DL-052, DL-060), eye tests (DL-007, DL-051) and auto-renewal consents (DL-065). */
public final class Records {

    private Records() {
    }

    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,39}$");
    private static final Pattern REF = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,99}$");

    // ---- cards (DL-052, DL-060)

    /** How the holder gets the card (DL-060): printed at a centre, or by a card printer with courier delivery. */
    public enum Delivery { CENTRE, COURIER }

    public enum CardReason { ISSUE, RENEWAL, REPLACEMENT, CHANGE, CLASS_ADDED }

    /**
     * Card states: a centre card is printed and collected; a courier card goes to the card printer, is printed,
     * dispatched and delivered. Any card not yet in the holder's hands, or held, can be cancelled (DL-052).
     */
    public enum CardStatus {
        PENDING_PRINT, SENT_TO_PRINTER, PRINTED, DISPATCHED, DELIVERED, COLLECTED, RETURNED, CANCELLED;

        private static final Map<CardStatus, Set<CardStatus>> NEXT = Map.of(
                PENDING_PRINT, EnumSet.of(SENT_TO_PRINTER, PRINTED, CANCELLED),
                SENT_TO_PRINTER, EnumSet.of(PRINTED, CANCELLED),
                PRINTED, EnumSet.of(DISPATCHED, COLLECTED, CANCELLED),
                DISPATCHED, EnumSet.of(DELIVERED, RETURNED, CANCELLED),
                RETURNED, EnumSet.of(DISPATCHED, COLLECTED, CANCELLED),
                DELIVERED, EnumSet.of(CANCELLED),
                COLLECTED, EnumSet.of(CANCELLED),
                CANCELLED, EnumSet.noneOf(CardStatus.class));

        public boolean canMoveTo(CardStatus next) {
            return NEXT.get(this).contains(next);
        }
    }

    public record Card(UUID id, UUID licenceId, int serial, CardReason reason, Delivery delivery, String address,
                       CardStatus status, String trackingRef, Instant createdAt, Instant statusAt) {
        public Card {
            Objects.requireNonNull(id);
            Objects.requireNonNull(licenceId);
            Objects.requireNonNull(reason);
            Objects.requireNonNull(delivery);
            Objects.requireNonNull(status);
            if (delivery == Delivery.COURIER && (address == null || address.isBlank())) {
                throw DomainException.validation("ADDRESS_REQUIRED", "courier delivery needs an address",
                        List.of("DL-060"));
            }
        }

        public Card moveTo(CardStatus next, String tracking, Instant now) {
            if (!status.canMoveTo(next)) {
                throw DomainException.conflict("CARD_TRANSITION_INVALID", "a " + status + " card cannot become " + next,
                        List.of("DL-060"), status.name(), next.name());
            }
            return new Card(id, licenceId, serial, reason, delivery, address, next,
                    tracking == null ? trackingRef : tracking, createdAt, now);
        }

        public boolean live() {
            return status != CardStatus.CANCELLED;
        }
    }

    /** Checks an address given for courier delivery: 5 to 300 printable characters. */
    public static String address(String value) {
        if (value == null) {
            return null;
        }
        String v = value.strip();
        if (v.length() < 5 || v.length() > 300 || v.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n')) {
            throw DomainException.validation("FIELD_INVALID", "address is malformed", List.of("DL-060"), "address");
        }
        return v;
    }

    // ---- eye tests (DL-007, DL-051)

    public enum EyeResult { PASS, FAIL }

    /**
     * An eye test result sent by an approved optometry centre (DL-007): result, corrective lens condition and the
     * licence classes it permits (empty = every class).
     */
    public record EyeTest(UUID id, String trafficFileNumber, String centreCode, String reference, LocalDate testedOn,
                          EyeResult result, boolean correctiveLenses, List<String> permittedClasses,
                          Instant receivedAt, String receivedBy) {
        public EyeTest {
            Objects.requireNonNull(id);
            if (trafficFileNumber == null || !REF.matcher(trafficFileNumber).matches()) {
                throw invalid("trafficFileNumber", "DL-007");
            }
            if (centreCode == null || !CODE.matcher(centreCode).matches()) {
                throw invalid("centreCode", "DL-007");
            }
            if (reference == null || !REF.matcher(reference).matches()) {
                throw invalid("reference", "DL-007");
            }
            if (testedOn == null || result == null) {
                throw invalid(testedOn == null ? "testedOn" : "result", "DL-007");
            }
            permittedClasses = permittedClasses == null ? List.of() : List.copyOf(permittedClasses);
            if (permittedClasses.size() > 30
                    || permittedClasses.stream().anyMatch(c -> c == null || !CODE.matcher(c).matches())) {
                throw invalid("permittedClasses", "DL-007");
            }
            Objects.requireNonNull(receivedAt);
        }

        /** A passed test, taken no earlier than {@code validDays} before {@code today}, covering every class. */
        public boolean covers(List<String> classCodes, LocalDate today, int validDays) {
            return result == EyeResult.PASS && !testedOn.isAfter(today)
                    && !testedOn.isBefore(today.minusDays(validDays))
                    && (permittedClasses.isEmpty() || permittedClasses.containsAll(classCodes));
        }
    }

    // ---- automatic renewal (DL-065)

    public enum ConsentStatus { ACTIVE, WITHDRAWN }

    /**
     * The holder's consent to automatic renewal with a payment method on file (DL-065). For each expiry date a notice
     * goes out first; renewal runs only after the notice period, and only once per expiry date.
     */
    public record AutoRenewal(UUID licenceId, String paymentMethodRef, ConsentStatus status, String consentBy,
                              Instant consentAt, LocalDate noticeForExpiry, Instant noticeAt,
                              LocalDate attemptForExpiry, Instant attemptAt, String withdrawnBy, Instant withdrawnAt) {
        public AutoRenewal {
            Objects.requireNonNull(licenceId);
            if (paymentMethodRef == null || !REF.matcher(paymentMethodRef).matches()) {
                throw invalid("paymentMethodRef", "DL-065");
            }
            Objects.requireNonNull(status);
        }

        public static AutoRenewal consent(UUID licenceId, String paymentMethodRef, String by, Instant now) {
            return new AutoRenewal(licenceId, paymentMethodRef, ConsentStatus.ACTIVE, by, now, null, null, null, null,
                    null, null);
        }

        public AutoRenewal noticed(LocalDate expiry, Instant now) {
            return new AutoRenewal(licenceId, paymentMethodRef, status, consentBy, consentAt, expiry, now,
                    attemptForExpiry, attemptAt, withdrawnBy, withdrawnAt);
        }

        public AutoRenewal attempted(LocalDate expiry, Instant now) {
            return new AutoRenewal(licenceId, paymentMethodRef, status, consentBy, consentAt, noticeForExpiry,
                    noticeAt, expiry, now, withdrawnBy, withdrawnAt);
        }

        public AutoRenewal withdrawn(String by, Instant now) {
            if (status != ConsentStatus.ACTIVE) {
                throw DomainException.conflict("CONSENT_NOT_ACTIVE", "automatic renewal is not switched on",
                        List.of("DL-065"));
            }
            return new AutoRenewal(licenceId, paymentMethodRef, ConsentStatus.WITHDRAWN, consentBy, consentAt,
                    noticeForExpiry, noticeAt, attemptForExpiry, attemptAt, by, now);
        }
    }

    private static DomainException invalid(String field, String ref) {
        return DomainException.validation("FIELD_INVALID", field + " is missing or malformed", List.of(ref), field);
    }
}
