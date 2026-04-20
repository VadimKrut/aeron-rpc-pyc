package ru.pathcreator.pyc.rpc.core.exceptions;

/**
 * Exception observed by the caller when the remote side returned a structured
 * error response.
 */
public final class RemoteRpcException extends RpcException {
    private final int statusCode;

    /**
     * Creates a remote exception with the provided status code and message.
     *
     * @param statusCode remote status code
     * @param message    remote error message
     */
    public RemoteRpcException(final int statusCode, final String message) {
        super("Remote RPC failed [" + statusCode + "]: " + message);
        this.statusCode = statusCode;
    }

    /**
     * Returns the remote status code.
     *
     * @return remote status code
     */
    public int statusCode() {
        return statusCode;
    }

    /**
     * Returns the known transport status for this code when available.
     *
     * @return enum value or {@code null} for custom application codes
     */
    public RpcStatus status() {
        return RpcStatus.fromCode(statusCode);
    }

    /**
     * Returns whether the remote status is known to be retryable.
     *
     * @return {@code true} for retryable built-in statuses
     */
    public boolean isRetryable() {
        final RpcStatus status = status();
        return status != null && status.isRetryable();
    }

    /**
     * Returns whether the remote status belongs to the built-in client-error range.
     *
     * @return {@code true} when the remote status is a built-in client error
     */
    public boolean isClientError() {
        final RpcStatus status = status();
        return status != null && status.isClientError();
    }

    /**
     * Returns whether the remote status belongs to the built-in server-error range.
     *
     * @return {@code true} when the remote status is a built-in server error
     */
    public boolean isServerError() {
        final RpcStatus status = status();
        return status != null && status.isServerError();
    }
}