package ru.pathcreator.pyc.rpc.core.internal;

import java.util.concurrent.locks.LockSupport;

/**
 * Ожидатель завершения {@link PendingCall} с таймаутом.
 *
 * <p>Класс реализует трехфазное ожидание: сначала короткий spin, затем yield,
 * затем park. Это позволяет не платить полный park latency в типичном hot path
 * локального RPC, но все же не крутиться бесконечно, если ответ действительно
 * задерживается.</p>
 *
 * <p>Waiter for {@link PendingCall} completion with a timeout. It uses a
 * three-phase strategy: short spin, then yield, then park. This avoids paying
 * full park latency in the common hot path while still backing off when a
 * response is genuinely delayed.</p>
 */
public final class SyncWaiter {

    private final int spinLimit;
    private final int yieldLimit;
    private final long coldParkNs;

    /**
     * Профиль по умолчанию для hot RPC на localhost или low-latency LAN.
     *
     * <p>Default profile for hot RPC on localhost or a low-latency LAN.</p>
     */
    public SyncWaiter() {
        this(20_000, 100, 1_000_000L);
    }

    /**
     * Создает ожидатель с явными лимитами spin/yield/park фаз.
     *
     * <p>Creates a waiter with explicit spin, yield, and park phase limits.</p>
     *
     * @param spinLimit  максимальное число spin-итераций /
     *                   maximum number of spin iterations
     * @param yieldLimit максимальное число yield-итераций /
     *                   maximum number of yield iterations
     * @param coldParkNs длительность cold-park ожидания в наносекундах /
     *                   cold park duration in nanoseconds
     */
    public SyncWaiter(final int spinLimit, final int yieldLimit, final long coldParkNs) {
        this.spinLimit = spinLimit;
        this.yieldLimit = yieldLimit;
        this.coldParkNs = coldParkNs;
    }

    /**
     * Ожидает завершения вызова до истечения таймаута.
     *
     * <p>Waits for a call to complete before the timeout expires.</p>
     *
     * @param call      ожидающий RPC-вызов / pending RPC call
     * @param timeoutNs таймаут ожидания в наносекундах / timeout in nanoseconds
     * @return {@code true}, если вызов завершился в срок; {@code false}, если
     * истек таймаут / {@code true} if the call completed in time; {@code false}
     * on timeout
     */
    public boolean await(final PendingCall call, final long timeoutNs) {
        for (int i = 0; i < spinLimit; i++) {
            if (call.isCompleted()) return true;
            Thread.onSpinWait();
        }

        final long deadline = System.nanoTime() + timeoutNs;

        for (int i = 0; i < yieldLimit; i++) {
            if (call.isCompleted()) return true;
            if (System.nanoTime() >= deadline) return call.isCompleted();
            Thread.yield();
        }

        while (!call.isCompleted()) {
            final long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return call.isCompleted();
            LockSupport.parkNanos(Math.min(coldParkNs, remaining));
        }
        return true;
    }
}