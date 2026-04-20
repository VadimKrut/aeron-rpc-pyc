package ru.pathcreator.pyc.rpc.metrics;

/**
 * Immutable snapshot of listener-based RPC metrics.
 *
 * @param callsStarted               number of call-start events observed
 * @param callsSucceeded             number of successful call completions
 * @param callsTimedOut              number of timeout completions
 * @param callsFailed                number of local call failures
 * @param remoteErrors               number of structured remote-error events
 * @param channelUps                 number of channel-up transitions
 * @param channelDowns               number of channel-down transitions
 * @param drainStarts                number of drain-start events
 * @param drainFinishes              number of drain-finished events
 * @param reconnectAttempts          number of reconnect attempts
 * @param reconnectSuccesses         number of reconnect successes
 * @param reconnectFailures          number of reconnect failures
 * @param protocolHandshakeStarts    number of handshake-start events
 * @param protocolHandshakeSuccesses number of successful handshakes
 * @param protocolHandshakeFailures  number of failed handshakes
 */
public record RpcMetricsSnapshot(
        long callsStarted,
        long callsSucceeded,
        long callsTimedOut,
        long callsFailed,
        long remoteErrors,
        long channelUps,
        long channelDowns,
        long drainStarts,
        long drainFinishes,
        long reconnectAttempts,
        long reconnectSuccesses,
        long reconnectFailures,
        long protocolHandshakeStarts,
        long protocolHandshakeSuccesses,
        long protocolHandshakeFailures
) {
    /**
     * Returns the total number of terminal local call outcomes represented by
     * this snapshot.
     *
     * @return completed local call count
     */
    public long completedCalls() {
        return callsSucceeded + callsTimedOut + callsFailed;
    }

    /**
     * Returns whether all counters in this snapshot are zero.
     *
     * @return {@code true} when the snapshot is empty
     */
    public boolean isEmpty() {
        return callsStarted == 0L &&
               callsSucceeded == 0L &&
               callsTimedOut == 0L &&
               callsFailed == 0L &&
               remoteErrors == 0L &&
               channelUps == 0L &&
               channelDowns == 0L &&
               drainStarts == 0L &&
               drainFinishes == 0L &&
               reconnectAttempts == 0L &&
               reconnectSuccesses == 0L &&
               reconnectFailures == 0L &&
               protocolHandshakeStarts == 0L &&
               protocolHandshakeSuccesses == 0L &&
               protocolHandshakeFailures == 0L;
    }

    /**
     * Returns a delta snapshot produced by subtracting the provided baseline
     * from this snapshot.
     *
     * @param baseline earlier snapshot
     * @return delta snapshot
     */
    public RpcMetricsSnapshot deltaSince(final RpcMetricsSnapshot baseline) {
        return new RpcMetricsSnapshot(
                callsStarted - baseline.callsStarted,
                callsSucceeded - baseline.callsSucceeded,
                callsTimedOut - baseline.callsTimedOut,
                callsFailed - baseline.callsFailed,
                remoteErrors - baseline.remoteErrors,
                channelUps - baseline.channelUps,
                channelDowns - baseline.channelDowns,
                drainStarts - baseline.drainStarts,
                drainFinishes - baseline.drainFinishes,
                reconnectAttempts - baseline.reconnectAttempts,
                reconnectSuccesses - baseline.reconnectSuccesses,
                reconnectFailures - baseline.reconnectFailures,
                protocolHandshakeStarts - baseline.protocolHandshakeStarts,
                protocolHandshakeSuccesses - baseline.protocolHandshakeSuccesses,
                protocolHandshakeFailures - baseline.protocolHandshakeFailures
        );
    }

    /**
     * Renders the snapshot as a readable multi-line report.
     *
     * @return human-readable metrics report
     */
    public String renderTextReport() {
        return """
                rpc-core metrics snapshot
                callsStarted: %d
                callsSucceeded: %d
                callsTimedOut: %d
                callsFailed: %d
                completedCalls: %d
                remoteErrors: %d
                channelUps: %d
                channelDowns: %d
                drainStarts: %d
                drainFinishes: %d
                reconnectAttempts: %d
                reconnectSuccesses: %d
                reconnectFailures: %d
                protocolHandshakeStarts: %d
                protocolHandshakeSuccesses: %d
                protocolHandshakeFailures: %d
                """.formatted(
                callsStarted,
                callsSucceeded,
                callsTimedOut,
                callsFailed,
                completedCalls(),
                remoteErrors,
                channelUps,
                channelDowns,
                drainStarts,
                drainFinishes,
                reconnectAttempts,
                reconnectSuccesses,
                reconnectFailures,
                protocolHandshakeStarts,
                protocolHandshakeSuccesses,
                protocolHandshakeFailures
        );
    }

    /**
     * Renders the snapshot as a compact JSON document.
     *
     * @return JSON metrics report
     */
    public String renderJsonReport() {
        return """
                {"callsStarted":%d,"callsSucceeded":%d,"callsTimedOut":%d,"callsFailed":%d,"completedCalls":%d,"remoteErrors":%d,"channelUps":%d,"channelDowns":%d,"drainStarts":%d,"drainFinishes":%d,"reconnectAttempts":%d,"reconnectSuccesses":%d,"reconnectFailures":%d,"protocolHandshakeStarts":%d,"protocolHandshakeSuccesses":%d,"protocolHandshakeFailures":%d}"""
                .formatted(
                        callsStarted,
                        callsSucceeded,
                        callsTimedOut,
                        callsFailed,
                        completedCalls(),
                        remoteErrors,
                        channelUps,
                        channelDowns,
                        drainStarts,
                        drainFinishes,
                        reconnectAttempts,
                        reconnectSuccesses,
                        reconnectFailures,
                        protocolHandshakeStarts,
                        protocolHandshakeSuccesses,
                        protocolHandshakeFailures
                );
    }
}