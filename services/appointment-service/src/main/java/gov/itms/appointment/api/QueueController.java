package gov.itms.appointment.api;

import gov.itms.appointment.application.AppointmentService;
import gov.itms.appointment.application.AppointmentService.QueueDay;
import gov.itms.appointment.application.AppointmentService.QueueMetrics;
import gov.itms.appointment.domain.QueueTicket;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Centre queue management (CH-031): staff issue walk-up tickets, call them to a counter, finish the service or
 * record a departure; the day's queue with its waiting and service times is read by the centre's staff. Setting
 * needs {@code appointment.queue.manage}; reading needs {@code appointment.read}.
 */
@RestController
class QueueController {

    private static final String TICKET_NO = "^[A-Z0-9][A-Z0-9_-]{1,29}$";

    private final AppointmentService appointments;
    private final Callers callers;

    QueueController(AppointmentService appointments, Callers callers) {
        this.appointments = appointments;
        this.callers = callers;
    }

    record IssueRequest(@NotBlank @Pattern(regexp = "^[A-Z0-9][A-Z0-9_]{1,39}$") String centreCode,
                        String serviceCode) {
    }

    record CallRequest(@NotBlank @Size(max = 40) String counter) {
    }

    record DepartureRequest(@NotBlank @Size(min = 3, max = 500) String reason) {
    }

    record TicketDto(String ticketNo, String centreCode, String serviceCode, QueueTicket.Status status,
                     String counter, Instant issuedAt, Instant calledAt, Instant finishedAt, Instant leftAt,
                     String leftReason, Long waitingMinutes, Long serviceMinutes, String issuedBy) {
        static TicketDto of(QueueTicket t) {
            return new TicketDto(t.ticketNo(), t.centreCode(), t.serviceCode(), t.status(), t.counter(),
                    t.issuedAt(), t.calledAt(), t.finishedAt(), t.leftAt(), t.leftReason(),
                    t.waiting() == null ? null : t.waiting().toMinutes(),
                    t.service() == null ? null : t.service().toMinutes(), t.issuedBy());
        }
    }

    record QueueDayDto(List<TicketDto> items, QueueMetrics metrics) {
    }

    @PostMapping("/v1/queue-tickets")
    @PreAuthorize("hasAuthority('appointment.queue.manage')")
    TicketDto issue(@RequestBody IssueRequest body) {
        QueueTicket t = appointments.issueTicket(body.centreCode(), body.serviceCode(), callers.current());
        return TicketDto.of(t);
    }

    @PostMapping("/v1/queue-tickets/{ticketNo}/calls")
    @PreAuthorize("hasAuthority('appointment.queue.manage')")
    TicketDto call(@PathVariable @Pattern(regexp = TICKET_NO) String ticketNo,
                   @Valid @RequestBody CallRequest body) {
        return TicketDto.of(appointments.callTicket(ticketNo, body.counter(), callers.current()));
    }

    @PostMapping("/v1/queue-tickets/{ticketNo}/completions")
    @PreAuthorize("hasAuthority('appointment.queue.manage')")
    TicketDto serve(@PathVariable @Pattern(regexp = TICKET_NO) String ticketNo) {
        return TicketDto.of(appointments.serveTicket(ticketNo, callers.current()));
    }

    @PostMapping("/v1/queue-tickets/{ticketNo}/departures")
    @PreAuthorize("hasAuthority('appointment.queue.manage')")
    TicketDto leave(@PathVariable @Pattern(regexp = TICKET_NO) String ticketNo,
                    @Valid @RequestBody DepartureRequest body) {
        return TicketDto.of(appointments.leaveTicket(ticketNo, body.reason(), callers.current()));
    }

    @GetMapping("/v1/queue-tickets")
    @PreAuthorize("hasAuthority('appointment.read')")
    QueueDayDto day(@RequestParam @Pattern(regexp = "^[A-Z0-9][A-Z0-9_]{1,39}$") String centreCode,
                    @RequestParam LocalDate date, @RequestParam(required = false) Integer limit) {
        int l = limit == null ? 50 : limit;
        QueueDay d = appointments.queueDay(centreCode, date, l, callers.current());
        return new QueueDayDto(d.tickets().stream().map(TicketDto::of).toList(), d.metrics());
    }
}
