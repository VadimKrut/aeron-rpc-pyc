package ru.pathcreator.pyc.exceptions;

public class RpcException extends RuntimeException {
    public RpcException(final String message) {
        super(message);
    }

    public RpcException(final String message, final Throwable cause) {
        super(message, cause);
    }
}