package gov.itms.transaction.application;

import java.util.Optional;

/**
 * Port to the signed-in customer's traffic file (ADR-067 d): traffic-file-service finds it by the national id in the
 * customer's own token. Empty when the customer has no file; throws a {@code DomainException} of kind
 * {@code UNAVAILABLE} when it cannot answer.
 */
public interface OwnFile {

    Optional<String> fileNumber();
}
