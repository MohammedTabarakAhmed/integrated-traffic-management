package gov.itms.vehicle.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One vehicle (VL-001; ADR-055), keyed by its VIN, with its technical data, category (VL-004), owner's traffic file,
 * registration state and current plate.
 *
 * <p>UNREGISTERED → REGISTERED (first registration paid) → REGISTERED with a later expiry on each renewal; a
 * registration past its expiry date is shown EXPIRED (derived, never stored). DEREGISTERED is final.
 */
public final class Vehicle {

    public enum Status { UNREGISTERED, REGISTERED, DEREGISTERED }

    public static final String CODE = "^[A-Z0-9][A-Z0-9_]{0,39}$";
    private static final Pattern CODE_PATTERN = Pattern.compile(CODE);
    private static final Pattern REF = Pattern.compile("^[A-Z0-9][A-Z0-9-]{0,59}$");

    /** Technical data (VL-001); codes come from reference lists (makes, models, colours, fuel types...). */
    public record Spec(String engineNo, String make, String model, int modelYear, String bodyType, List<String> colours,
                       String fuelType, String steering, int seats, int emptyWeightKg, int grossWeightKg, int axles,
                       int cylinders, String category) {
        public Spec {
            if (engineNo == null || !engineNo.matches("^[A-Z0-9-]{1,40}$")) {
                throw invalid("engineNo");
            }
            code("make", make);
            code("model", model);
            code("bodyType", bodyType);
            code("fuelType", fuelType);
            code("category", category);
            if (!"LEFT".equals(steering) && !"RIGHT".equals(steering)) {
                throw invalid("steering");
            }
            colours = List.copyOf(colours);
            if (colours.isEmpty() || colours.size() > 3) {
                throw invalid("colours");
            }
            colours.forEach(c -> code("colour", c));
            if (modelYear < 1886 || modelYear > 2100 || seats < 0 || seats > 200
                    || emptyWeightKg < 1 || grossWeightKg < emptyWeightKg || grossWeightKg > 200_000 || axles < 1
                    || axles > 20 || cylinders < 0 || cylinders > 32) {
                throw DomainException.validation("VEHICLE_DATA_INVALID", "a technical value is out of range",
                        List.of("VL-001"));
            }
        }
    }

    private final UUID id;
    private final String vin;
    private Spec spec;
    private final Instant createdAt;
    private String ownerFileNumber;
    private String region;
    private Status status;
    private LocalDate registrationExpiry;
    private UUID plateId;
    private String plateNumber;
    private String customsCertificateNo;
    private long version;

    private Vehicle(UUID id, String vin, Spec spec, Instant createdAt) {
        this.id = Objects.requireNonNull(id);
        this.vin = Objects.requireNonNull(vin);
        this.spec = Objects.requireNonNull(spec);
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public static Vehicle create(UUID id, String vin, Spec spec, String ownerFileNumber, String region,
                                 Instant now) {
        Vehicle v = new Vehicle(id, vin, spec, now);
        v.ownerFileNumber = ref("ownerFileNumber", ownerFileNumber);
        v.region = code("region", region);
        v.status = Status.UNREGISTERED;
        return v;
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    public static Vehicle restore(UUID id, String vin, Spec spec, Instant createdAt, String ownerFileNumber,
                                  String region, Status status, LocalDate registrationExpiry, UUID plateId,
                                  String plateNumber, long version) {
        Vehicle v = new Vehicle(id, vin, spec, createdAt);
        v.ownerFileNumber = ownerFileNumber;
        v.region = region;
        v.status = status;
        v.registrationExpiry = registrationExpiry;
        v.plateId = plateId;
        v.plateNumber = plateNumber;
        v.version = version;
        return v;
    }

    /** Age in whole years from the model year, used for inspection need and validity rules (VL-023, VL-041). */
    public int ageYears(LocalDate today) {
        return Math.max(0, Period.between(LocalDate.of(spec.modelYear(), 1, 1), today).getYears());
    }

    public boolean registrationValid(LocalDate today) {
        return status == Status.REGISTERED && registrationExpiry != null && !today.isAfter(registrationExpiry);
    }

    /** First registration paid (VL-010): plate assigned, valid until {@code expiry}. */
    public void registered(UUID plate, String number, LocalDate expiry) {
        if (status != Status.UNREGISTERED) {
            throw DomainException.conflict("ALREADY_REGISTERED", "the vehicle is already " + status,
                    List.of("VL-010"), status.name());
        }
        plateId = Objects.requireNonNull(plate);
        plateNumber = Objects.requireNonNull(number);
        registrationExpiry = Objects.requireNonNull(expiry);
        status = Status.REGISTERED;
    }

    /** Renewal paid (VL-011): the expiry moves on. */
    public void renewed(LocalDate newExpiry) {
        if (status != Status.REGISTERED) {
            throw DomainException.conflict("NOT_REGISTERED", "the vehicle is " + status, List.of("VL-011"),
                    status.name());
        }
        if (registrationExpiry != null && !newExpiry.isAfter(registrationExpiry)) {
            throw new IllegalStateException("a renewal moves the expiry forward");
        }
        registrationExpiry = newExpiry;
    }

    /** The customs or dealer certificate the record was pre-filled from (VL-002, VL-010). */
    public void linkCertificate(String certificateNo) {
        customsCertificateNo = ref("customsCertificateNo", certificateNo);
    }

    /** Handover of a new, not yet registered vehicle from the dealer to its buyer (VL-027). */
    public void handedOver(String buyerFile) {
        if (status != Status.UNREGISTERED) {
            throw DomainException.conflict("ALREADY_REGISTERED", "only a new, unregistered vehicle is handed over",
                    List.of("VL-027"), status.name());
        }
        ownerFileNumber = ref("buyerFileNumber", buyerFile);
    }

    /** Ownership transfer paid (VL-012): the buyer owns the vehicle; the plate may change with it. */
    public void transferred(String buyerFile, UUID plate, String number) {
        requireRegistered("VL-012");
        ownerFileNumber = ref("buyerFileNumber", buyerFile);
        if (plate != null) {
            plateId = plate;
            plateNumber = number;
        }
    }

    /** Change of colour, engine, body or use type after an inspection (VL-013). The VIN never changes. */
    public void changed(Spec newSpec) {
        if (status == Status.DEREGISTERED) {
            throw DomainException.conflict("VEHICLE_DEREGISTERED", "the vehicle is de-registered", List.of("VL-013"));
        }
        spec = Objects.requireNonNull(newSpec);
    }

    /** Another plate on the vehicle (VL-035 exchange, VL-037 replacement with a new number). */
    public void plateChanged(UUID plate, String number) {
        requireRegistered("VL-035");
        plateId = Objects.requireNonNull(plate);
        plateNumber = Objects.requireNonNull(number);
    }

    /** De-registered for scrap, export or total loss (VL-015): final. */
    public void deregistered() {
        if (status == Status.DEREGISTERED) {
            throw DomainException.conflict("VEHICLE_DEREGISTERED", "the vehicle is already de-registered",
                    List.of("VL-015"));
        }
        status = Status.DEREGISTERED;
        plateId = null;
        plateNumber = null;
    }

    private void requireRegistered(String requirement) {
        if (status != Status.REGISTERED) {
            throw DomainException.conflict("NOT_REGISTERED", "the vehicle is " + status, List.of(requirement),
                    status.name());
        }
    }

    public void saved() {
        version++;
    }

    static String code(String field, String value) {
        if (value == null || !CODE_PATTERN.matcher(value).matches()) {
            throw invalid(field);
        }
        return value;
    }

    static String ref(String field, String value) {
        if (value == null || !REF.matcher(value).matches()) {
            throw invalid(field);
        }
        return value;
    }

    static DomainException invalid(String field) {
        return DomainException.validation("FIELD_INVALID", field + " is missing or malformed", List.of("VL-001"),
                field);
    }

    public UUID id() {
        return id;
    }

    public String vin() {
        return vin;
    }

    public Spec spec() {
        return spec;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public String ownerFileNumber() {
        return ownerFileNumber;
    }

    public String region() {
        return region;
    }

    public Status status() {
        return status;
    }

    public LocalDate registrationExpiry() {
        return registrationExpiry;
    }

    public UUID plateId() {
        return plateId;
    }

    public String plateNumber() {
        return plateNumber;
    }

    public String customsCertificateNo() {
        return customsCertificateNo;
    }

    public long version() {
        return version;
    }
}
