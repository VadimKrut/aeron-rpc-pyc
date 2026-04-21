package ru.pathcreator.pyc.rpc.core.exceptions;

/**
 * Исключение, которое server-side application code может выбросить, чтобы
 * вернуть caller-у structured remote error.
 *
 * <p>Exception thrown by server-side application code to return a structured
 * remote error to the caller.</p>
 *
 * <p>Для общих system-like значений используй {@link RpcStatus}. Для
 * бизнес-ошибок сервиса можно использовать custom code {@code >= 1000}.</p>
 *
 * <p>Use {@link RpcStatus} for shared system-like semantics, or provide a
 * custom application code {@code >= 1000} when the service contract defines
 * its own business error space.</p>
 */
public final class RpcApplicationException extends RpcException {
    /**
     * status code, который нужно пропагировать remote-side / status code to propagate remotely.
     */
    private final int statusCode;

    /**
     * Создаёт application exception с predefined status.
     *
     * <p>Creates an application exception with a predefined status.</p>
     *
     * @param status  transport/application status
     * @param message human-readable error message
     */
    public RpcApplicationException(final RpcStatus status, final String message) {
        super(message);
        this.statusCode = requireKnownStatus(status).code();
    }

    /**
     * Создаёт application exception с predefined status и cause.
     *
     * <p>Creates an application exception with a predefined status and cause.</p>
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
     * Создаёт application exception с custom application code.
     *
     * <p>Creates an application exception with a custom application-defined code.</p>
     *
     * @param statusCode custom status code, must be {@code >= 1000}
     * @param message    human-readable error message
     */
    public RpcApplicationException(final int statusCode, final String message) {
        super(message);
        this.statusCode = validateCustomCode(statusCode);
    }

    /**
     * Создаёт application exception с custom application code и cause.
     *
     * <p>Creates an application exception with a custom application-defined code
     * and cause.</p>
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
     * Возвращает числовой status code, который должен уйти remote-side.
     *
     * <p>Returns the numeric status code that should be propagated remotely.</p>
     *
     * @return numeric remote status code
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