package ru.pathcreator.pyc;

/**
 * Что делать когда Aeron publication возвращает BACK_PRESSURED дольше
 * {@code offerTimeout}.
 *
 * <p>Упрощён до двух значений (убраны DROP_NEW / DROP_OLDEST). Для RPC
 * запрос/ответ они почти всегда вредны: caller ждёт ответа — если мы
 * "молча дропнем" или "вытесним" его запрос, он всё равно упадёт по
 * таймауту.</p>
 *
 * <p>Defines what the RPC channel should do when an Aeron publication stays
 * back-pressured longer than {@code offerTimeout}.</p>
 */
public enum BackpressurePolicy {

    /**
     * Caller паркуется/спинится пока Aeron не примет запрос, либо до
     * истечения {@code offerTimeout} — тогда {@link
     * ru.pathcreator.pyc.exceptions.BackpressureException}.
     *
     * <p>Default. Безопасно для RPC с I/O-бэкендом: просто "запрос
     * подождёт", как в HTTP при перегрузке сервера.</p>
     */
    BLOCK,

    /**
     * Сразу {@link ru.pathcreator.pyc.exceptions.BackpressureException}
     * если первая же попытка offer/tryClaim вернула BACK_PRESSURED.
     *
     * <p>Для сервисов где важнее быстро отказать и дать caller-у
     * сделать fallback, чем замедлять RPC-путь под нагрузкой.</p>
     *
     * <p>Fails immediately when the first offer attempt reports backpressure.</p>
     */
    FAIL_FAST
}