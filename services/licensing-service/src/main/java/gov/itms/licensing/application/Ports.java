package gov.itms.licensing.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The other services licensing-service calls (ADR-034: with its own client-credentials account). */
public final class Ports {

    private Ports() {
    }

    /** fees-service quotes (CC-047). */
    public interface FeeQuotes {

        record Line(String financialItem, String beneficiary, String description, long amountMinor) {
        }

        record Quote(String id, List<Line> lines, long total, String currency) {
            public Quote {
                lines = List.copyOf(lines);
            }
        }

        /**
         * @param quantities fee formula units, for example {@code CLASSES} (number of classes issued)
         * @param expiryDate the licence's current expiry, for late fees (DL-051); null when there is none
         */
        Quote quote(String serviceCode, String region, String customerCategory, String subjectCategory,
                    java.util.Map<String, Integer> quantities, LocalDate expiryDate);
    }

    /** payment-service payables (FN-002). */
    public interface Payables {

        UUID register(String itemRef, String trafficFileNumber, String region, String currency,
                      List<FeeQuotes.Line> lines);
    }

    /** hold-eligibility-service (CC-032): the holds that block a service for a person or licence (DL-050). */
    public interface Eligibility {

        record Subject(String type, String id) {
        }

        /** A hold's reason, next step and customer message, as hold-eligibility-service worded them. */
        record Block(String reasonCode, String nextStep, String message) {
        }

        /** Every block of {@code serviceCode} for the subjects; empty when the service may go ahead. */
        List<Block> blocks(String serviceCode, List<Subject> subjects, String channel);
    }

    /** A partner service did not answer; nothing was changed (fail closed). */
    public static class UnavailableException extends RuntimeException {
        private final String code;

        public UnavailableException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
