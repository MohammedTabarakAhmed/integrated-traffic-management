package gov.itms.platform.security;

/** The authorisation source could not be consulted. Requests are refused (fail closed), never allowed. */
public class AuthorisationUnavailableException extends RuntimeException {

    public AuthorisationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
