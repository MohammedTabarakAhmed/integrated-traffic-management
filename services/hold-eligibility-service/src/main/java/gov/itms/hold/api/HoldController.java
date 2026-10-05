package gov.itms.hold.api;

import gov.itms.hold.api.ApiModels.Collection;
import gov.itms.hold.api.ApiModels.HoldDto;
import gov.itms.hold.api.ApiModels.HoldVersionDto;
import gov.itms.hold.api.ApiModels.LiftHoldRequest;
import gov.itms.hold.api.ApiModels.PlaceHoldRequest;
import gov.itms.hold.application.Caller;
import gov.itms.hold.application.HoldService;
import gov.itms.hold.domain.Actor;
import gov.itms.hold.domain.Channel;
import gov.itms.hold.domain.Hold;
import gov.itms.hold.domain.Subject;
import gov.itms.platform.security.Authorisation;
import gov.itms.platform.security.CurrentActor;
import gov.itms.platform.security.CurrentActorProvider;
import gov.itms.platform.security.ScopeTarget;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Holds of {@code hold-eligibility-service.v1.yaml}. {@code @PreAuthorize} checks that the caller holds the permission
 * somewhere; {@link HoldService} checks that it reaches the hold's module (CC-001) and, for a lift, the hold's roles.
 */
@RestController
@RequestMapping("/v1/holds")
class HoldController {

    private final HoldService service;
    private final CurrentActorProvider actors;

    HoldController(HoldService service, CurrentActorProvider actors) {
        this.service = service;
        this.actors = actors;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('hold.place')")
    ResponseEntity<HoldDto> place(@Valid @RequestBody PlaceHoldRequest body) {
        Hold placed = service.place(body.toDomain(), caller());
        return ResponseEntity.created(URI.create("/v1/holds/" + placed.id())).body(HoldDto.of(placed, service.today()));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('hold.read')")
    Collection<HoldDto> bySubject(@RequestParam Subject.Type subjectType, @RequestParam String subjectId) {
        var today = service.today();
        return new Collection<>(service.bySubject(new Subject(subjectType, subjectId), caller()).stream()
                .map(h -> HoldDto.of(h, today)).toList());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('hold.read')")
    HoldDto get(@PathVariable UUID id) {
        return HoldDto.of(service.get(id, caller()), service.today());
    }

    @GetMapping("/{id}/history")
    @PreAuthorize("hasAuthority('hold.read')")
    Collection<HoldVersionDto> history(@PathVariable UUID id) {
        return new Collection<>(service.history(id, caller()).stream().map(HoldVersionDto::of).toList());
    }

    @PostMapping("/{id}/lifts")
    @PreAuthorize("hasAuthority('hold.lift')")
    HoldDto lift(@PathVariable UUID id, @Valid @RequestBody LiftHoldRequest body) {
        return HoldDto.of(service.lift(id, body.reason(), caller()), service.today());
    }

    /** The channel comes from {@code X-Channel}; anything else than a channel of {@link Channel} counts as none. */
    private Caller caller() {
        CurrentActor current = actors.current();
        Authorisation authorisation = actors.authorisation();
        Channel channel = Arrays.stream(Channel.values()).filter(c -> c.name().equals(current.channel())).findFirst()
                .orElse(null);
        return new Caller(new Actor(current.userId(), current.username()), channel, new Caller.Grants() {
            @Override
            public boolean allows(String permission, String module) {
                return authorisation.allows(permission, new ScopeTarget(null, null, module));
            }

            @Override
            public boolean allowsThroughRole(String permission, List<String> roles, String module) {
                ScopeTarget target = new ScopeTarget(null, null, module);
                return authorisation.grants().stream().anyMatch(g -> roles.contains(g.roleCode())
                        && g.permissions().contains(permission) && g.scopes().allows(target));
            }
        });
    }
}
