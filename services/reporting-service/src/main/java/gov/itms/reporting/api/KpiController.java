package gov.itms.reporting.api;

import gov.itms.platform.security.CurrentActorProvider;
import gov.itms.reporting.application.KpiSettingsService;
import gov.itms.reporting.domain.KpiSetting;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * KPI targets and red/amber/green thresholds a business user sets (RPT-012); a status call evaluates an actual
 * value by the thresholds. Setting needs {@code report.kpi.manage} and an Idempotency-Key; reading needs
 * {@code report.read}.
 */
@RestController
class KpiController {

    private static final DateTimeFormatter PRINTED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'");

    private final KpiSettingsService settings;
    private final CurrentActorProvider actors;

    KpiController(KpiSettingsService settings, CurrentActorProvider actors) {
        this.settings = settings;
        this.actors = actors;
    }

    record SetRequest(@NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,39}$") String code,
                      @NotNull Long target, @NotNull Long redThreshold, @NotNull Long amberThreshold,
                      Boolean higherIsBetter) {
    }

    record SettingDto(String code, long target, long redThreshold, long amberThreshold, boolean higherIsBetter,
                      String updatedBy, String updatedAt) {
        static SettingDto of(KpiSetting s) {
            return new SettingDto(s.code(), s.target(), s.red(), s.amber(), s.higherIsBetter(),
                    s.updatedBy(), PRINTED.format(s.updatedAt().atOffset(ZoneOffset.UTC)));
        }
    }

    record RagDto(String code, long actual, long target, String status) {
    }

    record Collection<T>(List<T> items) {
    }

    @PostMapping("/v1/kpi-settings")
    @PreAuthorize("hasAuthority('report.kpi.manage')")
    SettingDto set(@Valid @RequestBody SetRequest body) {
        boolean higher = body.higherIsBetter() == null || body.higherIsBetter();
        return SettingDto.of(settings.set(body.code(), body.target(), body.redThreshold(), body.amberThreshold(),
                higher, actors.current().userId()));
    }

    @GetMapping("/v1/kpi-settings")
    @PreAuthorize("hasAuthority('report.read')")
    Collection<SettingDto> all() {
        return new Collection<>(settings.all().stream().map(SettingDto::of).toList());
    }

    @GetMapping("/v1/kpi-settings/{code}")
    @PreAuthorize("hasAuthority('report.read')")
    SettingDto get(@PathVariable String code) {
        return SettingDto.of(settings.get(code));
    }

    @GetMapping(path = "/v1/kpi-settings/{code}/rag", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('report.read')")
    RagDto rag(@PathVariable String code, @RequestParam long actual) {
        KpiSetting s = settings.get(code);
        return new RagDto(s.code(), actual, s.target(), s.statusOf(actual));
    }
}
