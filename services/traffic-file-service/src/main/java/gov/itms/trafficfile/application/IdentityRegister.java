package gov.itms.trafficfile.application;

import gov.itms.trafficfile.domain.PersonName;
import java.time.LocalDate;
import java.util.Optional;

/**
 * The national identity, residency and citizenship register (partner #1, ICD-01; DL-001, DL-002). A port: the adapter
 * calls the register (or its mock, Gate C) and maps its codes.
 */
public interface IdentityRegister {

    /**
     * @return the register's record, or empty if the register does not know the number
     * @throws RegisterUnavailableException if the register did not answer in time or failed
     */
    Optional<RegisterPerson> lookup(String nationalIdNo);

    /** The fields ICD-01 returns. Codes are already mapped to ITMS reference-data codes. */
    record RegisterPerson(String nationalIdNo, PersonName name, String nationality, LocalDate birthDate, String gender,
                          String residencyStatus, LocalDate residencyExpiry, boolean deceased) {
    }

    class RegisterUnavailableException extends RuntimeException {
        public RegisterUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
