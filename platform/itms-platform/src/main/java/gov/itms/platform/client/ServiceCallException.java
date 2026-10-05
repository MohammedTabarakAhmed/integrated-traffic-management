package gov.itms.platform.client;

/**
 * A call to another ITMS service (or to the identity provider for its token) failed or gave no usable answer. The
 * calling use case decides what that means; business actions that depend on the answer are refused, never allowed.
 */
public class ServiceCallException extends RuntimeException {

    public ServiceCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
