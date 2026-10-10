package gov.itms.licensing.application;

import gov.itms.licensing.domain.ExaminationTransfer;
import gov.itms.licensing.domain.Licence;
import gov.itms.licensing.domain.LicenceRequest;
import gov.itms.licensing.domain.Records;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Licences, examination transfers, licensing requests and the licence history (DL-021…023, DL-036, DL-050). */
public interface LicenceRepository {

    /** One line of a licence's history; never changed or removed. */
    record HistoryEntry(String event, String actorId, Map<String, Object> detail, Instant at) {
        public HistoryEntry {
            detail = detail == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(detail));
        }
    }

    /** Serialises licensing changes of one person. */
    void lockHolder(String trafficFileNumber);

    // examination transfers (DL-036)
    void insertTransfer(ExaminationTransfer transfer);

    Optional<ExaminationTransfer> transfer(UUID id);

    Optional<ExaminationTransfer> transferForUpdate(UUID id);

    Optional<ExaminationTransfer> transferOf(String examinationFileRef, String classCode);

    List<ExaminationTransfer> transfersOf(String trafficFileNumber);

    void updateTransfer(ExaminationTransfer transfer);

    // licences (DL-021…023)
    void insertLicence(Licence licence);

    /** Saves a changed licence; fails with an optimistic-lock error when it changed meanwhile. */
    void updateLicence(Licence licence);

    Optional<Licence> licence(UUID id);

    Optional<Licence> licenceForUpdate(UUID id);

    Optional<Licence> licenceByNumber(String licenceNumber);

    /** Every licence the person ever held, newest first. */
    List<Licence> licencesOf(String trafficFileNumber);

    void history(UUID licenceId, String event, String actorId, Map<String, Object> detail, Instant at);

    List<HistoryEntry> history(UUID licenceId);

    // requests
    void insertRequest(LicenceRequest request);

    Optional<LicenceRequest> request(UUID id);

    Optional<LicenceRequest> requestForUpdate(UUID id);

    void updateRequest(LicenceRequest request);

    /** The person's requests of a kind that still wait for payment. */
    List<LicenceRequest> openRequests(String trafficFileNumber, LicenceRequest.Kind kind);

    /** A licence's requests, of any kind, that still wait for payment. */
    List<LicenceRequest> openRequestsOf(UUID licenceId);

    // cards (DL-052, DL-060)
    int nextCardSerial(UUID licenceId);

    void insertCard(Records.Card card);

    void updateCard(Records.Card card);

    Optional<Records.Card> card(UUID id);

    Optional<Records.Card> cardForUpdate(UUID id);

    /** A licence's cards, newest first. */
    List<Records.Card> cards(UUID licenceId);

    /** Cards in a status, oldest first (for the printer hand-over, DL-060). */
    List<Records.Card> cardsIn(Records.CardStatus status, Records.Delivery delivery, int limit);

    // eye tests (DL-007)
    boolean insertEyeTest(Records.EyeTest test);

    Optional<Records.EyeTest> eyeTest(String centreCode, String reference);

    /** A person's eye tests, newest test date first. */
    List<Records.EyeTest> eyeTests(String trafficFileNumber);

    // automatic renewal (DL-065)
    Optional<Records.AutoRenewal> autoRenewalForUpdate(UUID licenceId);

    Optional<Records.AutoRenewal> autoRenewal(UUID licenceId);

    void saveAutoRenewal(Records.AutoRenewal consent);

    /** Active consents whose licence is active and expires on or before {@code expiringBy}. */
    List<Records.AutoRenewal> autoRenewalsDue(LocalDate expiringBy, int limit);

    // suspensions (DL-058)
    /** False when this suspension was already recorded. */
    boolean insertSuspension(Records.Suspension suspension);

    List<Records.Suspension> suspensions(UUID licenceId);

    /** Suspended licences whose suspension ended before {@code day}. */
    List<UUID> suspensionsEndedBefore(LocalDate day, int limit);

    // no-objection certificates (DL-059)
    String nextCertificateNumber(int year);

    void insertCertificate(Records.Certificate certificate);

    Optional<Records.Certificate> certificate(String number);

    List<Records.Certificate> certificates(UUID licenceId);
}
