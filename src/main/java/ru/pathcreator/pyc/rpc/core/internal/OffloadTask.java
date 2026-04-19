package ru.pathcreator.pyc.rpc.core.internal;

import org.agrona.concurrent.ManyToManyConcurrentArrayQueue;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Переиспользуемая задача для выполнения серверных обработчиков в OFFLOAD-режиме.
 *
 * <p>Задача хранит все данные, необходимые для вызова обработчика, и после
 * выполнения возвращается в пул. Такой подход убирает создание lambda-объекта
 * или synthetic-класса на каждый входящий запрос.</p>
 *
 * <p>Reusable task for executing server handlers in OFFLOAD mode. The task keeps
 * all data required to invoke a handler and returns itself to a pool after
 * execution, avoiding per-request lambda allocation.</p>
 */
public final class OffloadTask implements Runnable {

    /**
     * Callback с телом выполнения offload-задачи.
     *
     * <p>Callback containing the body of an offload task.</p>
     */
    public interface Body {
        /**
         * Выполняет обработку одного входящего запроса.
         *
         * <p>Executes processing of one incoming request.</p>
         *
         * @param messageTypeId идентификатор типа сообщения / message type identifier
         * @param correlationId идентификатор корреляции запроса / request correlation identifier
         * @param payloadCopy   копия payload для offload-потока / payload copy for the offload thread
         * @param payloadLength длина payload в байтах / payload length in bytes
         * @param handlerEntry  запись зарегистрированного обработчика / registered handler entry
         */
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
     * Пул, в который задача возвращается после выполнения.
     */
    private Pool ownerPool;

    /**
     * Создает пустую offload-задачу для последующей инициализации.
     *
     * <p>Creates an empty offload task for later initialization.</p>
     */
    public OffloadTask() {
    }

    /**
     * Инициализирует задачу перед передачей в executor.
     *
     * <p>Initializes the task before it is submitted to an executor.</p>
     *
     * @param body          callback выполнения / execution callback
     * @param messageTypeId идентификатор типа сообщения / message type identifier
     * @param correlationId идентификатор корреляции запроса / request correlation identifier
     * @param payloadCopy   копия payload / payload copy
     * @param payloadLength длина payload в байтах / payload length in bytes
     * @param handlerEntry  запись обработчика / handler entry
     * @param ownerPool     пул, в который вернуть задачу / pool to return the task to
     */
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

    /**
     * Выполняет задачу и возвращает ее в пул после завершения.
     *
     * <p>Runs the task and returns it to the pool after completion.</p>
     */
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

    /**
     * Пул переиспользуемых {@link OffloadTask} экземпляров.
     *
     * <p>Pool of reusable {@link OffloadTask} instances.</p>
     */
    public static final class Pool {
        private final ManyToManyConcurrentArrayQueue<OffloadTask> free;

        /**
         * Создает пул с заранее выделенными задачами.
         *
         * <p>Creates a pool with preallocated tasks.</p>
         *
         * @param capacity количество заранее созданных задач / number of preallocated tasks
         */
        public Pool(final int capacity) {
            this.free = new ManyToManyConcurrentArrayQueue<>(capacity);
            for (int i = 0; i < capacity; i++) free.offer(new OffloadTask());
        }

        /**
         * Берет задачу из пула или создает новую при временном переполнении.
         *
         * <p>Acquires a task from the pool or creates a new one on temporary overflow.</p>
         *
         * @return задача для выполнения / task ready for initialization
         */
        public OffloadTask acquire() {
            final OffloadTask t = free.poll();
            return t != null ? t : new OffloadTask();
        }

        /**
         * Возвращает задачу в пул.
         *
         * <p>Returns a task to the pool.</p>
         *
         * @param task задача для возврата / task to return
         */
        public void release(final OffloadTask task) {
            free.offer(task);
        }
    }
}