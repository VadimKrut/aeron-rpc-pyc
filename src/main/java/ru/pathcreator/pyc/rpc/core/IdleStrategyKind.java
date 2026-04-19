package ru.pathcreator.pyc.rpc.core;

/**
 * Выбор idle-стратегии для sender / rx тредов канала.
 *
 * <h2>Почему это важно на Windows</h2>
 * <p>
 * На Windows стандартная разрешающая способность таймера — ~15.6 ms.
 * Это значит {@code LockSupport.parkNanos(x)} для любого {@code x > 0}
 * реально спит до 15.6 ms. Aeron MediaDriver включает
 * {@code useWindowsHighResTimer(true)}, что улучшает резолюцию до ~1 ms
 * глобально для процесса, но всё равно это МНОГО для RPC где целевая
 * latency 30–100 µs.
 * <p>
 * Поэтому на Windows {@link #BACKOFF} стратегия (где есть parkNanos)
 * приводит к "штрафам" по 1 ms на каждое idle-ожидание. В цепочке
 * round-trip (sender A → rx B → rx A) это может дать суммарно 3+ ms —
 * именно то, что наблюдалось в бенчмарках с p50 ≈ 3 ms на localhost.
 *
 * <h2>Trade-off</h2>
 *
 * <ul>
 *  <li>{@link #BUSY_SPIN} — самый быстрый (все CPU циклы на poll), но
 *      каждый тред держит ядро на 100%. Хорошо для lightly-loaded
 *      системы с 1-2 каналами на многоядерном железе.</li>
 *  <li>{@link #YIELDING} — {@code Thread.yield()} при простое. CPU всё
 *      ещё высокий, но OS может планировать другие задачи. НЕТ park,
 *      значит НЕТ таймер-штрафа. Разумный default для RPC.</li>
 *  <li>{@link #BACKOFF} — Agrona BackoffIdleStrategy (spin → yield →
 *      parkNanos до 100 µs). Минимум CPU, но на Windows даёт
 *      миллисекундные штрафы. Годится для систем с большим числом
 *      каналов, где важнее сохранить CPU.</li>
 * </ul>
 * <p>
 * Для low-latency RPC на Windows — используйте {@link #YIELDING}
 * или {@link #BUSY_SPIN}. На Linux {@link #BACKOFF} тоже приемлем,
 * потому что там parkNanos работает с наносекундной точностью.
 */
public enum IdleStrategyKind {
    /**
     * Постоянный busy-spin без park/yield.
     *
     * <p>Continuous busy spin without park or yield.</p>
     */
    BUSY_SPIN,

    /**
     * Yield при простое без parkNanos.
     *
     * <p>Yields while idle without using parkNanos.</p>
     */
    YIELDING,

    /**
     * Backoff-стратегия Agrona: spin, затем yield, затем park.
     *
     * <p>Agrona backoff strategy: spin, then yield, then park.</p>
     */
    BACKOFF
}