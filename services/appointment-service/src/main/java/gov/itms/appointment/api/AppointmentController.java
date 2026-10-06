package gov.itms.appointment.api;

import gov.itms.appointment.application.AppointmentService;
import gov.itms.appointment.domain.Booking;
import gov.itms.appointment.domain.Calendar;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Calendars, slots and bookings of {@code appointment-service.v1.yaml} (CC-090..CC-093). */
@RestController
class AppointmentController {

    private final AppointmentService appointments;
    private final Callers callers;

    AppointmentController(AppointmentService appointments, Callers callers) {
        this.appointments = appointments;
        this.callers = callers;
    }

    record PeriodDto(@NotNull LocalTime opens, @NotNull LocalTime closes) {
    }

    record CentreDto(@NotBlank String code, @NotBlank String region, @NotBlank String zone,
                     @NotNull @Size(max = 7) Map<DayOfWeek, @NotNull @Size(max = 6) List<@NotNull @Valid PeriodDto>>
                             weeklyHours, @NotNull Boolean active) {
        Calendar.Centre toDomain() {
            Map<DayOfWeek, List<Calendar.Period>> m = new EnumMap<>(DayOfWeek.class);
            weeklyHours.forEach((d, ps) -> m.put(d, ps.stream().map(p -> new Calendar.Period(p.opens(), p.closes()))
                    .toList()));
            ZoneId z;
            try {
                z = ZoneId.of(zone);
            } catch (RuntimeException e) {
                throw gov.itms.appointment.domain.DomainException.validation("FIELD_INVALID", "zone is unknown",
                        List.of("CC-092"), "zone");
            }
            return new Calendar.Centre(code, region, z, m, active);
        }

        static CentreDto of(Calendar.Centre c) {
            Map<DayOfWeek, List<PeriodDto>> m = new EnumMap<>(DayOfWeek.class);
            c.weeklyHours().forEach((d, ps) -> m.put(d, ps.stream().map(p -> new PeriodDto(p.opens(), p.closes()))
                    .toList()));
            return new CentreDto(c.code(), c.region(), c.zone().getId(), m, c.active());
        }
    }

    record ClosureRequest(String resourceCode, @NotNull LocalDate from, @NotNull LocalDate to,
                          @NotNull Calendar.ClosureKind kind, @NotBlank @Size(max = 200) String reason) {
    }

    record ResourceDto(@NotBlank String code, @NotBlank String centreCode, @NotNull Calendar.ResourceType type,
                       @NotBlank @Size(max = 200) String name, @NotNull @Size(min = 1, max = 50) Set<String> services,
                       @Min(1) @Max(100) int capacity, @NotNull Boolean active) {
        Calendar.Resource toDomain() {
            return new Calendar.Resource(code, centreCode, type, name, services, capacity, active);
        }

        static ResourceDto of(Calendar.Resource r) {
            return new ResourceDto(r.code(), r.centreCode(), r.type(), r.name(), r.services(), r.capacity(),
                    r.active());
        }
    }

    record SettingsDto(@NotBlank String serviceCode, @Min(5) @Max(480) int slotMinutes, @Min(1) @Max(365) int horizonDays,
                       @Min(0) @Max(43200) int minLeadMinutes, @Min(0) @Max(20) int maxChanges,
                       @Min(0) @Max(20) int noShowLimit, @Min(1) @Max(730) int noShowWindowDays,
                       @Min(0) @Max(365) int penaltyDays, String penaltyFeeCode) {
        Calendar.ServiceSettings toDomain() {
            return new Calendar.ServiceSettings(serviceCode, Duration.ofMinutes(slotMinutes), horizonDays,
                    Duration.ofMinutes(minLeadMinutes), maxChanges, noShowLimit, noShowWindowDays, penaltyDays,
                    penaltyFeeCode);
        }

        static SettingsDto of(Calendar.ServiceSettings s) {
            return new SettingsDto(s.serviceCode(), (int) s.slotLength().toMinutes(), s.horizonDays(),
                    (int) s.minLeadTime().toMinutes(), s.maxChanges(), s.noShowLimit(), s.noShowWindowDays(),
                    s.penaltyDays(), s.penaltyFeeCode());
        }
    }

    record SlotDto(String resourceCode, ZonedDateTime start, ZonedDateTime end, int free) {
    }

    record BookRequest(@NotBlank String serviceCode, @NotBlank String centreCode, String resourceCode,
                       @NotNull Instant start, @NotBlank @Size(max = 100) String customerId,
                       @Size(max = 60) String transactionReference) {
    }

    record ChangeRequest(String resourceCode, @NotNull Instant start) {
    }

    record CancelRequest(@Size(min = 3, max = 500) String reason) {
    }

    record AttendanceRequest(@NotNull Boolean attended) {
    }

    record BookingDto(UUID id, String serviceCode, String centreCode, String resourceCode, String customerId,
                      String transactionReference, Instant start, Instant end, Booking.Status status, int changes,
                      Instant createdAt, Instant closedAt) {
        static BookingDto of(Booking b) {
            return new BookingDto(b.id(), b.serviceCode(), b.centreCode(), b.resourceCode(), b.customerId(),
                    b.transactionReference(), b.start(), b.end(), b.status(), b.changes(), b.createdAt(),
                    b.closedAt());
        }
    }

    record Collection<T>(List<T> items) {
    }

    // ---- calendars

    @PostMapping("/v1/appointment-centres")
    @PreAuthorize("hasAuthority('appointment.calendar.manage')")
    CentreDto saveCentre(@Valid @RequestBody CentreDto body) {
        return CentreDto.of(appointments.saveCentre(body.toDomain(), callers.current()));
    }

    @GetMapping("/v1/appointment-centres/{code}")
    @PreAuthorize("isAuthenticated()")
    CentreDto centre(@PathVariable String code) {
        return CentreDto.of(appointments.centre(code));
    }

    @PostMapping("/v1/appointment-centres/{code}/closures")
    @PreAuthorize("hasAuthority('appointment.calendar.manage')")
    ResponseEntity<Calendar.Closure> addClosure(@PathVariable String code, @Valid @RequestBody ClosureRequest body) {
        Calendar.Closure c = appointments.addClosure(new Calendar.Closure(0, code, body.resourceCode(), body.from(),
                body.to(), body.kind(), body.reason()), callers.current());
        return ResponseEntity.created(URI.create("/v1/appointment-centres/" + code + "/closures")).body(c);
    }

    @GetMapping("/v1/appointment-centres/{code}/closures")
    @PreAuthorize("isAuthenticated()")
    Collection<Calendar.Closure> closures(@PathVariable String code, @RequestParam LocalDate from,
                                          @RequestParam LocalDate to) {
        return new Collection<>(appointments.closures(code, from, to));
    }

    @PostMapping("/v1/appointment-resources")
    @PreAuthorize("hasAuthority('appointment.calendar.manage')")
    ResourceDto saveResource(@Valid @RequestBody ResourceDto body) {
        return ResourceDto.of(appointments.saveResource(body.toDomain(), callers.current()));
    }

    @GetMapping("/v1/appointment-resources")
    @PreAuthorize("isAuthenticated()")
    Collection<ResourceDto> resources(@RequestParam String centreCode) {
        return new Collection<>(appointments.resources(centreCode).stream().map(ResourceDto::of).toList());
    }

    @PostMapping("/v1/appointment-service-settings")
    @PreAuthorize("hasAuthority('appointment.calendar.manage')")
    SettingsDto saveSettings(@Valid @RequestBody SettingsDto body) {
        return SettingsDto.of(appointments.saveSettings(body.toDomain(), callers.current()));
    }

    @GetMapping("/v1/appointment-service-settings/{serviceCode}")
    @PreAuthorize("isAuthenticated()")
    SettingsDto settings(@PathVariable String serviceCode) {
        return SettingsDto.of(appointments.settings(serviceCode));
    }

    // ---- slots and bookings

    @GetMapping("/v1/slots")
    @PreAuthorize("isAuthenticated()")
    Collection<SlotDto> slots(@RequestParam String centreCode, @RequestParam String serviceCode,
                              @RequestParam LocalDate from, @RequestParam LocalDate to) {
        return new Collection<>(appointments.slots(centreCode, serviceCode, from, to).stream()
                .map(s -> new SlotDto(s.resourceCode(), s.start(), s.end(), s.free())).toList());
    }

    @PostMapping("/v1/bookings")
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<BookingDto> book(@Valid @RequestBody BookRequest body) {
        Booking b = appointments.book(body.serviceCode(), body.centreCode(), body.resourceCode(), body.start(),
                body.customerId(), body.transactionReference(), callers.current());
        return ResponseEntity.created(URI.create("/v1/bookings/" + b.id())).body(BookingDto.of(b));
    }

    @GetMapping("/v1/bookings")
    @PreAuthorize("isAuthenticated()")
    Collection<BookingDto> bookings(@RequestParam(required = false) String customerId,
                                    @RequestParam(required = false) String centreCode,
                                    @RequestParam(required = false) LocalDate day,
                                    @RequestParam(defaultValue = "100") int limit) {
        List<Booking> found;
        if (centreCode != null && day != null) {
            found = appointments.byCentre(centreCode, day, limit, callers.current());
        } else {
            String who = customerId == null ? callers.current().actor().id() : customerId;
            found = appointments.byCustomer(who, limit, callers.current());
        }
        return new Collection<>(found.stream().map(BookingDto::of).toList());
    }

    @GetMapping("/v1/bookings/{id}")
    @PreAuthorize("isAuthenticated()")
    BookingDto get(@PathVariable UUID id) {
        return BookingDto.of(appointments.get(id, callers.current()));
    }

    @PostMapping("/v1/bookings/{id}/changes")
    @PreAuthorize("isAuthenticated()")
    BookingDto change(@PathVariable UUID id, @Valid @RequestBody ChangeRequest body) {
        return BookingDto.of(appointments.change(id, body.resourceCode(), body.start(), callers.current()));
    }

    @PostMapping("/v1/bookings/{id}/cancellations")
    @PreAuthorize("isAuthenticated()")
    BookingDto cancel(@PathVariable UUID id, @Valid @RequestBody CancelRequest body) {
        return BookingDto.of(appointments.cancel(id, body.reason(), callers.current()));
    }

    @PostMapping("/v1/bookings/{id}/attendances")
    @PreAuthorize("hasAuthority('appointment.attendance')")
    BookingDto attendance(@PathVariable UUID id, @Valid @RequestBody AttendanceRequest body) {
        return BookingDto.of(appointments.attendance(id, body.attended(), callers.current()));
    }
}
