package gov.itms.licensing.api;

import gov.itms.licensing.api.ApiModels.ClassVersionDto;
import gov.itms.licensing.api.ApiModels.Collection;
import gov.itms.licensing.api.ApiModels.DecisionRequest;
import gov.itms.licensing.api.ApiModels.RejectRequest;
import gov.itms.licensing.api.ApiModels.ResolvedRuleDto;
import gov.itms.licensing.api.ApiModels.SubmitClassRequest;
import gov.itms.licensing.application.LicenceClassService;
import gov.itms.licensing.domain.LicenceClassVersion;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Licence class versions and the classes in force of {@code licensing-service.v1.yaml} (DL-020, DL-062). */
@RestController
class LicenceClassController {

    private final LicenceClassService classes;
    private final Callers callers;

    LicenceClassController(LicenceClassService classes, Callers callers) {
        this.classes = classes;
        this.callers = callers;
    }

    @PostMapping("/v1/licence-class-versions")
    @PreAuthorize("hasAuthority('licensing.class.submit')")
    ResponseEntity<ClassVersionDto> submit(@Valid @RequestBody SubmitClassRequest body) {
        LicenceClassVersion v = classes.submit(body.toDomain(), body.reason(), callers.current());
        return ResponseEntity.created(URI.create("/v1/licence-class-versions/" + v.id()))
                .body(ClassVersionDto.of(v));
    }

    @GetMapping("/v1/licence-class-versions")
    @PreAuthorize("hasAuthority('licensing.class.read')")
    Collection<ClassVersionDto> versions(@RequestParam String classCode,
                                         @RequestParam(required = false) LicenceClassVersion.Status status) {
        return new Collection<>(classes.versions(classCode, callers.current()).stream()
                .filter(v -> status == null || v.status() == status).map(ClassVersionDto::of).toList());
    }

    @GetMapping("/v1/licence-class-versions/{versionId}")
    @PreAuthorize("hasAuthority('licensing.class.read')")
    ClassVersionDto version(@PathVariable UUID versionId) {
        return ClassVersionDto.of(classes.get(versionId, callers.current()));
    }

    @PostMapping("/v1/licence-class-versions/{versionId}/approvals")
    @PreAuthorize("hasAuthority('licensing.class.approve')")
    ClassVersionDto approve(@PathVariable UUID versionId, @Valid @RequestBody DecisionRequest body) {
        return ClassVersionDto.of(classes.approve(versionId, body.reason(), callers.current()));
    }

    /** A checker rejects; the maker may withdraw their own pending version. */
    @PostMapping("/v1/licence-class-versions/{versionId}/rejections")
    @PreAuthorize("hasAuthority('licensing.class.approve') or hasAuthority('licensing.class.submit')")
    ClassVersionDto reject(@PathVariable UUID versionId, @Valid @RequestBody RejectRequest body) {
        return ClassVersionDto.of(classes.reject(versionId, body.reason(), callers.current()));
    }

    @GetMapping("/v1/licence-classes")
    @PreAuthorize("hasAuthority('licensing.class.read')")
    Collection<ClassVersionDto> inForce(@RequestParam(required = false) LocalDate on) {
        return new Collection<>(classes.inForce(on, callers.current()).stream().map(ClassVersionDto::of).toList());
    }

    @GetMapping("/v1/licence-classes/{classCode}")
    @PreAuthorize("hasAuthority('licensing.class.read')")
    ClassVersionDto classInForce(@PathVariable String classCode, @RequestParam(required = false) LocalDate on) {
        return ClassVersionDto.of(classes.inForce(classCode, on, callers.current()));
    }

    @GetMapping("/v1/licence-classes/{classCode}/rules/{customerCategory}")
    @PreAuthorize("hasAuthority('licensing.class.read')")
    ResolvedRuleDto rule(@PathVariable String classCode, @PathVariable String customerCategory,
                         @RequestParam(required = false) LocalDate on) {
        return ResolvedRuleDto.of(customerCategory, classes.rule(classCode, customerCategory, on, callers.current()));
    }
}
