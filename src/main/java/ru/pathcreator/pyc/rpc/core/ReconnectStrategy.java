package ru.pathcreator.pyc.rpc.core;

/**
 * Strategy used when a call starts while the channel is temporarily disconnected.
 */
public enum ReconnectStrategy {
    /**
     * Fail immediately if the channel is not connected.
     */
    FAIL_FAST,

    /**
     * Wait until the existing Aeron publication and heartbeat become connected again.
     */
    WAIT_FOR_CONNECTION
}