package gov.itms.vehicle.application;

import gov.itms.platform.audit.AuditRecorder;
import gov.itms.platform.events.DomainEventRecorder;
import gov.itms.platform.security.CurrentActor;
import gov.itms.vehicle.domain.DomainException;
import gov.itms.vehicle.domain.Plates;
import gov.itms.vehicle.domain.Records;
import gov.itms.vehicle.domain.Registration;
import gov.itms.vehicle.domain.Vehicle;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plate numbers after the standard allocation (Increment 2; ADR-059):
 * <ul>
 *   <li>reservation of a chosen number for a fee, unpaid reservations expire after the reservation period
 *       (VL-033);</li>
 *   <li>premium numbers withdrawn and sold by auction, online or through partners; the winner owns the number
 *       (VL-034);</li>
 *   <li>a number moved to another of the owner's vehicles, or sold to another person (VL-035);</li>
 *   <li>manufacturing orders to plate factories, production, delivery and serial numbers (VL-036);</li>
 *   <li>lost or stolen plates reported under a police circular and replaced, same or new number (VL-037);</li>
 *   <li>trial plates for dealers for limited periods, exchanged and cancelled (VL-038);</li>
 *   <li>retention periods of numbers without a vehicle; not renewed, they return to the pool (VL-039).</li>
 * </ul>
 */
public class PlateService implements PaidHandler {

    public static final String AUCTION = "vehicle.plate.auction";
    public static final String FACTORY = "vehicle.plate.factory";
    private static final int BATCH = 500;

    private final Ports.VehicleRepository repo;
    private final Ports.RecordsRepository records;
    private final RequestDesk desk;
    private final AuditRecorder audit;
    private final DomainEventRecorder events;
    private final Clock clock;

    public PlateService(Ports.VehicleRepository repo, Ports.RecordsRepository records, RequestDesk desk,
                        AuditRecorder audit, DomainEventRecorder events, Clock clock) {
        this.repo = repo;
        this.records = records;
        this.desk = desk;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    // ---- reading

    @Transactional
    public Plates.Plate plate(UUID id, Caller caller) {
        Plates.Plate p = repo.findPlate(id).orElseThrow(() -> VehicleTransactions.notFound("PLATE_NOT_FOUND",
                "VL-031"));
        require(caller.grants().allowsInRegion(VehicleService.READ, seriesOf(p).region()));
        return p;
    }

    @Transactional
    public List<Plates.Plate> heldBy(String holderFile, Caller caller) {
        require(caller.grants().holds(VehicleService.READ));
        return records.platesHeldBy(VehicleTransactions.checkedRef("fileNumber", holderFile));
    }

    // ---- reservation (VL-033) and retention (VL-039)

    @Transactional
    public Registration.Request reserve(String seriesCode, long number, String holderFile, String customerCategory,
                                        Caller caller) {
        String channel = VehicleService.channelOf(caller);
        Plates.Series s = repo.findSeries(seriesCode).filter(Plates.Series::active)
                .orElseThrow(() -> VehicleTransactions.notFound("SERIES_NOT_FOUND", "VL-030"));
        require(caller.grants().allowsInRegion(VehicleTransactions.SERVICE, s.region()));
        if (!s.contains(number)) {
            throw DomainException.validation("NUMBER_OUT_OF_RANGE", "the number is not in the series",
                    List.of("VL-033"));
        }
        if (s.premium(number)) {
            throw DomainException.conflict("NUMBER_PREMIUM", "premium numbers are sold by auction only",
                    List.of("VL-033", "VL-034"));
        }
        if (records.livePlate(s.code(), number).isPresent()) {
            throw DomainException.conflict("NUMBER_TAKEN", "the number is not available", List.of("VL-033"));
        }
        String holder = VehicleTransactions.checkedRef("holderFileNumber", holderFile);
        Instant now = clock.instant();
        LocalDate until = desk.rules().addWorkingDays(desk.today(), desk.rules().reservationWorkingDays());
        Plates.Plate p = new Plates.Plate(UUID.randomUUID(), s.code(), number, s.display(number),
                Plates.Status.AVAILABLE, null, null, now).moveTo(Plates.Status.RESERVED, null, holder, now, until);
        repo.insertPlate(p);
        return desk.open(new RequestDesk.Spec(Registration.Kind.PLATE_RESERVATION, null, s.region(), holder,
                customerCategory, s.plateCategory(), p.id(), null, Map.of("plate", p.display(), "reservedUntil",
                until.toString()), p.id().toString()), channel, caller);
    }

    @Transactional
    public Registration.Request renewRetention(UUID plateId, String customerCategory, Caller caller) {
        String channel = VehicleService.channelOf(caller);
        Plates.Plate p = locked(plateId);
        Plates.Series s = seriesOf(p);
        require(caller.grants().allowsInRegion(VehicleTransactions.SERVICE, s.region()));
        if (p.status() != Plates.Status.RETAINED || p.vehicleId() != null) {
            throw DomainException.conflict("PLATE_NOT_RETAINED", "only a number held without a vehicle is renewed",
                    List.of("VL-039"), p.status().name());
        }
        requireNoOpen(p);
        return desk.open(new RequestDesk.Spec(Registration.Kind.PLATE_RETENTION, null, s.region(),
                p.holderFileNumber(), customerCategory, s.plateCategory(), p.id(), null,
                Map.of("plate", p.display()), p.id().toString()), channel, caller);
    }

    // ---- sale and exchange (VL-035)

    /** The holder of a retained number sells it to another person; the buyer pays the transfer fee. */
    @Transactional
    public Registration.Request sell(UUID plateId, String buyerFile, String customerCategory, Caller caller) {
        String channel = VehicleService.channelOf(caller);
        Plates.Plate p = locked(plateId);
        Plates.Series s = seriesOf(p);
        require(caller.grants().allowsInRegion(VehicleTransactions.SERVICE, s.region()));
        if (p.status() != Plates.Status.RETAINED || p.vehicleId() != null) {
            throw DomainException.conflict("PLATE_NOT_RETAINED", "only a number held without a vehicle is sold",
                    List.of("VL-035"), p.status().name());
        }
        String buyer = VehicleTransactions.checkedRef("buyerFileNumber", buyerFile);
        if (buyer.equals(p.holderFileNumber())) {
            throw DomainException.validation("BUYER_IS_OWNER", "the buyer already holds the number",
                    List.of("VL-035"));
        }
        requireNoOpen(p);
        desk.requireNoHolds(desk.rules().fee(Registration.Kind.PLATE_SALE), List.of(
                new Ports.Eligibility.Subject("TRAFFIC_FILE", p.holderFileNumber())), channel);
        return desk.open(new RequestDesk.Spec(Registration.Kind.PLATE_SALE, null, s.region(), buyer,
                customerCategory, s.plateCategory(), p.id(), null, Map.of("plate", p.display(), "seller",
                p.holderFileNumber(), "buyer", buyer), p.id().toString()), channel, caller);
    }

    /** Puts the owner's retained number on another of their vehicles; the vehicle's old number is retained. */
    @Transactional
    public Registration.Request exchange(String vin, UUID plateId, String customerCategory, Caller caller) {
        String channel = VehicleService.channelOf(caller);
        Vehicle v = lockedVehicle(vin);
        require(caller.grants().allowsInRegion(VehicleTransactions.SERVICE, v.region()));
        if (v.status() != Vehicle.Status.REGISTERED) {
            throw DomainException.conflict("NOT_REGISTERED", "the vehicle is " + v.status(), List.of("VL-035"),
                    v.status().name());
        }
        desk.requireNotBanned(v);
        desk.requireNoOpenRequest(v);
        Plates.Plate p = locked(plateId);
        if (p.status() != Plates.Status.RETAINED || p.vehicleId() != null
                || !v.ownerFileNumber().equals(p.holderFileNumber())) {
            throw DomainException.conflict("PLATE_NOT_OWNERS", "the number must be held by the vehicle's owner"
                    + " without a vehicle", List.of("VL-035"));
        }
        Plates.Series s = seriesOf(p);
        if (!s.plateCategory().equals(desk.vehicleRules().plateCategoryOf(v.spec().category()))) {
            throw DomainException.conflict("PLATE_CATEGORY_MISMATCH", "the number's plate category does not fit the"
                    + " vehicle", List.of("VL-035", "VL-030"), s.plateCategory());
        }
        return desk.open(new RequestDesk.Spec(Registration.Kind.PLATE_EXCHANGE, v, v.region(), v.ownerFileNumber(),
                customerCategory, v.spec().category(), p.id(), null, Map.of("plate", p.display()),
                p.id().toString()), channel, caller);
    }

    // ---- lost and stolen plates (VL-037)

    /**
     * Reports the plate lost or stolen: the police circular event goes out now; the replacement (same or new number)
     * is made once its fee is paid.
     */
    @Transactional
    public Registration.Request reportLoss(UUID plateId, String kind, String policeRef, String replacement,
                                          String customerCategory, Caller caller) {
        String channel = VehicleService.channelOf(caller);
        Plates.Plate p = locked(plateId);
        Plates.Series s = seriesOf(p);
        require(caller.grants().allowsInRegion(VehicleTransactions.SERVICE, s.region()));
        if (p.status() != Plates.Status.ASSIGNED && p.status() != Plates.Status.RETAINED) {
            throw DomainException.conflict("PLATE_STATUS_INVALID", "only a plate in use or held can be reported",
                    List.of("VL-037"), p.status().name());
        }
        if ("NEW".equals(replacement) && p.vehicleId() == null) {
            throw DomainException.validation("NEW_NUMBER_NEEDS_VEHICLE", "a new number is issued for a vehicle",
                    List.of("VL-037"));
        }
        requireNoOpen(p);
        Vehicle v = p.vehicleId() == null ? null : repo.findForUpdate(p.vehicleId()).orElse(null);
        if (v != null) {
            desk.requireNoOpenRequest(v);
        }
        Instant now = clock.instant();
        Plates.LossReport report = new Plates.LossReport(UUID.randomUUID(), p.id(), kind, policeRef, replacement,
                "AWAITING_PAYMENT", null, caller.actor().id(), now);
        records.insertLoss(report);
        Map<String, Object> circular = new LinkedHashMap<>();
        circular.put("plate", p.display());
        circular.put("serialNo", p.serialNo());
        circular.put("kind", kind);
        circular.put("policeReference", policeRef);
        circular.put("reportId", report.id().toString());
        audit.record("PLATE_REPORTED_" + kind, "Plate", p.id().toString(), null, circular, null);
        events.record("itms.vehicle.plate-reported-lost", p.display(), circular);
        if (v != null) {
            repo.history(v.id(), "PLATE_REPORTED_" + kind, caller.actor().id(), circular, now);
        }
        return desk.open(new RequestDesk.Spec(Registration.Kind.PLATE_REPLACEMENT, v, s.region(),
                p.holderFileNumber(), customerCategory, s.plateCategory(), p.id(), null,
                Map.of("plate", p.display(), "replacement", replacement, "reportId", report.id().toString()),
                p.id().toString()), channel, caller);
    }

    // ---- premium number auctions (VL-034)

    public record LotRequest(String seriesCode, long number, long reserveMinor) {
    }

    @Transactional
    public Plates.Auction createAuction(String title, Plates.AuctionChannel salesChannel, String partnerCode,
                                        Instant startsAt, Instant endsAt, String region, List<LotRequest> lots,
                                        Caller caller) {
        VehicleService.channelOf(caller);
        require(caller.grants().allowsInRegion(AUCTION, region));
        if (lots == null || lots.isEmpty() || lots.size() > BATCH) {
            throw DomainException.validation("LOTS_INVALID", "an auction has 1 to " + BATCH + " numbers",
                    List.of("VL-034"));
        }
        Instant now = clock.instant();
        Plates.Auction a = new Plates.Auction(UUID.randomUUID(), title, salesChannel, partnerCode, startsAt, endsAt,
                Plates.AuctionStatus.OPEN, region, caller.actor().id(), now, null, null);
        records.insertAuction(a);
        for (LotRequest l : lots) {
            Plates.Series s = repo.findSeries(l.seriesCode()).filter(x -> x.region().equals(region))
                    .orElseThrow(() -> VehicleTransactions.notFound("SERIES_NOT_FOUND", "VL-030"));
            if (!s.contains(l.number()) || !s.premium(l.number())) {
                throw DomainException.validation("NOT_PREMIUM", s.display(l.number())
                        + " is not a premium number of the series", List.of("VL-034"), s.display(l.number()));
            }
            if (records.livePlate(s.code(), l.number()).isPresent()) {
                throw DomainException.conflict("NUMBER_TAKEN", s.display(l.number()) + " is not available",
                        List.of("VL-034"), s.display(l.number()));
            }
            Plates.Plate p = new Plates.Plate(UUID.randomUUID(), s.code(), l.number(), s.display(l.number()),
                    Plates.Status.AVAILABLE, null, null, now).moveTo(Plates.Status.WITHDRAWN, null, null, now);
            repo.insertPlate(p);
            records.insertLot(new Plates.Lot(a.id(), p.id(), l.reserveMinor(), Plates.LotStatus.OPEN, null, null));
        }
        audit.record("PLATE_AUCTION_CREATED", "PlateAuction", a.id().toString(), null,
                Map.of("title", title, "channel", salesChannel.name(), "lots", lots.size()), null);
        events.record("itms.vehicle.plate-auction-opened", a.id().toString(), Map.of("auctionId", a.id().toString(),
                "channel", salesChannel.name(), "startsAt", startsAt.toString(), "endsAt", endsAt.toString()));
        return a;
    }

    public record AuctionView(Plates.Auction auction, List<Plates.Lot> lots, Map<UUID, Long> highestBids) {
    }

    @Transactional
    public AuctionView auction(UUID id, Caller caller) {
        Plates.Auction a = records.auction(id).orElseThrow(() -> VehicleTransactions.notFound("AUCTION_NOT_FOUND",
                "VL-034"));
        require(caller.grants().allowsInRegion(VehicleService.READ, a.region())
                || caller.grants().allowsInRegion(AUCTION, a.region())
                || caller.grants().allowsInRegion(VehicleTransactions.SERVICE, a.region()));
        List<Plates.Lot> lots = records.lots(id);
        Map<UUID, Long> best = new LinkedHashMap<>();
        for (Plates.Lot l : lots) {
            records.highestBid(id, l.plateId()).ifPresent(b -> best.put(l.plateId(), b.amountMinor()));
        }
        return new AuctionView(a, lots, best);
    }

    /**
     * A bid: online (customers or staff for them) while the auction runs, or reported by the auction partner
     * (VL-034). It must beat the reserve price and the highest bid so far.
     */
    @Transactional
    public Plates.Bid bid(UUID auctionId, UUID plateId, String bidderFile, long amountMinor, Caller caller) {
        VehicleService.channelOf(caller);
        Plates.Auction a = records.auction(auctionId).orElseThrow(() -> VehicleTransactions.notFound(
                "AUCTION_NOT_FOUND", "VL-034"));
        require(caller.grants().allowsInRegion(a.salesChannel() == Plates.AuctionChannel.PARTNER ? AUCTION
                : VehicleTransactions.SERVICE, a.region()));
        Instant now = clock.instant();
        if (!a.biddingOpen(now)) {
            throw DomainException.conflict("AUCTION_NOT_RUNNING", "bids are taken between " + a.startsAt() + " and "
                    + a.endsAt(), List.of("VL-034"));
        }
        Plates.Lot lot = records.lots(auctionId).stream().filter(l -> l.plateId().equals(plateId)).findFirst()
                .orElseThrow(() -> VehicleTransactions.notFound("LOT_NOT_FOUND", "VL-034"));
        long floor = Math.max(lot.reserveMinor(), records.highestBid(auctionId, plateId)
                .map(b -> b.amountMinor() + 1).orElse(0L));
        if (amountMinor < floor) {
            throw DomainException.conflict("BID_TOO_LOW", "the bid must be at least " + floor, List.of("VL-034"),
                    floor);
        }
        Plates.Bid b = new Plates.Bid(UUID.randomUUID(), auctionId, plateId, bidderFile, amountMinor,
                a.salesChannel().name(), caller.actor().id(), now);
        records.insertBid(b);
        audit.record("PLATE_BID_PLACED", "PlateAuction", auctionId.toString(), null,
                Map.of("plateId", plateId.toString(), "bidder", bidderFile, "amount", amountMinor), null);
        return b;
    }

    /**
     * Closes the auction after its end: each number goes to its highest bid at or above the reserve, who is asked to
     * pay the price; numbers without such a bid go back to the premium pool.
     */
    @Transactional
    public AuctionView closeAuction(UUID auctionId, Caller caller) {
        String channel = VehicleService.channelOf(caller);
        Plates.Auction a = records.auction(auctionId).orElseThrow(() -> VehicleTransactions.notFound(
                "AUCTION_NOT_FOUND", "VL-034"));
        require(caller.grants().allowsInRegion(AUCTION, a.region()));
        Instant now = clock.instant();
        Plates.Auction closed = a.closed(caller.actor().id(), now);
        records.updateAuction(closed);
        for (Plates.Lot l : records.lots(auctionId)) {
            Optional<Plates.Bid> best = records.highestBid(auctionId, l.plateId());
            Plates.Plate p = repo.findPlate(l.plateId()).orElseThrow();
            if (best.isPresent() && best.get().amountMinor() >= l.reserveMinor()) {
                Plates.Bid b = best.get();
                records.updateLot(new Plates.Lot(auctionId, l.plateId(), l.reserveMinor(), Plates.LotStatus.SOLD,
                        b.bidderFile(), b.amountMinor()));
                desk.openPriced(new RequestDesk.Spec(Registration.Kind.AUCTION, null, a.region(), b.bidderFile(),
                        null, null, p.id(), null, Map.of("plate", p.display(), "auctionId", auctionId.toString()),
                        p.id().toString()), b.amountMinor(), "Plate number " + p.display(), channel, caller);
            } else {
                records.updateLot(new Plates.Lot(auctionId, l.plateId(), l.reserveMinor(), Plates.LotStatus.UNSOLD,
                        null, null));
                repo.updatePlate(p.moveTo(Plates.Status.AVAILABLE, null, null, now));
            }
        }
        audit.record("PLATE_AUCTION_CLOSED", "PlateAuction", auctionId.toString(), Map.of("status", "OPEN"),
                Map.of("status", "CLOSED"), null);
        return auction(auctionId, caller);
    }

    // ---- manufacturing orders (VL-036)

    /** Puts every plate waiting to be made into one order to the factory. */
    @Transactional
    public Plates.Order raiseOrder(String factoryCode, Caller caller) {
        VehicleService.channelOf(caller);
        require(caller.grants().holds(VehicleService.PLATE_MANAGE));
        List<Plates.OrderItem> pending = records.pendingOrderItems(BATCH);
        if (pending.isEmpty()) {
            throw DomainException.conflict("NOTHING_TO_ORDER", "no plates are waiting to be made", List.of("VL-036"));
        }
        Instant now = clock.instant();
        Plates.Order o = new Plates.Order(UUID.randomUUID(), factoryCode, Plates.OrderStatus.ORDERED,
                caller.actor().id(), now, now);
        records.insertOrder(o, pending.stream().map(Plates.OrderItem::id).toList());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Plates.OrderItem i : pending) {
            Plates.Plate p = repo.findPlate(i.plateId()).orElseThrow();
            Plates.Series s = seriesOf(p);
            items.add(Map.of("itemId", i.id().toString(), "plate", p.display(), "design", s.design(), "colour",
                    s.colourCode(), "reason", i.reason()));
        }
        audit.record("PLATE_ORDER_RAISED", "PlateOrder", o.id().toString(), null,
                Map.of("factory", factoryCode, "items", pending.size()), null);
        events.record("itms.vehicle.plate-order-raised", o.id().toString(), Map.of("orderId", o.id().toString(),
                "factory", factoryCode, "items", items));
        return o;
    }

    public record OrderView(Plates.Order order, List<Plates.OrderItem> items) {
    }

    @Transactional
    public OrderView order(UUID id, Caller caller) {
        require(caller.grants().holds(VehicleService.PLATE_MANAGE) || caller.grants().holds(FACTORY));
        Plates.Order o = records.order(id).orElseThrow(() -> VehicleTransactions.notFound("ORDER_NOT_FOUND",
                "VL-036"));
        return new OrderView(o, records.orderItems(id));
    }

    /** The factory reports production and delivery; delivery carries every plate's serial number. */
    @Transactional
    public OrderView factoryUpdate(UUID orderId, Plates.OrderStatus next, Map<UUID, String> serials,
                                   Caller caller) {
        VehicleService.channelOf(caller);
        require(caller.grants().holds(FACTORY));
        Plates.Order o = records.order(orderId).orElseThrow(() -> VehicleTransactions.notFound("ORDER_NOT_FOUND",
                "VL-036"));
        Instant now = clock.instant();
        Plates.Order moved = o.moveTo(next, now);
        List<Plates.OrderItem> items = records.orderItems(orderId);
        if (next == Plates.OrderStatus.DELIVERED) {
            Map<UUID, String> given = serials == null ? Map.of() : serials;
            for (Plates.OrderItem i : items) {
                String serial = given.get(i.id());
                if (serial == null || !serial.matches("^[A-Z0-9-]{1,40}$")) {
                    throw DomainException.validation("SERIAL_REQUIRED", "every delivered plate needs its serial number",
                            List.of("VL-036"), i.id().toString());
                }
                records.itemSerial(i.id(), serial);
                Plates.Plate p = repo.findPlate(i.plateId()).orElseThrow();
                repo.updatePlate(p.withSerial(serial, now));
            }
        }
        records.updateOrder(moved);
        audit.record("PLATE_ORDER_" + next.name(), "PlateOrder", orderId.toString(),
                Map.of("status", o.status().name()), Map.of("status", next.name()), null);
        return new OrderView(moved, records.orderItems(orderId));
    }

    // ---- trial plates (VL-038)

    @Transactional
    public Registration.Request issueTrial(String dealerFile, String region, int days, String customerCategory,
                                          Caller caller) {
        String channel = VehicleService.channelOf(caller);
        require(caller.grants().allowsInRegion(VehicleTransactions.SERVICE, region));
        if (days < 1 || days > desk.rules().trialPlateMaxDays()) {
            throw DomainException.validation("TRIAL_DAYS_INVALID", "a trial plate is issued for 1 to "
                    + desk.rules().trialPlateMaxDays() + " days", List.of("VL-038"));
        }
        String dealer = VehicleTransactions.checkedRef("dealerFileNumber", dealerFile);
        desk.requireNoHolds(desk.rules().fee(Registration.Kind.TRIAL_PLATE), List.of(
                new Ports.Eligibility.Subject("TRAFFIC_FILE", dealer)), channel);
        Plates.Plate p = nextTrialPlate(region, dealer);
        Instant now = clock.instant();
        Plates.Trial t = new Plates.Trial(UUID.randomUUID(), p.id(), dealer, region,
                Plates.TrialStatus.AWAITING_PAYMENT, null, null, days, null, caller.actor().id(), now, now);
        records.insertTrial(t);
        return desk.open(new RequestDesk.Spec(Registration.Kind.TRIAL_PLATE, null, region, dealer, customerCategory,
                desk.rules().trialPlateCategory(), p.id(), null, Map.of("plate", p.display(), "days",
                Integer.toString(days)), t.id().toString()), channel, caller);
    }

    @Transactional
    public Plates.Trial trial(UUID id, Caller caller) {
        Plates.Trial t = records.trial(id).orElseThrow(() -> VehicleTransactions.notFound("TRIAL_PLATE_NOT_FOUND",
                "VL-038"));
        require(caller.grants().allowsInRegion(VehicleService.READ, t.region())
                || caller.grants().allowsInRegion(VehicleTransactions.SERVICE, t.region()));
        return t;
    }

    /** A new plate for the rest of the same period; the old one is returned. */
    @Transactional
    public Plates.Trial exchangeTrial(UUID id, Caller caller) {
        VehicleService.channelOf(caller);
        Plates.Trial t = trial(id, caller);
        require(caller.grants().allowsInRegion(VehicleTransactions.SERVICE, t.region()));
        Instant now = clock.instant();
        records.updateTrial(t.moveTo(Plates.TrialStatus.EXCHANGED, null, null, now));
        Plates.Plate old = repo.findPlate(t.plateId()).orElseThrow();
        repo.updatePlate(old.moveTo(Plates.Status.RETURNED, null, null, now));
        Plates.Plate fresh = nextTrialPlate(t.region(), t.dealerFile());
        fresh = fresh.moveTo(Plates.Status.RETAINED, null, t.dealerFile(), now, t.validTo());
        repo.updatePlate(fresh);
        desk.orderPlate(fresh.id(), "TRIAL");
        Plates.Trial next = new Plates.Trial(UUID.randomUUID(), fresh.id(), t.dealerFile(), t.region(),
                Plates.TrialStatus.ACTIVE, t.validFrom(), t.validTo(), t.days(), t.id(), caller.actor().id(), now,
                now);
        records.insertTrial(next);
        audit.record("TRIAL_PLATE_EXCHANGED", "TrialPlate", t.id().toString(), Map.of("plate", old.display()),
                Map.of("plate", fresh.display(), "trialId", next.id().toString()), null);
        return next;
    }

    @Transactional
    public Plates.Trial cancelTrial(UUID id, Caller caller) {
        VehicleService.channelOf(caller);
        Plates.Trial t = trial(id, caller);
        require(caller.grants().allowsInRegion(VehicleTransactions.SERVICE, t.region()));
        Instant now = clock.instant();
        Plates.Trial cancelled = t.moveTo(Plates.TrialStatus.CANCELLED, null, null, now);
        records.updateTrial(cancelled);
        Plates.Plate p = repo.findPlate(t.plateId()).orElseThrow();
        repo.updatePlate(t.status() == Plates.TrialStatus.ACTIVE ? p.moveTo(Plates.Status.RETURNED, null, null, now)
                : p.moveTo(Plates.Status.CANCELLED, null, null, now));
        audit.record("TRIAL_PLATE_CANCELLED", "TrialPlate", t.id().toString(), Map.of("status", t.status().name()),
                Map.of("status", "CANCELLED"), null);
        return cancelled;
    }

    private Plates.Plate nextTrialPlate(String region, String dealer) {
        for (Plates.Series s : repo.activeSeries(region, desk.rules().trialPlateCategory())) {
            Optional<Long> n = repo.nextFreeNumber(s);
            if (n.isPresent()) {
                Instant now = clock.instant();
                Plates.Plate p = new Plates.Plate(UUID.randomUUID(), s.code(), n.get(), s.display(n.get()),
                        Plates.Status.AVAILABLE, null, null, now).moveTo(Plates.Status.RESERVED, null, dealer, now);
                repo.insertPlate(p);
                return p;
            }
        }
        throw DomainException.conflict("NO_PLATE_AVAILABLE", "no trial plate number is free in this region",
                List.of("VL-038"), region);
    }

    // ---- expiry (VL-033, VL-038, VL-039)

    /**
     * Unpaid reservations past their reservation period are cancelled; retained numbers past their retention period
     * and trial plates past their end return to the pool. Returns how many changed.
     */
    @Transactional
    public int expire() {
        Instant now = clock.instant();
        LocalDate today = desk.today();
        CurrentActor system = CurrentActor.system("vehicle-plate-expiry");
        int n = 0;
        for (Plates.Plate p : records.holdsEndedBefore(Plates.Status.RESERVED, today, BATCH)) {
            if (p.vehicleId() != null) {
                continue;
            }
            records.openRequestFor(p.id().toString()).ifPresent(r -> repo.updateRequest(r.cancelled(now)));
            repo.updatePlate(p.moveTo(Plates.Status.CANCELLED, null, null, now));
            audit.recordAs(system, "PLATE_RESERVATION_EXPIRED", "Plate", p.id().toString(),
                    Map.of("status", "RESERVED"), Map.of("status", "CANCELLED", "plate", p.display()), null);
            n++;
        }
        for (Plates.Trial t : records.trialsEndedBefore(today, BATCH)) {
            records.updateTrial(t.moveTo(Plates.TrialStatus.EXPIRED, null, null, now));
            Plates.Plate p = repo.findPlate(t.plateId()).orElseThrow();
            repo.updatePlate(p.moveTo(Plates.Status.RETURNED, null, null, now));
            audit.recordAs(system, "TRIAL_PLATE_EXPIRED", "TrialPlate", t.id().toString(),
                    Map.of("status", "ACTIVE"), Map.of("status", "EXPIRED"), null);
            n++;
        }
        for (Plates.Plate p : records.holdsEndedBefore(Plates.Status.RETAINED, today, BATCH)) {
            if (p.vehicleId() != null || records.openRequestFor(p.id().toString()).isPresent()) {
                continue;
            }
            repo.updatePlate(p.moveTo(Plates.Status.RETURNED, null, null, now));
            audit.recordAs(system, "PLATE_RETENTION_ENDED", "Plate", p.id().toString(),
                    Map.of("status", "RETAINED", "holder", p.holderFileNumber()),
                    Map.of("status", "RETURNED", "plate", p.display()), null);
            events.recordAs(system, "itms.vehicle.plate-returned", p.display(), Map.of("plate", p.display(),
                    "holder", p.holderFileNumber()));
            n++;
        }
        return n;
    }

    // ---- completion on payment

    @Override
    public boolean handles(Registration.Kind kind) {
        return switch (kind) {
            case PLATE_RESERVATION, PLATE_RETENTION, PLATE_SALE, PLATE_EXCHANGE, PLATE_REPLACEMENT, TRIAL_PLATE,
                 AUCTION -> true;
            default -> false;
        };
    }

    @Override
    public void completed(Registration.Request r, Instant now, CurrentActor system) {
        LocalDate today = LocalDate.ofInstant(now, clock.getZone());
        Plates.Plate p = repo.findPlate(r.plateId()).orElseThrow();
        Map<String, Object> detail = new LinkedHashMap<>(r.payload());
        detail.put("requestId", r.id().toString());
        String event;
        switch (r.kind()) {
            case PLATE_RESERVATION -> {
                if (p.status() != Plates.Status.RESERVED) {
                    return;
                }
                repo.updatePlate(p.moveTo(Plates.Status.RETAINED, null, p.holderFileNumber(), now,
                        desk.retainedUntil(today)));
                event = "plate-reserved";
            }
            case PLATE_RETENTION -> {
                LocalDate from = p.holdUntil() == null || p.holdUntil().isBefore(today) ? today : p.holdUntil();
                repo.updatePlate(p.heldBy(p.holderFileNumber(), desk.retainedUntil(from), now));
                event = "plate-retention-renewed";
            }
            case PLATE_SALE -> {
                repo.updatePlate(p.heldBy(r.value("buyer"), p.holdUntil(), now));
                event = "plate-sold";
            }
            case PLATE_EXCHANGE -> {
                Vehicle v = repo.findForUpdate(r.vehicleId()).orElseThrow();
                if (v.plateId() != null) {
                    Plates.Plate old = repo.findPlate(v.plateId()).orElseThrow();
                    repo.updatePlate(old.moveTo(Plates.Status.RETAINED, null, v.ownerFileNumber(), now,
                            desk.retainedUntil(today)));
                }
                Plates.Plate assigned = p.moveTo(Plates.Status.ASSIGNED, v.id(), v.ownerFileNumber(), now);
                repo.updatePlate(assigned);
                v.plateChanged(assigned.id(), assigned.display());
                repo.updateVehicle(v);
                desk.orderPlate(assigned.id(), "EXCHANGE");
                desk.issueCard(v, Records.Delivery.CENTRE, null);
                repo.history(v.id(), "PLATE_EXCHANGED", system.userId(), detail, now);
                event = "plate-exchanged";
            }
            case PLATE_REPLACEMENT -> {
                event = replaced(r, p, now, system, detail);
            }
            case TRIAL_PLATE -> {
                Plates.Trial t = records.trial(UUID.fromString(r.subjectRef())).orElseThrow();
                LocalDate to = today.plusDays(t.days() - 1L);
                records.updateTrial(t.moveTo(Plates.TrialStatus.ACTIVE, today, to, now));
                repo.updatePlate(p.moveTo(Plates.Status.RETAINED, null, t.dealerFile(), now, null));
                desk.orderPlate(p.id(), "TRIAL");
                event = "trial-plate-issued";
            }
            case AUCTION -> {
                UUID auctionId = UUID.fromString(r.value("auctionId"));
                Plates.Lot lot = records.lots(auctionId).stream().filter(l -> l.plateId().equals(p.id()))
                        .findFirst().orElseThrow();
                records.updateLot(new Plates.Lot(auctionId, p.id(), lot.reserveMinor(), Plates.LotStatus.PAID,
                        lot.winnerFile(), lot.winningMinor()));
                repo.updatePlate(p.moveTo(Plates.Status.RETAINED, null, r.payerFile(), now,
                        desk.retainedUntil(today)));
                event = "plate-auction-won";
            }
            default -> throw new IllegalStateException("not a plate transaction: " + r.kind());
        }
        audit.recordAs(system, "PLATE_" + event.toUpperCase(Locale.ROOT).replace('-', '_'), "Plate",
                p.id().toString(), null, detail, null);
        events.recordAs(system, "itms.vehicle." + event, p.display(), detail);
    }

    private String replaced(Registration.Request r, Plates.Plate p, Instant now, CurrentActor system,
                            Map<String, Object> detail) {
        UUID reportId = UUID.fromString(r.value("reportId"));
        Plates.LossReport report = records.loss(reportId).orElseThrow();
        UUID newPlate = null;
        if ("SAME".equals(report.replacement())) {
            desk.orderPlate(p.id(), "REPLACEMENT");
        } else {
            Vehicle v = repo.findForUpdate(p.vehicleId()).orElseThrow();
            repo.updatePlate(p.moveTo(Plates.Status.LOST, null, p.holderFileNumber(), now));
            Plates.Plate fresh = desk.reserveNextPlate(v).moveTo(Plates.Status.ASSIGNED, v.id(), v.ownerFileNumber(),
                    now);
            repo.updatePlate(fresh);
            v.plateChanged(fresh.id(), fresh.display());
            repo.updateVehicle(v);
            desk.orderPlate(fresh.id(), "REPLACEMENT");
            desk.issueCard(v, Records.Delivery.CENTRE, null);
            newPlate = fresh.id();
            detail.put("newPlate", fresh.display());
            repo.history(v.id(), "PLATE_REPLACED", system.userId(), detail, now);
        }
        records.updateLoss(new Plates.LossReport(report.id(), report.plateId(), report.kind(), report.policeRef(),
                report.replacement(), "REPLACED", newPlate, report.reportedBy(), report.reportedAt()));
        return "plate-replaced";
    }

    // ---- helpers

    private void requireNoOpen(Plates.Plate p) {
        if (records.openRequestFor(p.id().toString()).isPresent()) {
            throw DomainException.conflict("REQUEST_OPEN", "another transaction of this plate waits for payment",
                    List.of("VL-033"));
        }
    }

    private Plates.Series seriesOf(Plates.Plate p) {
        return repo.findSeries(p.seriesCode()).orElseThrow();
    }

    private Plates.Plate locked(UUID id) {
        return repo.findPlate(id).orElseThrow(() -> VehicleTransactions.notFound("PLATE_NOT_FOUND", "VL-031"));
    }

    private Vehicle lockedVehicle(String vin) {
        String key = vin == null ? "" : vin.toUpperCase(Locale.ROOT);
        Vehicle v = repo.findByVin(key).orElseThrow(() -> VehicleTransactions.notFound("VEHICLE_NOT_FOUND",
                "VL-001"));
        return repo.findForUpdate(v.id()).orElseThrow();
    }

    private static void require(boolean allowed) {
        if (!allowed) {
            throw new DomainException(DomainException.Kind.OUT_OF_SCOPE, "SCOPE_EXCEEDED",
                    "you may not do this with this plate", List.of("CC-001"));
        }
    }
}
