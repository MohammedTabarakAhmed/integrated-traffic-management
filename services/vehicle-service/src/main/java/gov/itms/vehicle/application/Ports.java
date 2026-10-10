package gov.itms.vehicle.application;

import gov.itms.vehicle.domain.Plates;
import gov.itms.vehicle.domain.Records;
import gov.itms.vehicle.domain.Registration;
import gov.itms.vehicle.domain.Vehicle;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Ports of vehicle-service. */
public final class Ports {

    private Ports() {
    }

    public interface VehicleRepository {

        boolean activeVinExists(String vin);

        void insertVehicle(Vehicle vehicle);

        Optional<Vehicle> findByVin(String vin);

        /** The VIN of the vehicle that carries the assigned plate with this display text (XB-002). */
        default Optional<String> vinByPlate(String display) {
            return Optional.empty();
        }

        Optional<Vehicle> findForUpdate(UUID id);

        void updateVehicle(Vehicle vehicle);

        /** Appends to the vehicle file's history (VL-005). */
        void history(UUID vehicleId, String event, String actorId, Map<String, Object> detail, Instant at);

        record HistoryEntry(String event, String actorId, Map<String, Object> detail, Instant at) {
            public HistoryEntry {
                detail = detail == null ? null : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(detail));
            }
        }

        List<HistoryEntry> history(UUID vehicleId);

        void insertInspection(Registration.Inspection inspection);

        Optional<Registration.Inspection> latestInspection(UUID vehicleId);

        void saveSeries(Plates.Series series);

        Optional<Plates.Series> findSeries(String code);

        /** Active series of a region and plate category, by code. */
        List<Plates.Series> activeSeries(String region, String plateCategory);

        /** Locks the series and returns the next standard number not yet used, or empty when the range is used up. */
        Optional<Long> nextFreeNumber(Plates.Series series);

        void insertPlate(Plates.Plate plate);

        Optional<Plates.Plate> findPlate(UUID id);

        void updatePlate(Plates.Plate plate);

        void insertRequest(Registration.Request request);

        Optional<Registration.Request> findRequestForUpdate(UUID id);

        Optional<Registration.Request> openRequest(UUID vehicleId);

        void updateRequest(Registration.Request request);
    }

    /** The vehicle file's other records and the plate trade (Increment 2; ADR-059). */
    public interface RecordsRepository {

        // vehicles
        List<Vehicle> byOwner(String ownerFile, int limit);

        // customs and dealer certificates (VL-002)
        boolean insertCustoms(Records.CustomsCertificate certificate);

        Optional<Records.CustomsCertificate> customs(String certificateNo);

        void customsUsed(String certificateNo, UUID vehicleId);

        // bans (VL-006)
        void insertBan(Records.Ban ban);

        void updateBan(Records.Ban ban);

        Optional<Records.Ban> ban(UUID id);

        List<Records.Ban> bans(UUID vehicleId);

        // approvals (VL-018, VL-019)
        void insertApproval(Records.Approval approval);

        void updateApproval(Records.Approval approval);

        Optional<Records.Approval> approval(UUID id);

        List<Records.Approval> approvals(UUID vehicleId);

        // certificates (VL-015, VL-017, VL-020)
        String nextCertificateNumber(int year);

        void insertCertificate(Records.Certificate certificate);

        List<Records.Certificate> certificates(UUID vehicleId);

        // mortgages (VL-021)
        void insertMortgage(Records.Mortgage mortgage);

        void updateMortgage(Records.Mortgage mortgage);

        Optional<Records.Mortgage> mortgage(UUID id);

        List<Records.Mortgage> activeMortgages(UUID vehicleId);

        // cards (VL-014, VL-022)
        int nextCardSerial(UUID vehicleId);

        void insertCard(Records.Card card);

        void updateCard(Records.Card card);

        Optional<Records.Card> card(UUID id);

        List<Records.Card> cards(UUID vehicleId);

        // temporary permits (VL-016)
        String nextPermitNumber(int year);

        void insertPermit(Records.TemporaryPermit permit);

        void updatePermit(Records.TemporaryPermit permit);

        Optional<Records.TemporaryPermit> permit(UUID id);

        // fleets (VL-026)
        void endAssignment(UUID vehicleId, String by, Instant at);

        void insertAssignment(Records.DriverAssignment assignment);

        List<Records.DriverAssignment> assignments(String companyFile);

        // plates (VL-033…VL-039)
        Optional<Plates.Plate> livePlate(String seriesCode, long number);

        List<Plates.Plate> platesHeldBy(String holderFile);

        /** Live plates of {@code status} whose hold ended before {@code day}. */
        List<Plates.Plate> holdsEndedBefore(Plates.Status status, LocalDate day, int limit);

        void insertAuction(Plates.Auction auction);

        void updateAuction(Plates.Auction auction);

        Optional<Plates.Auction> auction(UUID id);

        void insertLot(Plates.Lot lot);

        void updateLot(Plates.Lot lot);

        List<Plates.Lot> lots(UUID auctionId);

        void insertBid(Plates.Bid bid);

        Optional<Plates.Bid> highestBid(UUID auctionId, UUID plateId);

        void insertOrderItem(Plates.OrderItem item);

        List<Plates.OrderItem> pendingOrderItems(int limit);

        void insertOrder(Plates.Order order, List<UUID> itemIds);

        void updateOrder(Plates.Order order);

        Optional<Plates.Order> order(UUID id);

        List<Plates.OrderItem> orderItems(UUID orderId);

        void itemSerial(UUID itemId, String serial);

        void insertLoss(Plates.LossReport report);

        void updateLoss(Plates.LossReport report);

        Optional<Plates.LossReport> loss(UUID id);

        void insertTrial(Plates.Trial trial);

        void updateTrial(Plates.Trial trial);

        Optional<Plates.Trial> trial(UUID id);

        List<Plates.Trial> trialsEndedBefore(LocalDate day, int limit);

        // requests
        Optional<Registration.Request> openRequestFor(String subjectRef);

    }

    /** ICD-02: the insurers' policy query (VL-024). */
    public interface InsuranceRegister {

        record Policy(String policyNo, String insurerCode, String coverType, LocalDate validFrom, LocalDate validTo,
                      String status) {
            public boolean validOn(LocalDate day) {
                return "ACTIVE".equals(status) && !day.isBefore(validFrom) && !day.isAfter(validTo);
            }
        }

        /** Throws {@link UnavailableException} when no answer could be obtained (fail closed, ICD-03). */
        List<Policy> policies(String vin, LocalDate asOf);
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

        Quote quote(String serviceCode, String region, String customerCategory, String subjectCategory,
                    LocalDate expiryDate);
    }

    /** payment-service payables (FN-002). */
    public interface Payables {

        UUID register(String itemRef, String trafficFileNumber, String region, String currency,
                      List<FeeQuotes.Line> lines);
    }

    /** hold-eligibility-service (CC-032): the holds that block a service for a vehicle and its owner (VL-011). */
    public interface Eligibility {

        record Subject(String type, String id) {
        }

        /** A hold's reason, next step and customer message, as hold-eligibility-service worded them. */
        record Block(String reasonCode, String nextStep, String message) {
        }

        /** Every block of {@code serviceCode} for the subjects; empty when the service may go ahead. */
        List<Block> blocks(String serviceCode, List<Subject> subjects, String channel);
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
