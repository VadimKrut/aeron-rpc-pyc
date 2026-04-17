package ru.pathcreator.pyc.internal;

import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

import java.nio.ByteBuffer;
import java.util.concurrent.locks.LockSupport;

/**
 * Слот состояния для одного ожидающего синхронного RPC-вызова.
 *
 * <p>Объект хранит поток caller-а, идентификатор корреляции и результат ожидания:
 * успешный response payload или причину fail-fast завершения. Response payload
 * копируется во внутренний direct-буфер, который переиспользуется между вызовами
 * и растет только при необходимости.</p>
 *
 * <p>State slot for one pending synchronous RPC call. It stores the caller thread,
 * correlation identifier, and completion result: either a response payload or a
 * fail-fast reason.</p>
 *
 * <p>Модель памяти / Memory model: все записи результата выполняются до записи
 * в volatile-поле {@code completed}; caller сначала читает {@code completed},
 * а затем безопасно читает остальные поля результата.</p>
 */
public final class PendingCall {

    private volatile boolean failed;
    private volatile boolean completed;
    private volatile Thread parkedThread;
    private volatile String failureReason;

    private long correlationId;

    // Response payload хранится в этом direct buffer.
    // Первая инициализация в constructor на "разумный" размер, растёт только вверх.
    private UnsafeBuffer responseBuffer;

    private int responseLength;

    private static final int INITIAL_RESPONSE_CAPACITY = 256;

    /**
     * Создает пустой слот ожидающего RPC-вызова.
     *
     * <p>Creates an empty pending RPC call slot.</p>
     */
    public PendingCall() {
        this.responseBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(INITIAL_RESPONSE_CAPACITY));
    }

    /**
     * Подготавливает слот перед регистрацией в реестре ожидающих вызовов.
     *
     * <p>Prepares the slot before it is registered in the pending call registry.</p>
     *
     * @param caller        поток, который ожидает ответ / thread waiting for the response
     * @param correlationId идентификатор корреляции запроса / request correlation identifier
     */
    public void prepare(final Thread caller, final long correlationId) {
        this.parkedThread = caller;
        this.correlationId = correlationId;
        this.completed = false;
        this.failed = false;
        this.failureReason = null;
        this.responseLength = 0;
    }

    /**
     * Записывает успешный результат и пробуждает ожидающий поток.
     *
     * <p>Метод копирует response payload во внутренний буфер, увеличивая его
     * емкость при необходимости. Обычно вызывается из rx-потока.</p>
     *
     * <p>Stores a successful response and unparks the waiting thread. The response
     * payload is copied into the internal buffer, which grows when required.</p>
     *
     * @param src    буфер-источник с payload ответа / source buffer containing the response payload
     * @param offset смещение payload в буфере-источнике / payload offset in the source buffer
     * @param length длина payload в байтах / payload length in bytes
     */
    public void completeOk(final org.agrona.DirectBuffer src, final int offset, final int length) {
        ensureResponseCapacity(length);
        responseBuffer.putBytes(0, src, offset, length);
        this.responseLength = length;
        this.completed = true;  // volatile release
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
        this.failureReason = reason;
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
     * @return {@code true}, если вызов завершен / {@code true} if the call is completed
     */
    public boolean isCompleted() {
        return completed;
    }

    /**
     * Проверяет, завершился ли вызов ошибкой.
     *
     * <p>Checks whether the call completed with a failure.</p>
     *
     * @return {@code true}, если вызов завершился ошибкой / {@code true} if the call failed
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
     * Возвращает идентификатор корреляции связанного запроса.
     *
     * <p>Returns the correlation identifier of the associated request.</p>
     *
     * @return идентификатор корреляции / correlation identifier
     */
    public long correlationId() {
        return correlationId;
    }

    /**
     * Возвращает буфер с response payload.
     *
     * <p>Caller должен читать этот буфер только после того, как {@link #isCompleted()}
     * вернул {@code true}.</p>
     *
     * <p>Returns the response payload buffer. The caller should read this buffer
     * only after {@link #isCompleted()} returns {@code true}.</p>
     *
     * @return буфер с payload ответа / response payload buffer
     */
    public MutableDirectBuffer responseBuffer() {
        return responseBuffer;
    }

    /**
     * Возвращает длину response payload.
     *
     * <p>Returns the response payload length.</p>
     *
     * @return длина payload ответа в байтах / response payload length in bytes
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
        this.correlationId = 0L;
        this.responseLength = 0;
    }

    private void ensureResponseCapacity(final int required) {
        if (responseBuffer.capacity() >= required) return;
        int newCap = responseBuffer.capacity();
        while (newCap < required) newCap <<= 1;
        this.responseBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(newCap));
    }
}