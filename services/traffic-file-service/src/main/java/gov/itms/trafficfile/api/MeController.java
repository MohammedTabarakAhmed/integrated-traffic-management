package gov.itms.trafficfile.api;

import gov.itms.platform.security.CurrentActorProvider;
import gov.itms.trafficfile.api.ApiModels.TrafficFileDto;
import gov.itms.trafficfile.application.TrafficFileService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in customer's own data (ADR-067 d). The customer is taken from the token by
 * {@link CurrentActorProvider#customer()}; a staff token is refused there even if it held the permission.
 */
@RestController
class MeController {

    private final TrafficFileService service;
    private final CurrentActorProvider actors;
    private final Callers callers;

    MeController(TrafficFileService service, CurrentActorProvider actors, Callers callers) {
        this.service = service;
        this.actors = actors;
        this.callers = callers;
    }

    /** The customer's own file, unmasked: it is their own personal data. */
    @GetMapping("/v1/me/traffic-file")
    @PreAuthorize("hasAuthority('customer.trafficfile.read')")
    TrafficFileDto ownFile() {
        String nationalId = actors.customer().nationalId();
        return TrafficFileDto.of(service.own(nationalId, callers.current()), true);
    }
}
