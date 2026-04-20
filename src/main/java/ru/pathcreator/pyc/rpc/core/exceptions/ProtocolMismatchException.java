package ru.pathcreator.pyc.rpc.core.exceptions;

/**
 * Exception raised when an optional protocol handshake detects incompatible peers.
 */
public final class ProtocolMismatchException extends RpcException {
    public ProtocolMismatchException(final String message) {
        super(message);
    }
}