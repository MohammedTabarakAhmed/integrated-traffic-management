package gov.itms.transaction.api;

import gov.itms.transaction.application.ServiceDefinitionRepository.ServiceVersion;
import gov.itms.transaction.application.TransactionService.NewTransaction;
import gov.itms.transaction.application.TransactionService.TransactionPage;
import gov.itms.transaction.domain.Actor;
import gov.itms.transaction.domain.Availability;
import gov.itms.transaction.domain.AvailabilityRule;
import gov.itms.transaction.domain.AvailabilityRuleSpec;
import gov.itms.transaction.domain.BilingualName;
import gov.itms.transaction.domain.CatalogueChange;
import gov.itms.transaction.domain.ChangeChannel;
import gov.itms.transaction.domain.Channel;
import gov.itms.transaction.domain.Codes;
import gov.itms.transaction.domain.DomainException;
import gov.itms.transaction.domain.ServiceDefinition;
import gov.itms.transaction.domain.ServiceSpec;
import gov.itms.transaction.domain.StatusChange;
import gov.itms.transaction.domain.StatusReason;
import gov.itms.transaction.domain.Subject;
import gov.itms.transaction.domain.Transaction;
import gov.itms.transaction.domain.TransactionStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request/response shapes of {@code transaction-service.v1.yaml}. */
final class ApiModels {

    private ApiModels() {
    }

    record Name(@NotBlank @Size(max = 200) String nl, @NotBlank @Size(max = 200) String en) {
        static Name of(BilingualName n) {
            return new Name(n.nl(), n.en());
        }
    }

    record ServiceSpecDto(
            @NotNull @Pattern(regexp = Codes.SERVICE_CODE) String code,
            @NotNull @Valid Name name,
            @NotNull @Pattern(regexp = Codes.CODE) String module,
            @NotNull @Size(min = 1, max = 50) List<@NotNull @Pattern(regexp = Codes.CODE) String> customerTypes,
            @NotNull @Size(min = 1, max = 9) List<@NotNull Channel> channels,
            @NotNull @Size(max = 50) List<@NotNull @Pattern(regexp = Codes.CODE) String> requiredDocuments,
            @Pattern(regexp = Codes.CODE) String feeCode,
            @Pattern(regexp = Codes.CODE) String ruleSetCode,
            @Pattern(regexp = Codes.CODE) String workflowCode,
            @NotNull @Pattern(regexp = ServiceSpec.SLA_PATTERN) String sla,
            @NotNull @Size(min = 1, max = 100) List<@NotNull @Pattern(regexp = "^(\\*|[A-Z0-9_]{1,40})$") String> regions,
            @NotNull Boolean anyRegion,
            @NotNull Boolean allowsConcurrentOpen) {

        static ServiceSpecDto of(ServiceSpec s) {
            return s == null ? null : new ServiceSpecDto(s.code(), Name.of(s.name()), s.module(), s.customerTypes(),
                    s.channels(), s.requiredDocuments(), s.feeCode(), s.ruleSetCode(), s.workflowCode(), s.sla(),
                    s.regions(), s.anyRegion(), s.allowsConcurrentOpen());
        }

        ServiceSpec toDomain() {
            return new ServiceSpec(code, new BilingualName(name.nl(), name.en()), module, customerTypes, channels,
                    requiredDocuments, feeCode, ruleSetCode, workflowCode, sla, regions, anyRegion,
                    allowsConcurrentOpen);
        }
    }

    record RuleSpecDto(
            @NotNull @Pattern(regexp = "^(\\*|[A-Z0-9_]{1,40})$") String region,
            @NotNull @Pattern(regexp = "^(\\*|PORTAL|APP|BACKOFFICE|PARTNER|KIOSK|CALLCENTRE|FIELD|ASSISTANT|AGENT)$")
            String channel,
            @NotNull Boolean enabled,
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo) {

        static RuleSpecDto of(AvailabilityRuleSpec r) {
            return r == null ? null : new RuleSpecDto(r.region(), r.allChannels() ? Codes.ALL : r.channel().name(),
                    r.enabled(), r.effectiveFrom(), r.effectiveTo());
        }

        AvailabilityRuleSpec toDomain() {
            return new AvailabilityRuleSpec(region, Codes.ALL.equals(channel) ? null : Channel.valueOf(channel),
                    enabled, effectiveFrom, effectiveTo);
        }
    }

    record ServiceDefinitionDto(ServiceSpecDto definition, long version, Instant updatedAt) {
        static ServiceDefinitionDto of(ServiceDefinition d) {
            return new ServiceDefinitionDto(ServiceSpecDto.of(d.spec()), d.version(), d.updatedAt());
        }
    }

    record ServiceVersionDto(ServiceSpecDto definition, long version, Instant validFrom, Instant validTo,
                             String changedBy, UUID changeId) {
        static ServiceVersionDto of(ServiceVersion v) {
            return new ServiceVersionDto(ServiceSpecDto.of(v.spec()), v.version(), v.validFrom(), v.validTo(),
                    v.changedBy(), v.changeId());
        }
    }

    record AvailabilityRuleDto(UUID id, String serviceCode, String region, String channel, boolean enabled,
                               LocalDate effectiveFrom, LocalDate effectiveTo, UUID changeId) {
        static AvailabilityRuleDto of(AvailabilityRule r) {
            AvailabilityRuleSpec s = r.spec();
            return new AvailabilityRuleDto(r.id(), r.serviceCode(), s.region(),
                    s.allChannels() ? Codes.ALL : s.channel().name(), s.enabled(), s.effectiveFrom(), s.effectiveTo(),
                    r.changeId());
        }
    }

    record AvailabilityDto(String serviceCode, String region, Channel channel, LocalDate date, boolean available,
                           Availability.Reason reason, UUID ruleId) {
        static AvailabilityDto of(Availability a) {
            return new AvailabilityDto(a.serviceCode(), a.region(), a.channel(), a.date(), a.available(), a.reason(),
                    a.ruleId());
        }
    }

    record Collection<T>(List<T> items) {
    }

    record SubmitCatalogueChangeRequest(
            @NotNull CatalogueChange.Type changeType,
            @NotNull @Pattern(regexp = Codes.SERVICE_CODE) String serviceCode,
            @Valid ServiceSpecDto service,
            @Min(1) Long baseVersion,
            @Valid RuleSpecDto rule,
            UUID ruleId,
            LocalDate endDate,
            @NotBlank @Size(min = 3, max = 500) String reason) {

        CatalogueChange toDomain(Actor maker, Instant now) {
            if (changeType == null) {
                throw DomainException.validation("CHANGE_TYPE_REQUIRED", "a change type is required");
            }
            return CatalogueChange.submit(changeType, serviceCode, service == null ? null : service.toDomain(),
                    baseVersion, rule == null ? null : rule.toDomain(), ruleId, endDate, reason, maker, now);
        }
    }

    record RejectCatalogueChangeRequest(@NotBlank @Size(min = 3, max = 500) String reason) {
    }

    record ActorDto(String id, String username) {
        static ActorDto of(Actor a) {
            return a == null ? null : new ActorDto(a.id(), a.username());
        }
    }

    record CatalogueChangeDto(UUID id, CatalogueChange.Type changeType, CatalogueChange.Status status,
                              String serviceCode, ServiceSpecDto service, Long baseVersion, RuleSpecDto rule,
                              UUID ruleId, LocalDate endDate, String reason, ActorDto maker, Instant submittedAt,
                              ActorDto checker, Instant decidedAt, String decisionReason) {
        static CatalogueChangeDto of(CatalogueChange c) {
            return new CatalogueChangeDto(c.id(), c.type(), c.status(), c.serviceCode(), ServiceSpecDto.of(c.service()),
                    c.baseVersion(), RuleSpecDto.of(c.rule()), c.ruleId(), c.endDate(), c.reason(),
                    ActorDto.of(c.maker()), c.submittedAt(), ActorDto.of(c.checker()), c.decidedAt(),
                    c.decisionReason());
        }
    }

    // ------------------------------------------------------------------ transactions (CC-021..CC-023, CC-027)

    record SubjectDto(@NotNull Subject.Type type, @NotNull @Pattern(regexp = Subject.BUSINESS_NUMBER) String id) {
        static SubjectDto of(Subject s) {
            return new SubjectDto(s.type(), s.id());
        }

        Subject toDomain() {
            return new Subject(type, id);
        }
    }

    record CreateTransactionRequest(
            @NotNull @Pattern(regexp = Codes.SERVICE_CODE) String serviceCode,
            @NotNull @Valid SubjectDto subject,
            @NotNull @Pattern(regexp = Subject.BUSINESS_NUMBER) String applicantFileNumber,
            @NotNull @Pattern(regexp = Codes.CODE) String homeRegion,
            @Pattern(regexp = Codes.CODE) String region) {

        NewTransaction toDomain() {
            return new NewTransaction(serviceCode, subject.toDomain(), applicantFileNumber, homeRegion, region);
        }
    }

    record CancelTransactionRequest(@NotBlank @Size(min = 3, max = 500) String reason) {
    }

    record TransactionDto(String reference, String serviceCode, long serviceVersion, String module, SubjectDto subject,
                          String applicantFileNumber, String homeRegion, String region, Channel channel,
                          TransactionStatus status, boolean paymentRequired, Instant createdAt, ActorDto createdBy,
                          Instant submittedAt, Instant statusChangedAt, long version) {
        static TransactionDto of(Transaction t) {
            return new TransactionDto(t.reference().toString(), t.serviceCode(), t.serviceVersion(), t.module(),
                    SubjectDto.of(t.subject()), t.applicantFileNumber(), t.homeRegion(), t.region(), t.channel(),
                    t.status(), t.paymentRequired(), t.createdAt(), ActorDto.of(t.createdBy()), t.submittedAt(),
                    t.lastChange().changedAt(), t.version());
        }
    }

    record TransactionPageDto(List<TransactionDto> items, String nextCursor) {
        static TransactionPageDto of(TransactionPage page) {
            return new TransactionPageDto(page.items().stream().map(TransactionDto::of).toList(), page.nextCursor());
        }
    }

    record StatusChangeDto(long sequence, TransactionStatus previousStatus, TransactionStatus status,
                           StatusReason reason, String note, ActorDto actor, ChangeChannel channel,
                           Instant changedAt) {
        static StatusChangeDto of(StatusChange c) {
            return new StatusChangeDto(c.sequence(), c.previousStatus(), c.status(), c.reason(), c.note(),
                    ActorDto.of(c.actor()), c.channel(), c.changedAt());
        }
    }
}
