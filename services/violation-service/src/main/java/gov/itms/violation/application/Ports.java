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

        /** True while the violation is in an instalment plan that is not completed (VF-073). */
        default boolean inInstalmentPlan(UUID violationId) {
            return false;
        }

        /** True while an ACTIVE instalment plan covers the violation, so no unpaid hold applies (VF-073). */
        default boolean coveredByPlan(UUID violationId) {
            return false;
        }

        /** The fine set by an objection accepted with a reduction (VF-051); empty when not reduced. */
        default java.util.OptionalLong reducedFine(UUID violationId) {
            return java.util.OptionalLong.empty();
        }

        /**
         * Open or paid local violations carrying points, recorded before {@code recordedBefore}, with no points added
         * and no objection pending or at court, oldest first (VF-030 confirmation).
         */
        List<Violation> dueForConfirmation(Instant recordedBefore, int limit);

        /** True once the violation's black points were added (VF-030). */
        boolean pointsAdded(UUID violationId);

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

        /** The payable that replaces a cancelled one after an objection reduced the fine (VF-051). */
        default UUID registerReduced(String violationNumber, String fileNumber, String region, String currency,
                                     long amountMinor, String financialItem, String beneficiary, String description) {
            return register(violationNumber, fileNumber, region, currency, amountMinor, financialItem, beneficiary,
                    description);
        }

        /** One revenue line of a payable (FN-001). */
        record Line(String financialItem, String beneficiary, String description, long amountMinor) {
        }

        /** The payable of one instalment of a plan (VF-071); its item reference is the instalment's reference. */
        default UUID registerInstalment(String itemRef, String fileNumber, String region, String currency,
                                        java.util.List<Line> lines) {
            Line first = lines.getFirst();
            return register(itemRef, fileNumber, region, currency,
                    lines.stream().mapToLong(Line::amountMinor).sum(), first.financialItem(), first.beneficiary(),
                    first.description());
        }

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

        /** A pending objection with the region of its violation (CC-001 scope of the review queue). */
        record Queued(gov.itms.violation.domain.Objection objection, String region) {
        }

        /**
         * Pending objections in submission order after the given position (both null for the first page), without
         * those to partner-state violations.
         */
        java.util.List<Queued> pendingLocal(int limit, java.time.Instant afterSubmittedAt, UUID afterId);

        java.util.Optional<gov.itms.violation.domain.Objection> lock(UUID id);

        void saveDecision(gov.itms.violation.domain.Objection decided);

        /** Decides the pending objection to a violation; false when none was pending. */
        boolean decidePending(String violationNumber, gov.itms.violation.domain.Objection.Decision decision,
                              String decidedBy, java.time.Instant at, String reason);
    }

    /** Instalment plans (VF-071, VF-073; violation V9). */
    public interface InstalmentPlans {

        long nextPlanSequence();

        /** Inserts the plan, its violation links and its instalments; false if a violation is already in a plan. */
        boolean insert(gov.itms.violation.domain.InstalmentPlan plan);

        java.util.Optional<gov.itms.violation.domain.InstalmentPlan> find(String number);

        java.util.Optional<gov.itms.violation.domain.InstalmentPlan> lock(String number);

        java.util.List<gov.itms.violation.domain.InstalmentPlan> byFile(String fileNumber, int limit);

        /** Numbers of ACTIVE plans with an instalment still unpaid after its due date plus the plan's grace days. */
        java.util.List<String> activeWithLapsedInstalment(java.time.LocalDate today, int limit);

        void instalmentPaid(UUID planId, int seq, java.time.Instant at);

        void instalmentReopened(UUID planId, int seq);

        void status(UUID planId, gov.itms.violation.domain.InstalmentPlan.Status status, java.time.Instant closedAt);
    }

    /** vehicle-service: the local vehicle carrying a plate (XB-002). */
    public interface Vehicles {

        record Registered(String vin, String ownerFileNumber, String region) {
        }

        /** Empty when no vehicle carries the plate here. */
        java.util.Optional<Registered> byPlate(String plate);
    }

    /** Pledges recorded on violations (VF-046). */
    public interface Pledges {

        /** False when the violation already has a pledge. */
        boolean insert(gov.itms.violation.domain.ViolationPledge pledge);

        /** The pledges of one violation, newest first. */
        List<gov.itms.violation.domain.ViolationPledge> pledgesOf(String violationNumber);
    }

    /** Administrative preservation orders recorded on violations (VF-047). */
    public interface PreservationOrders {

        /** False when the violation already carries this order reference. */
        boolean insert(gov.itms.violation.domain.PreservationOrder order);

        /** The orders of one violation, newest first. */
        List<gov.itms.violation.domain.PreservationOrder> ordersOf(String violationNumber);
    }

    /** rental-service: the contract covering a plate at an instant (TR-032, VF-014). */
    public interface Rentals {

        /** {@code renterFile} is null for a visitor renter without a traffic file. */
        record Cover(UUID contractId, String companyFile, String renterFile, boolean visitor) {
        }

        /** Empty when no contract covers the plate at that instant; rental-service errors are UnavailableException. */
        java.util.Optional<Cover> covering(String plate, java.time.Instant at);
    }

    /**
     * The signed-in customer's traffic file (ADR-067 d), from traffic-file-service by the national id in their token.
     * Empty when they have no file; errors are UnavailableException.
     */
    public interface OwnFile {
        java.util.Optional<String> fileNumber();
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
