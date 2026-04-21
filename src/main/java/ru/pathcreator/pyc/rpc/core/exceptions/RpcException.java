package ru.pathcreator.pyc.rpc.core.exceptions;

/**
 * Базовое unchecked-исключение для ошибок RPC-слоя.
 *
 * <p>Base unchecked exception for RPC-layer failures.</p>
 *
 * <p>От этого класса наследуются transport-ошибки, timeout-исключения,
 * structured remote errors, ошибки лимитов и другие сбои, которые могут
 * возникнуть в канале или вокруг него.</p>
 *
 * <p>Transport errors, timeout exceptions, structured remote errors, limit
 * violations, and related channel failures extend this class.</p>
 */
public class RpcException extends RuntimeException {

    /**
     * Создаёт исключение с текстовым описанием ошибки.
     *
     * <p>Creates an exception with an error message.</p>
     *
     * @param message описание ошибки / error message
     */
    public RpcException(final String message) {
        super(message);
    }

    /**
     * Создаёт исключение с текстовым описанием ошибки и исходной причиной.
     *
     * <p>Creates an exception with an error message and an original cause.</p>
     *
     * @param message описание ошибки / error message
     * @param cause   исходная причина / original cause
     */
    public RpcException(final String message, final Throwable cause) {
        super(message, cause);
    }
}