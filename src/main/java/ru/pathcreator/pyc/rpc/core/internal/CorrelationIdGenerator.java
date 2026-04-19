package ru.pathcreator.pyc.rpc.core.internal;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Генератор монотонно возрастающих транспортных идентификаторов корреляции.
 *
 * <p>Начальное значение берется из {@link System#nanoTime()}, чтобы после
 * перезапуска процесса снизить вероятность пересечения с ответами, которые
 * могли остаться в старом in-flight состоянии.</p>
 *
 * <p>Generator of monotonically increasing transport correlation identifiers.
 * The initial value is seeded from {@link System#nanoTime()} to reduce the
 * chance of colliding with stale in-flight responses after a process restart.</p>
 */
public final class CorrelationIdGenerator {

    private final AtomicLong counter = new AtomicLong(System.nanoTime());

    /**
     * Создает генератор идентификаторов корреляции.
     *
     * <p>Creates a correlation identifier generator.</p>
     */
    public CorrelationIdGenerator() {
    }

    /**
     * Возвращает следующий идентификатор корреляции.
     *
     * <p>Returns the next correlation identifier.</p>
     *
     * @return следующий идентификатор корреляции / next correlation identifier
     */
    public long next() {
        return counter.incrementAndGet();
    }
}