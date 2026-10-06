package gov.itms.fees.application;

import gov.itms.fees.domain.FeeVersion;
import gov.itms.fees.domain.Quote;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Fee versions and quotes (CC-040..CC-047). */
public interface FeeRepository {

    /** The next version number of a fee code (1 for a new fee). */
    int nextVersionNo(String feeCode);

    /** Serialises changes to one fee code (submission numbering, approval overlap checks). */
    void lockFee(String feeCode);

    void insert(FeeVersion version);

    Optional<FeeVersion> find(UUID id);

    Optional<FeeVersion> findForUpdate(UUID id);

    /** Every version of a fee, oldest first. */
    List<FeeVersion> byFee(String feeCode);

    /** Approved versions of a service's fees that may be in force on {@code date}. */
    List<FeeVersion> approvedForService(String serviceCode, LocalDate date);

    /** All versions of a service's fees, newest first, optionally of one status. */
    List<FeeVersion> byService(String serviceCode, FeeVersion.Status status);

    /** Saves a decision (status, decision fields) on a pending version. */
    void saveDecision(FeeVersion version);

    /** Saves a shortened end date of an approved version. */
    void saveEnd(FeeVersion version);

    void insertQuote(Quote quote);

    Optional<Quote> findQuote(UUID id);
}
