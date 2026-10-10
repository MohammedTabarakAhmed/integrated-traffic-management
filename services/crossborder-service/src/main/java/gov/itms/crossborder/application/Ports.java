package gov.itms.crossborder.application;

import gov.itms.crossborder.application.CrossborderService.Message;
import gov.itms.crossborder.application.CrossborderService.Partner;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class Ports {
    private Ports() { }

    public interface ExchangeRepository {
        void savePartner(Partner partner, String actor, Instant at);
        Optional<Partner> partner(String code);
        List<Partner> partners(String mode);
        void saveMapping(String partner, String domain, String local, String external, String actor, Instant at);
        Optional<String> localCode(String partner, String domain, String external);
        Optional<String> externalCode(String partner, String domain, String local);

        void insert(Message message, String actor);
        Optional<Message> message(UUID id);
        Optional<Message> message(String partner, String externalId);
        /** The violation message linked to a local violation number, newest first. */
        Optional<Message> violationByLocalReference(String localReference);
        List<Message> messages(String partner, String type, String status, int limit);
        List<History> history(UUID messageId);
        /** Changes the status and appends the change to the history (XB-007). */
        void mark(UUID id, String fromStatus, String status, Instant at, String detail, String actor);
        void setLocalReference(UUID id, String localReference, Instant at);
        void markPaid(UUID id, String fromStatus, String paidIn, String paymentReference, int commissionBasisPoints,
                      Instant at, String actor);

        void createDelivery(UUID messageId, Instant dueAt);
        /** Outbound messages due for delivery to the partner; with a window, only those that occurred inside it. */
        List<Message> due(String partner, Instant from, Instant to, Instant dueAt, int limit);
        /**
         * Claims one attempt: 1 if this instance may send now. The next try is scheduled by exponential back-off from
         * {@code retryBaseSeconds}, capped at {@code retryMaxSeconds}.
         */
        int claimDelivery(UUID messageId, Instant at, long retryBaseSeconds, long retryMaxSeconds, int maxAttempts);
        /** ACKNOWLEDGED, PENDING (retry later) or FAILED; null if the delivery was not waiting. */
        String acknowledgeDelivery(UUID messageId, boolean accepted, boolean permanent, String response,
                                   String partnerReference, Instant at, int maxAttempts);

        List<Message> unsettled(String partner, Instant from, Instant to);
        void insertStatement(Statement statement, List<StatementLine> lines);
        Optional<Statement> statement(UUID id);
        List<StatementLine> statementLines(UUID id);
        List<Statement> statements(String partner, int limit);

        void insertProposal(Proposal proposal);
        Optional<Proposal> proposalForUpdate(UUID id);
        List<Proposal> openProposals(int limit);
        void decideProposal(UUID id, String status, String actor, Instant at, String reason);
    }

    /** A partner state's exchange endpoint (XB-001, XB-008). */
    public interface PartnerTransport {
        /** accepted: the partner took it; permanent: a refusal that a retry will not change. */
        record Outcome(boolean accepted, boolean permanent, String response, String partnerReference) { }
        Outcome send(Partner partner, Map<String, Object> envelope);
        Outcome sendBatch(Partner partner, List<Map<String, Object>> envelopes);
    }

    public record History(String fromStatus, String toStatus, String detail, String changedBy, Instant changedAt) { }

    public record Statement(UUID id, String partner, Instant from, Instant to, String currency,
                            long collectedHereMinor, long commissionHereMinor, long owedToPartnerMinor,
                            long collectedByPartnerMinor, long commissionPartnerMinor, long owedByPartnerMinor,
                            long netMinor, int lineCount, String generatedBy, Instant generatedAt) { }

    public record StatementLine(UUID messageId, String paidIn, long amountMinor, int commissionBasisPoints,
                                long commissionMinor) { }

    public record Proposal(UUID id, String kind, String subject, Map<String, Object> payload, String status,
                           String proposedBy, Instant proposedAt, String reason, String decidedBy,
                           Instant decidedAt, String decisionReason) { }
}
