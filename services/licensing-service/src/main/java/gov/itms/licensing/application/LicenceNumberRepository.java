package gov.itms.licensing.application;

import gov.itms.licensing.domain.LicenceNumberRange;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Licence number ranges and the numbers handed out from them (DL-061). */
public interface LicenceNumberRepository {

    /** A number handed out: its display form, range, region and when. */
    record UsedNumber(String licenceNumber, UUID rangeId, String region, long number, Instant allocatedAt,
                      String allocatedTo) {
    }

    /** Serialises range changes for one prefix, so overlapping ranges are never both accepted. */
    void lockPrefix(String prefix);

    /** Serialises allocation in one region. */
    void lockRegion(String region);

    void insert(LicenceNumberRange range);

    Optional<LicenceNumberRange> find(UUID id);

    Optional<LicenceNumberRange> findForUpdate(UUID id);

    /** Pending and active ranges with this prefix. */
    List<LicenceNumberRange> openWithPrefix(String prefix);

    /** Ranges of a region (every region when null), oldest first. */
    List<LicenceNumberRange> byRegion(String region);

    /** Active ranges of a region in allocation order: earliest approved first, then lowest first number. */
    List<LicenceNumberRange> activeInRegion(String region);

    void saveDecision(LicenceNumberRange range);

    void saveNext(LicenceNumberRange range);

    void insertUsed(UsedNumber used);

    List<UsedNumber> usedInRange(UUID rangeId, int offset, int limit);
}
