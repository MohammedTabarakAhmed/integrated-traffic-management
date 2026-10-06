package gov.itms.trafficfile.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The economic licensing authorities (partner #9, ICD-04; DL-003): the commercial licence of a company, read from the
 * authority of the issuing region. A port: the adapter calls the authority (or its mock, Gate C) and maps its codes.
 */
public interface CommercialRegister {

    /**
     * @param source the ITMS code of the issuing authority (reference list of commercial licence sources)
     * @return the licence, or empty if the authority does not know it
     * @throws RegisterUnavailableException if the authority did not answer in time or failed
     */
    Optional<CompanyLicence> lookup(String source, String licenceNo);

    /** The fields ICD-04 returns. Activity codes are already mapped to ITMS reference-data codes. */
    record CompanyLicence(String licenceNo, String nameNl, String nameEn, List<String> activities,
                          LocalDate expiryDate, boolean cancelled) {
    }

    class RegisterUnavailableException extends RuntimeException {
        public RegisterUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
