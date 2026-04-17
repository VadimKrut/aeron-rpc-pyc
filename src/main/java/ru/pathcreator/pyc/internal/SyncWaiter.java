package ru.pathcreator.pyc.internal;

import java.util.concurrent.locks.LockSupport;

/**
 * Ожидание завершения {@link PendingCall} с таймаутом.
 *
 * <h3>Почему это нетривиально</h3>
 * <p>
 * На Windows {@code LockSupport.parkNanos(x)} имеет минимальную
 * резолюцию ~15.6 ms (или ~1 ms если кто-то в процессе включил
 * high-res timer — что MediaDriver-а Aeron-а делает). Это значит что
 * {@code parkNanos(100_000)} (100 µs) на Windows реально спит 1 ms.
 * Для RPC с целевой latency 30-100 µs это разрушительно.
 *
 * <h3>Стратегия</h3>
 * <p>
 * Hot path (ожидаемая latency &lt; 500 µs):
 * 1. Длинный {@code Thread.onSpinWait()} spin (до spinLimit).
 * Это покрывает весь ожидаемый round-trip на localhost.
 * 2. Короткий {@code Thread.yield()} loop.
 * <p>
 * Cold path (ожидание дольше spinLimit+yieldLimit):
 * 3. {@code parkNanos} сразу с большим интервалом (coldParkNs, default 1 ms).
 * На Windows это всё равно спит минимум 1 ms — не пытаемся просить меньше.
 * <p>
 * Дизайн рассчитан на типичный RPC где ответ приходит за микросекунды.
 * Если ответ задерживается (cold case — например канал DOWN или remote
 * медленный), платформенный поток уходит в park до unpark от rx-треда
 * или до истечения таймаута.
 *
 * <h3>CPU cost</h3>
 * <p>
 * Spin-фаза тратит CPU на ожидающем треде. Для синхронного RPC это ровно
 * то что нужно — caller всё равно ничего не делает до получения ответа,
 * лучше потратить этот CPU на быстрое пробуждение. Если этот принцип не
 * подходит (например тысячи одновременных виртуалок ждут ответа с большой
 * latency), сократите spinLimit.
 */
public final class SyncWaiter {

    private final int spinLimit;
    private final int yieldLimit;
    private final long coldParkNs;

    /**
     * Default profile: hot RPC на localhost / LAN.
     * 20_000 spin + 100 yield — покрывает до ~500 µs ожидания без park.
     * Cold park = 1 ms — Windows всё равно не спит короче.
     */
    public SyncWaiter() {
        this(20_000, 100, 1_000_000L);
    }

    public SyncWaiter(final int spinLimit, final int yieldLimit, final long coldParkNs) {
        this.spinLimit = spinLimit;
        this.yieldLimit = yieldLimit;
        this.coldParkNs = coldParkNs;
    }

    /**
     * @return true если call завершился в срок; false если таймаут.
     */
    public boolean await(final PendingCall call, final long timeoutNs) {
        // Phase 1: spin.
        // onSpinWait — инструкция CPU "pause" (x86 PAUSE / ARM YIELD),
        // дешёвая и сигнализирующая ветвепредсказателю, что мы в spin.
        for (int i = 0; i < spinLimit; i++) {
            if (call.isCompleted()) return true;
            Thread.onSpinWait();
        }

        final long deadline = System.nanoTime() + timeoutNs;

        // Phase 2: yield.
        for (int i = 0; i < yieldLimit; i++) {
            if (call.isCompleted()) return true;
            if (System.nanoTime() >= deadline) return call.isCompleted();
            Thread.yield();
        }

        // Phase 3: cold park. Только когда ответ реально задерживается.
        // coldParkNs выбран так, чтобы быть ≥ Windows таймер-резолюции.
        while (!call.isCompleted()) {
            final long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return call.isCompleted();
            LockSupport.parkNanos(Math.min(coldParkNs, remaining));
        }
        return true;
    }
}