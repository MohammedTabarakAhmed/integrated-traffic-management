package gov.itms.licensing.api;

import gov.itms.licensing.application.LicenceClassService;
import gov.itms.licensing.domain.Channel;
import gov.itms.licensing.domain.LicenceClassVersion;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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

    record Collection<T>(List<T> items) {
    }
}
