package gov.itms.vehicle.infrastructure;

import gov.itms.vehicle.application.Ports;
import gov.itms.vehicle.domain.Plates;
import gov.itms.vehicle.domain.Records;
import gov.itms.vehicle.domain.Registration;
import gov.itms.vehicle.domain.Vehicle;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** The vehicle file's other records and the plate trade in PostgreSQL (V3; ADR-059). */
class JdbcRecordsRepository implements Ports.RecordsRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final JdbcVehicleRepository vehicles;

    JdbcRecordsRepository(JdbcClient jdbc, ObjectMapper json, JdbcVehicleRepository vehicles) {
        this.jdbc = jdbc;
        this.json = json;
        this.vehicles = vehicles;
    }

    // ---- vehicles

    @Override
    public List<Vehicle> byOwner(String ownerFile, int limit) {
        return jdbc.sql(JdbcVehicleRepository.VEHICLE_SELECT + " where owner_file_number = :o and status <> 'DEREGISTERED'"
                        + " order by vin limit :n")
                .param("o", ownerFile).param("n", limit).query(JdbcVehicleRepository::mapVehicle).list();
    }

    // ---- customs certificates

    @Override
    public boolean insertCustoms(Records.CustomsCertificate c) {
        return jdbc.sql("""
                        insert into customs_certificate (certificate_no, source, issuer_code, vin, spec, issued_on,
                            received_by, received_at)
                        values (:no, :source, :issuer, :vin, cast(:spec as jsonb), :on, :by, :at)
                        on conflict (certificate_no) do nothing""")
                .param("no", c.certificateNo()).param("source", c.source().name()).param("issuer", c.issuerCode())
                .param("vin", c.vin()).param("spec", json.writeValueAsString(c.spec()))
                .param("on", Date.valueOf(c.issuedOn())).param("by", c.receivedBy())
                .param("at", Timestamp.from(c.receivedAt())).update() == 1;
    }

    @Override
    public Optional<Records.CustomsCertificate> customs(String certificateNo) {
        return jdbc.sql("""
                        select certificate_no, source, issuer_code, vin, spec::text as spec, issued_on, received_by,
                            received_at, used_by
                        from customs_certificate where certificate_no = :no for update""")
                .param("no", certificateNo)
                .query((rs, n) -> new Records.CustomsCertificate(rs.getString("certificate_no"),
                        Records.CustomsSource.valueOf(rs.getString("source")), rs.getString("issuer_code"),
                        rs.getString("vin"), json.readValue(rs.getString("spec"), Vehicle.Spec.class),
                        rs.getDate("issued_on").toLocalDate(), rs.getString("received_by"),
                        rs.getTimestamp("received_at").toInstant(), rs.getObject("used_by", UUID.class)))
                .optional();
    }

    @Override
    public void customsUsed(String certificateNo, UUID vehicleId) {
        jdbc.sql("update customs_certificate set used_by = :v where certificate_no = :no and used_by is null")
                .param("v", vehicleId).param("no", certificateNo).update();
    }

    // ---- bans

    @Override
    public void insertBan(Records.Ban b) {
        jdbc.sql("""
                        insert into vehicle_ban (id, vehicle_id, reason_code, reference, imposed_by, imposed_at)
                        values (:id, :v, :reason, :ref, :by, :at)""")
                .param("id", b.id()).param("v", b.vehicleId()).param("reason", b.reasonCode())
                .param("ref", b.reference()).param("by", b.imposedBy()).param("at", Timestamp.from(b.imposedAt()))
                .update();
    }

    @Override
    public void updateBan(Records.Ban b) {
        jdbc.sql("update vehicle_ban set lifted_by = :by, lifted_at = :at, lift_reason = :r where id = :id")
                .param("by", b.liftedBy()).param("at", ts(b.liftedAt())).param("r", b.liftReason())
                .param("id", b.id()).update();
    }

    private static final String BAN_SELECT = """
            select id, vehicle_id, reason_code, reference, imposed_by, imposed_at, lifted_by, lifted_at, lift_reason
            from vehicle_ban""";

    @Override
    public Optional<Records.Ban> ban(UUID id) {
        return jdbc.sql(BAN_SELECT + " where id = :id for update").param("id", id)
                .query(JdbcRecordsRepository::mapBan).optional();
    }

    @Override
    public List<Records.Ban> bans(UUID vehicleId) {
        return jdbc.sql(BAN_SELECT + " where vehicle_id = :v order by imposed_at").param("v", vehicleId)
                .query(JdbcRecordsRepository::mapBan).list();
    }

    private static Records.Ban mapBan(ResultSet rs, int row) throws SQLException {
        return new Records.Ban(rs.getObject("id", UUID.class), rs.getObject("vehicle_id", UUID.class),
                rs.getString("reason_code"), rs.getString("reference"), rs.getString("imposed_by"),
                rs.getTimestamp("imposed_at").toInstant(), rs.getString("lifted_by"), instant(rs, "lifted_at"),
                rs.getString("lift_reason"));
    }

    // ---- approvals

    @Override
    public void insertApproval(Records.Approval a) {
        jdbc.sql("""
                        insert into vehicle_approval (id, vehicle_id, kind, status, detail, submitted_by, submitted_at)
                        values (:id, :v, :kind, :status, cast(:detail as jsonb), :by, :at)""")
                .param("id", a.id()).param("v", a.vehicleId()).param("kind", a.kind().name())
                .param("status", a.status().name()).param("detail", json.writeValueAsString(a.detail()))
                .param("by", a.submittedBy()).param("at", Timestamp.from(a.submittedAt())).update();
    }

    @Override
    public void updateApproval(Records.Approval a) {
        jdbc.sql("""
                        update vehicle_approval set status = :status, decided_by = :by, decided_at = :at,
                            decision_note = :note
                        where id = :id""")
                .param("status", a.status().name()).param("by", a.decidedBy()).param("at", ts(a.decidedAt()))
                .param("note", a.decisionNote()).param("id", a.id()).update();
    }

    private static final String APPROVAL_SELECT = """
            select id, vehicle_id, kind, status, detail::text as detail, submitted_by, submitted_at, decided_by,
                decided_at, decision_note
            from vehicle_approval""";

    @Override
    public Optional<Records.Approval> approval(UUID id) {
        return jdbc.sql(APPROVAL_SELECT + " where id = :id for update").param("id", id).query(this::mapApproval)
                .optional();
    }

    @Override
    public List<Records.Approval> approvals(UUID vehicleId) {
        return jdbc.sql(APPROVAL_SELECT + " where vehicle_id = :v order by submitted_at").param("v", vehicleId)
                .query(this::mapApproval).list();
    }

    private Records.Approval mapApproval(ResultSet rs, int row) throws SQLException {
        Map<String, String> detail = new LinkedHashMap<>();
        json.readTree(rs.getString("detail")).properties().forEach(e -> detail.put(e.getKey(),
                e.getValue().asString()));
        return new Records.Approval(rs.getObject("id", UUID.class), rs.getObject("vehicle_id", UUID.class),
                Records.ApprovalKind.valueOf(rs.getString("kind")),
                Records.ApprovalStatus.valueOf(rs.getString("status")), detail, rs.getString("submitted_by"),
                rs.getTimestamp("submitted_at").toInstant(), rs.getString("decided_by"), instant(rs, "decided_at"),
                rs.getString("decision_note"));
    }

    // ---- certificates

    @Override
    public String nextCertificateNumber(int year) {
        long n = jdbc.sql("select nextval('vehicle_certificate_seq')").query(Long.class).single();
        return String.format("VC-%d-%09d", year, n);
    }

    @Override
    public void insertCertificate(Records.Certificate c) {
        jdbc.sql("""
                        insert into vehicle_certificate (id, number, vehicle_id, kind, content, issued_by, issued_at)
                        values (:id, :no, :v, :kind, cast(:content as jsonb), :by, :at)""")
                .param("id", c.id()).param("no", c.number()).param("v", c.vehicleId()).param("kind", c.kind().name())
                .param("content", json.writeValueAsString(c.content())).param("by", c.issuedBy())
                .param("at", Timestamp.from(c.issuedAt())).update();
    }

    @Override
    public List<Records.Certificate> certificates(UUID vehicleId) {
        return jdbc.sql("""
                        select id, number, vehicle_id, kind, content::text as content, issued_by, issued_at
                        from vehicle_certificate where vehicle_id = :v order by issued_at""")
                .param("v", vehicleId)
                .query((rs, n) -> new Records.Certificate(rs.getObject("id", UUID.class), rs.getString("number"),
                        rs.getObject("vehicle_id", UUID.class),
                        Records.CertificateKind.valueOf(rs.getString("kind")),
                        JdbcVehicleRepository.map(json.readTree(rs.getString("content"))), rs.getString("issued_by"),
                        rs.getTimestamp("issued_at").toInstant()))
                .list();
    }

    // ---- mortgages

    @Override
    public void insertMortgage(Records.Mortgage m) {
        jdbc.sql("""
                        insert into mortgage (id, vehicle_id, bank_code, contract_ref, debtor_file, status,
                            registered_by, registered_at)
                        values (:id, :v, :bank, :contract, :debtor, :status, :by, :at)""")
                .param("id", m.id()).param("v", m.vehicleId()).param("bank", m.bankCode())
                .param("contract", m.contractRef()).param("debtor", m.debtorFile()).param("status", m.status().name())
                .param("by", m.registeredBy()).param("at", Timestamp.from(m.registeredAt())).update();
    }

    @Override
    public void updateMortgage(Records.Mortgage m) {
        jdbc.sql("""
                        update mortgage set status = :status, debtor_file = :debtor, transfer_consent = :consent,
                            released_by = :by, released_at = :at
                        where id = :id""")
                .param("status", m.status().name()).param("debtor", m.debtorFile())
                .param("consent", m.transferConsent()).param("by", m.releasedBy()).param("at", ts(m.releasedAt()))
                .param("id", m.id()).update();
    }

    private static final String MORTGAGE_SELECT = """
            select id, vehicle_id, bank_code, contract_ref, debtor_file, status, transfer_consent, registered_by,
                registered_at, released_by, released_at
            from mortgage""";

    @Override
    public Optional<Records.Mortgage> mortgage(UUID id) {
        return jdbc.sql(MORTGAGE_SELECT + " where id = :id for update").param("id", id)
                .query(JdbcRecordsRepository::mapMortgage).optional();
    }

    @Override
    public List<Records.Mortgage> activeMortgages(UUID vehicleId) {
        return jdbc.sql(MORTGAGE_SELECT + " where vehicle_id = :v and status = 'ACTIVE' order by registered_at")
                .param("v", vehicleId).query(JdbcRecordsRepository::mapMortgage).list();
    }

    private static Records.Mortgage mapMortgage(ResultSet rs, int row) throws SQLException {
        return new Records.Mortgage(rs.getObject("id", UUID.class), rs.getObject("vehicle_id", UUID.class),
                rs.getString("bank_code"), rs.getString("contract_ref"), rs.getString("debtor_file"),
                Records.MortgageStatus.valueOf(rs.getString("status")), rs.getObject("transfer_consent", UUID.class),
                rs.getString("registered_by"), rs.getTimestamp("registered_at").toInstant(),
                rs.getString("released_by"), instant(rs, "released_at"));
    }

    // ---- cards

    @Override
    public int nextCardSerial(UUID vehicleId) {
        return jdbc.sql("select coalesce(max(serial), 0) + 1 from vehicle_card where vehicle_id = :v")
                .param("v", vehicleId).query(Integer.class).single();
    }

    @Override
    public void insertCard(Records.Card c) {
        jdbc.sql("""
                        insert into vehicle_card (id, vehicle_id, serial, status, delivery, address, issued_at,
                            changed_at, tracking_ref)
                        values (:id, :v, :serial, :status, :delivery, :address, :issued, :changed, :tracking)""")
                .param("id", c.id()).param("v", c.vehicleId()).param("serial", c.serial())
                .param("status", c.status().name()).param("delivery", c.delivery().name())
                .param("address", c.address()).param("issued", Timestamp.from(c.issuedAt()))
                .param("changed", Timestamp.from(c.changedAt())).param("tracking", c.trackingRef()).update();
    }

    @Override
    public void updateCard(Records.Card c) {
        jdbc.sql("update vehicle_card set status = :status, changed_at = :at, tracking_ref = :t where id = :id")
                .param("status", c.status().name()).param("at", Timestamp.from(c.changedAt()))
                .param("t", c.trackingRef()).param("id", c.id()).update();
    }

    private static final String CARD_SELECT = """
            select id, vehicle_id, serial, status, delivery, address, issued_at, changed_at, tracking_ref
            from vehicle_card""";

    @Override
    public Optional<Records.Card> card(UUID id) {
        return jdbc.sql(CARD_SELECT + " where id = :id for update").param("id", id)
                .query(JdbcRecordsRepository::mapCard).optional();
    }

    @Override
    public List<Records.Card> cards(UUID vehicleId) {
        return jdbc.sql(CARD_SELECT + " where vehicle_id = :v order by serial").param("v", vehicleId)
                .query(JdbcRecordsRepository::mapCard).list();
    }

    private static Records.Card mapCard(ResultSet rs, int row) throws SQLException {
        return new Records.Card(rs.getObject("id", UUID.class), rs.getObject("vehicle_id", UUID.class),
                rs.getInt("serial"), Records.CardStatus.valueOf(rs.getString("status")),
                Records.Delivery.valueOf(rs.getString("delivery")), rs.getString("address"),
                rs.getTimestamp("issued_at").toInstant(), rs.getTimestamp("changed_at").toInstant(),
                rs.getString("tracking_ref"));
    }

    // ---- temporary permits

    @Override
    public String nextPermitNumber(int year) {
        long n = jdbc.sql("select nextval('temporary_permit_seq')").query(Long.class).single();
        return String.format("TP-%d-%08d", year, n);
    }

    @Override
    public void insertPermit(Records.TemporaryPermit p) {
        jdbc.sql("""
                        insert into temporary_permit (id, number, kind, status, vehicle_id, vin, foreign_plate,
                            country, holder_file, border_point, valid_from, valid_to, region, created_by, created_at)
                        values (:id, :no, :kind, :status, :v, :vin, :plate, :country, :holder, :border, :from, :to,
                            :region, :by, :at)""")
                .param("id", p.id()).param("no", p.number()).param("kind", p.kind().name())
                .param("status", p.status().name()).param("v", p.vehicleId()).param("vin", p.vin())
                .param("plate", p.foreignPlate()).param("country", p.country()).param("holder", p.holderFile())
                .param("border", p.borderPoint()).param("from", Date.valueOf(p.validFrom()))
                .param("to", Date.valueOf(p.validTo())).param("region", p.region()).param("by", p.createdBy())
                .param("at", Timestamp.from(p.createdAt())).update();
    }

    @Override
    public void updatePermit(Records.TemporaryPermit p) {
        jdbc.sql("update temporary_permit set status = :status, closed_at = :at where id = :id")
                .param("status", p.status().name()).param("at", ts(p.closedAt())).param("id", p.id()).update();
    }

    @Override
    public Optional<Records.TemporaryPermit> permit(UUID id) {
        return jdbc.sql("""
                        select id, number, kind, status, vehicle_id, vin, foreign_plate, country, holder_file,
                            border_point, valid_from, valid_to, region, created_by, created_at, closed_at
                        from temporary_permit where id = :id for update""")
                .param("id", id)
                .query((rs, n) -> new Records.TemporaryPermit(rs.getObject("id", UUID.class), rs.getString("number"),
                        Records.PermitKind.valueOf(rs.getString("kind")),
                        Records.PermitStatus.valueOf(rs.getString("status")), rs.getObject("vehicle_id", UUID.class),
                        rs.getString("vin"), rs.getString("foreign_plate"), rs.getString("country"),
                        rs.getString("holder_file"), rs.getString("border_point"),
                        rs.getDate("valid_from").toLocalDate(), rs.getDate("valid_to").toLocalDate(),
                        rs.getString("region"), rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(),
                        instant(rs, "closed_at")))
                .optional();
    }

    // ---- fleet driver assignments

    @Override
    public void endAssignment(UUID vehicleId, String by, Instant at) {
        jdbc.sql("""
                        update driver_assignment set ended_by = :by, ended_at = :at
                        where vehicle_id = :v and ended_at is null""")
                .param("by", by).param("at", Timestamp.from(at)).param("v", vehicleId).update();
    }

    @Override
    public void insertAssignment(Records.DriverAssignment a) {
        jdbc.sql("""
                        insert into driver_assignment (id, vehicle_id, company_file, driver_file, valid_from, valid_to,
                            assigned_by, assigned_at)
                        values (:id, :v, :company, :driver, :from, :to, :by, :at)""")
                .param("id", a.id()).param("v", a.vehicleId()).param("company", a.companyFile())
                .param("driver", a.driverFile()).param("from", Date.valueOf(a.validFrom()))
                .param("to", a.validTo() == null ? null : Date.valueOf(a.validTo())).param("by", a.assignedBy())
                .param("at", Timestamp.from(a.assignedAt())).update();
    }

    @Override
    public List<Records.DriverAssignment> assignments(String companyFile) {
        return jdbc.sql("""
                        select id, vehicle_id, company_file, driver_file, valid_from, valid_to, assigned_by,
                            assigned_at, ended_by, ended_at
                        from driver_assignment where company_file = :c and ended_at is null order by assigned_at""")
                .param("c", companyFile)
                .query((rs, n) -> new Records.DriverAssignment(rs.getObject("id", UUID.class),
                        rs.getObject("vehicle_id", UUID.class), rs.getString("company_file"),
                        rs.getString("driver_file"), rs.getDate("valid_from").toLocalDate(), date(rs, "valid_to"),
                        rs.getString("assigned_by"), rs.getTimestamp("assigned_at").toInstant(),
                        rs.getString("ended_by"), instant(rs, "ended_at")))
                .list();
    }

    // ---- plates

    @Override
    public Optional<Plates.Plate> livePlate(String seriesCode, long number) {
        return jdbc.sql(JdbcVehicleRepository.PLATE_SELECT + " where series_code = :s and number = :n and status in"
                        + " ('RESERVED', 'ASSIGNED', 'RETAINED', 'WITHDRAWN', 'LOST')")
                .param("s", seriesCode).param("n", number).query(JdbcVehicleRepository::mapPlate).optional();
    }

    @Override
    public List<Plates.Plate> platesHeldBy(String holderFile) {
        return jdbc.sql(JdbcVehicleRepository.PLATE_SELECT + " where holder_file_number = :h and status in"
                        + " ('RESERVED', 'ASSIGNED', 'RETAINED') order by display")
                .param("h", holderFile).query(JdbcVehicleRepository::mapPlate).list();
    }

    @Override
    public List<Plates.Plate> holdsEndedBefore(Plates.Status status, LocalDate day, int limit) {
        return jdbc.sql(JdbcVehicleRepository.PLATE_SELECT + " where status = :s and hold_until < :d"
                        + " order by hold_until limit :n for update skip locked")
                .param("s", status.name()).param("d", Date.valueOf(day)).param("n", limit)
                .query(JdbcVehicleRepository::mapPlate).list();
    }

    @Override
    public void insertAuction(Plates.Auction a) {
        jdbc.sql("""
                        insert into plate_auction (id, title, sales_channel, partner_code, starts_at, ends_at, status,
                            region, created_by, created_at)
                        values (:id, :title, :channel, :partner, :starts, :ends, :status, :region, :by, :at)""")
                .param("id", a.id()).param("title", a.title()).param("channel", a.salesChannel().name())
                .param("partner", a.partnerCode()).param("starts", Timestamp.from(a.startsAt()))
                .param("ends", Timestamp.from(a.endsAt())).param("status", a.status().name())
                .param("region", a.region()).param("by", a.createdBy()).param("at", Timestamp.from(a.createdAt()))
                .update();
    }

    @Override
    public void updateAuction(Plates.Auction a) {
        jdbc.sql("update plate_auction set status = :status, closed_by = :by, closed_at = :at where id = :id")
                .param("status", a.status().name()).param("by", a.closedBy()).param("at", ts(a.closedAt()))
                .param("id", a.id()).update();
    }

    @Override
    public Optional<Plates.Auction> auction(UUID id) {
        return jdbc.sql("""
                        select id, title, sales_channel, partner_code, starts_at, ends_at, status, region, created_by,
                            created_at, closed_by, closed_at
                        from plate_auction where id = :id for update""")
                .param("id", id)
                .query((rs, n) -> new Plates.Auction(rs.getObject("id", UUID.class), rs.getString("title"),
                        Plates.AuctionChannel.valueOf(rs.getString("sales_channel")), rs.getString("partner_code"),
                        rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("ends_at").toInstant(),
                        Plates.AuctionStatus.valueOf(rs.getString("status")), rs.getString("region"),
                        rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(),
                        rs.getString("closed_by"), instant(rs, "closed_at")))
                .optional();
    }

    @Override
    public void insertLot(Plates.Lot l) {
        jdbc.sql("""
                        insert into auction_lot (auction_id, plate_id, reserve_minor, status)
                        values (:a, :p, :reserve, :status)""")
                .param("a", l.auctionId()).param("p", l.plateId()).param("reserve", l.reserveMinor())
                .param("status", l.status().name()).update();
    }

    @Override
    public void updateLot(Plates.Lot l) {
        jdbc.sql("""
                        update auction_lot set status = :status, winner_file = :winner, winning_minor = :amount
                        where auction_id = :a and plate_id = :p""")
                .param("status", l.status().name()).param("winner", l.winnerFile())
                .param("amount", l.winningMinor()).param("a", l.auctionId()).param("p", l.plateId()).update();
    }

    @Override
    public List<Plates.Lot> lots(UUID auctionId) {
        return jdbc.sql("""
                        select auction_id, plate_id, reserve_minor, status, winner_file, winning_minor
                        from auction_lot where auction_id = :a order by plate_id""")
                .param("a", auctionId)
                .query((rs, n) -> new Plates.Lot(rs.getObject("auction_id", UUID.class),
                        rs.getObject("plate_id", UUID.class), rs.getLong("reserve_minor"),
                        Plates.LotStatus.valueOf(rs.getString("status")), rs.getString("winner_file"),
                        rs.getObject("winning_minor", Long.class)))
                .list();
    }

    @Override
    public void insertBid(Plates.Bid b) {
        jdbc.sql("""
                        insert into auction_bid (id, auction_id, plate_id, bidder_file, amount_minor, source, placed_by,
                            placed_at)
                        values (:id, :a, :p, :bidder, :amount, :source, :by, :at)""")
                .param("id", b.id()).param("a", b.auctionId()).param("p", b.plateId())
                .param("bidder", b.bidderFile()).param("amount", b.amountMinor()).param("source", b.source())
                .param("by", b.placedBy()).param("at", Timestamp.from(b.placedAt())).update();
    }

    @Override
    public Optional<Plates.Bid> highestBid(UUID auctionId, UUID plateId) {
        return jdbc.sql("""
                        select id, auction_id, plate_id, bidder_file, amount_minor, source, placed_by, placed_at
                        from auction_bid where auction_id = :a and plate_id = :p
                        order by amount_minor desc, placed_at limit 1""")
                .param("a", auctionId).param("p", plateId)
                .query((rs, n) -> new Plates.Bid(rs.getObject("id", UUID.class),
                        rs.getObject("auction_id", UUID.class), rs.getObject("plate_id", UUID.class),
                        rs.getString("bidder_file"), rs.getLong("amount_minor"), rs.getString("source"),
                        rs.getString("placed_by"), rs.getTimestamp("placed_at").toInstant()))
                .optional();
    }

    @Override
    public void insertOrderItem(Plates.OrderItem i) {
        jdbc.sql("""
                        insert into plate_order_item (id, plate_id, reason, created_at)
                        values (:id, :p, :reason, :at)""")
                .param("id", i.id()).param("p", i.plateId()).param("reason", i.reason())
                .param("at", Timestamp.from(i.createdAt())).update();
    }

    private static final String ITEM_SELECT = """
            select id, plate_id, order_id, reason, serial_no, created_at from plate_order_item""";

    @Override
    public List<Plates.OrderItem> pendingOrderItems(int limit) {
        return jdbc.sql(ITEM_SELECT + " where order_id is null order by created_at limit :n for update skip locked")
                .param("n", limit).query(JdbcRecordsRepository::mapItem).list();
    }

    @Override
    public void insertOrder(Plates.Order o, List<UUID> itemIds) {
        jdbc.sql("""
                        insert into plate_order (id, factory_code, status, raised_by, raised_at, changed_at)
                        values (:id, :factory, :status, :by, :at, :at)""")
                .param("id", o.id()).param("factory", o.factoryCode()).param("status", o.status().name())
                .param("by", o.raisedBy()).param("at", Timestamp.from(o.raisedAt())).update();
        for (UUID item : itemIds) {
            jdbc.sql("update plate_order_item set order_id = :o where id = :i and order_id is null")
                    .param("o", o.id()).param("i", item).update();
        }
    }

    @Override
    public void updateOrder(Plates.Order o) {
        jdbc.sql("update plate_order set status = :status, changed_at = :at where id = :id")
                .param("status", o.status().name()).param("at", Timestamp.from(o.changedAt())).param("id", o.id())
                .update();
    }

    @Override
    public Optional<Plates.Order> order(UUID id) {
        return jdbc.sql("""
                        select id, factory_code, status, raised_by, raised_at, changed_at
                        from plate_order where id = :id for update""")
                .param("id", id)
                .query((rs, n) -> new Plates.Order(rs.getObject("id", UUID.class), rs.getString("factory_code"),
                        Plates.OrderStatus.valueOf(rs.getString("status")), rs.getString("raised_by"),
                        rs.getTimestamp("raised_at").toInstant(), rs.getTimestamp("changed_at").toInstant()))
                .optional();
    }

    @Override
    public List<Plates.OrderItem> orderItems(UUID orderId) {
        return jdbc.sql(ITEM_SELECT + " where order_id = :o order by created_at").param("o", orderId)
                .query(JdbcRecordsRepository::mapItem).list();
    }

    @Override
    public void itemSerial(UUID itemId, String serial) {
        jdbc.sql("update plate_order_item set serial_no = :s where id = :i").param("s", serial).param("i", itemId)
                .update();
    }

    private static Plates.OrderItem mapItem(ResultSet rs, int row) throws SQLException {
        return new Plates.OrderItem(rs.getObject("id", UUID.class), rs.getObject("plate_id", UUID.class),
                rs.getObject("order_id", UUID.class), rs.getString("reason"), rs.getString("serial_no"),
                rs.getTimestamp("created_at").toInstant());
    }

    @Override
    public void insertLoss(Plates.LossReport r) {
        jdbc.sql("""
                        insert into plate_loss_report (id, plate_id, kind, police_ref, replacement, status,
                            reported_by, reported_at)
                        values (:id, :p, :kind, :police, :replacement, :status, :by, :at)""")
                .param("id", r.id()).param("p", r.plateId()).param("kind", r.kind()).param("police", r.policeRef())
                .param("replacement", r.replacement()).param("status", r.status()).param("by", r.reportedBy())
                .param("at", Timestamp.from(r.reportedAt())).update();
    }

    @Override
    public void updateLoss(Plates.LossReport r) {
        jdbc.sql("update plate_loss_report set status = :status, new_plate_id = :p where id = :id")
                .param("status", r.status()).param("p", r.newPlateId()).param("id", r.id()).update();
    }

    @Override
    public Optional<Plates.LossReport> loss(UUID id) {
        return jdbc.sql("""
                        select id, plate_id, kind, police_ref, replacement, status, new_plate_id, reported_by,
                            reported_at
                        from plate_loss_report where id = :id for update""")
                .param("id", id)
                .query((rs, n) -> new Plates.LossReport(rs.getObject("id", UUID.class),
                        rs.getObject("plate_id", UUID.class), rs.getString("kind"), rs.getString("police_ref"),
                        rs.getString("replacement"), rs.getString("status"), rs.getObject("new_plate_id", UUID.class),
                        rs.getString("reported_by"), rs.getTimestamp("reported_at").toInstant()))
                .optional();
    }

    @Override
    public void insertTrial(Plates.Trial t) {
        jdbc.sql("""
                        insert into trial_plate (id, plate_id, dealer_file, region, status, valid_from, valid_to, days,
                            replaces, created_by, created_at, changed_at)
                        values (:id, :p, :dealer, :region, :status, :from, :to, :days, :replaces, :by, :at, :changed)""")
                .param("id", t.id()).param("p", t.plateId()).param("dealer", t.dealerFile())
                .param("region", t.region()).param("status", t.status().name())
                .param("from", t.validFrom() == null ? null : Date.valueOf(t.validFrom()))
                .param("to", t.validTo() == null ? null : Date.valueOf(t.validTo())).param("days", t.days())
                .param("replaces", t.replaces()).param("by", t.createdBy()).param("at", Timestamp.from(t.createdAt()))
                .param("changed", Timestamp.from(t.changedAt())).update();
    }

    @Override
    public void updateTrial(Plates.Trial t) {
        jdbc.sql("""
                        update trial_plate set status = :status, valid_from = :from, valid_to = :to, changed_at = :at
                        where id = :id""")
                .param("status", t.status().name())
                .param("from", t.validFrom() == null ? null : Date.valueOf(t.validFrom()))
                .param("to", t.validTo() == null ? null : Date.valueOf(t.validTo()))
                .param("at", Timestamp.from(t.changedAt())).param("id", t.id()).update();
    }

    private static final String TRIAL_SELECT = """
            select id, plate_id, dealer_file, region, status, valid_from, valid_to, days, replaces, created_by,
                created_at, changed_at
            from trial_plate""";

    @Override
    public Optional<Plates.Trial> trial(UUID id) {
        return jdbc.sql(TRIAL_SELECT + " where id = :id for update").param("id", id)
                .query(JdbcRecordsRepository::mapTrial).optional();
    }

    @Override
    public List<Plates.Trial> trialsEndedBefore(LocalDate day, int limit) {
        return jdbc.sql(TRIAL_SELECT + " where status = 'ACTIVE' and valid_to < :d order by valid_to limit :n"
                        + " for update skip locked")
                .param("d", Date.valueOf(day)).param("n", limit).query(JdbcRecordsRepository::mapTrial).list();
    }

    private static Plates.Trial mapTrial(ResultSet rs, int row) throws SQLException {
        return new Plates.Trial(rs.getObject("id", UUID.class), rs.getObject("plate_id", UUID.class),
                rs.getString("dealer_file"), rs.getString("region"),
                Plates.TrialStatus.valueOf(rs.getString("status")), date(rs, "valid_from"), date(rs, "valid_to"),
                rs.getInt("days"), rs.getObject("replaces", UUID.class), rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("changed_at").toInstant());
    }

    // ---- requests

    @Override
    public Optional<Registration.Request> openRequestFor(String subjectRef) {
        return jdbc.sql(JdbcVehicleRepository.REQUEST_SELECT + " where subject_ref = :s and status in"
                        + " ('AWAITING_PAYMENT', 'AWAITING_CONSENT') limit 1 for update")
                .param("s", subjectRef).query(vehicles::mapRequest).optional();
    }

    // ---- helpers

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static LocalDate date(ResultSet rs, String column) throws SQLException {
        Date d = rs.getDate(column);
        return d == null ? null : d.toLocalDate();
    }
}
