package gov.itms.vehicle.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The vehicle file's other records (Increment 2; ADR-059): customs/dealer certificates (VL-002), bans (VL-006),
 * approvals (VL-018, VL-019), certificates (VL-017, VL-020), mortgages (VL-021), registration cards (VL-014,
 * VL-022), temporary entry/exit permits (VL-016) and fleet driver assignments (VL-026).
 */
public final class Records {

    private Records() {
    }

    public enum CustomsSource { CUSTOMS, DEALER }

    /** A customs clearance or manufacturer/dealer certificate received from the feed; it pre-fills a vehicle (VL-002). */
    public record CustomsCertificate(String certificateNo, CustomsSource source, String issuerCode, String vin,
                                     Vehicle.Spec spec, LocalDate issuedOn, String receivedBy, Instant receivedAt,
                                     UUID usedBy) {
        public CustomsCertificate {
            Vehicle.ref("certificateNo", certificateNo);
            Objects.requireNonNull(source);
            Vehicle.code("issuerCode", issuerCode);
            Objects.requireNonNull(vin);
            Objects.requireNonNull(spec);
            Objects.requireNonNull(issuedOn);
        }
    }

    /** A vehicle banned from use, for example after a total loss or a security order (VL-006). */
    public record Ban(UUID id, UUID vehicleId, String reasonCode, String reference, String imposedBy,
                      Instant imposedAt, String liftedBy, Instant liftedAt, String liftReason) {
        public Ban {
            Objects.requireNonNull(id);
            Objects.requireNonNull(vehicleId);
            Vehicle.code("reasonCode", reasonCode);
            Vehicle.ref("reference", reference);
        }

        public boolean active() {
            return liftedAt == null;
        }

        /** Lifted by someone other than who imposed it (CC-002 four eyes). */
        public Ban lifted(String by, String reason, Instant now) {
            if (!active()) {
                throw DomainException.conflict("BAN_LIFTED", "the ban is already lifted", List.of("VL-006"));
            }
            if (by.equals(imposedBy)) {
                throw DomainException.conflict("SAME_PERSON", "the person who imposed a ban cannot lift it",
                        List.of("VL-006", "CC-002"));
            }
            if (reason == null || reason.isBlank() || reason.length() > 500) {
                throw Vehicle.invalid("reason");
            }
            return new Ban(id, vehicleId, reasonCode, reference, imposedBy, imposedAt, by, now, reason);
        }
    }

    public enum ApprovalKind { PUBLIC_DECLARATION, SECURITY_INSPECTION }

    public enum ApprovalStatus { SUBMITTED, APPROVED, REJECTED }

    /**
     * The public transport undertaking (VL-018) or the cash-transport security inspection (VL-019): submitted,
     * then approved or rejected by someone else; an approval is needed before registration.
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public record Approval(UUID id, UUID vehicleId, ApprovalKind kind, ApprovalStatus status,
                           Map<String, String> detail, String submittedBy, Instant submittedAt, String decidedBy,
                           Instant decidedAt, String decisionNote) {
        public Approval {
            Objects.requireNonNull(id);
            Objects.requireNonNull(vehicleId);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(status);
            detail = detail == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(detail));
            if (detail.size() > 30 || detail.values().stream().anyMatch(v -> v == null || v.length() > 500)) {
                throw Vehicle.invalid("detail");
            }
        }

        public Approval decided(boolean approve, String by, String note, Instant now) {
            if (status != ApprovalStatus.SUBMITTED) {
                throw DomainException.conflict("APPROVAL_DECIDED", "the approval is already " + status,
                        List.of("VL-018", "VL-019"), status.name());
            }
            if (by.equals(submittedBy)) {
                throw DomainException.conflict("SAME_PERSON", "the submitter cannot decide their own request",
                        List.of("VL-018", "VL-019", "CC-002"));
            }
            if (note != null && note.length() > 500) {
                throw Vehicle.invalid("note");
            }
            return new Approval(id, vehicleId, kind, approve ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED,
                    detail, submittedBy, submittedAt, by, now, note);
        }
    }

    public enum CertificateKind {
        OWNERSHIP, NO_OBJECTION, REGISTRATION_HISTORY, CLEARANCE, EXPORT, INSURANCE_RECOVERY
    }

    /** An issued vehicle certificate with its unique number and frozen content (VL-015, VL-017, VL-020). */
    public record Certificate(UUID id, String number, UUID vehicleId, CertificateKind kind,
                              Map<String, Object> content, String issuedBy, Instant issuedAt) {
        public Certificate {
            Objects.requireNonNull(id);
            Objects.requireNonNull(number);
            Objects.requireNonNull(vehicleId);
            Objects.requireNonNull(kind);
            content = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(content));
        }
    }

    public enum MortgageStatus { ACTIVE, RELEASED }

    /**
     * A mortgage registered by a bank through integration (VL-021). An active mortgage blocks transfer and
     * de-registration, unless the bank consented to move it with a transfer ({@code transferConsent}).
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public record Mortgage(UUID id, UUID vehicleId, String bankCode, String contractRef, String debtorFile,
                           MortgageStatus status, UUID transferConsent, String registeredBy, Instant registeredAt,
                           String releasedBy, Instant releasedAt) {
        public Mortgage {
            Objects.requireNonNull(id);
            Objects.requireNonNull(vehicleId);
            Vehicle.code("bankCode", bankCode);
            Vehicle.ref("contractRef", contractRef);
            Vehicle.ref("debtorFile", debtorFile);
            Objects.requireNonNull(status);
        }

        public Mortgage released(String by, Instant now) {
            if (status != MortgageStatus.ACTIVE) {
                throw DomainException.conflict("MORTGAGE_RELEASED", "the mortgage is already released",
                        List.of("VL-021"));
            }
            return new Mortgage(id, vehicleId, bankCode, contractRef, debtorFile, MortgageStatus.RELEASED,
                    transferConsent, registeredBy, registeredAt, by, now);
        }

        public Mortgage consentTo(UUID transferId) {
            if (status != MortgageStatus.ACTIVE) {
                throw DomainException.conflict("MORTGAGE_RELEASED", "the mortgage is already released",
                        List.of("VL-021"));
            }
            return new Mortgage(id, vehicleId, bankCode, contractRef, debtorFile, status, transferId, registeredBy,
                    registeredAt, releasedBy, releasedAt);
        }

        /** The mortgage follows the vehicle to its buyer (bank consent, VL-012). */
        public Mortgage movedTo(String buyerFile) {
            return new Mortgage(id, vehicleId, bankCode, contractRef, buyerFile, status, null, registeredBy,
                    registeredAt, releasedBy, releasedAt);
        }
    }

    public enum CardStatus { PRINT_QUEUED, PRINTED, DISPATCHED, DELIVERED, COLLECTED, ACTIVE_DIGITAL, CANCELLED }

    public enum Delivery { CENTRE, COURIER, DIGITAL }

    /**
     * A registration card (VL-022): printed at a centre and collected, sent by courier, or digital in the app.
     * A replacement cancels the old card (VL-014).
     */
    public record Card(UUID id, UUID vehicleId, int serial, CardStatus status, Delivery delivery, String address,
                       Instant issuedAt, Instant changedAt, String trackingRef) {
        public Card {
            Objects.requireNonNull(id);
            Objects.requireNonNull(vehicleId);
            Objects.requireNonNull(status);
            Objects.requireNonNull(delivery);
            if (delivery == Delivery.COURIER && (address == null || address.isBlank())) {
                throw DomainException.validation("ADDRESS_REQUIRED", "courier delivery needs an address",
                        List.of("VL-022"));
            }
            if (address != null && address.length() > 300) {
                throw Vehicle.invalid("address");
            }
        }

        public static CardStatus first(Delivery delivery) {
            return delivery == Delivery.DIGITAL ? CardStatus.ACTIVE_DIGITAL : CardStatus.PRINT_QUEUED;
        }

        public Card moveTo(CardStatus next, String tracking, Instant now) {
            boolean ok = switch (status) {
                case PRINT_QUEUED -> next == CardStatus.PRINTED || next == CardStatus.CANCELLED;
                case PRINTED -> (delivery == Delivery.COURIER ? next == CardStatus.DISPATCHED
                        : next == CardStatus.COLLECTED) || next == CardStatus.CANCELLED;
                case DISPATCHED -> next == CardStatus.DELIVERED || next == CardStatus.CANCELLED;
                case DELIVERED, COLLECTED, ACTIVE_DIGITAL -> next == CardStatus.CANCELLED;
                case CANCELLED -> false;
            };
            if (!ok) {
                throw DomainException.conflict("CARD_STATUS_INVALID", "a card cannot go from " + status + " to "
                        + next, List.of("VL-022"), status.name(), next.name());
            }
            return new Card(id, vehicleId, serial, next, delivery, address, issuedAt, now,
                    tracking == null ? trackingRef : tracking);
        }
    }

    public enum PermitKind { ENTRY, EXIT }

    public enum PermitStatus { AWAITING_PAYMENT, ACTIVE, CLOSED, CANCELLED }

    /**
     * A temporary entry permit for a foreign vehicle or an exit permit for a local one (VL-016), valid between two
     * dates; closed when the vehicle leaves or returns.
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public record TemporaryPermit(UUID id, String number, PermitKind kind, PermitStatus status, UUID vehicleId,
                                  String vin, String foreignPlate, String country, String holderFile,
                                  String borderPoint, LocalDate validFrom, LocalDate validTo, String region,
                                  String createdBy, Instant createdAt, Instant closedAt) {
        public TemporaryPermit {
            Objects.requireNonNull(id);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(status);
            Objects.requireNonNull(vin);
            if (country == null || !country.matches("^[A-Z]{3}$")) {
                throw Vehicle.invalid("country");
            }
            if (kind == PermitKind.ENTRY && (foreignPlate == null || !foreignPlate.matches("^[A-Z0-9 -]{1,20}$"))) {
                throw Vehicle.invalid("foreignPlate");
            }
            Vehicle.ref("holderFileNumber", holderFile);
            Vehicle.code("borderPoint", borderPoint);
            if (validFrom == null || validTo == null || validTo.isBefore(validFrom)) {
                throw DomainException.validation("PERMIT_DATES_INVALID", "the permit ends before it starts",
                        List.of("VL-016"));
            }
        }

        public TemporaryPermit moveTo(PermitStatus next, Instant now) {
            boolean ok = switch (status) {
                case AWAITING_PAYMENT -> next == PermitStatus.ACTIVE || next == PermitStatus.CANCELLED;
                case ACTIVE -> next == PermitStatus.CLOSED;
                case CLOSED, CANCELLED -> false;
            };
            if (!ok) {
                throw DomainException.conflict("PERMIT_STATUS_INVALID", "a permit cannot go from " + status + " to "
                        + next, List.of("VL-016"), status.name(), next.name());
            }
            return new TemporaryPermit(id, number, kind, next, vehicleId, vin, foreignPlate, country, holderFile,
                    borderPoint, validFrom, validTo, region, createdBy, createdAt,
                    next == PermitStatus.ACTIVE ? null : now);
        }
    }

    /** A company vehicle assigned to one of the company's drivers (VL-026). */
    public record DriverAssignment(UUID id, UUID vehicleId, String companyFile, String driverFile, LocalDate validFrom,
                                   LocalDate validTo, String assignedBy, Instant assignedAt, String endedBy,
                                   Instant endedAt) {
        public DriverAssignment {
            Objects.requireNonNull(id);
            Objects.requireNonNull(vehicleId);
            Vehicle.ref("companyFile", companyFile);
            Vehicle.ref("driverFileNumber", driverFile);
            Objects.requireNonNull(validFrom);
            if (validTo != null && validTo.isBefore(validFrom)) {
                throw Vehicle.invalid("validTo");
            }
        }
    }
}
