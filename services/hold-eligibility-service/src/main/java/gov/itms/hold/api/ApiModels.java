package gov.itms.hold.api;

import gov.itms.hold.application.HoldRepository.HoldVersion;
import gov.itms.hold.domain.Actor;
import gov.itms.hold.domain.Channel;
import gov.itms.hold.domain.Codes;
import gov.itms.hold.domain.Eligibility;
import gov.itms.hold.domain.Hold;
import gov.itms.hold.domain.HoldOverride;
import gov.itms.hold.domain.HoldStatus;
import gov.itms.hold.domain.Subject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request/response shapes of {@code hold-eligibility-service.v1.yaml}. */
final class ApiModels {

    private ApiModels() {
    }

    record SubjectDto(@NotNull Subject.Type type, @NotNull @Pattern(regexp = Subject.BUSINESS_NUMBER) String id) {

        static SubjectDto of(Subject s) {
            return new SubjectDto(s.type(), s.id());
        }

        Subject toDomain() {
            return new Subject(type, id);
        }
    }

    record PlaceHoldRequest(
            @NotNull @Valid SubjectDto subject,
            @NotNull @Pattern(regexp = Codes.CODE) String holdType,
            @NotNull @Pattern(regexp = Codes.CODE) String module,
            @NotNull @Pattern(regexp = Codes.CODE) String source,
            @Pattern(regexp = Codes.SOURCE_REF) String sourceRef,
            @NotBlank @Size(min = 3, max = 500) String reason,
            LocalDate startDate,
            LocalDate endDate,
            @NotNull @Size(min = 1, max = 100)
            List<@NotNull @Pattern(regexp = "^(\\*|[A-Z][A-Z0-9_]{1,19})$") String> blockedServices,
            @NotNull @Size(min = 1, max = 20) List<@NotNull @Pattern(regexp = Codes.ROLE_CODE) String> liftableByRoles) {

        Hold.Spec toDomain() {
            return new Hold.Spec(subject.toDomain(), holdType, module, source, sourceRef, reason, startDate, endDate,
                    blockedServices, liftableByRoles);
        }
    }

    record LiftHoldRequest(@NotBlank @Size(min = 3, max = 500) String reason) {
    }

    /** CC-034: the supporting document, by type and number (a reference until the document service exists, Q-055). */
    record DocumentDto(@NotNull @Pattern(regexp = Codes.CODE) String type,
                       @NotNull @Pattern(regexp = Codes.SOURCE_REF) String number) {

        static DocumentDto of(HoldOverride.Document d) {
            return new DocumentDto(d.type(), d.number());
        }

        HoldOverride.Document toDomain() {
            return new HoldOverride.Document(type, number);
        }
    }

    /** CC-034: override a hold for one transaction, with a mandatory reason and supporting document. */
    record OverrideHoldRequest(
            @NotNull @Pattern(regexp = HoldOverride.TRANSACTION_REFERENCE) String transactionReference,
            @NotBlank @Size(min = 3, max = 500) String reason,
            @NotNull @Valid DocumentDto supportingDocument) {
    }

    record HoldOverrideDto(UUID id, UUID holdId, String transactionReference, String serviceCode, String reason,
                           DocumentDto supportingDocument, Instant overriddenAt, ActorDto overriddenBy,
                           Channel overriddenChannel) {

        static HoldOverrideDto of(HoldOverride o) {
            return new HoldOverrideDto(o.id(), o.holdId(), o.transactionReference(), o.serviceCode(), o.reason(),
                    DocumentDto.of(o.document()), o.at(), ActorDto.of(o.actor()), o.channel());
        }
    }

    record ActorDto(String id, String username) {
        static ActorDto of(Actor a) {
            return a == null ? null : new ActorDto(a.id(), a.username());
        }
    }

    /**
     * A hold. {@code status} is the status on the service's current day (Expired once the end date has passed);
     * {@code inForce} says whether it applies today (Active and started); it is null in the history.
     */
    record HoldDto(UUID id, SubjectDto subject, String holdType, String module, String source, String sourceRef,
                   String reason, LocalDate startDate, LocalDate endDate, List<String> blockedServices,
                   List<String> liftableByRoles, HoldStatus status, Boolean inForce, Instant placedAt,
                   ActorDto placedBy, Channel placedChannel, Instant liftedAt, ActorDto liftedBy,
                   Channel liftedChannel, String liftReason, long version) {

        static HoldDto of(Hold h, LocalDate today) {
            return of(h, h.status(today), h.inForce(today));
        }

        private static HoldDto of(Hold h, HoldStatus status, Boolean inForce) {
            Hold.Spec s = h.spec();
            Hold.Change lifted = h.lifted();
            return new HoldDto(h.id(), SubjectDto.of(s.subject()), s.holdType(), s.module(), s.source(),
                    s.sourceRef(), s.reason(), h.startDate(), s.endDate(), s.blockedServices(), s.liftableByRoles(),
                    status, inForce, h.placed().at(), ActorDto.of(h.placed().actor()), h.placed().channel(),
                    lifted == null ? null : lifted.at(), lifted == null ? null : ActorDto.of(lifted.actor()),
                    lifted == null ? null : lifted.channel(), lifted == null ? null : lifted.reason(), h.version());
        }
    }

    /** One stored version (DAT-002): the hold as stored then ({@code status} is the stored ACTIVE or LIFTED). */
    record HoldVersionDto(HoldDto hold, Instant validFrom, Instant validTo, String changedBy) {
        static HoldVersionDto of(HoldVersion v) {
            return new HoldVersionDto(HoldDto.of(v.hold(), v.hold().storedStatus(), null), v.validFrom(),
                    v.validTo(), v.changedBy());
        }
    }

    record Collection<T>(List<T> items) {
    }

    /**
     * CC-032: may {@code serviceCode} be submitted for these subjects (the transaction's subject and its parties)?
     * {@code transactionReference} (optional) names the transaction, so holds overridden for it do not count (CC-034).
     */
    record EligibilityCheckRequest(
            @NotNull @Pattern(regexp = Codes.SERVICE_CODE) String serviceCode,
            @NotNull @Size(min = 1, max = Eligibility.MAX_SUBJECTS) List<@NotNull @Valid SubjectDto> subjects,
            @Pattern(regexp = HoldOverride.TRANSACTION_REFERENCE) String transactionReference) {

        List<Subject> toDomain() {
            return subjects.stream().map(SubjectDto::toDomain).toList();
        }
    }

    /** One reason the service is blocked, as the customer may be told it, with the text in the caller's language. */
    record EligibilityBlockDto(String reasonCode, String nextStep, String message) {
    }

    /** The answer of a check; it never names holds, their sources or their reasons (Q-053). */
    record EligibilityDto(String serviceCode, LocalDate checkedOn, boolean eligible,
                          List<EligibilityBlockDto> blocks) {
    }
}
