package gov.itms.reporting.api;

import gov.itms.platform.security.Authorisation;
import gov.itms.platform.security.CurrentActorProvider;
import gov.itms.platform.security.ScopeTarget;
import gov.itms.platform.security.Scopes;
import gov.itms.reporting.application.Ports;
import gov.itms.reporting.application.ReportingService;
import gov.itms.reporting.domain.Report;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Report definitions and report runs (RPT-001, RPT-002, RPT-004), as JSON or as CSV. */
@RestController
class ReportController {

    private static final DateTimeFormatter PRINTED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'");
    private final ReportingService reporting;
    private final CurrentActorProvider actors;

    ReportController(ReportingService reporting, CurrentActorProvider actors) {
        this.reporting = reporting;
        this.actors = actors;
    }

    record DefinitionDto(String code, String titleNl, String titleEn, List<String> eventTypes, String amountField) {
        static DefinitionDto of(Report.Definition d) {
            return new DefinitionDto(d.code(), d.titleNl(), d.titleEn(), d.eventTypes(), d.amountField());
        }
    }

    record Collection<T>(List<T> items) {
    }

    record RunRequest(@NotNull @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,39}$") String code, @NotNull LocalDate from,
                      @NotNull LocalDate to, Report.Period period,
                      @Size(max = 50) Set<@Pattern(regexp = "^[A-Z0-9_]{1,40}$") String> regions,
                      @Pattern(regexp = "^[A-Z]{2,20}$") String channel,
                      @Pattern(regexp = "^[A-Z_]{2,30}$") String status) {
        Report.Request toRequest() {
            return new Report.Request(code, from, to, period, regions, channel, status);
        }
    }

    record RowDto(LocalDate period, String region, String eventType, long count, Long amountMinor) {
    }

    record ResultDto(String code, String titleNl, String titleEn, LocalDate from, LocalDate to, Report.Period period,
                     List<RowDto> rows, String printedAt) {
    }

    @GetMapping("/v1/reports")
    @PreAuthorize("hasAuthority('report.read')")
    Collection<DefinitionDto> definitions() {
        return new Collection<>(reporting.definitions().stream().map(DefinitionDto::of).toList());
    }

    @PostMapping(path = "/v1/report-runs", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('report.read')")
    ResultDto run(@Valid @RequestBody RunRequest body) {
        Report.Result r = reporting.run(body.toRequest(), caller());
        return new ResultDto(r.definition().code(), r.definition().titleNl(), r.definition().titleEn(),
                r.request().from(), r.request().to(), r.request().period(), r.rows().stream()
                .map(x -> new RowDto(x.period(), x.region(), x.eventType(), x.count(), x.amountMinor())).toList(),
                PRINTED.format(r.printedAt().atOffset(ZoneOffset.UTC)));
    }

    /**
     * The same report as CSV (RPT-002): UTF-8 with a byte-order mark, the bilingual title and print date first, then
     * the rows. A cell that a spreadsheet could read as a formula is quoted as text.
     */
    @PostMapping(path = "/v1/report-runs", produces = "text/csv")
    @PreAuthorize("hasAuthority('report.read')")
    ResponseEntity<byte[]> csv(@Valid @RequestBody RunRequest body) {
        Report.Result r = reporting.run(body.toRequest(), caller());
        StringBuilder out = new StringBuilder("﻿");
        line(out, r.definition().titleNl(), r.definition().titleEn());
        line(out, "تاريخ الطباعة / Printed", PRINTED.format(r.printedAt().atOffset(ZoneOffset.UTC)));
        line(out, "الفترة / Period", r.request().from() + " - " + r.request().to());
        line(out, "period", "region", "eventType", "count", "amountMinor");
        for (Report.Row x : r.rows()) {
            line(out, x.period().toString(), x.region() == null ? "" : x.region(), x.eventType(),
                    Long.toString(x.count()), x.amountMinor() == null ? "" : Long.toString(x.amountMinor()));
        }
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header("Content-Disposition", "attachment; filename=\"" + r.definition().code() + ".csv\"")
                .body(out.toString().getBytes(StandardCharsets.UTF_8));
    }

    static void line(StringBuilder out, String... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(cell(cells[i]));
        }
        out.append("\r\n");
    }

    static String cell(String value) {
        String v = value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0 && !v.matches("^-?[0-9]+$")) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r") || !v.equals(value)) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    private Ports.Caller caller() {
        Authorisation auth = actors.authorisation();
        return new Ports.Caller(actors.current().userId(), new Ports.Grants() {
            @Override
            public boolean allowsInRegion(String region) {
                return auth.allows(ReportingService.READ, ScopeTarget.region(region));
            }

            @Override
            public boolean national() {
                return auth.covers(ReportingService.READ, Scopes.NATIONAL);
            }
        });
    }
}
