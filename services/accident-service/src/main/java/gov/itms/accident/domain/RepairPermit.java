package gov.itms.accident.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Permission to repair accident damage on one vehicle (AC-020, AC-021). */
public record RepairPermit(UUID id, String number, String accidentReportNumber, String vehiclePlate,
                            String region, List<String> damagedParts, Instant issuedAt, String status, boolean valid) {
    public RepairPermit {
        damagedParts = List.copyOf(damagedParts);
    }
}
