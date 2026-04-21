package ru.pathcreator.pyc.rpc.metrics;

/**
 * Immutable snapshot listener-based RPC метрик.
 *
 * <p>Immutable snapshot of listener-based RPC metrics.</p>
 *
 * @param callsStarted               число call-start событий /
 *                                   number of call-start events observed
 * @param callsSucceeded             число успешных завершений /
 *                                   number of successful call completions
 * @param callsTimedOut              число timeout-завершений /
 *                                   number of timeout completions
 * @param callsFailed                число локальных call failures /
 *                                   number of local call failures
 * @param remoteErrors               число structured remote-error событий /
 *                                   number of structured remote-error events
 * @param channelUps                 число channel-up переходов /
 *                                   number of channel-up transitions
 * @param channelDowns               число channel-down переходов /
 *                                   number of channel-down transitions
 * @param drainStarts                число drain-start событий /
 *                                   number of drain-start events
 * @param drainFinishes              число drain-finished событий /
 *                                   number of drain-finished events
 * @param reconnectAttempts          число reconnect attempts /
 *                                   number of reconnect attempts
 * @param reconnectSuccesses         число успешных reconnect /
 *                                   number of reconnect successes
 * @param reconnectFailures          число failed reconnect /
 *                                   number of reconnect failures
 * @param protocolHandshakeStarts    число handshake-start событий /
 *                                   number of handshake-start events
 * @param protocolHandshakeSuccesses число успешных handshake /
 *                                   number of successful handshakes
 * @param protocolHandshakeFailures  число failed handshake /
 *                                   number of failed handshakes
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
     * Возвращает суммарное число terminal local call outcomes.
     *
     * <p>Returns the total number of terminal local call outcomes represented
     * by this snapshot.</p>
     *
     * @return total number of completed local call outcomes
     */
    public long completedCalls() {
        return callsSucceeded + callsTimedOut + callsFailed;
    }

    /**
     * Возвращает, равны ли все счетчики в snapshot нулю.
     *
     * <p>Returns whether all counters in this snapshot are zero.</p>
     *
     * @return {@code true} when all counters are zero
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
     * Возвращает delta-snapshot как разницу между текущим snapshot и baseline.
     *
     * <p>Returns a delta snapshot produced by subtracting the provided baseline
     * from this snapshot.</p>
     *
     * @param baseline более ранний snapshot / earlier snapshot
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
     * Рендерит snapshot как читаемый multi-line report.
     *
     * <p>Renders the snapshot as a readable multi-line report.</p>
     *
     * @return text report
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
                protocolHandshakeFailures);
    }

    /**
     * Рендерит snapshot как compact JSON document.
     *
     * <p>Renders the snapshot as a compact JSON document.</p>
     *
     * @return JSON report
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
                        protocolHandshakeFailures);
    }
}