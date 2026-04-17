package ru.pathcreator.pyc.internal;

import org.agrona.concurrent.ManyToManyConcurrentArrayQueue;
import org.agrona.concurrent.UnsafeBuffer;

import java.nio.ByteBuffer;

/**
 * Один "кадр" в TX-очереди: подготовленный к отправке envelope+payload.
 * <p>
 * Это pooled объект — избегаем аллокации на hot-path. Pool — lock-free
 * MPMC (нам нужен MPMC, т.к. и acquire, и release делают разные потоки:
 * caller-ы (multi-producer) acquire, sender-тред после offer() делает release,
 * но при DROP_OLDEST sender может и acquire и release — так что MPMC
 * универсально).
 * <p>
 * Buffer у каждого кадра размера txStagingCapacity. Если пользователь
 * пытается записать больше — PayloadTooLargeException ещё на уровне caller-а.
 */
public final class TxFrame {

    int length;
    long correlationId;
    final UnsafeBuffer buffer;

    /**
     * Указатель на PendingCall, если он есть (для request — чтобы failfast
     * при DROP_OLDEST или backpressure timeout). Null для response / heartbeat.
     */
    PendingCall pendingCall;

    public TxFrame(final int capacity) {
        this.buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(capacity));
    }

    public UnsafeBuffer buffer() {
        return buffer;
    }

    public int length() {
        return length;
    }

    public long correlationId() {
        return correlationId;
    }

    public PendingCall pendingCall() {
        return pendingCall;
    }

    public void set(final int length, final long correlationId, final PendingCall pc) {
        this.length = length;
        this.correlationId = correlationId;
        this.pendingCall = pc;
    }

    public void clear() {
        this.length = 0;
        this.correlationId = 0L;
        this.pendingCall = null;
    }

    /**
     * MPMC pool.
     */
    public static final class Pool {
        private final ManyToManyConcurrentArrayQueue<TxFrame> free;
        private final int bufferCapacity;

        public Pool(final int poolSize, final int bufferCapacity) {
            this.bufferCapacity = bufferCapacity;
            this.free = new ManyToManyConcurrentArrayQueue<>(poolSize);
            for (int i = 0; i < poolSize; i++) {
                free.offer(new TxFrame(bufferCapacity));
            }
        }

        public TxFrame acquire() {
            final TxFrame f = free.poll();
            // cold path: если пул опустел (пиковая нагрузка), аллоцируем.
            // Обратно в пул вернётся как обычно — пул подрастёт до пика и
            // стабилизируется.
            return f != null ? f : new TxFrame(bufferCapacity);
        }

        public void release(final TxFrame frame) {
            frame.clear();
            free.offer(frame);
        }
    }
}