package gov.itms.violation.application;

import gov.itms.violation.domain.BlackPoints;
import gov.itms.violation.domain.CatalogueEntry;
import gov.itms.violation.domain.Violation;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Ports of violation-service. */
public final class Ports {

    private Ports() {
    }

    public interface ViolationRepository {

        void lockCatalogue(String code);

        int nextCatalogueVersion(String code);

        void insertCatalogue(CatalogueEntry entry);

        /** Every version of an entry, newest first. */
        List<CatalogueEntry> catalogueVersions(String code);

        List<CatalogueEntry> latestCatalogue();

        long nextViolationSequence();

        void insertViolation(Violation violation);

        Optional<Violation> findByNumber(String number);

        Optional<Violation> findForUpdate(String number);

        void updateViolation(Violation violation);

        /** Violations of a responsible file, newest first. */
        List<Violation> byFile(String fileNumber, Violation.Status status, int limit);

        /** Whether the file had a non-cancelled violation of this code since {@code since} (VF-003). */
        boolean hadViolationSince(String fileNumber, String catalogueCode, Instant since);

        /** Locks a file's black points account (serialises additions for one driver). */
        void lockFile(String fileNumber);

        void insertPoints(BlackPoints.Entry entry);

        List<BlackPoints.Entry> points(String fileNumber);

        void setPointsStatus(List<UUID> ids, BlackPoints.EntryStatus status);

        void cancelPointsOf(UUID violationId);

        List<BlackPoints.Suspension> suspensions(String fileNumber);

        void insertSuspension(BlackPoints.Suspension suspension);
    }

    /** payment-service payables (FN-002). */
    public interface Payables {

        UUID register(String violationNumber, String fileNumber, String region, String currency, long amountMinor,
                      String financialItem, String beneficiary, String description);

        void cancel(UUID payableId, String reason);
    }

    /** hold-eligibility-service holds (CC-030). */
    public interface Holds {

        /** Places a hold on the file's licence services from {@code from} to {@code until}; returns the hold id. */
        String placeSuspensionHold(String fileNumber, LocalDate from, LocalDate until, String sourceRef,
                                   String reason);
    }

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
