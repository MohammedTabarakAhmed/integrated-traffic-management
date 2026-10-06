package gov.itms.trafficfile.api;

import gov.itms.trafficfile.application.RegisterSyncService;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;

/** Identity register change notifications (DL-010, ICD-01 {@code IdentityChanged}). */
@RestController
@RequestMapping("/v1/identity-register-notifications")
class RegisterNotificationController {

    private final RegisterSyncService service;
    private final Callers callers;

    RegisterNotificationController(RegisterSyncService service, Callers callers) {
        this.service = service;
        this.callers = callers;
    }

    record NotificationRequest(@NotNull @Pattern(regexp = "^[A-Za-z0-9._:-]{1,100}$") String eventId,
                               @NotNull @Size(max = 40) String nationalIdNo,
                               @NotNull RegisterSyncService.ChangeType changeType, LocalDate effectiveDate) {
    }

    record NotificationResult(RegisterSyncService.Outcome outcome, String fileNumber) {
    }

    /** 200 in every handled case (including a repeat); 503 when the register cannot be read, so it sends again. */
    @PostMapping
    @PreAuthorize("hasAuthority('trafficfile.register.notify')")
    NotificationResult notify(@Valid @RequestBody NotificationRequest body) {
        RegisterSyncService.Result result = service.notify(new RegisterSyncService.Notification(body.eventId(),
                body.nationalIdNo(), body.changeType(), body.effectiveDate()), callers.current());
        return new NotificationResult(result.outcome(), result.fileNumber());
    }
}
