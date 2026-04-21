package ru.pathcreator.pyc.rpc.core.exceptions;

/**
 * Исключение, которое наблюдает caller, когда другая сторона вернула
 * structured remote error.
 *
 * <p>Exception observed by the caller when the remote side returned a
 * structured error response.</p>
 */
public final class RemoteRpcException extends RpcException {
    /**
     * remote status code, полученный от peer / remote status code returned by the peer.
     */
    private final int statusCode;

    /**
     * Создаёт remote-исключение с кодом статуса и текстом ошибки.
     *
     * <p>Creates a remote exception with the provided status code and message.</p>
     *
     * @param statusCode код статуса remote-side / remote status code
     * @param message    remote сообщение об ошибке / remote error message
     */
    public RemoteRpcException(final int statusCode, final String message) {
        super("Remote RPC failed [" + statusCode + "]: " + message);
        this.statusCode = statusCode;
    }

    /**
     * Возвращает числовой remote status code.
     *
     * <p>Returns the remote status code.</p>
     *
     * @return remote status code
     */
    public int statusCode() {
        return statusCode;
    }

    /**
     * Возвращает известный built-in статус для этого кода, если он есть.
     *
     * <p>Returns the known transport status for this code when available.</p>
     *
     * @return enum значение или {@code null} для custom application codes /
     * enum value or {@code null} for custom application codes
     */
    public RpcStatus status() {
        return RpcStatus.fromCode(statusCode);
    }

    /**
     * Возвращает, считается ли remote status retryable.
     *
     * <p>Returns whether the remote status is known to be retryable.</p>
     *
     * @return {@code true} when the status is retryable
     */
    public boolean isRetryable() {
        final RpcStatus status = status();
        return status != null && status.isRetryable();
    }

    /**
     * Возвращает, относится ли remote status к built-in client-error диапазону.
     *
     * <p>Returns whether the remote status belongs to the built-in client-error range.</p>
     *
     * @return {@code true} when the status is a client error
     */
    public boolean isClientError() {
        final RpcStatus status = status();
        return status != null && status.isClientError();
    }

    /**
     * Возвращает, относится ли remote status к built-in server-error диапазону.
     *
     * <p>Returns whether the remote status belongs to the built-in server-error range.</p>
     *
     * @return {@code true} when the status is a server error
     */
    public boolean isServerError() {
        final RpcStatus status = status();
        return status != null && status.isServerError();
    }
}