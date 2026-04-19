package ru.pathcreator.pyc.rpc.core.internal;

import org.agrona.collections.Long2ObjectHashMap;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Реестр ожидающих RPC-вызовов по идентификатору корреляции.
 *
 * <p>Реестр хранит соответствие {@code correlationId -> PendingCall}. Критические
 * секции короткие и защищены {@link ReentrantLock}, что явно отделяет синхронизацию
 * от мониторов объектов.</p>
 *
 * <p>Registry of pending RPC calls by correlation identifier. It stores
 * {@code correlationId -> PendingCall} mappings and keeps critical sections short.</p>
 */
public final class PendingCallRegistry {

    private final Long2ObjectHashMap<PendingCall> map;
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Создает реестр ожидающих вызовов.
     *
     * <p>Creates a pending call registry.</p>
     *
     * @param initialCapacity начальная емкость внутренней карты / initial capacity of the internal map
     */
    public PendingCallRegistry(final int initialCapacity) {
        this.map = new Long2ObjectHashMap<>(initialCapacity, 0.65f);
    }

    /**
     * Регистрирует ожидающий вызов по идентификатору корреляции.
     *
     * <p>Registers a pending call by correlation identifier.</p>
     *
     * @param correlationId идентификатор корреляции / correlation identifier
     * @param call          ожидающий вызов / pending call
     */
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

    /**
     * Удаляет и возвращает ожидающий вызов по идентификатору корреляции.
     *
     * <p>Removes and returns a pending call by correlation identifier.</p>
     *
     * @param correlationId идентификатор корреляции / correlation identifier
     * @return найденный ожидающий вызов или {@code null} / matching pending call or {@code null}
     */
    public PendingCall remove(final long correlationId) {
        lock.lock();
        try {
            return map.remove(correlationId);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Применяет действие ко всем ожидающим вызовам и очищает реестр.
     *
     * <p>Метод используется при переходе канала в DOWN, чтобы завершить все
     * pending-запросы одной fail-fast причиной.</p>
     *
     * <p>Applies an action to all pending calls and clears the registry.</p>
     *
     * @param action действие для каждого ожидающего вызова / action for each pending call
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

    /**
     * Возвращает текущее количество ожидающих вызовов.
     *
     * <p>Returns the current number of pending calls.</p>
     *
     * @return размер реестра / registry size
     */
    public int size() {
        lock.lock();
        try {
            return map.size();
        } finally {
            lock.unlock();
        }
    }
}