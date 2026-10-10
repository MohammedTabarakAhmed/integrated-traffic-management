package gov.itms.vehicle.application;

import gov.itms.vehicle.domain.Plates;
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
