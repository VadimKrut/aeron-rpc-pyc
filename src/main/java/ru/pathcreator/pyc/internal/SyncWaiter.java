package ru.pathcreator.pyc.internal;

import java.util.concurrent.locks.LockSupport;

/**
 * Умное ожидание завершения PendingCall с таймаутом.
 * <p>
 * Три фазы:
 * 1. Tight spin (наносекундные ответы).
 * 2. Thread.yield (отдаём carrier, но не блокируемся).
 * 3. parkNanos с экспоненциальным ростом до maxParkNs.
 * <p>
 * Дружит с виртуальными потоками: parkNanos корректно паркует виртуалку,
 * освобождая carrier; unpark от rx-треда возобновит.
 * <p>
 * Для платформенных потоков spin+yield сжирает CPU — если вызывать с
 * platform thread, сокращай spin/yield limits.
 */
public final class SyncWaiter {

    private final int spinLimit;
    private final int yieldLimit;
    private final long initialParkNs;
    private final long maxParkNs;

    public SyncWaiter() {
        this(200, 50, 1_000L, 100_000L);
    }

    public SyncWaiter(final int spinLimit, final int yieldLimit,
                      final long initialParkNs, final long maxParkNs) {
        this.spinLimit = spinLimit;
        this.yieldLimit = yieldLimit;
        this.initialParkNs = initialParkNs;
        this.maxParkNs = maxParkNs;
    }

    /**
     * @return true если call завершился в срок; false если таймаут.
     */
    public boolean await(final PendingCall call, final long timeoutNs) {
        // Phase 1: spin
        for (int i = 0; i < spinLimit; i++) {
            if (call.isCompleted()) return true;
            Thread.onSpinWait();
        }
        final long deadline = System.nanoTime() + timeoutNs;

        // Phase 2: yield
        for (int i = 0; i < yieldLimit; i++) {
            if (call.isCompleted()) return true;
            if (System.nanoTime() >= deadline) return call.isCompleted();
            Thread.yield();
        }

        // Phase 3: park
        long parkNs = initialParkNs;
        while (!call.isCompleted()) {
            final long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return call.isCompleted();
            LockSupport.parkNanos(Math.min(parkNs, remaining));
            parkNs = Math.min(parkNs << 1, maxParkNs);
        }
        return true;
    }
}