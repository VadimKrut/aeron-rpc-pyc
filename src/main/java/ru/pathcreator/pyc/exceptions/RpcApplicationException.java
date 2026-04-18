package ru.pathcreator.pyc.exceptions;

/**
 * Exception thrown by server-side application code to return a structured
 * remote error to the caller.
 *
 * <p>Use {@link RpcStatus} for shared system-like semantics, or provide a
 * custom application code {@code >= 1000} when the service contract defines
 * its own business error space.</p>
 */
public final class RpcApplicationException extends RpcException {
    private final int statusCode;

    /**
     * Creates an application exception with a predefined status.
     *
     * @param status  transport/application status
     * @param message human-readable error message
     */
    public RpcApplicationException(final RpcStatus status, final String message) {
        super(message);
        this.statusCode = requireKnownStatus(status).code();
    }

    /**
     * Creates an application exception with a predefined status and cause.
     *
     * @param status  transport/application status
     * @param message human-readable error message
     * @param cause   original cause
     */
    public RpcApplicationException(final RpcStatus status, final String message, final Throwable cause) {
        super(message, cause);
        this.statusCode = requireKnownStatus(status).code();
    }

    /**
     * Creates an application exception with a custom application-defined code.
     *
     * @param statusCode custom status code, must be {@code >= 1000}
     * @param message    human-readable error message
     */
    public RpcApplicationException(final int statusCode, final String message) {
        super(message);
        this.statusCode = validateCustomCode(statusCode);
    }

    /**
     * Creates an application exception with a custom application-defined code
     * and cause.
     *
     * @param statusCode custom status code, must be {@code >= 1000}
     * @param message    human-readable error message
     * @param cause      original cause
     */
    public RpcApplicationException(final int statusCode, final String message, final Throwable cause) {
        super(message, cause);
        this.statusCode = validateCustomCode(statusCode);
    }

    /**
     * Returns the numeric status code that should be propagated remotely.
     *
     * @return numeric status code
     */
    public int statusCode() {
        return statusCode;
    }

    private static RpcStatus requireKnownStatus(final RpcStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        return status;
    }

    private static int validateCustomCode(final int statusCode) {
        if (statusCode < 1000) {
            throw new IllegalArgumentException("custom application statusCode must be >= 1000");
        }
        return statusCode;
    }
}