package ru.pathcreator.pyc.internal;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Монотонный 64-битный counter, seed = System.nanoTime() чтобы после рестарта
 * не коллизить со "старыми" in-flight ответами.
 */
public final class CorrelationIdGenerator {

    private final AtomicLong counter = new AtomicLong(System.nanoTime());

    public long next() {
        return counter.incrementAndGet();
    }
}