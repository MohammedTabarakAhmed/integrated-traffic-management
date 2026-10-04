package gov.itms.iam.api;

import gov.itms.iam.application.RoleRepository.RoleVersion;
import gov.itms.iam.domain.Actor;
import gov.itms.iam.domain.AssignmentScopes;
import gov.itms.iam.domain.BilingualName;
import gov.itms.iam.domain.Codes;
import gov.itms.iam.domain.Permission;
import gov.itms.iam.domain.Role;
import gov.itms.iam.domain.RoleAssignment;
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
                             Instant validFrom, Instant validTo, RoleAssignment.Status status, String reason,
                             ActorDto assignedBy, Instant assignedAt, ActorDto revokedBy, Instant revokedAt,
                             String revocationReason) {
        static RoleAssignmentDto of(RoleAssignment a) {
            return new RoleAssignmentDto(a.id(), a.subjectId(), a.subjectUsername(), a.roleCode(),
                    ScopesDto.of(a.scopes()), a.validFrom(), a.validTo(), a.status(), a.reason(),
                    ActorDto.of(a.assignedBy()), a.assignedAt(), ActorDto.of(a.revokedBy()), a.revokedAt(),
                    a.revocationReason());
        }
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

    record GrantDto(String roleCode, List<String> permissions, ScopesDto scopes, Instant validTo) {
        static GrantDto of(Authorisation.Grant g) {
            return new GrantDto(g.roleCode(), g.permissions().stream().sorted().toList(), ScopesDto.of(g.scopes()),
                    g.validTo());
        }
    }

    record EffectiveAuthorisationDto(String subjectId, Instant evaluatedAt, List<GrantDto> grants) {
        static EffectiveAuthorisationDto of(Authorisation a) {
            return new EffectiveAuthorisationDto(a.subjectId(), a.evaluatedAt(),
                    a.grants().stream().map(GrantDto::of).toList());
        }
    }
}
