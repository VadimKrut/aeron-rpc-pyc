package ru.pathcreator.pyc;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Маркер-executor для опции "выполнять handler прямо в rx-треде".
 *
 * <p>Не должен реально вызываться: RpcChannel проверяет {@link
 * ChannelConfig#isDirectExecutor()} и выбирает in-thread путь до того
 * как что-то submit-ить. На всякий случай реализует
 * {@link #execute(Runnable)} синхронно, но это fallback.</p>
 */
final class DirectExecutorMarker extends AbstractExecutorService {
    @Override
    public void execute(final Runnable command) {
        command.run();
    }

    @Override
    public void shutdown() {
    }

    @Override
    public List<Runnable> shutdownNow() {
        return List.of();
    }

    @Override
    public boolean isShutdown() {
        return false;
    }

    @Override
    public boolean isTerminated() {
        return false;
    }

    @Override
    public boolean awaitTermination(final long timeout, final TimeUnit unit) {
        return true;
    }
}