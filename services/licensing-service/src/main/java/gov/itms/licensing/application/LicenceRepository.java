package gov.itms.licensing.application;

import gov.itms.licensing.domain.ExaminationTransfer;
import gov.itms.licensing.domain.Licence;
import gov.itms.licensing.domain.LicenceRequest;
import java.time.Instant;
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
}
