package gov.itms.transaction.api;

import gov.itms.platform.security.CurrentActor;
import gov.itms.platform.security.CurrentActorProvider;
import gov.itms.transaction.api.ApiModels.AvailabilityDto;
import gov.itms.transaction.api.ApiModels.AvailabilityRuleDto;
import gov.itms.transaction.api.ApiModels.CatalogueChangeDto;
import gov.itms.transaction.api.ApiModels.Collection;
import gov.itms.transaction.api.ApiModels.RejectCatalogueChangeRequest;
import gov.itms.transaction.api.ApiModels.ServiceDefinitionDto;
import gov.itms.transaction.api.ApiModels.ServiceVersionDto;
import gov.itms.transaction.api.ApiModels.SubmitCatalogueChangeRequest;
import gov.itms.transaction.application.CatalogueService;
import gov.itms.transaction.domain.Actor;
import gov.itms.transaction.domain.CatalogueChange;
import gov.itms.transaction.domain.Channel;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Implements {@code transaction-service.v1.yaml}. Permissions follow the spec's {@code x-itms-permission}. */
@RestController
@RequestMapping("/v1")
class CatalogueController {

    private final CatalogueService service;
    private final CurrentActorProvider actors;
    private final Clock clock;

    CatalogueController(CatalogueService service, CurrentActorProvider actors, Clock clock) {
        this.service = service;
        this.actors = actors;
        this.clock = clock;
    }

    @GetMapping("/services")
    @PreAuthorize("isAuthenticated()")
    Collection<ServiceDefinitionDto> services(@RequestParam(required = false) String region,
                                              @RequestParam(required = false) Channel channel,
                                              @RequestParam(required = false)
                                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return new Collection<>(service.services(region, channel, date).stream().map(ServiceDefinitionDto::of).toList());
    }

    @GetMapping("/services/{serviceCode}")
    @PreAuthorize("isAuthenticated()")
    ServiceDefinitionDto service(@PathVariable String serviceCode) {
        return ServiceDefinitionDto.of(service.service(serviceCode));
    }

    @GetMapping("/services/{serviceCode}/history")
    @PreAuthorize("hasAuthority('catalogue.history.read')")
    Collection<ServiceVersionDto> history(@PathVariable String serviceCode) {
        return new Collection<>(service.history(serviceCode).stream().map(ServiceVersionDto::of).toList());
    }

    @GetMapping("/services/{serviceCode}/availability-rules")
    @PreAuthorize("isAuthenticated()")
    Collection<AvailabilityRuleDto> rules(@PathVariable String serviceCode) {
        return new Collection<>(service.rules(serviceCode).stream().map(AvailabilityRuleDto::of).toList());
    }

    @GetMapping("/services/{serviceCode}/availability")
    @PreAuthorize("isAuthenticated()")
    AvailabilityDto availability(@PathVariable String serviceCode, @RequestParam String region,
                                 @RequestParam Channel channel,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                 LocalDate date) {
        return AvailabilityDto.of(service.availability(serviceCode, region, channel, date));
    }

    @PostMapping("/catalogue-changes")
    @PreAuthorize("hasAuthority('catalogue.change.submit')")
    ResponseEntity<CatalogueChangeDto> submit(@Valid @RequestBody SubmitCatalogueChangeRequest body) {
        CatalogueChange saved = service.submit(body.toDomain(actor(), clock.instant()));
        return ResponseEntity.created(URI.create("/v1/catalogue-changes/" + saved.id()))
                .body(CatalogueChangeDto.of(saved));
    }

    @GetMapping("/catalogue-changes")
    @PreAuthorize("hasAuthority('catalogue.change.read')")
    Collection<CatalogueChangeDto> changes(@RequestParam(required = false) CatalogueChange.Status status) {
        return new Collection<>(service.changes(status).stream().map(CatalogueChangeDto::of).toList());
    }

    @GetMapping("/catalogue-changes/{changeId}")
    @PreAuthorize("hasAuthority('catalogue.change.read')")
    CatalogueChangeDto change(@PathVariable UUID changeId) {
        return CatalogueChangeDto.of(service.change(changeId));
    }

    @PostMapping("/catalogue-changes/{changeId}/approvals")
    @PreAuthorize("hasAuthority('catalogue.change.approve')")
    CatalogueChangeDto approve(@PathVariable UUID changeId) {
        return CatalogueChangeDto.of(service.approve(changeId, actor()));
    }

    @PostMapping("/catalogue-changes/{changeId}/rejections")
    @PreAuthorize("hasAuthority('catalogue.change.approve')")
    CatalogueChangeDto reject(@PathVariable UUID changeId, @Valid @RequestBody RejectCatalogueChangeRequest body) {
        return CatalogueChangeDto.of(service.reject(changeId, actor(), body.reason()));
    }

    private Actor actor() {
        CurrentActor a = actors.current();
        return new Actor(a.userId(), a.username());
    }
}
