package ru.pathcreator.pyc.internal;

import org.agrona.concurrent.ManyToManyConcurrentArrayQueue;

/**
 * Пул PendingCall. Lock-free Agrona queue. Переполнение пула — ok, создаём
 * новый слот (warmup / peak traffic).
 */
public final class PendingCallPool {

    private final ManyToManyConcurrentArrayQueue<PendingCall> free;

    public PendingCallPool(final int capacity) {
        this.free = new ManyToManyConcurrentArrayQueue<>(capacity);
        for (int i = 0; i < capacity; i++) free.offer(new PendingCall());
    }

    public PendingCall acquire() {
        final PendingCall c = free.poll();
        return c != null ? c : new PendingCall();
    }

    public void release(final PendingCall call) {
        call.reset();
        free.offer(call);
    }
}