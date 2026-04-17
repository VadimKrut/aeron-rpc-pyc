package ru.pathcreator.pyc.internal;

import org.agrona.collections.Long2ObjectHashMap;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * correlationId -> PendingCall.
 * <p>
 * ReentrantLock (не synchronized) — корректно работает с виртуальными потоками
 * (не пиннит carrier на старых JDK; на JDK 24+ с JEP 491 разница уходит, но
 * ReentrantLock всё равно явнее).
 * <p>
 * Критическая секция максимально короткая. forEachAndClear() используется при
 * обнаружении channel DOWN: все висящие calls failfast-ятся с одной причиной.
 */
public final class PendingCallRegistry {

    private final Long2ObjectHashMap<PendingCall> map;
    private final ReentrantLock lock = new ReentrantLock();

    public PendingCallRegistry(final int initialCapacity) {
        this.map = new Long2ObjectHashMap<>(initialCapacity, 0.65f);
    }

    public void register(final long correlationId, final PendingCall call) {
        lock.lock();
        try {
            if (map.put(correlationId, call) != null) {
                throw new IllegalStateException("Duplicate correlationId: " + correlationId);
            }
        } finally {
            lock.unlock();
        }
    }

    public PendingCall remove(final long correlationId) {
        lock.lock();
        try {
            return map.remove(correlationId);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Применить action ко всем висящим calls и удалить их. Используется при
     * обнаружении channel DOWN для failfast всех pending-запросов одной
     * причиной.
     */
    public void forEachAndClear(final Consumer<PendingCall> action) {
        lock.lock();
        try {
            map.values().forEach(action);
            map.clear();
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return map.size();
        } finally {
            lock.unlock();
        }
    }
}