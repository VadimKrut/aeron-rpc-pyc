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
    WAIT_FOR_CONNECTION,

    /**
     * Recreate the local publication/subscription pair when the transport is
     * disconnected, then wait for the recreated path to become connected.
     *
     * <p>This is more expensive than {@link #WAIT_FOR_CONNECTION} and should
     * be treated as an opt-in recovery mode for environments where local
     * channel resources may need to be rebuilt after a disconnect.</p>
     */
    RECREATE_ON_DISCONNECT
}