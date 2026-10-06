package gov.itms.trafficfile.application;

import gov.itms.trafficfile.domain.TrafficFile;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Traffic files (individuals and companies) and their temporal history (DAT-002). */
public interface TrafficFileRepository {

    /** The next file number from the database sequence, in the configured format (Q-022). */
    String nextFileNumber();

    /**
     * Inserts a new file.
     *
     * @throws DuplicateIdentityException if an active file already holds the same identity document
     */
    void insert(TrafficFile file);

    Optional<TrafficFile> findByNumber(String fileNumber);

    Optional<TrafficFile> findByNumberForUpdate(String fileNumber);

    /** The file holding this national identity number that is not merged away, if any. */
    Optional<TrafficFile> findByNationalId(String nationalIdNo);

    Optional<TrafficFile> findByPassport(String passportNo, String passportCountry);

    /** The company file of this commercial licence that is not merged away, if any (DL-003). */
    Optional<TrafficFile> findByLicence(String source, String licenceNo);

    /** The files with these numbers that exist (any status); missing numbers are left out. */
    List<TrafficFile> findByNumbers(List<String> fileNumbers);

    /** Saves a company's representatives and the new version; the stored version must be {@code version - 1}. */
    void saveRepresentatives(TrafficFile file);

    /** Active individuals' files with this birth date and nationality, except {@code excludeId} (DL-009). */
    List<TrafficFile> findDuplicateCandidates(java.time.LocalDate birthDate, String nationality, UUID excludeId);

    /** Saves a MERGED file (status, surviving file, version); the stored version must be {@code version - 1}. */
    void saveMerge(TrafficFile merged);

    /** Records a merge (append-only). */
    void insertMerge(DuplicateMergeService.Merge merge, UUID mergedFileId, UUID survivingFileId);

        /** Saves the profile and the new version; the stored version must be {@code version - 1}. */
    void saveProfile(TrafficFile file);

    /** Every version of the file, oldest first. */
    List<FileVersion> history(UUID fileId);

    record FileVersion(long version, Instant validFrom, Instant validTo, String changedBy, Map<String, Object> state) {
    }

    class DuplicateIdentityException extends RuntimeException {
        public DuplicateIdentityException(Throwable cause) {
            super("identity document already has a file", cause);
        }
    }
}
