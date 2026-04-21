package ru.pathcreator.pyc.rpc.core;

/**
 * Политика поведения, когда Aeron publication остается back-pressured дольше
 * {@code offerTimeout}.
 *
 * <p>Policy describing what to do when an Aeron publication remains
 * back-pressured longer than {@code offerTimeout}.</p>
 *
 * <p>Политика intentionally сведена к двум значениям. Для request/response RPC
 * варианты вроде silent drop обычно вредны: caller всё равно ждет ответ и в
 * итоге получит timeout или ошибку, только менее понятным способом.</p>
 *
 * <p>The policy is intentionally reduced to two values. For request/response
 * RPC, silent-drop style behaviors are usually harmful: the caller still waits
 * for a response and eventually ends up with a timeout or failure, just in a
 * less explicit way.</p>
 */
public enum BackpressurePolicy {

    /**
     * Ждать, пока publication снова сможет принять сообщение, или до
     * истечения {@code offerTimeout}.
     *
     * <p>Wait until the publication can accept the message again, or until
     * {@code offerTimeout} expires.</p>
     */
    BLOCK,

    /**
     * Сразу завершиться ошибкой при первой же backpressure-неудаче.
     *
     * <p>Fail immediately on the first backpressure result.</p>
     */
    FAIL_FAST
}