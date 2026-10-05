package gov.itms.hold.api;

import gov.itms.hold.api.ApiModels.Collection;
import gov.itms.hold.api.ApiModels.HoldDto;
import gov.itms.hold.api.ApiModels.HoldOverrideDto;
import gov.itms.hold.api.ApiModels.HoldVersionDto;
import gov.itms.hold.api.ApiModels.LiftHoldRequest;
import gov.itms.hold.api.ApiModels.OverrideHoldRequest;
import gov.itms.hold.api.ApiModels.PlaceHoldRequest;
import gov.itms.hold.application.Caller;
import gov.itms.hold.application.HoldService;
import gov.itms.hold.domain.Hold;
import gov.itms.hold.domain.HoldOverride;
import gov.itms.hold.domain.Subject;
import jakarta.validation.Valid;
import java.net.URI;
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
    private final Callers callers;

    HoldController(HoldService service, Callers callers) {
        this.service = service;
        this.callers = callers;
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

    @PostMapping("/{id}/overrides")
    @PreAuthorize("hasAuthority('hold.override')")
    ResponseEntity<HoldOverrideDto> override(@PathVariable UUID id, @Valid @RequestBody OverrideHoldRequest body) {
        HoldOverride override = service.override(id, body.transactionReference(), body.reason(),
                body.supportingDocument().toDomain(), caller());
        return ResponseEntity.created(URI.create("/v1/holds/" + id + "/overrides"))
                .body(HoldOverrideDto.of(override));
    }

    @GetMapping("/{id}/overrides")
    @PreAuthorize("hasAuthority('hold.read')")
    Collection<HoldOverrideDto> overrides(@PathVariable UUID id) {
        return new Collection<>(service.overrides(id, caller()).stream().map(HoldOverrideDto::of).toList());
    }

    private Caller caller() {
        return callers.current();
    }
}
