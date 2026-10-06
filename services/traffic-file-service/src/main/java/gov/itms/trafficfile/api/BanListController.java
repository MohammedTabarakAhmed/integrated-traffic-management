package gov.itms.trafficfile.api;

import gov.itms.trafficfile.api.ApiModels.Collection;
import gov.itms.trafficfile.api.ApiModels.DocumentDto;
import gov.itms.trafficfile.application.BanListService;
import gov.itms.trafficfile.application.Caller;
import gov.itms.trafficfile.application.TrafficFileService;
import gov.itms.trafficfile.domain.BanEntry;
import gov.itms.trafficfile.domain.Channel;
import gov.itms.trafficfile.domain.Codes;
import gov.itms.trafficfile.domain.Identity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The ban list of {@code traffic-file-service.v1.yaml} (DL-013). */
@RestController
@RequestMapping("/v1/ban-entries")
class BanListController {

    private final BanListService service;
    private final Callers callers;

    BanListController(BanListService service, Callers callers) {
        this.service = service;
        this.callers = callers;
    }

    record PlaceBanRequest(@NotNull @Valid DocumentDto person, @NotBlank @Size(min = 3, max = 500) String reason,
                           @NotNull @Pattern(regexp = Codes.CODE) String source,
                           @Pattern(regexp = BanEntry.SOURCE_REF) String sourceRef, LocalDate startDate,
                           LocalDate endDate) {
    }

    record LiftBanRequest(@NotBlank @Size(min = 3, max = 500) String reason) {
    }

    record SearchBansRequest(@NotNull @Valid DocumentDto person) {
    }

    record ChangeDto(String actorId, String actorUsername, Channel channel, Instant at, String reason) {
        static ChangeDto of(BanEntry.Change c) {
            return c == null ? null : new ChangeDto(c.actor().id(), c.actor().username(), c.channel(), c.at(),
                    c.reason());
        }
    }

    record PersonDto(ApiModels.DocumentType type, String nationalIdNo, String passportNo, String passportCountry) {
        static PersonDto of(Identity.IdentityDocument d, boolean sensitive) {
            return switch (d) {
                case Identity.NationalId n -> new PersonDto(ApiModels.DocumentType.NATIONAL_ID,
                        sensitive ? n.number() : ApiModels.mask(n.number()), null, null);
                case Identity.Passport p -> new PersonDto(ApiModels.DocumentType.PASSPORT, null,
                        sensitive ? p.number() : ApiModels.mask(p.number()), p.country());
            };
        }
    }

    record BanDto(UUID id, PersonDto person, String fileNumber, String reason, String source, String sourceRef,
                  LocalDate startDate, LocalDate endDate, BanEntry.Status status, boolean inForce, ChangeDto placed,
                  ChangeDto lifted, long version) {
        static BanDto of(BanEntry b, LocalDate today, boolean sensitive) {
            return new BanDto(b.id(), PersonDto.of(b.spec().person(), sensitive), b.fileNumber(), b.spec().reason(),
                    b.spec().source(), b.spec().sourceRef(), b.startDate(), b.spec().endDate(), b.status(today),
                    b.inForce(today), ChangeDto.of(b.placed()), ChangeDto.of(b.lifted()), b.version());
        }
    }

    @PostMapping
    @PreAuthorize("hasAuthority('trafficfile.ban.place')")
    ResponseEntity<BanDto> place(@Valid @RequestBody PlaceBanRequest body) {
        Caller caller = callers.current();
        BanEntry ban = service.place(new BanEntry.Spec(body.person().toDomain(), body.reason(), body.source(),
                body.sourceRef(), body.startDate(), body.endDate()), caller);
        return ResponseEntity.created(URI.create("/v1/ban-entries/" + ban.id()))
                .body(BanDto.of(ban, service.today(), sensitive(caller)));
    }

    @PostMapping("/searches")
    @PreAuthorize("hasAuthority('trafficfile.ban.read')")
    Collection<BanDto> search(@Valid @RequestBody SearchBansRequest body) {
        Caller caller = callers.current();
        var today = service.today();
        return new Collection<>(service.byDocument(body.person().toDomain(), caller).stream()
                .map(b -> BanDto.of(b, today, sensitive(caller))).toList());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('trafficfile.ban.read')")
    BanDto get(@PathVariable UUID id) {
        Caller caller = callers.current();
        return BanDto.of(service.get(id, caller), service.today(), sensitive(caller));
    }

    @PostMapping("/{id}/lifts")
    @PreAuthorize("hasAuthority('trafficfile.ban.lift')")
    BanDto lift(@PathVariable UUID id, @Valid @RequestBody LiftBanRequest body) {
        Caller caller = callers.current();
        return BanDto.of(service.lift(id, body.reason(), caller), service.today(), sensitive(caller));
    }

    private static boolean sensitive(Caller caller) {
        return caller.grants().holds(TrafficFileService.SENSITIVE_READ);
    }
}
