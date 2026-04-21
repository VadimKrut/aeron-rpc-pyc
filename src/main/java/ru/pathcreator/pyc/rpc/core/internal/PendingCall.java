package ru.pathcreator.pyc.rpc.core.internal;

import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import ru.pathcreator.pyc.rpc.core.exceptions.RpcException;

import java.nio.ByteBuffer;
import java.util.concurrent.locks.LockSupport;

/**
 * Слот состояния для одного ожидающего синхронного RPC-вызова.
 *
 * <p>Объект связывает caller thread, correlation id и результат ожидания:
 * успешный response payload либо структурированную ошибку. Response payload
 * копируется во внутренний direct buffer, который переиспользуется между
 * вызовами и увеличивается только при необходимости.</p>
 *
 * <p>State slot for one pending synchronous RPC call. It stores the caller
 * thread, correlation identifier, and completion result: either a successful
 * response payload or a structured failure.</p>
 *
 * <p>Модель памяти / Memory model: данные результата записываются до volatile
 * флага {@code completed}; ожидающий поток сначала читает {@code completed}, а
 * затем безопасно читает остальные поля результата.</p>
 */
public final class PendingCall {

    private volatile boolean failed;
    private volatile boolean completed;
    private volatile Thread parkedThread;
    private volatile String failureReason;
    private volatile RpcException failure;

    private long correlationId;
    private int expectedResponseTypeId;

    // Response payload lives in this reusable direct buffer. The capacity grows
    // only when a larger response must be copied in.
    private UnsafeBuffer responseBuffer;

    private int responseLength;

    private static final int INITIAL_RESPONSE_CAPACITY = 256;

    /**
     * Создает пустой slot ожидающего RPC-вызова.
     *
     * <p>Creates an empty pending RPC call slot.</p>
     */
    public PendingCall() {
        this.responseBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(INITIAL_RESPONSE_CAPACITY));
    }

    /**
     * Подготавливает слот перед регистрацией в реестре ожидающих вызовов.
     *
     * <p>Prepares the slot before it is registered in the pending call
     * registry.</p>
     *
     * @param caller        поток, который ожидает ответ /
     *                      thread waiting for the response
     * @param correlationId идентификатор корреляции запроса /
     *                      request correlation identifier
     */
    public void prepare(final Thread caller, final long correlationId) {
        prepare(caller, correlationId, 0);
    }

    /**
     * Подготавливает слот перед регистрацией в реестре ожидающих вызовов.
     *
     * <p>Prepares the slot before it is registered in the pending call
     * registry.</p>
     *
     * @param caller                 поток, который ожидает ответ /
     *                               caller thread waiting for the response
     * @param correlationId          идентификатор корреляции запроса /
     *                               request correlation identifier
     * @param expectedResponseTypeId ожидаемый тип response message /
     *                               expected response message type identifier
     */
    public void prepare(final Thread caller, final long correlationId, final int expectedResponseTypeId) {
        this.parkedThread = caller;
        this.correlationId = correlationId;
        this.expectedResponseTypeId = expectedResponseTypeId;
        this.completed = false;
        this.failed = false;
        this.failureReason = null;
        this.failure = null;
        this.responseLength = 0;
    }

    /**
     * Записывает успешный результат и пробуждает ожидающий поток.
     *
     * <p>Payload ответа копируется во внутренний буфер, который при
     * необходимости увеличивается. Обычно метод вызывается из receive path.</p>
     *
     * <p>Stores a successful result and unparks the waiting thread. The
     * response payload is copied into the internal buffer, which grows when
     * needed. The method is typically called from the receive path.</p>
     *
     * @param src    буфер-источник с payload ответа /
     *               source buffer containing the response payload
     * @param offset смещение payload в буфере-источнике /
     *               payload offset in the source buffer
     * @param length длина payload в байтах /
     *               payload length in bytes
     */
    public void completeOk(final org.agrona.DirectBuffer src, final int offset, final int length) {
        ensureResponseCapacity(length);
        responseBuffer.putBytes(0, src, offset, length);
        this.responseLength = length;
        this.completed = true;
        final Thread t = this.parkedThread;
        if (t != null) LockSupport.unpark(t);
    }

    /**
     * Завершает вызов ошибкой и пробуждает ожидающий поток.
     *
     * <p>Completes the call with a failure and unparks the waiting thread.</p>
     *
     * @param reason текстовая причина завершения / failure reason
     */
    public void completeFail(final String reason) {
        completeFail(new RpcException(reason));
    }

    /**
     * Завершает вызов структурированной ошибкой и пробуждает ожидающий поток.
     *
     * <p>Completes the call with a structured exception and unparks the waiting
     * thread.</p>
     *
     * @param exception ошибка для передачи caller-у /
     *                  failure to propagate to the caller
     */
    public void completeFail(final RpcException exception) {
        this.failure = exception;
        this.failureReason = exception.getMessage();
        this.failed = true;
        this.completed = true;
        final Thread t = this.parkedThread;
        if (t != null) LockSupport.unpark(t);
    }

    /**
     * Проверяет, был ли вызов завершен.
     *
     * <p>Checks whether the call has completed.</p>
     *
     * @return {@code true}, если вызов завершен /
     * {@code true} if the call is completed
     */
    public boolean isCompleted() {
        return completed;
    }

    /**
     * Проверяет, завершился ли вызов ошибкой.
     *
     * <p>Checks whether the call completed with a failure.</p>
     *
     * @return {@code true}, если вызов завершился ошибкой /
     * {@code true} if the call failed
     */
    public boolean isFailed() {
        return failed;
    }

    /**
     * Возвращает причину ошибочного завершения.
     *
     * <p>Returns the failure reason.</p>
     *
     * @return причина ошибки или {@code null}, если ошибки нет /
     * failure reason or {@code null} when there is no failure
     */
    public String failureReason() {
        return failureReason;
    }

    /**
     * Возвращает исключение, сохраненное для вызова.
     *
     * <p>Returns the failure exception captured for the call.</p>
     *
     * @return исключение ошибки или {@code null}, если ошибки нет /
     * failure exception or {@code null} when there is no failure
     */
    public RpcException failure() {
        return failure;
    }

    /**
     * Возвращает correlation id связанного запроса.
     *
     * <p>Returns the correlation identifier of the associated request.</p>
     *
     * @return идентификатор корреляции / correlation identifier
     */
    public long correlationId() {
        return correlationId;
    }

    /**
     * Возвращает ожидаемый response message type id.
     *
     * <p>Returns the expected response message type identifier.</p>
     *
     * @return ожидаемый идентификатор типа ответа /
     * expected response message type identifier
     */
    public int expectedResponseTypeId() {
        return expectedResponseTypeId;
    }

    /**
     * Возвращает буфер с payload ответа.
     *
     * <p>Caller должен читать этот буфер только после того, как
     * {@link #isCompleted()} вернул {@code true}.</p>
     *
     * <p>Returns the response payload buffer. The caller should read it only
     * after {@link #isCompleted()} returns {@code true}.</p>
     *
     * @return буфер с payload ответа / response payload buffer
     */
    public MutableDirectBuffer responseBuffer() {
        return responseBuffer;
    }

    /**
     * Возвращает длину payload ответа.
     *
     * <p>Returns the response payload length.</p>
     *
     * @return длина payload ответа в байтах /
     * response payload length in bytes
     */
    public int responseLength() {
        return responseLength;
    }

    /**
     * Сбрасывает слот перед возвратом в пул.
     *
     * <p>Resets the slot before returning it to the pool.</p>
     */
    public void reset() {
        this.parkedThread = null;
        this.completed = false;
        this.failed = false;
        this.failureReason = null;
        this.failure = null;
        this.correlationId = 0L;
        this.expectedResponseTypeId = 0;
        this.responseLength = 0;
    }

    private void ensureResponseCapacity(final int required) {
        if (responseBuffer.capacity() >= required) return;
        int newCap = responseBuffer.capacity();
        while (newCap < required) newCap <<= 1;
        this.responseBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(newCap));
    }
}