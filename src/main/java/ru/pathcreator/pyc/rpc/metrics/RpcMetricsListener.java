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
 * Готовый listener-backed collector простых метрик для {@code rpc-core}.
 *
 * <p>Ready-to-use listener-backed metrics collector for {@code rpc-core}.</p>
 *
 * <p>Этот класс живёт вне {@code rpc.core} и строится поверх optional
 * {@link RpcChannelListener} API. Когда он не подключён, transport path не
 * платит за него ничего. Когда подключён, он собирает простые счётчики,
 * пригодные для логирования, отладки и дальнейшего экспорта.</p>
 *
 * <p>This class stays outside {@code rpc.core} and builds on top of the
 * optional {@link RpcChannelListener} API. When not configured, it has zero
 * impact on the transport path. When configured, it records simple counters
 * suitable for logging, debugging, or exporting to an external metrics
 * backend.</p>
 */
public final class RpcMetricsListener implements RpcChannelListener {
    /**
     * Создаёт пустой metrics listener с нулевыми счётчиками.
     *
     * <p>Creates an empty metrics listener with all counters set to zero.</p>
     */
    public RpcMetricsListener() {
    }

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
     * Возвращает стабильную snapshot-копию текущих счётчиков.
     *
     * <p>Returns a stable copy of the current counters.</p>
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
     * Сбрасывает все счётчики в ноль.
     *
     * <p>Resets all counters to zero.</p>
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
     * Возвращает суммарное число terminal local call outcome-ов.
     *
     * <p>Returns the total number of terminal call outcomes currently recorded by
     * this listener.</p>
     *
     * <p>Это convenience aggregate над successful, timed-out и failed local
     * calls. Remote errors остаются отдельным сигналом, потому что это уже
     * завершившиеся remote response-ы, а не локальные transport failures.</p>
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
     * Возвращает текущий snapshot и сразу сбрасывает все счётчики.
     *
     * <p>Returns the current snapshot and resets all counters afterwards.</p>
     *
     * @return immutable metrics snapshot captured before reset
     */
    public RpcMetricsSnapshot snapshotAndReset() {
        final RpcMetricsSnapshot snapshot = snapshot();
        reset();
        return snapshot;
    }

    /**
     * Пишет текущий text report в указанный файл.
     *
     * <p>Writes the current text report to the provided file path.</p>
     *
     * @param path путь назначения / destination file path
     * @throws IOException если записать отчёт не удалось / when the report cannot be written
     */
    public void writeTextReport(final Path path) throws IOException {
        write(path, snapshot().renderTextReport());
    }

    /**
     * Пишет текущий JSON report в указанный файл.
     *
     * <p>Writes the current JSON report to the provided file path.</p>
     *
     * @param path путь назначения / destination file path
     * @throws IOException если записать отчёт не удалось / when the report cannot be written
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