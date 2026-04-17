package ru.pathcreator.pyc.internal;

import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

import java.nio.ByteBuffer;
import java.util.concurrent.locks.LockSupport;

/**
 * Один "слот" под ожидающий синхронный RPC.
 * <p>
 * Хранит ссылку на caller-а (paredThread) и результат. Результат — либо
 * успех (скопированные байты payload-а response + correlationId), либо
 * failure (reason). Никаких аллокаций на happy-path: внутренний response
 * buffer — переиспользуемый direct-буфер, выросший один раз до размера.
 * <p>
 * Memory model:
 * - ALL writes к полям результата выполнены ДО записи в volatile completed.
 * - Caller читает completed первым (volatile read = acquire), потом безопасно
 * читает поля результата.
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

    public PendingCall() {
        this.responseBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(INITIAL_RESPONSE_CAPACITY));
    }

    /**
     * Подготовка перед регистрацией. Вызывается caller-ом.
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
     * Заполнение успешного результата и пробуждение caller-а.
     * Копирует payload в локальный буфер (растит его если нужно). Вызывается
     * из rx-треда, поэтому должно быть быстрым.
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
     * Failfast: канал DOWN / timeout / encode error.
     */
    public void completeFail(final String reason) {
        this.failureReason = reason;
        this.failed = true;
        this.completed = true;
        final Thread t = this.parkedThread;
        if (t != null) LockSupport.unpark(t);
    }

    public boolean isCompleted() {
        return completed;
    }

    public boolean isFailed() {
        return failed;
    }

    public String failureReason() {
        return failureReason;
    }

    public long correlationId() {
        return correlationId;
    }

    /**
     * Response payload для чтения caller-ом ПОСЛЕ isCompleted()==true.
     */
    public MutableDirectBuffer responseBuffer() {
        return responseBuffer;
    }

    public int responseLength() {
        return responseLength;
    }

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