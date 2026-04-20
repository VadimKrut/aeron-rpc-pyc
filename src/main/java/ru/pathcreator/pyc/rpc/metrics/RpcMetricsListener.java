package ru.pathcreator.pyc.rpc.metrics;

import ru.pathcreator.pyc.rpc.core.ReconnectStrategy;
import ru.pathcreator.pyc.rpc.core.RpcChannel;
import ru.pathcreator.pyc.rpc.core.RpcChannelListener;
import ru.pathcreator.pyc.rpc.core.exceptions.RpcException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.LongAdder;

/**
 * Ready-to-use listener-backed metrics collector for {@code rpc-core}.
 *
 * <p>This class stays outside {@code rpc.core} and builds on top of the
 * optional {@link RpcChannelListener} API. When not configured, it has zero
 * impact on the transport path. When configured, it records simple counters
 * suitable for logging, debugging, or exporting to an external metrics
 * backend.</p>
 */
public final class RpcMetricsListener implements RpcChannelListener {
    private final LongAdder callsStarted = new LongAdder();
    private final LongAdder callsSucceeded = new LongAdder();
    private final LongAdder callsTimedOut = new LongAdder();
    private final LongAdder callsFailed = new LongAdder();
    private final LongAdder remoteErrors = new LongAdder();
    private final LongAdder channelUps = new LongAdder();
    private final LongAdder channelDowns = new LongAdder();
    private final LongAdder drainStarts = new LongAdder();
    private final LongAdder drainFinishes = new LongAdder();
    private final LongAdder reconnectAttempts = new LongAdder();
    private final LongAdder reconnectSuccesses = new LongAdder();
    private final LongAdder reconnectFailures = new LongAdder();
    private final LongAdder protocolHandshakeStarts = new LongAdder();
    private final LongAdder protocolHandshakeSuccesses = new LongAdder();
    private final LongAdder protocolHandshakeFailures = new LongAdder();

    @Override
    public void onCallStarted(final RpcChannel channel, final int requestMessageTypeId, final long correlationId) {
        callsStarted.increment();
    }

    @Override
    public void onCallSucceeded(final RpcChannel channel, final int requestMessageTypeId, final long correlationId) {
        callsSucceeded.increment();
    }

    @Override
    public void onCallTimedOut(
            final RpcChannel channel,
            final int requestMessageTypeId,
            final long correlationId,
            final long timeoutNs
    ) {
        callsTimedOut.increment();
    }

    @Override
    public void onCallFailed(
            final RpcChannel channel,
            final int requestMessageTypeId,
            final long correlationId,
            final RpcException failure
    ) {
        callsFailed.increment();
    }

    @Override
    public void onRemoteError(
            final RpcChannel channel,
            final int responseMessageTypeId,
            final long correlationId,
            final RpcException failure
    ) {
        remoteErrors.increment();
    }

    @Override
    public void onChannelUp(final RpcChannel channel) {
        channelUps.increment();
    }

    @Override
    public void onChannelDown(final RpcChannel channel) {
        channelDowns.increment();
    }

    @Override
    public void onDrainStarted(final RpcChannel channel) {
        drainStarts.increment();
    }

    @Override
    public void onDrainFinished(final RpcChannel channel) {
        drainFinishes.increment();
    }

    @Override
    public void onReconnectAttempt(final RpcChannel channel, final ReconnectStrategy strategy) {
        reconnectAttempts.increment();
    }

    @Override
    public void onReconnectSucceeded(final RpcChannel channel, final ReconnectStrategy strategy) {
        reconnectSuccesses.increment();
    }

    @Override
    public void onReconnectFailed(final RpcChannel channel, final ReconnectStrategy strategy, final Throwable failure) {
        reconnectFailures.increment();
    }

    @Override
    public void onProtocolHandshakeStarted(final RpcChannel channel) {
        protocolHandshakeStarts.increment();
    }

    @Override
    public void onProtocolHandshakeSucceeded(final RpcChannel channel, final int remoteVersion, final long remoteCapabilities) {
        protocolHandshakeSuccesses.increment();
    }

    @Override
    public void onProtocolHandshakeFailed(final RpcChannel channel, final RpcException failure) {
        protocolHandshakeFailures.increment();
    }

    /**
     * Returns a stable copy of the current counters.
     *
     * @return immutable metrics snapshot
     */
    public RpcMetricsSnapshot snapshot() {
        return new RpcMetricsSnapshot(
                callsStarted.sum(),
                callsSucceeded.sum(),
                callsTimedOut.sum(),
                callsFailed.sum(),
                remoteErrors.sum(),
                channelUps.sum(),
                channelDowns.sum(),
                drainStarts.sum(),
                drainFinishes.sum(),
                reconnectAttempts.sum(),
                reconnectSuccesses.sum(),
                reconnectFailures.sum(),
                protocolHandshakeStarts.sum(),
                protocolHandshakeSuccesses.sum(),
                protocolHandshakeFailures.sum()
        );
    }

    /**
     * Resets all counters to zero.
     */
    public void reset() {
        callsStarted.reset();
        callsSucceeded.reset();
        callsTimedOut.reset();
        callsFailed.reset();
        remoteErrors.reset();
        channelUps.reset();
        channelDowns.reset();
        drainStarts.reset();
        drainFinishes.reset();
        reconnectAttempts.reset();
        reconnectSuccesses.reset();
        reconnectFailures.reset();
        protocolHandshakeStarts.reset();
        protocolHandshakeSuccesses.reset();
        protocolHandshakeFailures.reset();
    }

    /**
     * Returns the total number of terminal call outcomes currently recorded by
     * this listener.
     *
     * <p>This is a convenience aggregate over successful, timed-out, and
     * failed local calls. Remote errors are kept as a separate signal because
     * they represent completed remote responses rather than local transport
     * failures.</p>
     *
     * @return total number of completed local call outcomes
     */
    public long completedCalls() {
        return callsSucceeded.sum() + callsTimedOut.sum() + callsFailed.sum();
    }

    /**
     * Returns the current snapshot and resets all counters afterwards.
     *
     * @return snapshot collected before reset
     */
    public RpcMetricsSnapshot snapshotAndReset() {
        final RpcMetricsSnapshot snapshot = snapshot();
        reset();
        return snapshot;
    }

    /**
     * Writes the current text report to the provided file path.
     *
     * @param path destination file path
     * @throws IOException when the report cannot be written
     */
    public void writeTextReport(final Path path) throws IOException {
        write(path, snapshot().renderTextReport());
    }

    /**
     * Writes the current JSON report to the provided file path.
     *
     * @param path destination file path
     * @throws IOException when the report cannot be written
     */
    public void writeJsonReport(final Path path) throws IOException {
        write(path, snapshot().renderJsonReport());
    }

    private static void write(final Path path, final String content) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, content);
    }
}