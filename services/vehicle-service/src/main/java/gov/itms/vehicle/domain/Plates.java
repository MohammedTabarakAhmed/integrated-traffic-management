package gov.itms.vehicle.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Plate number series, plates, premium number auctions, manufacturing orders, loss reports and trial plates
 * (VL-030…VL-039; ADR-055, ADR-059).
 */
public final class Plates {

    private Plates() {
    }

    /**
     * A number series (VL-030): per region, plate category, colour code and design, a code prefix and a number range.
     * Standard numbers are handed out in order; numbers matching the premium pattern are held back for auction
     * (VL-034).
     */
    public record Series(String code, String region, String plateCategory, String colourCode, String design,
                         String prefix, long firstNumber, long lastNumber, String premiumPattern, boolean active) {
        public Series {
            Vehicle.code("series code", code);
            Vehicle.code("region", region);
            Vehicle.code("plateCategory", plateCategory);
            Vehicle.code("colourCode", colourCode);
            Vehicle.code("design", design);
            if (prefix == null || !prefix.matches("^[A-Z0-9]{0,5}$")) {
                throw Vehicle.invalid("prefix");
            }
            if (firstNumber < 1 || lastNumber < firstNumber || lastNumber > 99_999_999L) {
                throw DomainException.validation("SERIES_RANGE_INVALID", "the number range is not valid",
                        List.of("VL-030"));
            }
            if (premiumPattern != null) {
                try {
                    Pattern.compile(premiumPattern);
                } catch (RuntimeException e) {
                    throw Vehicle.invalid("premiumPattern");
                }
                if (premiumPattern.length() > 200) {
                    throw Vehicle.invalid("premiumPattern");
                }
            }
        }

        public boolean premium(long number) {
            return premiumPattern != null && Pattern.matches(premiumPattern, Long.toString(number));
        }

        public boolean contains(long number) {
            return number >= firstNumber && number <= lastNumber;
        }

        public String display(long number) {
            return (prefix.isEmpty() ? "" : prefix + " ") + number;
        }
    }

    /** Plate statuses (VL-031). */
    public enum Status { AVAILABLE, RESERVED, ASSIGNED, RETAINED, RETURNED, CANCELLED, WITHDRAWN, LOST }

    /**
     * A plate number in use or held. Numbers never used have no row: they are AVAILABLE by definition. A number whose
     * last row is RETURNED or CANCELLED is free again (VL-033, VL-039); the old rows stay as its history.
     * {@code holdUntil} is when an unpaid reservation (VL-033) or an unassigned retention (VL-039) ends;
     * {@code serialNo} is the physical plate's serial from the factory (VL-036).
     */
    public record Plate(UUID id, String seriesCode, long number, String display, Status status, UUID vehicleId,
                        String holderFileNumber, Instant changedAt, LocalDate holdUntil, String serialNo) {
        public Plate {
            Objects.requireNonNull(id);
            Objects.requireNonNull(seriesCode);
            Objects.requireNonNull(status);
        }

        public Plate(UUID id, String seriesCode, long number, String display, Status status, UUID vehicleId,
                     String holderFileNumber, Instant changedAt) {
            this(id, seriesCode, number, display, status, vehicleId, holderFileNumber, changedAt, null, null);
        }

        /** VL-031 transitions allowed from this plate's status. */
        public Plate moveTo(Status next, UUID vehicle, String holder, Instant now) {
            return moveTo(next, vehicle, holder, now, null);
        }

        /**
         * VL-031 transitions. Besides the BRD's main line, a paid reservation without a vehicle (VL-033) and a won
         * auction number (VL-034) become RETAINED, and a retained number can be reported lost (VL-037).
         */
        public Plate moveTo(Status next, UUID vehicle, String holder, Instant now, LocalDate until) {
            boolean ok = switch (status) {
                case AVAILABLE -> next == Status.RESERVED || next == Status.ASSIGNED || next == Status.WITHDRAWN
                        || next == Status.RETAINED;
                case RESERVED -> next == Status.ASSIGNED || next == Status.AVAILABLE || next == Status.CANCELLED
                        || next == Status.RETAINED;
                case ASSIGNED -> next == Status.RETAINED || next == Status.RETURNED || next == Status.LOST
                        || next == Status.CANCELLED;
                case RETAINED -> next == Status.ASSIGNED || next == Status.RETURNED || next == Status.CANCELLED
                        || next == Status.LOST;
                case WITHDRAWN -> next == Status.ASSIGNED || next == Status.RETAINED || next == Status.AVAILABLE;
                case RETURNED, CANCELLED, LOST -> false;
            };
            if (!ok) {
                throw DomainException.conflict("PLATE_STATUS_INVALID", "a plate cannot go from " + status + " to "
                        + next, List.of("VL-031"), status.name(), next.name());
            }
            return new Plate(id, seriesCode, number, display, next, vehicle, holder, now, until, serialNo);
        }

        /** A retained number sold to someone else (VL-035), or its retention renewed (VL-039). */
        public Plate heldBy(String holder, LocalDate until, Instant now) {
            if (status != Status.RETAINED) {
                throw DomainException.conflict("PLATE_NOT_RETAINED", "the plate number is " + status,
                        List.of("VL-035", "VL-039"), status.name());
            }
            return new Plate(id, seriesCode, number, display, status, null, holder, now, until, serialNo);
        }

        public Plate withSerial(String serial, Instant now) {
            return new Plate(id, seriesCode, number, display, status, vehicleId, holderFileNumber, now, holdUntil,
                    serial);
        }
    }

    // ---- premium number auctions (VL-034)

    public enum AuctionChannel { ONLINE, PARTNER }

    public enum AuctionStatus { OPEN, CLOSED }

    @SuppressWarnings("checkstyle:ParameterNumber")
    public record Auction(UUID id, String title, AuctionChannel salesChannel, String partnerCode, Instant startsAt,
                          Instant endsAt, AuctionStatus status, String region, String createdBy, Instant createdAt,
                          String closedBy, Instant closedAt) {
        public Auction {
            Objects.requireNonNull(id);
            if (title == null || title.isBlank() || title.length() > 200) {
                throw Vehicle.invalid("title");
            }
            Objects.requireNonNull(salesChannel);
            if (salesChannel == AuctionChannel.PARTNER) {
                Vehicle.code("partnerCode", partnerCode);
            }
            if (startsAt == null || endsAt == null || !endsAt.isAfter(startsAt)) {
                throw DomainException.validation("AUCTION_DATES_INVALID", "an auction ends after it starts",
                        List.of("VL-034"));
            }
            Vehicle.code("region", region);
            Objects.requireNonNull(status);
        }

        public boolean biddingOpen(Instant now) {
            return status == AuctionStatus.OPEN && !now.isBefore(startsAt) && now.isBefore(endsAt);
        }

        public Auction closed(String by, Instant now) {
            if (status != AuctionStatus.OPEN) {
                throw DomainException.conflict("AUCTION_CLOSED", "the auction is already closed", List.of("VL-034"));
            }
            if (now.isBefore(endsAt)) {
                throw DomainException.conflict("AUCTION_RUNNING", "the auction runs until " + endsAt,
                        List.of("VL-034"), endsAt.toString());
            }
            return new Auction(id, title, salesChannel, partnerCode, startsAt, endsAt, AuctionStatus.CLOSED, region,
                    createdBy, createdAt, by, now);
        }
    }

    public enum LotStatus { OPEN, SOLD, UNSOLD, PAID }

    /** One premium number in an auction, with its reserve price and, once closed, the winner. */
    public record Lot(UUID auctionId, UUID plateId, long reserveMinor, LotStatus status, String winnerFile,
                      Long winningMinor) {
        public Lot {
            Objects.requireNonNull(auctionId);
            Objects.requireNonNull(plateId);
            if (reserveMinor < 0) {
                throw Vehicle.invalid("reserveMinor");
            }
            Objects.requireNonNull(status);
        }
    }

    /** A bid, placed online or reported by an auction partner (VL-034). */
    public record Bid(UUID id, UUID auctionId, UUID plateId, String bidderFile, long amountMinor, String source,
                      String placedBy, Instant placedAt) {
        public Bid {
            Objects.requireNonNull(id);
            Vehicle.ref("bidderFileNumber", bidderFile);
            if (amountMinor <= 0) {
                throw Vehicle.invalid("amountMinor");
            }
        }
    }

    // ---- manufacturing orders (VL-036)

    public enum OrderStatus { ORDERED, IN_PRODUCTION, DELIVERED, CANCELLED }

    public record Order(UUID id, String factoryCode, OrderStatus status, String raisedBy, Instant raisedAt,
                        Instant changedAt) {
        public Order {
            Objects.requireNonNull(id);
            Vehicle.code("factoryCode", factoryCode);
            Objects.requireNonNull(status);
        }

        public Order moveTo(OrderStatus next, Instant now) {
            boolean ok = switch (status) {
                case ORDERED -> next == OrderStatus.IN_PRODUCTION || next == OrderStatus.CANCELLED;
                case IN_PRODUCTION -> next == OrderStatus.DELIVERED || next == OrderStatus.CANCELLED;
                case DELIVERED, CANCELLED -> false;
            };
            if (!ok) {
                throw DomainException.conflict("ORDER_STATUS_INVALID", "an order cannot go from " + status + " to "
                        + next, List.of("VL-036"), status.name(), next.name());
            }
            return new Order(id, factoryCode, next, raisedBy, raisedAt, now);
        }
    }

    /** One physical plate to make, queued until an order takes it (VL-036). */
    public record OrderItem(UUID id, UUID plateId, UUID orderId, String reason, String serialNo, Instant createdAt) {
    }

    // ---- lost and stolen plates (VL-037)

    public record LossReport(UUID id, UUID plateId, String kind, String policeRef, String replacement, String status,
                             UUID newPlateId, String reportedBy, Instant reportedAt) {
        public LossReport {
            Objects.requireNonNull(id);
            if (!"LOST".equals(kind) && !"STOLEN".equals(kind)) {
                throw Vehicle.invalid("kind");
            }
            Vehicle.ref("policeReference", policeRef);
            if (!"SAME".equals(replacement) && !"NEW".equals(replacement)) {
                throw Vehicle.invalid("replacement");
            }
        }
    }

    // ---- trial (dealer) plates (VL-038)

    public enum TrialStatus { AWAITING_PAYMENT, ACTIVE, EXCHANGED, CANCELLED, EXPIRED }

    @SuppressWarnings("checkstyle:ParameterNumber")
    public record Trial(UUID id, UUID plateId, String dealerFile, String region, TrialStatus status,
                        LocalDate validFrom, LocalDate validTo, int days, UUID replaces, String createdBy,
                        Instant createdAt, Instant changedAt) {
        public Trial {
            Objects.requireNonNull(id);
            Objects.requireNonNull(plateId);
            Vehicle.ref("dealerFileNumber", dealerFile);
            Objects.requireNonNull(status);
        }

        public Trial moveTo(TrialStatus next, LocalDate from, LocalDate to, Instant now) {
            boolean ok = switch (status) {
                case AWAITING_PAYMENT -> next == TrialStatus.ACTIVE || next == TrialStatus.CANCELLED;
                case ACTIVE -> next == TrialStatus.EXCHANGED || next == TrialStatus.CANCELLED
                        || next == TrialStatus.EXPIRED;
                case EXCHANGED, CANCELLED, EXPIRED -> false;
            };
            if (!ok) {
                throw DomainException.conflict("TRIAL_PLATE_STATUS_INVALID", "a trial plate cannot go from " + status
                        + " to " + next, List.of("VL-038"), status.name(), next.name());
            }
            return new Trial(id, plateId, dealerFile, region, next, from == null ? validFrom : from,
                    to == null ? validTo : to, days, replaces, createdBy, createdAt, now);
        }
    }
}
