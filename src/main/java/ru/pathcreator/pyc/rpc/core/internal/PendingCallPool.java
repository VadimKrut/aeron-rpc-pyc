package ru.pathcreator.pyc.rpc.core.internal;

import org.agrona.concurrent.ManyToManyConcurrentArrayQueue;

/**
 * Пул переиспользуемых {@link PendingCall} объектов.
 *
 * <p>Пул основан на lock-free очереди Agrona. Если пул временно пуст во время
 * прогрева или пикового трафика, создается новый слот.</p>
 *
 * <p>Pool of reusable {@link PendingCall} objects backed by an Agrona lock-free
 * queue. If the pool is temporarily empty, a new slot is created.</p>
 */
public final class PendingCallPool {

    private final ManyToManyConcurrentArrayQueue<PendingCall> free;

    /**
     * Создает пул с заранее выделенными слотами.
     *
     * <p>Creates a pool with preallocated slots.</p>
     *
     * @param capacity количество заранее созданных слотов / number of preallocated slots
     */
    public PendingCallPool(final int capacity) {
        this.free = new ManyToManyConcurrentArrayQueue<>(capacity);
        for (int i = 0; i < capacity; i++) free.offer(new PendingCall());
    }

    /**
     * Берет слот из пула или создает новый при временном переполнении.
     *
     * <p>Acquires a slot from the pool or creates a new one on temporary overflow.</p>
     *
     * @return слот ожидающего RPC-вызова / pending RPC call slot
     */
    public PendingCall acquire() {
        final PendingCall c = free.poll();
        return c != null ? c : new PendingCall();
    }

    /**
     * Сбрасывает слот и возвращает его в пул.
     *
     * <p>Resets a slot and returns it to the pool.</p>
     *
     * @param call слот для возврата / slot to return
     */
    public void release(final PendingCall call) {
        call.reset();
        free.offer(call);
    }
}