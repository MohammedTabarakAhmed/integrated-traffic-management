package gov.itms.accident.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A reported road accident and its immutable report facts (AC-001..AC-006). */
public record Accident(UUID id, String reportNumber, String region, Instant occurredAt, String location,
                       String roadCondition, String weather, String status, String origin, String recordedBy,
                       Instant recordedAt, List<Party> parties, List<String> evidenceDocumentIds) {

    public enum Status { REPORTED, UNDER_INVESTIGATION, FAULT_DECIDED, CLOSED, REFERRED_TO_PROSECUTION }
    public enum Origin { CUSTOMER_APP, OFFICER_DEVICE, PAPER_BOOK }
    public enum Role { DRIVER, PASSENGER, PEDESTRIAN }

    public record Party(int partyNo, String trafficFileNumber, String vehiclePlate, Role role, String seatPosition,
                        String injuryDegree, String transportMethod, String intoxicationStatus,
                        Integer faultPercent, boolean agrees, boolean unknownParty) { }

    public Accident {
        parties = List.copyOf(parties);
        evidenceDocumentIds = List.copyOf(evidenceDocumentIds);
    }
}
