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
}