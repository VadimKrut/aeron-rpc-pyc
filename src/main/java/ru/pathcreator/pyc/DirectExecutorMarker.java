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
 *
 * <p>Marker executor for the option that executes handlers directly in the
 * receive thread.</p>
 */
final class DirectExecutorMarker extends AbstractExecutorService {
    /**
     * Выполняет команду синхронно в текущем потоке.
     *
     * <p>Executes the command synchronously in the current thread.</p>
     *
     * @param command команда для выполнения / command to execute
     */
    @Override
    public void execute(final Runnable command) {
        command.run();
    }

    /**
     * Завершает executor.
     *
     * <p>Shuts down the executor.</p>
     */
    @Override
    public void shutdown() {
    }

    /**
     * Завершает executor и возвращает невыполненные задачи.
     *
     * <p>Shuts down the executor and returns pending tasks.</p>
     *
     * @return пустой список задач / empty task list
     */
    @Override
    public List<Runnable> shutdownNow() {
        return List.of();
    }

    /**
     * Проверяет, был ли executor остановлен.
     *
     * <p>Checks whether the executor has been shut down.</p>
     *
     * @return {@code false}, так как маркер не хранит состояние shutdown /
     * {@code false} because the marker does not keep shutdown state
     */
    @Override
    public boolean isShutdown() {
        return false;
    }

    /**
     * Проверяет, завершился ли executor.
     *
     * <p>Checks whether the executor has terminated.</p>
     *
     * @return {@code false}, так как маркер не хранит состояние завершения /
     * {@code false} because the marker does not keep termination state
     */
    @Override
    public boolean isTerminated() {
        return false;
    }

    /**
     * Ожидает завершения executor-а.
     *
     * <p>Waits for executor termination.</p>
     *
     * @param timeout максимальное время ожидания / maximum wait time
     * @param unit    единица измерения времени / time unit
     * @return {@code true}, так как у маркера нет фоновых задач /
     * {@code true} because the marker has no background tasks
     */
    @Override
    public boolean awaitTermination(final long timeout, final TimeUnit unit) {
        return true;
    }
}