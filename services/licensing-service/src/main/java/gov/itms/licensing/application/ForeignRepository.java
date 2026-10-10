package gov.itms.licensing.application;

import gov.itms.licensing.domain.Foreign;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Country exchange rules, the foreign licence master file and permits (DL-054…DL-056, DL-063). */
public interface ForeignRepository {

    /** Serialises rule changes of one country. */
    void lockCountry(String countryCode);

    void insertRule(Foreign.CountryRule rule);

    void updateRule(Foreign.CountryRule rule);

    Optional<Foreign.CountryRule> rule(UUID id);

    Optional<Foreign.CountryRule> ruleForUpdate(UUID id);

    /** Rules of a country (every country when null), newest first. */
    List<Foreign.CountryRule> rules(String countryCode);

    Optional<Foreign.CountryRule> approvedRule(String countryCode);

    /** False when a foreign licence with this country and number is already recorded. */
    boolean insertForeign(Foreign.ForeignLicence licence);

    void updateForeign(Foreign.ForeignLicence licence);

    Optional<Foreign.ForeignLicence> foreign(UUID id);

    Optional<Foreign.ForeignLicence> foreignForUpdate(UUID id);

    List<Foreign.ForeignLicence> foreignOf(String trafficFileNumber);

    /** The master file: foreign licences by country and expiry (DL-063). */
    List<Foreign.ForeignLicence> foreignMasterFile(String countryCode, LocalDate expiringBefore, int offset,
                                                   int limit);

    void insertPermit(Foreign.TemporaryPermit permit);

    void updatePermit(Foreign.TemporaryPermit permit);

    Optional<Foreign.TemporaryPermit> permit(UUID id);

    Optional<Foreign.TemporaryPermit> permitForUpdate(UUID id);

    List<Foreign.TemporaryPermit> permitsOf(String trafficFileNumber);

    /** The master file: temporary permits by expiry (DL-063). */
    List<Foreign.TemporaryPermit> permitMasterFile(LocalDate expiringBefore, int offset, int limit);

    String nextIdpNumber(int year);

    void insertIdp(Foreign.InternationalPermit permit);

    void updateIdp(Foreign.InternationalPermit permit);

    Optional<Foreign.InternationalPermit> idpForUpdate(UUID id);

    List<Foreign.InternationalPermit> idpsOf(UUID licenceId);
}
