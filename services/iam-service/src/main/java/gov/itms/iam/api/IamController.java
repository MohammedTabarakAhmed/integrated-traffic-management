package gov.itms.iam.api;

import gov.itms.iam.api.ApiModels.ApprovalLimitDto;
import gov.itms.iam.api.ApiModels.Collection;
import gov.itms.iam.api.ApiModels.CreateDelegationRequest;
import gov.itms.iam.api.ApiModels.CreateRoleAssignmentRequest;
import gov.itms.iam.api.ApiModels.CreateRoleRequest;
import gov.itms.iam.api.ApiModels.EffectiveAuthorisationDto;
import gov.itms.iam.api.ApiModels.PermissionDto;
import gov.itms.iam.api.ApiModels.ReasonRequest;
import gov.itms.iam.api.ApiModels.ReplaceApprovalLimitsRequest;
import gov.itms.iam.api.ApiModels.RoleApprovalLimitsDto;
import gov.itms.iam.api.ApiModels.RoleAssignmentDto;
import gov.itms.iam.api.ApiModels.RoleDto;
import gov.itms.iam.api.ApiModels.RoleVersionDto;
import gov.itms.iam.api.ApiModels.UpdateRoleRequest;
import gov.itms.iam.application.IamService;
import gov.itms.iam.domain.Actor;
import gov.itms.iam.domain.Role;
import gov.itms.iam.domain.RoleAssignment;
import gov.itms.platform.security.CurrentActor;
import gov.itms.platform.security.CurrentActorProvider;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Implements {@code iam-service.v1.yaml}. Permissions follow the spec's {@code x-itms-permission}. */
@RestController
@RequestMapping("/v1")
class IamController {

    private final IamService service;
    private final CurrentActorProvider actors;

    IamController(IamService service, CurrentActorProvider actors) {
        this.service = service;
        this.actors = actors;
    }

    @GetMapping("/permissions")
    @PreAuthorize("hasAuthority('iam.permission.read')")
    Collection<PermissionDto> permissions() {
        return new Collection<>(service.permissions().stream().map(PermissionDto::of).toList());
    }

    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('iam.role.read')")
    Collection<RoleDto> roles() {
        return new Collection<>(service.roles().stream().map(RoleDto::of).toList());
    }

    @PostMapping("/roles")
    @PreAuthorize("hasAuthority('iam.role.manage')")
    ResponseEntity<RoleDto> createRole(@Valid @RequestBody CreateRoleRequest body) {
        Role role = service.createRole(body.code(), body.name().toDomain(), body.description(), body.permissions(),
                body.reason(), actor());
        return ResponseEntity.created(URI.create("/v1/roles/" + role.code())).body(RoleDto.of(role));
    }

    @GetMapping("/roles/{roleCode}")
    @PreAuthorize("hasAuthority('iam.role.read')")
    RoleDto role(@PathVariable String roleCode) {
        return RoleDto.of(service.role(roleCode));
    }

    @PutMapping("/roles/{roleCode}")
    @PreAuthorize("hasAuthority('iam.role.manage')")
    RoleDto updateRole(@PathVariable String roleCode, @Valid @RequestBody UpdateRoleRequest body) {
        return RoleDto.of(service.updateRole(roleCode, body.name().toDomain(), body.description(), body.active(),
                body.permissions(), body.version(), body.reason(), actor(), actors.authorisation()));
    }

    @GetMapping("/roles/{roleCode}/history")
    @PreAuthorize("hasAuthority('iam.history.read')")
    Collection<RoleVersionDto> roleHistory(@PathVariable String roleCode) {
        return new Collection<>(service.roleHistory(roleCode).stream().map(RoleVersionDto::of).toList());
    }

    @GetMapping("/roles/{roleCode}/approval-limits")
    @PreAuthorize("hasAuthority('iam.role.read')")
    RoleApprovalLimitsDto approvalLimits(@PathVariable String roleCode) {
        return RoleApprovalLimitsDto.of(service.approvalLimits(roleCode));
    }

    @PutMapping("/roles/{roleCode}/approval-limits")
    @PreAuthorize("hasAuthority('iam.role.manage')")
    RoleApprovalLimitsDto replaceApprovalLimits(@PathVariable String roleCode,
                                                @Valid @RequestBody ReplaceApprovalLimitsRequest body) {
        return RoleApprovalLimitsDto.of(service.replaceApprovalLimits(roleCode,
                body.limits().stream().map(ApprovalLimitDto::toDomain).toList(), body.version(), body.reason(),
                actor(), actors.authorisation()));
    }

    @GetMapping("/role-assignments")
    @PreAuthorize("hasAuthority('iam.assignment.read')")
    Collection<RoleAssignmentDto> assignments(@RequestParam(required = false) String subjectId,
                                              @RequestParam(required = false) String roleCode,
                                              @RequestParam(required = false) RoleAssignment.Status status) {
        return new Collection<>(service.assignments(subjectId, roleCode, status, actors.authorisation()).stream()
                .map(RoleAssignmentDto::of).toList());
    }

    @PostMapping("/role-assignments")
    @PreAuthorize("hasAuthority('iam.assignment.manage')")
    ResponseEntity<RoleAssignmentDto> assign(@Valid @RequestBody CreateRoleAssignmentRequest body) {
        RoleAssignment a = service.assign(body.subjectId(), body.subjectUsername(), body.roleCode(),
                body.scopes().toDomain(), body.validFrom(), body.validTo(), body.reason(), actor(),
                actors.authorisation());
        return ResponseEntity.created(URI.create("/v1/role-assignments/" + a.id())).body(RoleAssignmentDto.of(a));
    }

    @GetMapping("/role-assignments/{assignmentId}")
    @PreAuthorize("hasAuthority('iam.assignment.read')")
    RoleAssignmentDto assignment(@PathVariable UUID assignmentId) {
        return RoleAssignmentDto.of(service.assignment(assignmentId, actors.authorisation()));
    }

    @PostMapping("/role-assignments/{assignmentId}/revocations")
    @PreAuthorize("hasAuthority('iam.assignment.manage')")
    RoleAssignmentDto revoke(@PathVariable UUID assignmentId, @Valid @RequestBody ReasonRequest body) {
        return RoleAssignmentDto.of(service.revoke(assignmentId, body.reason(), actor(), actors.authorisation()));
    }

    /** Any authenticated subject; the service decides visibility (parties, or iam.delegation.read in scope). */
    @GetMapping("/delegations")
    Collection<RoleAssignmentDto> delegations(@RequestParam(required = false) String subjectId) {
        return new Collection<>(service.delegations(subjectId, actors.authorisation()).stream()
                .map(RoleAssignmentDto::of).toList());
    }

    /** Any authenticated subject; the service allows the holder or iam.delegation.create in scope (CC-004). */
    @PostMapping("/delegations")
    ResponseEntity<RoleAssignmentDto> delegate(@Valid @RequestBody CreateDelegationRequest body) {
        RoleAssignment d = service.delegate(body.sourceAssignmentId(), body.delegateSubjectId(),
                body.delegateUsername(), body.validFrom(), body.validTo(), body.reason(), actor(),
                actors.authorisation());
        return ResponseEntity.created(URI.create("/v1/role-assignments/" + d.id())).body(RoleAssignmentDto.of(d));
    }

    @PostMapping("/delegations/{delegationId}/revocations")
    RoleAssignmentDto revokeDelegation(@PathVariable UUID delegationId, @Valid @RequestBody ReasonRequest body) {
        return RoleAssignmentDto.of(service.revokeDelegation(delegationId, body.reason(), actor(),
                actors.authorisation()));
    }

    /** Any authenticated subject may read their own grants; every ITMS service calls this (ADR-022). */
    @GetMapping("/me/authorisation")
    EffectiveAuthorisationDto myAuthorisation() {
        return EffectiveAuthorisationDto.of(actors.authorisation());
    }

    private Actor actor() {
        CurrentActor a = actors.current();
        return new Actor(a.userId(), a.username());
    }
}
