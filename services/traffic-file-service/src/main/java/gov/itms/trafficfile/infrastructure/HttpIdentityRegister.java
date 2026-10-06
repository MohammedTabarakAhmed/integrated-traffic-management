package gov.itms.trafficfile.infrastructure;

import gov.itms.trafficfile.application.IdentityRegister;
import gov.itms.trafficfile.domain.PersonName;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * ICD-01 lookup {@code GET /v1/persons/{nationalIdNo}} against the identity register, or its mock in local development
 * and tests (Gate C). The register's gender and residency codes are mapped to ITMS codes through configuration until
 * reference data holds the external codes (CC-110, Q-057). 404 means unknown; any other failure means unavailable.
 * Only the ICD fields are read (SEC-017); the identity number is never logged (SEC-014).
 */
class HttpIdentityRegister implements IdentityRegister {

    private static final Logger log = LoggerFactory.getLogger(HttpIdentityRegister.class);

    private final RestClient client;
    private final Map<String, String> genderCodes;
    private final Map<String, String> residencyCodes;

    HttpIdentityRegister(RestClient client, Map<String, String> genderCodes, Map<String, String> residencyCodes) {
        this.client = client;
        this.genderCodes = Map.copyOf(genderCodes);
        this.residencyCodes = Map.copyOf(residencyCodes);
    }

    /** The register's answer (ICD-01). */
    record RegisterRecord(String nationalIdNo, String givenNameNl, String familyNameNl, String givenNameEn,
                          String familyNameEn, String nationalityCode, String residencyStatus,
                          LocalDate residencyExpiry, LocalDate birthDate, String genderCode, String lifeStatus) {
    }

    @Override
    public Optional<RegisterPerson> lookup(String nationalIdNo) {
        RegisterRecord record;
        try {
            record = client.get().uri("/v1/persons/{id}", nationalIdNo).retrieve().body(RegisterRecord.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatusCode.valueOf(404))) {
                return Optional.empty();
            }
            log.warn("Identity register answered {}", e.getStatusCode().value());
            throw new RegisterUnavailableException("identity register answered " + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            log.warn("Identity register did not answer: {}", e.getClass().getSimpleName());
            throw new RegisterUnavailableException("identity register did not answer", e);
        }
        if (record == null || !nationalIdNo.equals(record.nationalIdNo())) {
            throw new RegisterUnavailableException("identity register returned another or no record", null);
        }
        String gender = genderCodes.get(record.genderCode());
        String residency = record.residencyStatus() == null ? null : residencyCodes.get(record.residencyStatus());
        if (gender == null || (record.residencyStatus() != null && residency == null)) {
            throw new RegisterUnavailableException("identity register returned an unmapped code", null);
        }
        return Optional.of(new RegisterPerson(record.nationalIdNo(),
                new PersonName(record.givenNameNl(), record.familyNameNl(), record.givenNameEn(),
                        record.familyNameEn()),
                record.nationalityCode(), record.birthDate(), gender, residency, record.residencyExpiry(),
                "DECEASED".equals(record.lifeStatus())));
    }
}
