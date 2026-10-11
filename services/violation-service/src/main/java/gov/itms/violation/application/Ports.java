package gov.itms.violation.application;

import gov.itms.violation.domain.BlackPoints;
import gov.itms.violation.domain.CatalogueEntry;
import gov.itms.violation.domain.UnpaidHold;
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

        /**
         * Open violations recorded before {@code recordedBefore} without an active unpaid hold and not under objection
         * (VF-052, Q-074 (f)), oldest first.
         */
        List<Violation> overdueWithoutHold(Instant recordedBefore, int limit);

        /** True while the violation has an objection awaiting review or referred to court (VF-052). */
        boolean underObjection(UUID violationId);

        /** Active unpaid holds whose violation is no longer open (paid or cancelled), oldest first. */
        List<UnpaidHold> settledWithActiveHold(int limit);

        Optional<UnpaidHold> activeUnpaidHold(UUID violationId);

        void insertUnpaidHold(UnpaidHold hold);

        void unpaidHoldLifted(UUID id, Instant at);
        void insertPaperBook(ViolationBooks.Book book);
        Optional<ViolationBooks.Book> paperBook(String serial);
        void insertPaperBookEntry(String serial,String reportSerial,String violationNumber,Instant at);
        List<String> paperBookReports(String serial);
        void finishPaperBook(String serial,String status,String actor,Instant at);
    }

    /** payment-service payables (FN-002). */
    public interface Payables {

        UUID register(String violationNumber, String fileNumber, String region, String currency, long amountMinor,
                      String financialItem, String beneficiary, String description);

        void cancel(UUID payableId, String reason);

        void hold(UUID payableId, String sourceRef, String reason);

        void liftHold(UUID payableId, String sourceRef, String reason);
    }

    /** hold-eligibility-service holds (CC-030). */
    public interface Holds {

        /** Places a hold on the file's licence services from {@code from} to {@code until}; returns the hold id. */
        String placeSuspensionHold(String fileNumber, LocalDate from, LocalDate until, String sourceRef,
                                   String reason);
    }

    /** hold-eligibility-service holds for unpaid violations (VF-023). */
    public interface UnpaidFineHolds {

        /** Places an UNPAID_FINES hold on the subject with no end date; returns the hold id. */
        String place(UnpaidHold.Subject subject, String sourceRef, String reason);

        /** Lifts the hold; a hold that is no longer active counts as lifted. */
        void lift(String holdId, String reason);
    }

    /** Which violations crossed a border, and with which partner state (XB-001, XB-002). */
    public interface CrossborderLinks {

        enum Direction { INBOUND, OUTBOUND }

        record Link(UUID violationId, String violationNumber, String partner, Direction direction,
                    String externalId) {
        }

        void insert(Link link, java.time.Instant at);

        java.util.Optional<Link> byExternalId(String partner, String externalId);

        java.util.Optional<Link> byNumber(String violationNumber);
    }

    /** Customer and partner objections (VF-050..VF-052, XB-005). */
    public interface Objections {

        /** False when the violation already has an objection. */
        boolean insert(gov.itms.violation.domain.Objection objection);

        /** Pending objections in submission order, without those to partner-state violations. */
        java.util.List<gov.itms.violation.domain.Objection> pendingLocal(int limit);

        java.util.Optional<gov.itms.violation.domain.Objection> lock(UUID id);

        void saveDecision(gov.itms.violation.domain.Objection decided);

        /** Decides the pending objection to a violation; false when none was pending. */
        boolean decidePending(String violationNumber, gov.itms.violation.domain.Objection.Decision decision,
                              String decidedBy, java.time.Instant at, String reason);
    }

    /** vehicle-service: the local vehicle carrying a plate (XB-002). */
    public interface Vehicles {

        record Registered(String vin, String ownerFileNumber, String region) {
        }

        /** Empty when no vehicle carries the plate here. */
        java.util.Optional<Registered> byPlate(String plate);
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
