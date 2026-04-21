package ru.pathcreator.pyc.rpc.core.exceptions;

/**
 * Исключение, которое сигнализирует о несовместимости peers на этапе
 * optional protocol handshake.
 *
 * <p>Exception raised when an optional protocol handshake detects
 * incompatible peers.</p>
 */
public final class ProtocolMismatchException extends RpcException {

    /**
     * Создаёт исключение с описанием несовместимости.
     *
     * <p>Creates an exception with a mismatch description.</p>
     *
     * @param message описание несовместимости / mismatch description
     */
    public ProtocolMismatchException(final String message) {
        super(message);
    }
}