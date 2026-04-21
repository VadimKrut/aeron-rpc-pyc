package ru.pathcreator.pyc.rpc.core;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Маркерный executor для режима "выполнять handler прямо в RX-thread".
 *
 * <p>Класс не предназначен для обычного submit/use как полноценный executor.
 * {@link RpcChannel} распознает этот marker и переключается в direct
 * in-thread execution path до фактического submit-а задачи.</p>
 *
 * <p>Marker executor for the mode that executes handlers directly in the
 * receive thread. It is not intended to behave like a regular executor.
 * {@link RpcChannel} recognizes this marker and switches into the direct
 * in-thread execution path before submitting anything.</p>
 */
final class DirectExecutorMarker extends AbstractExecutorService {
    /**
     * Выполняет команду синхронно в текущем потоке.
     *
     * <p>Executes the command synchronously in the current thread.</p>
     */
    @Override
    public void execute(final Runnable command) {
        command.run();
    }

    /**
     * Логически завершает marker-executor.
     *
     * <p>Logically shuts down the marker executor.</p>
     */
    @Override
    public void shutdown() {
    }

    /**
     * Возвращает пустой список, потому что marker не держит очередь задач.
     *
     * <p>Returns an empty list because the marker keeps no task queue.</p>
     */
    @Override
    public List<Runnable> shutdownNow() {
        return List.of();
    }

    /**
     * Всегда возвращает {@code false}, так как marker не отслеживает shutdown state.
     *
     * <p>Always returns {@code false} because the marker does not track
     * shutdown state.</p>
     */
    @Override
    public boolean isShutdown() {
        return false;
    }

    /**
     * Всегда возвращает {@code false}, так как marker не отслеживает termination state.
     *
     * <p>Always returns {@code false} because the marker does not track
     * termination state.</p>
     */
    @Override
    public boolean isTerminated() {
        return false;
    }

    /**
     * Всегда возвращает {@code true}, потому что marker не имеет фоновых задач.
     *
     * <p>Always returns {@code true} because the marker has no background
     * tasks.</p>
     */
    @Override
    public boolean awaitTermination(final long timeout, final TimeUnit unit) {
        return true;
    }
}