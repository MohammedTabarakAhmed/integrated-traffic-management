package gov.itms.iam.api;

import gov.itms.iam.application.AccessReviewService;
import gov.itms.iam.application.IamService.RoleApprovalLimits;
import gov.itms.iam.application.IamService.RoleConflictViolation;
import gov.itms.iam.application.IamService.SubjectDeactivation;
import gov.itms.iam.application.RoleRepository.RoleVersion;
import gov.itms.iam.domain.AccessReviewCampaign;
import gov.itms.iam.domain.AccessReviewItem;
import gov.itms.iam.domain.Actor;
import gov.itms.iam.domain.ApprovalLimit;
import gov.itms.iam.domain.AssignmentScopes;
import gov.itms.iam.domain.BilingualName;
import gov.itms.iam.domain.Codes;
import gov.itms.iam.domain.Permission;
import gov.itms.iam.domain.Role;
import gov.itms.iam.domain.RoleAssignment;
import gov.itms.iam.domain.RoleConflict;
import gov.itms.platform.security.Authorisation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Request/response shapes of {@code iam-service.v1.yaml}. */
final class ApiModels {

    private ApiModels() {
    }

    record Name(@NotBlank @Size(max = 200) String nl, @NotBlank @Size(max = 200) String en) {
        static Name of(BilingualName n) {
            return new Name(n.nl(), n.en());
        }

        BilingualName toDomain() {
            return new BilingualName(nl, en);
        }
    }

    record ActorDto(String id, String username) {
        static ActorDto of(Actor a) {
            return a == null ? null : new ActorDto(a.id(), a.username());
        }
    }

    record Collection<T>(List<T> items) {
    }

    record PermissionDto(String code, String service, Name description) {
        static PermissionDto of(Permission p) {
            return new PermissionDto(p.code(), p.service(), Name.of(p.description()));
        }
    }

    record RoleDto(String code, Name name, String description, boolean active, List<String> permissions,
                   long version) {
        static RoleDto of(Role r) {
            return new RoleDto(r.code(), Name.of(r.name()), r.description(), r.active(), List.copyOf(r.permissions()),
                    r.version());
        }
    }

    record RoleVersionDto(String code, Name name, String description, boolean active, List<String> permissions,
                          long version, Instant validFrom, Instant validTo, String changedBy) {
        static RoleVersionDto of(RoleVersion v) {
            Role r = v.role();
            return new RoleVersionDto(r.code(), Name.of(r.name()), r.description(), r.active(),
                    List.copyOf(r.permissions()), r.version(), v.validFrom(), v.validTo(), v.changedBy());
        }
    }

    record CreateRoleRequest(
            @NotNull @Pattern(regexp = Codes.ROLE_CODE) String code,
            @NotNull @Valid Name name,
            @Size(max = 1000) String description,
            @NotNull @Size(max = 500) Set<@Pattern(regexp = Codes.PERMISSION_CODE) String> permissions,
            @NotNull @Size(min = 3, max = 500) String reason) {
    }

    record UpdateRoleRequest(
            @NotNull @Valid Name name,
            @Size(max = 1000) String description,
            @NotNull Boolean active,
            @NotNull @Size(max = 500) Set<@Pattern(regexp = Codes.PERMISSION_CODE) String> permissions,
            @NotNull @Min(1) Long version,
            @NotNull @Size(min = 3, max = 500) String reason) {
    }

    record ScopesDto(
            @NotEmpty @Size(max = 100) Set<@Pattern(regexp = Codes.SCOPE_VALUE) String> regions,
            @NotEmpty @Size(max = 100) Set<@Pattern(regexp = Codes.SCOPE_VALUE) String> centres,
            @NotEmpty @Size(max = 100) Set<@Pattern(regexp = Codes.SCOPE_VALUE) String> modules) {
        static ScopesDto of(AssignmentScopes s) {
            return new ScopesDto(s.regions(), s.centres(), s.modules());
        }

        static ScopesDto of(gov.itms.platform.security.Scopes s) {
            return new ScopesDto(new java.util.TreeSet<>(s.regions()), new java.util.TreeSet<>(s.centres()),
                    new java.util.TreeSet<>(s.modules()));
        }

        AssignmentScopes toDomain() {
            return AssignmentScopes.of(regions, centres, modules);
        }
    }

    record RoleAssignmentDto(UUID id, String subjectId, String subjectUsername, String roleCode, ScopesDto scopes,
                             Instant validFrom, Instant validTo, RoleAssignment.Status status,
                             RoleAssignment.Source source, UUID delegatedFrom, String reason,
                             ActorDto assignedBy, Instant assignedAt, ActorDto revokedBy, Instant revokedAt,
                             String revocationReason) {
        static RoleAssignmentDto of(RoleAssignment a) {
            return new RoleAssignmentDto(a.id(), a.subjectId(), a.subjectUsername(), a.roleCode(),
                    ScopesDto.of(a.scopes()), a.validFrom(), a.validTo(), a.status(), a.source(), a.delegatedFrom(),
                    a.reason(), ActorDto.of(a.assignedBy()), a.assignedAt(), ActorDto.of(a.revokedBy()),
                    a.revokedAt(), a.revocationReason());
        }
    }

    record CreateDelegationRequest(
            @NotNull UUID sourceAssignmentId,
            @NotNull @Pattern(regexp = Codes.SUBJECT_ID) String delegateSubjectId,
            @Size(min = 1, max = 200) String delegateUsername,
            Instant validFrom,
            @NotNull Instant validTo,
            @NotNull @Size(min = 3, max = 500) String reason) {
    }

    record ApprovalLimitDto(
            @NotNull @Pattern(regexp = ApprovalLimit.LIMIT_TYPE) String limitType,
            @NotNull @Min(0) Long maxAmountMinor,
            @NotNull @Pattern(regexp = ApprovalLimit.CURRENCY) String currency) {
        static ApprovalLimitDto of(ApprovalLimit l) {
            return new ApprovalLimitDto(l.limitType(), l.maxAmountMinor(), l.currency());
        }

        static ApprovalLimitDto of(Authorisation.ApprovalLimit l) {
            return new ApprovalLimitDto(l.limitType(), l.maxAmountMinor(), l.currency());
        }

        ApprovalLimit toDomain() {
            return new ApprovalLimit(limitType, maxAmountMinor, currency);
        }
    }

    record RoleApprovalLimitsDto(String roleCode, long version, List<ApprovalLimitDto> items) {
        static RoleApprovalLimitsDto of(RoleApprovalLimits l) {
            return new RoleApprovalLimitsDto(l.roleCode(), l.version(),
                    l.limits().stream().map(ApprovalLimitDto::of).toList());
        }
    }

    record ReplaceApprovalLimitsRequest(
            @NotNull @Size(max = 100) List<@NotNull @Valid ApprovalLimitDto> limits,
            @NotNull @Min(1) Long version,
            @NotNull @Size(min = 3, max = 500) String reason) {
    }

    record CreateRoleAssignmentRequest(
            @NotNull @Pattern(regexp = Codes.SUBJECT_ID) String subjectId,
            @Size(min = 1, max = 200) String subjectUsername,
            @NotNull @Pattern(regexp = Codes.ROLE_CODE) String roleCode,
            @NotNull @Valid ScopesDto scopes,
            Instant validFrom,
            Instant validTo,
            @NotNull @Size(min = 3, max = 500) String reason) {
    }

    record ReasonRequest(@NotNull @Size(min = 3, max = 500) String reason) {
    }

    record RoleConflictDto(UUID id, String roleA, String roleB, String reason, RoleConflict.Status status,
                           ActorDto createdBy, Instant createdAt, ActorDto retiredBy, Instant retiredAt,
                           String retirementReason) {
        static RoleConflictDto of(RoleConflict c) {
            return new RoleConflictDto(c.id(), c.roleA(), c.roleB(), c.reason(), c.status(),
                    ActorDto.of(c.createdBy()), c.createdAt(), ActorDto.of(c.retiredBy()), c.retiredAt(),
                    c.retirementReason());
        }
    }

    record CreateRoleConflictRequest(
            @NotNull @Pattern(regexp = Codes.ROLE_CODE) String roleA,
            @NotNull @Pattern(regexp = Codes.ROLE_CODE) String roleB,
            @NotNull @Size(min = 3, max = 500) String reason) {
    }

    record RoleConflictViolationDto(UUID conflictId, String roleA, String roleB, String subjectId,
                                    String subjectUsername, List<UUID> assignmentIds) {
        static RoleConflictViolationDto of(RoleConflictViolation v) {
            return new RoleConflictViolationDto(v.conflict().id(), v.conflict().roleA(), v.conflict().roleB(),
                    v.subjectId(), v.subjectUsername(), v.assignmentIds());
        }
    }

    record SubjectDeactivationDto(String subjectId, Instant deactivatedAt, int revokedCount,
                                  List<RoleAssignmentDto> revoked) {
        static SubjectDeactivationDto of(SubjectDeactivation d) {
            return new SubjectDeactivationDto(d.subjectId(), d.deactivatedAt(), d.revoked().size(),
                    d.revoked().stream().map(RoleAssignmentDto::of).toList());
        }
    }

    record AccessReviewRequest(
            @NotNull @Size(min = 3, max = 200) String title,
            @NotNull @Valid ScopesDto scopes,
            @NotNull Instant dueAt,
            @NotNull @Size(min = 3, max = 500) String reason) {
    }

    record AccessReviewDecisionRequest(
            @NotNull AccessReviewItem.Decision decision,
            @Size(min = 3, max = 500) String reason) {
    }

    record AccessReviewCountsDto(int total, int pending, int overdue, int kept, int revoked, int notReviewed,
                                 int ended) {
        static AccessReviewCountsDto of(AccessReviewService.Counts c) {
            return new AccessReviewCountsDto(c.total(), c.pending(), c.overdue(), c.kept(), c.revoked(),
                    c.notReviewed(), c.ended());
        }
    }

    record AccessReviewDto(UUID id, String title, ScopesDto scopes, Instant dueAt, String reason,
                           AccessReviewCampaign.Status status, ActorDto createdBy, Instant createdAt,
                           ActorDto closedBy, Instant closedAt, String closureReason, boolean overdue,
                           AccessReviewCountsDto counts, Integer skippedUnderReview) {
        static AccessReviewDto of(AccessReviewService.Review r) {
            AccessReviewCampaign c = r.campaign();
            return new AccessReviewDto(c.id(), c.title(), ScopesDto.of(c.scopes()), c.dueAt(), c.reason(),
                    c.status(), ActorDto.of(c.createdBy()), c.createdAt(), ActorDto.of(c.closedBy()), c.closedAt(),
                    c.closureReason(), r.overdue(), AccessReviewCountsDto.of(r.counts()), r.skippedUnderReview());
        }
    }

    record AccessReviewItemDto(UUID id, UUID campaignId, Instant campaignDueAt, UUID assignmentId, String subjectId,
                               String subjectUsername, String roleCode, ScopesDto scopes, ActorDto assignedBy,
                               Instant validFrom, Instant validTo, AccessReviewItem.Status status, boolean overdue,
                               ActorDto decidedBy, Instant decidedAt, String decisionReason, boolean decidedLate) {
        static AccessReviewItemDto of(AccessReviewService.ReviewItem v) {
            AccessReviewItem i = v.item();
            return new AccessReviewItemDto(i.id(), i.campaignId(), i.campaignDueAt(), i.assignmentId(),
                    i.subjectId(), i.subjectUsername(), i.roleCode(), ScopesDto.of(i.scopes()),
                    ActorDto.of(i.assignedBy()), i.validFrom(), i.validTo(), i.status(), v.overdue(),
                    ActorDto.of(i.decidedBy()), i.decidedAt(), i.decisionReason(), i.decidedLate());
        }
    }

    record OverdueAssignmentDto(RoleAssignmentDto assignment, Instant lastReviewedAt, Instant reviewDueSince,
                                UUID pendingItemId) {
        static OverdueAssignmentDto of(AccessReviewService.OverdueAssignment o) {
            return new OverdueAssignmentDto(RoleAssignmentDto.of(o.assignment()), o.lastReviewedAt(),
                    o.reviewDueSince(), o.pendingItemId());
        }
    }

    record OverdueAssignmentsDto(String reviewPeriod, Instant evaluatedAt, List<OverdueAssignmentDto> assignments) {
        static OverdueAssignmentsDto of(AccessReviewService.OverdueAssignments o) {
            return new OverdueAssignmentsDto(o.reviewPeriod().toString(), o.evaluatedAt(),
                    o.assignments().stream().map(OverdueAssignmentDto::of).toList());
        }
    }

    record GrantDto(String roleCode, List<String> permissions, ScopesDto scopes, Instant validTo,
                    List<ApprovalLimitDto> approvalLimits) {
        static GrantDto of(Authorisation.Grant g) {
            return new GrantDto(g.roleCode(), g.permissions().stream().sorted().toList(), ScopesDto.of(g.scopes()),
                    g.validTo(), g.approvalLimits().stream().map(ApprovalLimitDto::of).toList());
        }
    }

    record EffectiveAuthorisationDto(String subjectId, Instant evaluatedAt, List<GrantDto> grants) {
        static EffectiveAuthorisationDto of(Authorisation a) {
            return new EffectiveAuthorisationDto(a.subjectId(), a.evaluatedAt(),
                    a.grants().stream().map(GrantDto::of).toList());
        }
    }
}
