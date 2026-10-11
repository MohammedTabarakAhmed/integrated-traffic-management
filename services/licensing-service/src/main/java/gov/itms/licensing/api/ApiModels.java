package gov.itms.licensing.api;

import gov.itms.licensing.application.LicenceClassService;
import gov.itms.licensing.application.LicenceNumberRepository;
import gov.itms.licensing.application.LicenceNumberService;
import gov.itms.licensing.application.LicenceRepository;
import gov.itms.licensing.application.LicenceService;
import gov.itms.licensing.application.LicenceTransactions;
import gov.itms.licensing.domain.Channel;
import gov.itms.licensing.domain.ExaminationTransfer;
import gov.itms.licensing.domain.Licence;
import gov.itms.licensing.domain.LicenceClassVersion;
import gov.itms.licensing.domain.LicenceNumberRange;
import gov.itms.licensing.domain.LicenceRequest;
import gov.itms.licensing.domain.Records;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Request/response shapes of {@code licensing-service.v1.yaml}. */
final class ApiModels {

    private ApiModels() {
    }

    record CategoryRuleDto(String customerCategory, @NotNull Integer minAgeYears, Integer maxAgeYears,
                           @Size(max = 10) List<String> requiredTests, @NotNull Integer minTrainingHours,
                           @NotNull Boolean medicalRequired, @NotNull Integer validityMonths,
                           @NotNull Boolean residencyLinked) {

        LicenceClassVersion.CategoryRule toDomain() {
            return new LicenceClassVersion.CategoryRule(customerCategory, minAgeYears, maxAgeYears, requiredTests,
                    minTrainingHours, medicalRequired, validityMonths, residencyLinked);
        }

        static CategoryRuleDto of(LicenceClassVersion.CategoryRule r) {
            return new CategoryRuleDto(r.customerCategory(), r.minAgeYears(), r.maxAgeYears(), r.requiredTests(),
                    r.minTrainingHours(), r.medicalRequired(), r.validityMonths(), r.residencyLinked());
        }
    }

    record SubmitClassRequest(@NotBlank String classCode, @NotBlank String nameNl, @NotBlank String nameEn,
                              @NotBlank String vehicleTypeNl, @NotBlank String vehicleTypeEn,
                              @NotNull Boolean gearboxRecorded,
                              @NotNull @Size(min = 1, max = 50) List<@NotNull @Valid CategoryRuleDto> rules,
                              @NotNull LocalDate effectiveFrom, LocalDate effectiveTo,
                              @NotBlank @Size(min = 3, max = 500) String reason) {

        LicenceClassVersion.Spec toDomain() {
            return new LicenceClassVersion.Spec(classCode, nameNl, nameEn, vehicleTypeNl, vehicleTypeEn,
                    gearboxRecorded, rules.stream().map(CategoryRuleDto::toDomain).toList(), effectiveFrom,
                    effectiveTo);
        }
    }

    record DecisionRequest(@Size(min = 3, max = 500) String reason) {
    }

    record RejectRequest(@NotBlank @Size(min = 3, max = 500) String reason) {
    }

    record DecisionDto(String actorId, String actorUsername, Channel channel, Instant at, String reason) {
        static DecisionDto of(LicenceClassVersion.Decision d) {
            return d == null ? null : new DecisionDto(d.actor().id(), d.actor().username(), d.channel(), d.at(),
                    d.reason());
        }
    }

    record ClassVersionDto(UUID id, String classCode, int versionNo, String nameNl, String nameEn,
                           String vehicleTypeNl, String vehicleTypeEn, boolean gearboxRecorded,
                           List<CategoryRuleDto> rules, LocalDate effectiveFrom, LocalDate effectiveTo,
                           LicenceClassVersion.Status status, DecisionDto submitted, DecisionDto decided) {
        static ClassVersionDto of(LicenceClassVersion v) {
            LicenceClassVersion.Spec s = v.spec();
            return new ClassVersionDto(v.id(), s.classCode(), v.versionNo(), s.nameNl(), s.nameEn(),
                    s.vehicleTypeNl(), s.vehicleTypeEn(), s.gearboxRecorded(),
                    s.rules().stream().map(CategoryRuleDto::of).toList(), s.effectiveFrom(), v.effectiveTo(),
                    v.status(), DecisionDto.of(v.submitted()), DecisionDto.of(v.decided()));
        }
    }

    record ResolvedRuleDto(String classCode, UUID versionId, int versionNo, String customerCategory,
                           boolean gearboxRecorded, CategoryRuleDto rule) {
        static ResolvedRuleDto of(String customerCategory, LicenceClassService.ResolvedRule r) {
            return new ResolvedRuleDto(r.version().spec().classCode(), r.version().id(), r.version().versionNo(),
                    customerCategory, r.version().spec().gearboxRecorded(), CategoryRuleDto.of(r.rule()));
        }
    }

    record SubmitNumberRangeRequest(@NotBlank String region, String prefix, @NotNull Integer width,
                                    @NotNull Long firstNumber, @NotNull Long lastNumber,
                                    @NotBlank @Size(min = 3, max = 500) String reason) {

        LicenceNumberRange.Spec toDomain() {
            return new LicenceNumberRange.Spec(region, prefix, width, firstNumber, lastNumber);
        }
    }

    record NumberRangeDto(UUID id, String region, String prefix, int width, long firstNumber, long lastNumber,
                          long nextNumber, LicenceNumberRange.Status status, long total, long used, long available,
                          DecisionDto submitted, DecisionDto decided) {
        static NumberRangeDto of(LicenceNumberRange r) {
            LicenceNumberRange.Spec s = r.spec();
            return new NumberRangeDto(r.id(), s.region(), s.prefix(), s.width(), s.firstNumber(), s.lastNumber(),
                    r.nextNumber(), r.status(), r.total(), r.used(), r.available(), DecisionDto.of(r.submitted()),
                    DecisionDto.of(r.decided()));
        }
    }

    record NumberReportDto(String region, List<NumberRangeDto> ranges, long total, long used, long available) {
        static NumberReportDto of(LicenceNumberService.Report r) {
            return new NumberReportDto(r.region(), r.ranges().stream().map(u -> NumberRangeDto.of(u.range())).toList(),
                    r.totals().total(), r.totals().used(), r.totals().available());
        }
    }

    record UsedNumberDto(String licenceNumber, UUID rangeId, String region, long number, Instant allocatedAt,
                         String allocatedTo) {
        static UsedNumberDto of(LicenceNumberRepository.UsedNumber u) {
            return new UsedNumberDto(u.licenceNumber(), u.rangeId(), u.region(), u.number(), u.allocatedAt(),
                    u.allocatedTo());
        }
    }

    record TransferRequest(@NotBlank String examinationFileRef, @NotBlank String trafficFileNumber,
                           @NotBlank String classCode, @NotNull ExaminationTransfer.Gearbox gearbox,
                           @NotNull LocalDate passedOn, @NotBlank String region) {

        ExaminationTransfer.Spec toDomain() {
            return new ExaminationTransfer.Spec(examinationFileRef, trafficFileNumber, classCode, gearbox, passedOn,
                    region);
        }
    }

    record TransferDto(UUID id, String examinationFileRef, String trafficFileNumber, String classCode,
                       ExaminationTransfer.Gearbox gearbox, LocalDate passedOn, String region,
                       ExaminationTransfer.Status status, UUID licenceId, Instant receivedAt,
                       String reversalReason, Instant changedAt) {
        static TransferDto of(ExaminationTransfer t) {
            ExaminationTransfer.Spec s = t.spec();
            return new TransferDto(t.id(), s.examinationFileRef(), s.trafficFileNumber(), s.classCode(), s.gearbox(),
                    s.passedOn(), s.region(), t.status(), t.licenceId(), t.receivedAt(), t.reversalReason(),
                    t.changedAt());
        }
    }

    record ConditionRequest(@NotBlank String code, @Size(max = 200) String note) {
    }

    record IssueRequest(@NotBlank String trafficFileNumber, @NotBlank String region,
                        @NotNull @Size(min = 1, max = 10) List<@NotNull UUID> transferIds,
                        String customerCategory, LocalDate residencyExpiresOn,
                        @Size(max = 20) List<@NotNull @Valid ConditionRequest> conditions,
                        Records.Delivery delivery, @Size(max = 300) String address) {

        LicenceService.IssueSpec toDomain(LocalDate today) {
            return new LicenceService.IssueSpec(trafficFileNumber, region, transferIds, customerCategory,
                    residencyExpiresOn, conditions == null ? List.of() : conditions.stream()
                    .map(c -> new Licence.Condition(c.code(), c.note(), today)).toList(), delivery, address);
        }
    }

    record AddConditionRequest(@NotBlank String code, @Size(max = 200) String note,
                               @NotBlank @Size(min = 3, max = 500) String reason) {
    }

    record RequestDto(UUID id, LicenceRequest.Kind kind, LicenceRequest.Status status, UUID licenceId,
                      String trafficFileNumber, String region, UUID payableId, long amountMinor, String currency,
                      List<UUID> transferIds, Instant requestedAt, Instant completedAt) {
        static RequestDto of(LicenceRequest r) {
            return new RequestDto(r.id(), r.kind(), r.status(), r.licenceId(), r.trafficFileNumber(), r.region(),
                    r.payableId(), r.amountMinor(), r.currency(), r.transferIds(), r.requestedAt(), r.completedAt());
        }
    }

    record LicenceClassDto(String classCode, UUID classVersionId, LocalDate issuedOn, Licence.ClassStatus status,
                           boolean automaticOnly) {
        static LicenceClassDto of(Licence.LicenceClass c) {
            return new LicenceClassDto(c.classCode(), c.classVersionId(), c.issuedOn(), c.status(), c.automaticOnly());
        }
    }

    record ConditionDto(String code, String note, LocalDate since) {
        static ConditionDto of(Licence.Condition c) {
            return new ConditionDto(c.code(), c.note(), c.since());
        }
    }

    record LicenceDto(UUID id, String licenceNumber, String trafficFileNumber, String region,
                      String customerCategory, String status, LocalDate issuedOn, LocalDate expiresOn,
                      LocalDate probationUntil, boolean probationary, LocalDate residencyExpiresOn,
                      LocalDate suspendedUntil, List<LicenceClassDto> classes, List<ConditionDto> conditions,
                      long version) {
        static LicenceDto of(Licence l, LocalDate today) {
            return new LicenceDto(l.id(), l.licenceNumber(), l.trafficFileNumber(), l.region(),
                    l.customerCategory(), l.statusOn(today), l.issuedOn(), l.expiresOn(), l.probationUntil(),
                    l.probationaryOn(today), l.residencyExpiresOn(), l.suspendedUntil(),
                    l.classes().stream().map(LicenceClassDto::of).toList(),
                    l.conditions().stream().map(ConditionDto::of).toList(), l.version());
        }
    }

    record HistoryDto(String event, String actorId, Map<String, Object> detail, Instant at) {
        static HistoryDto of(LicenceRepository.HistoryEntry h) {
            return new HistoryDto(h.event(), h.actorId(), h.detail(), h.at());
        }
    }

    record RenewRequest(String customerCategory, LocalDate residencyExpiresOn, Records.Delivery delivery,
                        @Size(max = 300) String address) {
        LicenceTransactions.RenewSpec toDomain() {
            return new LicenceTransactions.RenewSpec(customerCategory, residencyExpiresOn, delivery, address);
        }
    }

    record ReplacementRequest(@NotNull LicenceTransactions.ReplacementReason reason, Records.Delivery delivery,
                              @Size(max = 300) String address) {
    }

    record ChangeRequest(String customerCategory, LocalDate residencyExpiresOn,
                         @NotBlank @Size(min = 3, max = 500) String description,
                         @NotBlank String evidenceDocumentId, Records.Delivery delivery,
                         @Size(max = 300) String address) {
        LicenceTransactions.ChangeSpec toDomain() {
            return new LicenceTransactions.ChangeSpec(customerCategory, residencyExpiresOn, description,
                    evidenceDocumentId, delivery, address);
        }
    }

    record CardDto(UUID id, int serial, Records.CardReason reason, Records.Delivery delivery, String address,
                   Records.CardStatus status, String trackingRef, Instant createdAt, Instant statusAt) {
        static CardDto of(Records.Card c) {
            return new CardDto(c.id(), c.serial(), c.reason(), c.delivery(), c.address(), c.status(), c.trackingRef(),
                    c.createdAt(), c.statusAt());
        }
    }

    record EyeTestRequest(@NotBlank String trafficFileNumber, @NotBlank String centreCode,
                          @NotBlank String reference, @NotNull LocalDate testedOn,
                          @NotNull Records.EyeResult result, @NotNull Boolean correctiveLenses,
                          @Size(max = 30) List<@NotNull String> permittedClasses) {
        Records.EyeTest toDomain() {
            return new Records.EyeTest(UUID.randomUUID(), trafficFileNumber, centreCode, reference, testedOn, result,
                    correctiveLenses, permittedClasses, Instant.EPOCH, "-");
        }
    }

    record EyeTestDto(UUID id, String trafficFileNumber, String centreCode, String reference, LocalDate testedOn,
                      Records.EyeResult result, boolean correctiveLenses, List<String> permittedClasses,
                      Instant receivedAt) {
        static EyeTestDto of(Records.EyeTest t) {
            return new EyeTestDto(t.id(), t.trafficFileNumber(), t.centreCode(), t.reference(), t.testedOn(),
                    t.result(), t.correctiveLenses(), t.permittedClasses(), t.receivedAt());
        }
    }

    record ConsentRequest(@NotBlank @Size(max = 100) String paymentMethodRef) {
    }

    record AutoRenewalDto(UUID licenceId, String paymentMethodRef, Records.ConsentStatus status, Instant consentAt,
                          LocalDate noticeForExpiry, Instant noticeAt, LocalDate attemptForExpiry,
                          Instant withdrawnAt) {
        static AutoRenewalDto of(Records.AutoRenewal a) {
            return new AutoRenewalDto(a.licenceId(), a.paymentMethodRef(), a.status(), a.consentAt(),
                    a.noticeForExpiry(), a.noticeAt(), a.attemptForExpiry(), a.withdrawnAt());
        }
    }

    record Collection<T>(List<T> items) {
    }
}
