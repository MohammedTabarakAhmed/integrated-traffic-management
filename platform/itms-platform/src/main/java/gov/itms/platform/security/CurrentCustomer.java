package gov.itms.platform.security;

/**
 * The signed-in customer of a {@code /v1/me/...} request (ADR-067 d). Taken from the token only, never from a request
 * parameter, so a customer can reach no one else's data.
 *
 * @param subjectId  identity-provider subject ({@code sub})
 * @param nationalId national identity number from the {@code national_id} claim
 */
public record CurrentCustomer(String subjectId, String nationalId) {

    @Override
    public String toString() {
        // Personal data stays out of logs (SEC rules): only the subject is printed.
        return "CurrentCustomer[subjectId=" + subjectId + "]";
    }
}
