package ru.pathcreator.pyc.exceptions;

/**
 * Базовое непроверяемое исключение для ошибок RPC-слоя.
 *
 * <p>От этого класса наследуются специализированные исключения, которые описывают
 * транспортные ошибки, таймауты, превышение лимитов и другие сбои RPC-канала.</p>
 *
 * <p>Base unchecked exception for RPC-layer failures. Specialized exceptions
 * extend this class to describe transport errors, timeouts, limit violations,
 * and other RPC channel failures.</p>
 */
public class RpcException extends RuntimeException {
    /**
     * Создает исключение с текстовым описанием ошибки.
     *
     * <p>Creates an exception with an error message.</p>
     *
     * @param message описание ошибки / error message
     */
    public RpcException(final String message) {
        super(message);
    }

    /**
     * Создает исключение с текстовым описанием ошибки и исходной причиной.
     *
     * <p>Creates an exception with an error message and the original cause.</p>
     *
     * @param message описание ошибки / error message
     * @param cause   исходная причина ошибки / original cause
     */
    public RpcException(final String message, final Throwable cause) {
        super(message, cause);
    }
}