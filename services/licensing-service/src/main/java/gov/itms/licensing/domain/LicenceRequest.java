package gov.itms.licensing.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A paid licensing transaction waiting for payment-service (DL-050…DL-060). The fee was quoted at fees-service and
 * the payable registered at payment-service when the request was opened; the change it asks for is made once payment
 * is confirmed ({@code itms.payment.confirmed}), and only once. {@code payload} keeps what the change needs.
 */
public record LicenceRequest(UUID id, Kind kind, Status status, UUID licenceId, String trafficFileNumber,
                             String region, String quoteId, UUID payableId, long amountMinor, String currency,
                             Map<String, String> payload, List<UUID> transferIds, String requestedBy,
                             Channel channel, Instant requestedAt, Instant completedAt) {

    public enum Kind { ISSUE, RENEWAL, REPLACEMENT, CHANGE, NOC }

    public enum Status { AWAITING_PAYMENT, COMPLETED }

    public LicenceRequest {
        Objects.requireNonNull(id);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(status);
        Objects.requireNonNull(trafficFileNumber);
        Objects.requireNonNull(region);
        payload = payload == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        transferIds = transferIds == null ? List.of() : List.copyOf(transferIds);
        Objects.requireNonNull(requestedAt);
    }

    public LicenceRequest completed(UUID licence, Instant now) {
        if (status != Status.AWAITING_PAYMENT) {
            throw DomainException.conflict("REQUEST_CLOSED", "request " + id + " is " + status, List.of("DL-050"),
                    status.name());
        }
        return new LicenceRequest(id, kind, Status.COMPLETED, licence, trafficFileNumber, region, quoteId, payableId,
                amountMinor, currency, payload, transferIds, requestedBy, channel, requestedAt, now);
    }

    public String value(String key) {
        return payload.get(key);
    }

    /** The payable's item reference at payment-service. */
    public String itemRef() {
        return itemRef(id);
    }

    public static String itemRef(UUID id) {
        return "LR-" + id.toString().toUpperCase(java.util.Locale.ROOT);
    }

    /** The request id of a payable's item reference, or null when it is not a licensing item. */
    public static UUID idOf(String itemRef) {
        if (itemRef == null || !itemRef.startsWith("LR-")) {
            return null;
        }
        try {
            return UUID.fromString(itemRef.substring(3));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
