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
}