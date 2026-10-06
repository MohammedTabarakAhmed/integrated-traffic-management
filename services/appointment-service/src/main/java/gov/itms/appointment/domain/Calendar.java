package gov.itms.appointment.domain;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Calendar configuration (CC-090, CC-092; ADR-052): a service centre's working hours per weekday and time zone, its
 * closures (holidays and blackout periods), the resources that serve appointments there (examiners, inspection lanes,
 * committees) with their capacity, and per-service booking settings. Slots are computed, never stored.
 */
public final class Calendar {

    private Calendar() {
    }

    public static final String CODE = "^[A-Z0-9][A-Z0-9_]{1,39}$";
    private static final Pattern CODE_PATTERN = Pattern.compile(CODE);

    static String checkCode(String field, String value) {
        if (value == null || !CODE_PATTERN.matcher(value).matches()) {
            throw invalid(field);
        }
        return value;
    }

    static DomainException invalid(String field) {
        return DomainException.validation("FIELD_INVALID", field + " is missing or malformed", List.of("CC-090"),
                field);
    }

    /** Opening hours of one weekday; several periods allowed (for example a split shift). */
    public record Period(LocalTime opens, LocalTime closes) {
        public Period {
            if (opens == null || closes == null || !opens.isBefore(closes)) {
                throw DomainException.validation("HOURS_INVALID", "a period opens before it closes",
                        List.of("CC-092"));
            }
        }
    }

    /** A centre's calendar (CC-092). */
    public record Centre(String code, String region, ZoneId zone, Map<DayOfWeek, List<Period>> weeklyHours,
                         boolean active) {
        public Centre {
            checkCode("centre code", code);
            checkCode("region", region);
            Objects.requireNonNull(zone, "zone");
            Map<DayOfWeek, List<Period>> copy = new EnumMap<>(DayOfWeek.class);
            if (weeklyHours != null) {
                weeklyHours.forEach((day, periods) -> {
                    List<Period> sorted = new ArrayList<>(periods);
                    sorted.sort((a, b) -> a.opens().compareTo(b.opens()));
                    for (int i = 1; i < sorted.size(); i++) {
                        if (sorted.get(i).opens().isBefore(sorted.get(i - 1).closes())) {
                            throw DomainException.validation("HOURS_INVALID", "periods of " + day + " overlap",
                                    List.of("CC-092"));
                        }
                    }
                    copy.put(day, List.copyOf(sorted));
                });
            }
            weeklyHours = Map.copyOf(copy);
        }

        public List<Period> hours(DayOfWeek day) {
            return weeklyHours.getOrDefault(day, List.of());
        }
    }

    public enum ClosureKind { HOLIDAY, BLACKOUT }

    /** A closed date range (inclusive) of a centre, or of one resource when {@code resourceCode} is set (CC-092). */
    public record Closure(long id, String centreCode, String resourceCode, LocalDate from, LocalDate to,
                          ClosureKind kind, String reason) {
        public Closure {
            checkCode("centre code", centreCode);
            if (resourceCode != null) {
                checkCode("resource code", resourceCode);
            }
            if (from == null || to == null || to.isBefore(from) || from.plusYears(2).isBefore(to)) {
                throw DomainException.validation("CLOSURE_INVALID", "a closure runs forwards for at most 2 years",
                        List.of("CC-092"));
            }
            Objects.requireNonNull(kind, "kind");
            if (reason == null || reason.isBlank() || reason.length() > 200) {
                throw invalid("reason");
            }
        }

        public boolean covers(LocalDate day, String resource) {
            return !day.isBefore(from) && !day.isAfter(to) && (resourceCode == null || resourceCode.equals(resource));
        }
    }

    public enum ResourceType { EXAMINER, INSPECTION_LANE, COMMITTEE, COUNTER }

    /** Something appointments are booked on (CC-090), serving some services, {@code capacity} bookings per slot. */
    public record Resource(String code, String centreCode, ResourceType type, String name, Set<String> services,
                           int capacity, boolean active) {
        public Resource {
            checkCode("resource code", code);
            checkCode("centre code", centreCode);
            Objects.requireNonNull(type, "type");
            if (name == null || name.isBlank() || name.length() > 200) {
                throw invalid("name");
            }
            services = Set.copyOf(services);
            if (services.isEmpty() || services.size() > 50) {
                throw invalid("services");
            }
            services.forEach(s -> checkCode("service code", s));
            if (capacity < 1 || capacity > 100) {
                throw DomainException.validation("CAPACITY_INVALID", "capacity is 1 to 100 per slot",
                        List.of("CC-092"));
            }
        }
    }

    /**
     * Booking rules of one service (CC-091, CC-093): slot length, how far ahead and how late customers may book,
     * change or cancel, how many changes are allowed (BRD example: 2), and the no-show penalty.
     *
     * @param noShowLimit       no-shows within {@code noShowWindowDays} that trigger the penalty (0 = no penalty)
     * @param penaltyDays       days during which a penalised customer cannot book online
     * @param penaltyFeeCode    fee charged for a no-show, when set (fees-service), else {@code null}
     */
    public record ServiceSettings(String serviceCode, Duration slotLength, int horizonDays, Duration minLeadTime,
                                  int maxChanges, int noShowLimit, int noShowWindowDays, int penaltyDays,
                                  String penaltyFeeCode) {
        public ServiceSettings {
            checkCode("service code", serviceCode);
            if (slotLength == null || slotLength.toMinutes() < 5 || slotLength.toMinutes() > 480
                    || slotLength.toSeconds() % 60 != 0) {
                throw DomainException.validation("SETTINGS_INVALID", "a slot is 5 minutes to 8 hours, whole minutes",
                        List.of("CC-091"));
            }
            if (horizonDays < 1 || horizonDays > 365 || minLeadTime == null || minLeadTime.isNegative()
                    || minLeadTime.compareTo(Duration.ofDays(30)) > 0 || maxChanges < 0 || maxChanges > 20
                    || noShowLimit < 0 || noShowLimit > 20 || noShowWindowDays < 1 || noShowWindowDays > 730
                    || penaltyDays < 0 || penaltyDays > 365) {
                throw DomainException.validation("SETTINGS_INVALID", "a booking setting is out of range",
                        List.of("CC-091", "CC-093"));
            }
            if (penaltyFeeCode != null) {
                checkCode("penalty fee code", penaltyFeeCode);
            }
        }
    }

    /** A free slot on one resource (computed). */
    public record Slot(String resourceCode, ZonedDateTime start, ZonedDateTime end, int free) {
    }

    /**
     * The slots of a resource on a day: working periods cut into slot lengths, nothing before {@code notBefore},
     * nothing on a closed day (CC-092). Free capacity is filled in by the caller.
     */
    public static List<ZonedDateTime> slotStarts(Centre centre, LocalDate day, Duration length,
                                                 List<Closure> closures, String resourceCode,
                                                 ZonedDateTime notBefore) {
        if (closures.stream().anyMatch(c -> c.covers(day, resourceCode))) {
            return List.of();
        }
        List<ZonedDateTime> starts = new ArrayList<>();
        for (Period p : centre.hours(day.getDayOfWeek())) {
            LocalDateTime t = day.atTime(p.opens());
            LocalDateTime end = day.atTime(p.closes());
            while (!t.plus(length).isAfter(end)) {
                ZonedDateTime z = t.atZone(centre.zone());
                if (!z.isBefore(notBefore)) {
                    starts.add(z);
                }
                t = t.plus(length);
            }
        }
        return starts;
    }
}
