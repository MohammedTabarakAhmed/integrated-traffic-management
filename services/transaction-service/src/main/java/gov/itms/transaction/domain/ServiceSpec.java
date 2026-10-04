package gov.itms.transaction.domain;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What a service definition records (CC-020): code, names in both languages, module, eligible customer types,
 * channels, required documents, fee, rule and workflow references, SLA and the regions where it is offered. Rules:
 * <ul>
 *   <li>{@code regions} is either {@code ["*"]} (every region) or a list of region codes (CC-112);</li>
 *   <li>{@code anyRegion} false means customers may use the service only in their file's home region (CC-113);</li>
 *   <li>{@code allowsConcurrentOpen} true lets one subject hold several open transactions of the service (CC-027,
 *       Q-005; default false);</li>
 *   <li>{@code sla} is a positive ISO 8601 duration in days, hours or minutes, kept as written (for example
 *       {@code P5D});</li>
 *   <li>fee, rule and workflow references are codes of services built later; they are not resolved yet (Q-044).</li>
 * </ul>
 */
public record ServiceSpec(String code, BilingualName name, String module, List<String> customerTypes,
                          List<Channel> channels, List<String> requiredDocuments, String feeCode, String ruleSetCode,
                          String workflowCode, String sla, List<String> regions, boolean anyRegion,
                          boolean allowsConcurrentOpen) {

    public static final String SLA_PATTERN = "^P(?!$)(\\d{1,4}D)?(T(?=\\d)(\\d{1,5}H)?(\\d{1,6}M)?)?$";
    private static final Pattern SLA = Pattern.compile(SLA_PATTERN);

    public ServiceSpec {
        code = Codes.serviceCode(code);
        if (name == null) {
            throw DomainException.validation("NAME_REQUIRED", "name is required in both languages", "name");
        }
        module = Codes.code("module", module);
        customerTypes = Codes.codeList("customerTypes", customerTypes, 1, 50);
        channels = Codes.distinct("channels", channels, 1, Channel.values().length);
        requiredDocuments = Codes.codeList("requiredDocuments", requiredDocuments, 0, 50);
        feeCode = Codes.optionalCode("feeCode", feeCode);
        ruleSetCode = Codes.optionalCode("ruleSetCode", ruleSetCode);
        workflowCode = Codes.optionalCode("workflowCode", workflowCode);
        sla = sla(sla);
        regions = regions(regions);
    }

    private static String sla(String value) {
        if (value == null || !SLA.matcher(value).matches() || Duration.parse(value).isZero()) {
            throw DomainException.validation("SLA_INVALID",
                    "the SLA must be a positive duration in days, hours or minutes, for example P5D or PT4H");
        }
        return value;
    }

    private static List<String> regions(List<String> values) {
        List<String> list = Codes.distinct("regions", values, 1, 100);
        if (list.contains(Codes.ALL)) {
            if (list.size() > 1) {
                throw DomainException.validation("REGIONS_ALL_MIXED",
                        "regions is either * (every region) or a list of region codes, not both");
            }
            return list;
        }
        list.forEach(r -> Codes.code("regions", r));
        return list;
    }

    public Duration slaDuration() {
        return Duration.parse(sla);
    }

    /** Offered in {@code region} by this definition (CC-112). */
    public boolean offersRegion(String region) {
        return regions.contains(Codes.ALL) || regions.contains(region);
    }

    public boolean offersChannel(Channel channel) {
        return channels.contains(channel);
    }
}
