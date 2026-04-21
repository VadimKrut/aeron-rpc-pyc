package ru.pathcreator.pyc.rpc.core;

/**
 * Выбор idle-стратегии для receive и вспомогательных polling loops.
 *
 * <p>Стратегия определяет, как поток ведет себя во время простоя: крутится,
 * уступает квант планировщику или уходит в backoff с park. От этого зависит
 * баланс между latency и CPU usage.</p>
 *
 * <p>Idle strategy selection for receive and helper polling loops. The choice
 * determines whether a thread spins, yields, or backs off with parking while
 * idle. That directly affects the latency-versus-CPU trade-off.</p>
 */
public enum IdleStrategyKind {
    /**
     * Постоянный busy-spin без yield и park.
     *
     * <p>Continuous busy spin without yield or park.</p>
     */
    BUSY_SPIN,

    /**
     * Вызывает {@code Thread.yield()} во время простоя без park.
     *
     * <p>Uses {@code Thread.yield()} while idle and avoids parking.</p>
     */
    YIELDING,

    /**
     * Backoff-стратегия Agrona: spin, затем yield, затем park.
     *
     * <p>Agrona backoff strategy: spin, then yield, then park.</p>
     */
    BACKOFF
}