package gov.itms.trafficfile.infrastructure;

import gov.itms.trafficfile.application.CommercialRegister;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * ICD-04 lookup {@code GET /v1/licences/{licenceNo}} against the economic licensing authority of the issuing region
 * (partner #9), or its mock (Gate C). Each licence source (an ITMS reference-data code) has its own authority address
 * in configuration. 404 means unknown; any other failure means unavailable. A source without an address is unknown.
 */
class HttpCommercialRegister implements CommercialRegister {

    private static final Logger log = LoggerFactory.getLogger(HttpCommercialRegister.class);

    private final Map<String, RestClient> authorities;

    HttpCommercialRegister(Map<String, RestClient> authorities) {
        this.authorities = Map.copyOf(authorities);
    }

    /** The authority's answer (ICD-04). */
    record LicenceRecord(String licenceNo, String tradeNameNl, String tradeNameEn, List<String> activityCodes,
                         LocalDate expiryDate, String status) {
    }

    @Override
    public Optional<CompanyLicence> lookup(String source, String licenceNo) {
        RestClient client = authorities.get(source);
        if (client == null) {
            return Optional.empty();
        }
        LicenceRecord record;
        try {
            record = client.get().uri("/v1/licences/{no}", licenceNo).retrieve().body(LicenceRecord.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatusCode.valueOf(404))) {
                return Optional.empty();
            }
            log.warn("Licensing authority {} answered {}", source, e.getStatusCode().value());
            throw new RegisterUnavailableException("licensing authority answered " + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            log.warn("Licensing authority {} did not answer: {}", source, e.getClass().getSimpleName());
            throw new RegisterUnavailableException("licensing authority did not answer", e);
        }
        if (record == null || !licenceNo.equals(record.licenceNo()) || record.expiryDate() == null) {
            throw new RegisterUnavailableException("licensing authority returned another or an incomplete record", null);
        }
        return Optional.of(new CompanyLicence(record.licenceNo(), record.tradeNameNl(), record.tradeNameEn(),
                record.activityCodes() == null ? List.of() : record.activityCodes(), record.expiryDate(),
                "CANCELLED".equals(record.status())));
    }
}
