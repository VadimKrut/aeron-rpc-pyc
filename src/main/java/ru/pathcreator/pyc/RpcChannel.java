package ru.pathcreator.pyc;

import io.aeron.*;
import io.aeron.logbuffer.BufferClaim;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.collections.Int2ObjectHashMap;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.UnsafeBuffer;
import ru.pathcreator.pyc.codec.MessageCodec;
import ru.pathcreator.pyc.envelope.Envelope;
import ru.pathcreator.pyc.envelope.EnvelopeCodec;
import ru.pathcreator.pyc.exceptions.NotConnectedException;
import ru.pathcreator.pyc.exceptions.PayloadTooLargeException;
import ru.pathcreator.pyc.exceptions.RpcException;
import ru.pathcreator.pyc.exceptions.RpcTimeoutException;
import ru.pathcreator.pyc.internal.*;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Один двунаправленный RPC-канал.
 * <p>
 * Архитектура:
 * <p>
 * +-------- caller threads (virtual or platform) --------+
 * |                                                     |
 * call() ------>| acquire TxFrame, encode envelope+payload            |
 * |  submit(frame, policy)                               |
 * |                                                     |
 * +----------------------------------+------------------+
 * v
 * lock-free MPSC (SenderQueue)
 * v
 * +---- single sender thread ----+
 * |  publication.offer() loop    |
 * |  with BackoffIdleStrategy    |
 * +------------------------------+
 * v
 * Aeron UDP
 * v
 * +---- single rx thread --------+
 * |  subscription.poll() loop    |
 * |  dispatch by messageTypeId   |
 * +------------------------------+
 * v
 * response -> PendingCall (unpark caller)
 * request (INLINE) -> handler in rx-thread
 * request (OFFLOAD) -> copy + submit to executor
 * <p>
 * Выделенный sender thread убирает contention: caller-ы только пишут в
 * lock-free очередь, за Publication конкуренция нулевая (single producer).
 * Для размеров &lt;= maxPayloadLength мы используем tryClaim() — zero-copy
 * путь напрямую в log buffer публикации.
 */
public final class RpcChannel implements AutoCloseable {

    private static final int MIN_USER_MESSAGE_TYPE_ID = 1;

    // ---- handler entry types ----
    private static abstract class HandlerEntry {
        final HandlerMode mode;

        HandlerEntry(final HandlerMode mode) {
            this.mode = mode;
        }
    }

    private static final class HighLevelEntry extends HandlerEntry {

        final int responseMessageTypeId;
        final MessageCodec<Object> reqCodec;
        final MessageCodec<Object> respCodec;
        final RequestHandler<Object, Object> handler;

        @SuppressWarnings("unchecked")
        HighLevelEntry(
                final HandlerMode mode,
                final MessageCodec<?> reqCodec,
                final MessageCodec<?> respCodec,
                final int responseMessageTypeId,
                final RequestHandler<?, ?> handler
        ) {
            super(mode);
            this.reqCodec = (MessageCodec<Object>) reqCodec;
            this.respCodec = (MessageCodec<Object>) respCodec;
            this.responseMessageTypeId = responseMessageTypeId;
            this.handler = (RequestHandler<Object, Object>) handler;
        }
    }

    private static final class RawEntry extends HandlerEntry {

        final int responseMessageTypeId;
        final RawRequestHandler handler;

        RawEntry(final HandlerMode mode, final int responseMessageTypeId, final RawRequestHandler handler) {
            super(mode);
            this.responseMessageTypeId = responseMessageTypeId;
            this.handler = handler;
        }
    }

    // ---- fields ----
    private final ChannelConfig config;
    private final ExecutorService offloadExecutor;

    private final Subscription subscription;
    private final ExclusivePublication publication;

    // Read-only after start()
    private final Int2ObjectHashMap<HandlerEntry> handlers = new Int2ObjectHashMap<>();

    // pools / registries
    private final TxFrame.Pool txFramePool;
    private final PendingCallPool pendingPool;
    private final OffloadTask.Pool offloadTaskPool;
    private final PendingCallRegistry pendingRegistry;
    private final SyncWaiter waiter = new SyncWaiter();
    private final CorrelationIdGenerator correlations = new CorrelationIdGenerator();

    private final int offloadCopyBufferSize;
    // Offload copy: пул буферов для копирования payload при OFFLOAD.
    // Индекс свободных буферов через MPMC очередь (Agrona).
    private final org.agrona.concurrent.ManyToManyConcurrentArrayQueue<UnsafeBuffer> offloadCopyPool;

    // tx path
    private final SenderQueue sender;

    // rx path
    private final Thread rxThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // heartbeat
    private final HeartbeatManager heartbeat;

    // Stable reference to the offload body — создаётся один раз.
    private final OffloadTask.Body offloadBody = this::runOffload;

    // Per-response BufferClaim for tryClaim fast-path (single-threaded sender).
    private final BufferClaim bufferClaim = new BufferClaim();

    public RpcChannel(
            final ChannelConfig config,
            final Aeron aeron,
            final ExecutorService nodeDefaultExecutor
    ) {
        this.config = config;
        this.offloadExecutor = config.offloadExecutor() != null
                ? config.offloadExecutor()
                : nodeDefaultExecutor;

        // Build Aeron channels
        final ChannelUriStringBuilder outbound = new ChannelUriStringBuilder()
                .media("udp")
                .endpoint(config.remoteEndpoint())
                .mtu(config.mtuLength())
                .termLength(config.termLength())
                .socketSndbufLength(config.socketSndBuf())
                .socketRcvbufLength(config.socketRcvBuf())
                .reliable(true);
        if (config.sessionId() != 0) outbound.sessionId(config.sessionId());
        this.publication = aeron.addExclusivePublication(outbound.build(), config.streamId());

        final String inbound = new ChannelUriStringBuilder()
                .media("udp")
                .endpoint(config.localEndpoint())
                .reliable(true)
                .build();
        this.subscription = aeron.addSubscription(inbound, config.streamId());

        // Pools
        this.pendingPool = new PendingCallPool(config.pendingPoolCapacity());
        this.pendingRegistry = new PendingCallRegistry(config.registryInitialCapacity());
        this.txFramePool = new TxFrame.Pool(config.txFramePoolSize(), config.txStagingCapacity());
        this.offloadTaskPool = new OffloadTask.Pool(config.offloadTaskPoolSize());
        this.offloadCopyBufferSize = config.offloadCopyBufferSize();
        this.offloadCopyPool = new org.agrona.concurrent.ManyToManyConcurrentArrayQueue<>(config.offloadCopyPoolSize());
        for (int i = 0; i < config.offloadCopyPoolSize(); i++) {
            offloadCopyPool.offer(new UnsafeBuffer(
                    java.nio.ByteBuffer.allocateDirect(offloadCopyBufferSize)));
        }

        // Sender
        this.sender = new SenderQueue(
                config.localEndpoint() + "-s" + config.streamId(),
                publication,
                txFramePool,
                pendingRegistry,
                config.senderQueueCapacity(),
                config.offerTimeout().toNanos(),
                config.wireBatchingEnabled(),
                config.maxBatchMessages());

        // Rx
        this.rxThread = new Thread(this::rxLoop, "rpc-rx-" + config.localEndpoint() + "-s" + config.streamId());
        this.rxThread.setDaemon(false);

        // Heartbeat
        this.heartbeat = new HeartbeatManager(
                config.localEndpoint() + "-s" + config.streamId(),
                config.heartbeatInterval().toNanos(),
                config.heartbeatMissedLimit(),
                this::emitHeartbeat,
                this::onChannelDown,
                this::onChannelUp);
    }

    // ================================================================
    //                          handler registration
    // ================================================================
    public void onRaw(
            final int requestMessageTypeId,
            final int responseMessageTypeId,
            final HandlerMode mode,
            final RawRequestHandler handler
    ) {
        validateUserMessageTypeId(requestMessageTypeId);
        validateUserMessageTypeId(responseMessageTypeId);
        ensureNotStarted();
        if (handlers.put(requestMessageTypeId, new RawEntry(mode, responseMessageTypeId, handler)) != null) {
            throw new IllegalStateException("handler already registered for messageTypeId=" + requestMessageTypeId);
        }
    }

    public <Req, Resp> void onRequest(
            final int requestMessageTypeId,
            final int responseMessageTypeId,
            final MessageCodec<Req> reqCodec,
            final MessageCodec<Resp> respCodec,
            final HandlerMode mode,
            final RequestHandler<Req, Resp> handler
    ) {
        validateUserMessageTypeId(requestMessageTypeId);
        validateUserMessageTypeId(responseMessageTypeId);
        ensureNotStarted();
        if (handlers.put(requestMessageTypeId, new HighLevelEntry(mode, reqCodec, respCodec, responseMessageTypeId, handler)) != null) {
            throw new IllegalStateException("handler already registered for messageTypeId=" + requestMessageTypeId);
        }
    }

    // ================================================================
    //                               lifecycle
    // ================================================================
    public void start() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("already started");
        }
        sender.start();
        rxThread.start();
        heartbeat.start();
    }

    @Override
    public void close() {
        running.set(false);
        heartbeat.close();
        sender.close();
        try {
            rxThread.join(2000);
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        // Failfast висящие calls.
        pendingRegistry.forEachAndClear(pc -> {
            pc.completeFail("channel closed");
            pendingPool.release(pc);
        });
        CloseHelper.closeAll(subscription, publication);
    }

    public boolean isConnected() {
        return publication.isConnected() && heartbeat.isConnected();
    }

    // ================================================================
    //                           client API: call()
    // ================================================================
    public <Req, Resp> Resp call(
            final int requestMessageTypeId,
            final int expectedResponseTypeId,
            final Req request,
            final MessageCodec<Req> reqCodec,
            final MessageCodec<Resp> respCodec
    ) {
        return call(requestMessageTypeId, expectedResponseTypeId, request, reqCodec, respCodec,
                config.defaultTimeout().toNanos(),
                config.backpressurePolicy());
    }

    public <Req, Resp> Resp call(
            final int requestMessageTypeId,
            final int expectedResponseTypeId,
            final Req request,
            final MessageCodec<Req> reqCodec,
            final MessageCodec<Resp> respCodec,
            final long timeout,
            final TimeUnit unit
    ) {
        return call(requestMessageTypeId, expectedResponseTypeId, request, reqCodec, respCodec,
                unit.toNanos(timeout), config.backpressurePolicy());
    }

    public <Req, Resp> Resp call(
            final int requestMessageTypeId,
            final int expectedResponseTypeId,
            final Req request,
            final MessageCodec<Req> reqCodec,
            final MessageCodec<Resp> respCodec,
            final long timeoutNs,
            final BackpressurePolicy policy
    ) {
        validateUserMessageTypeId(requestMessageTypeId);
        validateUserMessageTypeId(expectedResponseTypeId);
        if (!isConnected()) {
            throw new NotConnectedException("channel not connected: " + config.remoteEndpoint());
        }

        final PendingCall call = pendingPool.acquire();
        final long correlationId = correlations.next();
        call.prepare(Thread.currentThread(), correlationId);
        pendingRegistry.register(correlationId, call);

        boolean submitted = false;
        try {
            // Acquire frame (из пула; аллокация только если пул исчерпан).
            final TxFrame frame = txFramePool.acquire();
            final MutableDirectBuffer buf = frame.buffer();

            // Encode payload сразу за envelope'ом.
            final int payloadLen = reqCodec.encode(request, buf, Envelope.LENGTH);
            final int totalLen = Envelope.LENGTH + payloadLen;

            if (totalLen > config.maxMessageSize()) {
                txFramePool.release(frame);
                throw new PayloadTooLargeException(totalLen, config.maxMessageSize());
            }
            if (totalLen > buf.capacity()) {
                txFramePool.release(frame);
                throw new RpcException(
                        "Encoded request does not fit TxFrame buffer; increase txStagingCapacity; " +
                        "totalLen=" + totalLen + ", capacity=" + buf.capacity());
            }

            EnvelopeCodec.encode(buf, 0,
                    requestMessageTypeId, correlationId,
                    Envelope.FLAG_IS_REQUEST, payloadLen);

            frame.set(totalLen, correlationId, call);

            // Submit в sender queue. Может бросить BackpressureException.
            submitted = sender.submit(frame, policy);

            if (!submitted) {
                // DROP_NEW: запрос молча отброшен — caller получает fail.
                throw new ru.pathcreator.pyc.exceptions.BackpressureException(
                        "request dropped by policy DROP_NEW");
            }

            // Ждём ответ.
            final boolean ok = waiter.await(call, timeoutNs);
            if (!ok) {
                throw new RpcTimeoutException(correlationId, timeoutNs);
            }
            if (call.isFailed()) {
                throw new RpcException("RPC failed: " + call.failureReason());
            }

            return respCodec.decode(call.responseBuffer(), 0, call.responseLength());

        } finally {
            pendingRegistry.remove(correlationId);
            pendingPool.release(call);
        }
    }

    // ================================================================
    //                               RX
    // ================================================================

    private void rxLoop() {
        final FragmentHandler dispatcher = this::dispatch;
        final FragmentAssembler assembler = new FragmentAssembler(dispatcher);

        final IdleStrategy idle = new BackoffIdleStrategy(
                100, 10,
                TimeUnit.NANOSECONDS.toNanos(1),
                TimeUnit.MICROSECONDS.toNanos(100));

        while (running.get()) {
            final int fragments = subscription.poll(assembler, 16);
            idle.idle(fragments);
        }
    }

    /**
     * Парсит содержимое одного фрагмента Aeron-а. Фрагмент может содержать
     * 1..N envelope-ов подряд (wire-batching на отправителе), поэтому идём
     * циклом пока не кончится length.
     * <p>
     * Хорошая новость для receiver-а: ЭТО ТОТ ЖЕ КОД что для одиночного
     * envelope-а, просто обёрнутый в цикл. Совместим и со старыми отправителями
     * (которые шлют по одному envelope-у) без изменений.
     */
    private void dispatch(
            final DirectBuffer buffer,
            final int offset,
            final int length,
            final Header header
    ) {
        int cursor = 0;
        while (cursor < length) {
            // Нужно минимум ENVELOPE, иначе это не наше сообщение / мусор.
            if (length - cursor < Envelope.LENGTH) return;

            final int absOffset = offset + cursor;

            final short magic = EnvelopeCodec.magic(buffer, absOffset);
            if (magic != Envelope.MAGIC) return;
            if (EnvelopeCodec.version(buffer, absOffset) != Envelope.VERSION) return;

            final int msgTypeId = EnvelopeCodec.messageTypeId(buffer, absOffset);
            final long correlationId = EnvelopeCodec.correlationId(buffer, absOffset);
            final int flags = EnvelopeCodec.flags(buffer, absOffset);
            final int payloadLen = EnvelopeCodec.payloadLength(buffer, absOffset);

            final int totalLen = Envelope.LENGTH + payloadLen;
            // Защита от corrupt payloadLength (не даём уйти за пределы фрагмента).
            if (totalLen < Envelope.LENGTH || cursor + totalLen > length) return;

            if (EnvelopeCodec.isHeartbeat(flags) || msgTypeId == Envelope.RESERVED_HEARTBEAT) {
                heartbeat.onHeartbeatReceived();
            } else {
                final int payloadOffset = absOffset + Envelope.LENGTH;
                if (EnvelopeCodec.isRequest(flags)) {
                    handleRequest(msgTypeId, correlationId, buffer, payloadOffset, payloadLen);
                } else {
                    handleResponse(correlationId, buffer, payloadOffset, payloadLen);
                }
            }

            cursor += totalLen;
        }
    }

    private void handleRequest(
            final int messageTypeId,
            final long correlationId,
            final DirectBuffer buffer,
            final int payloadOffset,
            final int payloadLen
    ) {
        final HandlerEntry entry = handlers.get(messageTypeId);
        if (entry == null) return;
        if (entry.mode == HandlerMode.INLINE) {
            invokeHandler(entry, messageTypeId, correlationId, buffer, payloadOffset, payloadLen);
            return;
        }

        // OFFLOAD: копируем payload в буфер из пула + сабмитим pooled task.
        if (payloadLen > offloadCopyBufferSize) {
            // Fallback в INLINE, чтобы не ронять; или можно дропать — зависит от политики.
            // По умолчанию предпочитаем обработать inline с логом.
            invokeHandler(entry, messageTypeId, correlationId, buffer, payloadOffset, payloadLen);
            return;
        }

        final UnsafeBuffer copy = acquireOffloadCopy();
        copy.putBytes(0, buffer, payloadOffset, payloadLen);

        final OffloadTask task = offloadTaskPool.acquire();
        task.init(offloadBody, messageTypeId, correlationId, copy, payloadLen, entry, offloadTaskPool);
        offloadExecutor.execute(task);
    }

    /**
     * Body для OffloadTask. Вызывается из executor-а после копирования.
     */
    private void runOffload(
            final int messageTypeId,
            final long correlationId,
            final UnsafeBuffer payloadCopy,
            final int payloadLength,
            final Object handlerEntryObj
    ) {
        try {
            invokeHandler((HandlerEntry) handlerEntryObj, messageTypeId, correlationId, payloadCopy, 0, payloadLength);
        } finally {
            releaseOffloadCopy(payloadCopy);
        }
    }

    private void invokeHandler(
            final HandlerEntry entry,
            final int requestMessageTypeId,
            final long correlationId,
            final DirectBuffer buffer,
            final int payloadOffset,
            final int payloadLen
    ) {
        try {
            if (entry instanceof RawEntry raw) {
                invokeRawHandler(raw, correlationId, buffer, payloadOffset, payloadLen);
            } else {
                final HighLevelEntry hl = (HighLevelEntry) entry;
                invokeHighLevelHandler(hl, correlationId, buffer, payloadOffset, payloadLen);
            }
        } catch (final Throwable t) {
            // Никогда не даём упасть handler-у раз-handler-у:
            // лог + пропуск ответа. Caller упадёт по таймауту.
            t.printStackTrace(System.err);
        }
    }

    private void invokeRawHandler(
            final RawEntry entry,
            final long correlationId,
            final DirectBuffer buffer,
            final int payloadOffset,
            final int payloadLen
    ) {
        // Берём TxFrame под ответ, даём handler-у его buffer с местом начиная с envelope offset.
        final TxFrame frame = txFramePool.acquire();
        final MutableDirectBuffer outBuf = frame.buffer();
        final int responseOffset = Envelope.LENGTH;
        final int responseCapacity = outBuf.capacity() - responseOffset;
        final int written = entry.handler.handle(buffer, payloadOffset, payloadLen, outBuf, responseOffset, responseCapacity);
        if (written <= 0) {
            // One-way / handler не хочет отвечать.
            txFramePool.release(frame);
            return;
        }
        if (written > responseCapacity) {
            txFramePool.release(frame);
            throw new PayloadTooLargeException(Envelope.LENGTH + written, config.maxMessageSize());
        }

        EnvelopeCodec.encode(outBuf, 0, entry.responseMessageTypeId, correlationId, 0, written);
        frame.set(Envelope.LENGTH + written, correlationId, null);
        // Для ответов политика — BLOCK (нельзя потерять ответ, caller ждёт).
        sender.submit(frame, BackpressurePolicy.BLOCK);
    }

    private void invokeHighLevelHandler(
            final HighLevelEntry entry,
            final long correlationId,
            final DirectBuffer buffer,
            final int payloadOffset,
            final int payloadLen
    ) {
        final Object reqObj = entry.reqCodec.decode(buffer, payloadOffset, payloadLen);
        final Object respObj = entry.handler.handle(reqObj);
        if (respObj == null) return;  // handler решил не отвечать.
        final TxFrame frame = txFramePool.acquire();
        final MutableDirectBuffer outBuf = frame.buffer();
        final int respLen = entry.respCodec.encode(respObj, outBuf, Envelope.LENGTH);
        final int totalLen = Envelope.LENGTH + respLen;
        if (totalLen > config.maxMessageSize()) {
            txFramePool.release(frame);
            throw new PayloadTooLargeException(totalLen, config.maxMessageSize());
        }
        EnvelopeCodec.encode(outBuf, 0, entry.responseMessageTypeId, correlationId, 0, respLen);
        frame.set(totalLen, correlationId, null);
        sender.submit(frame, BackpressurePolicy.BLOCK);
    }

    private void handleResponse(
            final long correlationId,
            final DirectBuffer buffer,
            final int payloadOffset,
            final int payloadLen
    ) {
        final PendingCall call = pendingRegistry.remove(correlationId);
        if (call == null) return;  // поздний / после таймаута
        call.completeOk(buffer, payloadOffset, payloadLen);
    }

    // ================================================================
    //                             heartbeat TX
    // ================================================================
    private void emitHeartbeat(final long nowNanos) {
        // Heartbeat всегда идёт мимо sender queue напрямую — он маленький
        // (24 байта), не должен блокировать другие сообщения.
        // Делаем это через ту же single-sender-thread модель: кладём в очередь.
        // Если полна — пропускаем (следующий тик перекроет).
        final TxFrame frame = txFramePool.acquire();
        final MutableDirectBuffer buf = frame.buffer();
        EnvelopeCodec.encode(buf, 0, Envelope.RESERVED_HEARTBEAT, 0L, Envelope.FLAG_IS_HEARTBEAT, 0);
        frame.set(Envelope.LENGTH, 0L, null);
        // DROP_NEW: пропустим этот тик если очередь забита.
        try {
            sender.submit(frame, BackpressurePolicy.DROP_NEW);
        } catch (final Throwable t) {
            // не даём упасть heartbeat-треду
        }
    }

    private void onChannelDown() {
        System.err.println("RPC: channel DOWN -> failfast " + pendingRegistry.size() + " pending calls");
        pendingRegistry.forEachAndClear(pc -> {
            pc.completeFail("channel DOWN (heartbeat missed)");
            pendingPool.release(pc);
        });
    }

    private void onChannelUp() {
        System.err.println("RPC: channel UP");
    }

    // ================================================================
    //                           offload copy pool
    // ================================================================
    private UnsafeBuffer acquireOffloadCopy() {
        final UnsafeBuffer b = offloadCopyPool.poll();
        return b != null ? b : new UnsafeBuffer(java.nio.ByteBuffer.allocateDirect(offloadCopyBufferSize));
    }

    private void releaseOffloadCopy(final UnsafeBuffer buffer) {
        offloadCopyPool.offer(buffer);
    }

    // ================================================================
    //                              validation
    // ================================================================
    private void validateUserMessageTypeId(final int id) {
        if (id < MIN_USER_MESSAGE_TYPE_ID) {
            throw new IllegalArgumentException("messageTypeId must be >= " + MIN_USER_MESSAGE_TYPE_ID + " (zero and negative reserved). Got: " + id);
        }
    }

    private void ensureNotStarted() {
        if (running.get()) {
            throw new IllegalStateException("handlers must be registered before start()");
        }
    }
}