package ru.pathcreator.pyc.internal;

import io.aeron.ExclusivePublication;
import io.aeron.Publication;
import io.aeron.logbuffer.BufferClaim;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.ManyToOneConcurrentArrayQueue;
import ru.pathcreator.pyc.BackpressurePolicy;
import ru.pathcreator.pyc.exceptions.BackpressureException;
import ru.pathcreator.pyc.exceptions.NotConnectedException;
import ru.pathcreator.pyc.exceptions.RpcException;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * Sender тред одного канала.
 * <p>
 * Три пути отправки (выбираются автоматически):
 * <p>
 * 1) {@code tryClaim} + copy для одного frame   — для сообщений &le; maxPayloadLength
 * и batching disabled / батч из одного.
 * 2) {@code tryClaim} + copy для батча из N frames — ТОЛЬКО когда:
 * - wireBatchingEnabled,
 * - frame#1 &le; maxPayloadLength,
 * - у нас уже готовы ≥ 2 frames в очереди (не ждём, opportunistic!),
 * - сумма длин &le; maxPayloadLength.
 * <p>
 * 3) {@code offer()} fallback для сообщений &gt; maxPayloadLength.
 * Aeron сам разобьёт на фрагменты, FragmentAssembler на receiver-е
 * соберёт обратно.
 * <p>
 * Почему так:
 * - {@code tryClaim} пишет в log buffer напрямую, без лишней копии через
 * TxFrame→log (она, правда, всё равно делается из frame.buffer, но в
 * отличие от offer() мы получаем гарантированно ОДИН wire-фрагмент без
 * промежуточной буферизации Aeron-а);
 * - Batching амортизирует overhead claim-операции (один claim на N
 * сообщений) и уменьшает число wire-фрагментов;
 * - Opportunistic drain: НЕ ЖДЁМ пока соберётся batch — это linger
 * (вредно для latency). Пакуем только то, что уже готово СЕЙЧАС.
 * <p>
 * Важный инвариант: если после poll выяснилось что добавленный frame не
 * помещается в текущий batch, мы держим его в поле {@code pendingLead},
 * чтобы в следующей итерации он стал главой нового batch-а. Sender — один
 * тред, поэтому это поле безопасно без синхронизации.
 */
public final class SenderQueue {

    private final long offerTimeoutNs;
    private final TxFrame.Pool framePool;
    private final PendingCallRegistry registry;
    private final ExclusivePublication publication;
    private final ManyToOneConcurrentArrayQueue<TxFrame> queue;

    private final int maxBatchMessages;
    private final boolean wireBatchingEnabled;

    private final Thread thread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Reusable BufferClaim — sender single-threaded, безопасно шарить.
    private final BufferClaim bufferClaim = new BufferClaim();

    // Массив для сборки batch-а. Размер = maxBatchMessages (+ 1 для lead).
    private final TxFrame[] batch;

    // Frame, который был poll-нут для проверки, но не влез в текущий batch.
    // В следующей итерации он — lead нового batch-а.
    private TxFrame pendingLead;

    public SenderQueue(
            final String name,
            final ExclusivePublication publication,
            final TxFrame.Pool framePool,
            final PendingCallRegistry registry,
            final int capacity,
            final long offerTimeoutNs,
            final boolean wireBatchingEnabled,
            final int maxBatchMessages
    ) {
        this.publication = publication;
        this.framePool = framePool;
        this.registry = registry;
        this.offerTimeoutNs = offerTimeoutNs;
        this.wireBatchingEnabled = wireBatchingEnabled;
        this.maxBatchMessages = maxBatchMessages;
        this.queue = new ManyToOneConcurrentArrayQueue<>(capacity);
        this.batch = new TxFrame[Math.max(maxBatchMessages, 1)];
        this.thread = new Thread(this::loop, "rpc-tx-" + name);
        this.thread.setDaemon(false);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("already started");
        }
        thread.start();
    }

    public void close() {
        running.set(false);
        LockSupport.unpark(thread);
        try {
            thread.join(2000);
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        // Очистка: pendingLead + всё что в очереди.
        if (pendingLead != null) {
            failPending(pendingLead, "channel closed");
            framePool.release(pendingLead);
            pendingLead = null;
        }
        TxFrame f;
        while ((f = queue.poll()) != null) {
            failPending(f, "channel closed");
            framePool.release(f);
        }
    }

    /**
     * Submit. При success frame теперь владение sender-а; при failure frame
     * уже возвращён в pool. См. {@link BackpressurePolicy} для поведения.
     *
     * @return true — принято; false — отброшено (DROP_NEW).
     */
    public boolean submit(final TxFrame frame, final BackpressurePolicy policy) {
        if (queue.offer(frame)) {
            LockSupport.unpark(thread);  // быстрее разбудить sender
            return true;
        }

        switch (policy) {
            case FAIL_FAST: {
                framePool.release(frame);
                throw new BackpressureException("sender queue full (FAIL_FAST)");
            }
            case DROP_NEW: {
                framePool.release(frame);
                return false;
            }
            case DROP_OLDEST: {
                for (int attempt = 0; attempt < 4; attempt++) {
                    final TxFrame oldest = queue.poll();
                    if (oldest != null) {
                        failPending(oldest, "displaced by newer request (DROP_OLDEST)");
                        framePool.release(oldest);
                    }
                    if (queue.offer(frame)) {
                        LockSupport.unpark(thread);
                        return true;
                    }
                }
                framePool.release(frame);
                throw new BackpressureException("failed to make space under DROP_OLDEST");
            }
            case BLOCK:
            default: {
                final long deadline = System.nanoTime() + offerTimeoutNs;
                long parkNs = 1_000L;
                while (!queue.offer(frame)) {
                    final long remaining = deadline - System.nanoTime();
                    if (remaining <= 0L) {
                        framePool.release(frame);
                        throw new BackpressureException(
                                "sender queue full, BLOCK timeout after " +
                                TimeUnit.NANOSECONDS.toMillis(offerTimeoutNs) + " ms");
                    }
                    LockSupport.parkNanos(Math.min(parkNs, remaining));
                    if (parkNs < 100_000L) parkNs <<= 1;
                }
                LockSupport.unpark(thread);
                return true;
            }
        }
    }

    // ================================================================
    //                             sender loop
    // ================================================================
    private void loop() {
        final IdleStrategy idle = new BackoffIdleStrategy(
                100, 10,
                TimeUnit.NANOSECONDS.toNanos(1),
                TimeUnit.MICROSECONDS.toNanos(100));

        while (running.get()) {
            // 1. Получаем lead: либо "зависший" от прошлой итерации, либо свежий.
            TxFrame lead = pendingLead;
            pendingLead = null;
            if (lead == null) lead = queue.poll();
            if (lead == null) {
                idle.idle();
                continue;
            }

            try {
                processLead(lead);
            } catch (final Throwable t) {
                // Любая ошибка отправки lead-а — fail pending и отпускаем frame.
                failPending(lead, t.getMessage() != null ? t.getMessage() : t.toString());
                framePool.release(lead);
            }
            idle.reset();
        }
    }

    /**
     * Решает по какому пути отправить lead:
     * - если wireBatch включён и lead подходит для tryClaim → пробует собрать batch,
     * - если lead не влезает в tryClaim → offer() fallback,
     * - если batching выключен → одиночный tryClaim.
     */
    private void processLead(final TxFrame lead) {
        final int maxPayload = publication.maxPayloadLength();

        if (lead.length() > maxPayload) {
            // Large message: tryClaim не подходит, делаем offer() — Aeron сам фрагментирует.
            sendViaOffer(lead);
            framePool.release(lead);
            return;
        }

        if (!wireBatchingEnabled || maxBatchMessages <= 1) {
            sendSingleTryClaim(lead);
            framePool.release(lead);
            return;
        }

        // Batching: собираем lead + ещё frames, пока влезают и пока есть в очереди.
        int count = 1;
        int totalLen = lead.length();
        batch[0] = lead;

        while (count < maxBatchMessages) {
            final TxFrame next = queue.poll();
            if (next == null) break;             // очередь пуста — останавливаемся
            if (next.length() > maxPayload) {
                // Он сам по себе не помещается в один tryClaim — отправим его
                // в следующей итерации через offer(). Сохраняем как pendingLead.
                pendingLead = next;
                break;
            }
            if (totalLen + next.length() > maxPayload) {
                // Не влезает в текущий claim — откладываем как новый lead.
                pendingLead = next;
                break;
            }
            batch[count++] = next;
            totalLen += next.length();
        }
        try {
            if (count == 1) {
                sendSingleTryClaim(lead);
            } else {
                sendBatchTryClaim(totalLen, count);
            }
        } finally {
            // Возвращаем все frames в пул.
            for (int i = 0; i < count; i++) {
                framePool.release(batch[i]);
                batch[i] = null;
            }
        }
    }

    // ---- paths ----

    /**
     * Одно сообщение через {@link ExclusivePublication#tryClaim}.
     * Размер гарантированно &le; maxPayloadLength (проверено caller-ом).
     */
    private void sendSingleTryClaim(final TxFrame frame) {

        final long deadline = System.nanoTime() + offerTimeoutNs;
        final IdleStrategy idle = new BackoffIdleStrategy(10, 20, 1, 1000);

        while (true) {
            final long r = publication.tryClaim(frame.length(), bufferClaim);
            if (r > 0) {
                final MutableDirectBuffer buf = bufferClaim.buffer();
                final int offset = bufferClaim.offset();
                buf.putBytes(offset, frame.buffer(), 0, frame.length());
                bufferClaim.commit();
                return;
            }
            handleClaimError(r, deadline, idle);
        }
    }

    /**
     * Batch из {@code count} frames через один tryClaim.
     * Все frames уже в массиве {@code batch[0..count)}, суммарная длина totalLen.
     */
    private void sendBatchTryClaim(final int totalLen, final int count) {

        final long deadline = System.nanoTime() + offerTimeoutNs;
        final IdleStrategy idle = new BackoffIdleStrategy(10, 20, 1, 1000);

        while (true) {
            final long r = publication.tryClaim(totalLen, bufferClaim);
            if (r > 0) {
                final MutableDirectBuffer buf = bufferClaim.buffer();
                int dstOffset = bufferClaim.offset();
                for (int i = 0; i < count; i++) {
                    final TxFrame f = batch[i];
                    buf.putBytes(dstOffset, f.buffer(), 0, f.length());
                    dstOffset += f.length();
                }
                bufferClaim.commit();
                return;
            }
            handleClaimError(r, deadline, idle);
        }
    }

    /**
     * Fallback для больших сообщений (> maxPayloadLength). Aeron сам режет
     * на фрагменты. Здесь batching невозможен (tryClaim не работает).
     */
    private void sendViaOffer(final TxFrame frame) {

        final long deadline = System.nanoTime() + offerTimeoutNs;
        final IdleStrategy idle = new BackoffIdleStrategy(10, 20, 1, 1000);

        while (true) {
            final long r = publication.offer(frame.buffer(), 0, frame.length());
            if (r > 0) return;
            handleClaimError(r, deadline, idle);
        }
    }

    /**
     * Общая обработка ошибок tryClaim / offer. Либо ретраим с idle,
     * либо бросаем типизированное исключение.
     */
    private void handleClaimError(final long result, final long deadline, final IdleStrategy idle) {
        if (result == Publication.NOT_CONNECTED) {
            throw new NotConnectedException("publication not connected");
        }
        if (result == Publication.CLOSED) {
            throw new RpcException("publication is closed");
        }
        if (result == Publication.MAX_POSITION_EXCEEDED) {
            throw new RpcException("publication max position exceeded");
        }
        if (result == Publication.ADMIN_ACTION || result == Publication.BACK_PRESSURED) {
            if (System.nanoTime() >= deadline) {
                throw new BackpressureException("publication back-pressured beyond offerTimeout (result=" + result + ")");
            }
            idle.idle();
            return;
        }
        // Неизвестный код — ретраим с idle.
        if (System.nanoTime() >= deadline) {
            throw new RpcException("publication returned unexpected code=" + result);
        }
        idle.idle();
    }

    // ---- helpers ----
    private void failPending(final TxFrame f, final String reason) {
        final PendingCall pc = f.pendingCall();
        if (pc == null) return;
        final PendingCall removed = registry.remove(pc.correlationId());
        if (removed != null) {
            removed.completeFail(reason);
        }
    }
}