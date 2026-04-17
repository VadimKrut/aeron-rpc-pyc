package ru.pathcreator.pyc.internal;

import org.agrona.concurrent.ManyToManyConcurrentArrayQueue;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Pooled Runnable для OFFLOAD-режима handler-ов.
 * <p>
 * Вместо {@code executor.submit(() -> handler.run(...))} с капчей переменных
 * в synthetic inner class (= аллокация на каждый request), мы берём
 * переиспользуемый OffloadTask из пула, заполняем его полями, отправляем
 * в executor; executor вызывает run(), который зовёт пользовательский
 * handler и возвращает task в пул.
 * <p>
 * Интерфейс-callback {@link Body} пользователь не видит — RpcChannel сам
 * выставляет его на старте. Reset обнуляет все ссылки перед возвратом в пул.
 */
public final class OffloadTask implements Runnable {

    /**
     * Что именно выполнять. Реализуется RpcChannel-ом один раз, не в hot-path.
     */
    public interface Body {
        void execute(
                int messageTypeId,
                long correlationId,
                UnsafeBuffer payloadCopy,
                int payloadLength,
                Object handlerEntry
        );
    }

    private Body body;

    private int messageTypeId;
    private int payloadLength;
    private long correlationId;
    private Object handlerEntry;
    private UnsafeBuffer payloadCopy;

    /**
     * Пул, в который вернуть себя после run().
     */
    private Pool ownerPool;

    public void init(
            final Body body,
            final int messageTypeId,
            final long correlationId,
            final UnsafeBuffer payloadCopy,
            final int payloadLength,
            final Object handlerEntry,
            final Pool ownerPool
    ) {
        this.body = body;
        this.messageTypeId = messageTypeId;
        this.correlationId = correlationId;
        this.payloadCopy = payloadCopy;
        this.payloadLength = payloadLength;
        this.handlerEntry = handlerEntry;
        this.ownerPool = ownerPool;
    }

    @Override
    public void run() {
        final Body b = this.body;
        final UnsafeBuffer copy = this.payloadCopy;
        final int msgTypeId = this.messageTypeId;
        final long corrId = this.correlationId;
        final int len = this.payloadLength;
        final Object entry = this.handlerEntry;
        final Pool pool = this.ownerPool;

        try {
            b.execute(msgTypeId, corrId, copy, len, entry);
        } finally {
            reset();
            if (pool != null) pool.release(this);
        }
    }

    private void reset() {
        this.body = null;
        this.messageTypeId = 0;
        this.correlationId = 0L;
        this.payloadCopy = null;
        this.payloadLength = 0;
        this.handlerEntry = null;
        this.ownerPool = null;
    }

    public static final class Pool {
        private final ManyToManyConcurrentArrayQueue<OffloadTask> free;

        public Pool(final int capacity) {
            this.free = new ManyToManyConcurrentArrayQueue<>(capacity);
            for (int i = 0; i < capacity; i++) free.offer(new OffloadTask());
        }

        public OffloadTask acquire() {
            final OffloadTask t = free.poll();
            return t != null ? t : new OffloadTask();
        }

        public void release(final OffloadTask task) {
            free.offer(task);
        }
    }
}