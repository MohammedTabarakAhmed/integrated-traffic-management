package gov.itms.refdata.api;

import gov.itms.platform.security.CurrentActor;
import gov.itms.platform.security.CurrentActorProvider;
import gov.itms.refdata.api.ApiModels.ChangeDto;
import gov.itms.refdata.api.ApiModels.Collection;
import gov.itms.refdata.api.ApiModels.ItemDto;
import gov.itms.refdata.api.ApiModels.ItemVersionDto;
import gov.itms.refdata.api.ApiModels.ListDto;
import gov.itms.refdata.api.ApiModels.RejectChangeRequest;
import gov.itms.refdata.api.ApiModels.SubmitChangeRequest;
import gov.itms.refdata.application.ReferenceDataService;
import gov.itms.refdata.domain.ChangeRequest;
import gov.itms.refdata.domain.ChangeStatus;
import jakarta.validation.Valid;
import java.net.URI;
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

/** Implements {@code reference-data-service.v1.yaml}. Permissions follow the spec's {@code x-itms-permission}. */
@RestController
@RequestMapping("/v1")
class ReferenceDataController {

    private final ReferenceDataService service;
    private final CurrentActorProvider actors;

    ReferenceDataController(ReferenceDataService service, CurrentActorProvider actors) {
        this.service = service;
        this.actors = actors;
    }

    @GetMapping("/reference-lists")
    @PreAuthorize("hasAuthority('refdata.read')")
    Collection<ListDto> lists() {
        return new Collection<>(service.lists().stream().map(ListDto::of).toList());
    }

    @GetMapping("/reference-lists/{listCode}/items")
    @PreAuthorize("hasAuthority('refdata.read')")
    Collection<ItemDto> items(@PathVariable String listCode,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
                              @RequestParam(required = false) String region,
                              @RequestParam(defaultValue = "false") boolean includeInactive) {
        return new Collection<>(service.items(listCode, asOf, region, includeInactive).stream().map(ItemDto::of).toList());
    }

    @GetMapping("/reference-lists/{listCode}/items/{itemCode}")
    @PreAuthorize("hasAuthority('refdata.read')")
    ItemDto item(@PathVariable String listCode, @PathVariable String itemCode,
                 @RequestParam(required = false) String region) {
        return ItemDto.of(service.item(listCode, itemCode, region));
    }

    @GetMapping("/reference-lists/{listCode}/items/{itemCode}/history")
    @PreAuthorize("hasAuthority('refdata.history.read')")
    Collection<ItemVersionDto> history(@PathVariable String listCode, @PathVariable String itemCode,
                                       @RequestParam(required = false) String region) {
        return new Collection<>(service.history(listCode, itemCode, region).stream().map(ItemVersionDto::of).toList());
    }

    @PostMapping("/reference-data-changes")
    @PreAuthorize("hasAuthority('refdata.change.submit')")
    ResponseEntity<ChangeDto> submit(@Valid @RequestBody SubmitChangeRequest body) {
        ChangeRequest change = service.prepare(body.changeType(), body.listCode(), body.itemCode(), body.regionCode(),
                body.name().toDomain(), body.parentListCode(), body.itemData(), body.baseVersion(), body.reason(),
                actor());
        ChangeRequest saved = service.submit(change);
        return ResponseEntity.created(URI.create("/v1/reference-data-changes/" + saved.id())).body(ChangeDto.of(saved));
    }

    @GetMapping("/reference-data-changes")
    @PreAuthorize("hasAuthority('refdata.change.read')")
    Collection<ChangeDto> changes(@RequestParam(required = false) ChangeStatus status) {
        return new Collection<>(service.changes(status).stream().map(ChangeDto::of).toList());
    }

    @GetMapping("/reference-data-changes/{changeId}")
    @PreAuthorize("hasAuthority('refdata.change.read')")
    ChangeDto change(@PathVariable UUID changeId) {
        return ChangeDto.of(service.change(changeId));
    }

    @PostMapping("/reference-data-changes/{changeId}/approvals")
    @PreAuthorize("hasAuthority('refdata.change.approve')")
    ChangeDto approve(@PathVariable UUID changeId) {
        return ChangeDto.of(service.approve(changeId, actor()));
    }

    @PostMapping("/reference-data-changes/{changeId}/rejections")
    @PreAuthorize("hasAuthority('refdata.change.approve')")
    ChangeDto reject(@PathVariable UUID changeId, @Valid @RequestBody RejectChangeRequest body) {
        return ChangeDto.of(service.reject(changeId, actor(), body.reason()));
    }

    private ChangeRequest.Actor actor() {
        CurrentActor a = actors.current();
        return new ChangeRequest.Actor(a.userId(), a.username());
    }
}
