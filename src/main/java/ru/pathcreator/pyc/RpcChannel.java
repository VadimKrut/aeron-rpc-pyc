package ru.pathcreator.pyc;

import io.aeron.*;
import io.aeron.logbuffer.BufferClaim;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.collections.Int2ObjectHashMap;
import org.agrona.concurrent.*;
import ru.pathcreator.pyc.codec.MessageCodec;
import ru.pathcreator.pyc.envelope.Envelope;
import ru.pathcreator.pyc.envelope.EnvelopeCodec;
import ru.pathcreator.pyc.exceptions.*;
import ru.pathcreator.pyc.internal.*;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Один двунаправленный RPC-канал.
 *
 * <h3>Архитектура (после оптимизации)</h3>
 *
 * <pre>
 *   caller (virtual or platform thread)
 *     │
 *     │ call(req, codec, respCodec)
 *     ▼
 *   ┌────────────────────────────────────────────┐
 *   │ 1. acquire PendingCall from pool           │
 *   │ 2. register correlationId                  │
 *   │ 3. ThreadLocal staging buffer              │
 *   │ 4. encode envelope + payload               │
 *   │ 5. publication.tryClaim + commit           │
 *   │    (ConcurrentPublication — concurrent)    │
 *   │ 6. SyncWaiter.await()                      │
 *   └────────────────────────────────────────────┘
 *
 *                          │ UDP
 *                          ▼
 *   single rx thread per channel
 *   └── subscription.poll() loop with IdleStrategy
 *       │
 *       ├── response → pendingRegistry.remove → PendingCall.completeOk → unpark caller
 *       │
 *       └── request → OFFLOAD to executor (virtual threads by default)
 *                   OR direct-execute in rx thread (if DIRECT_EXECUTOR configured)
 * </pre>
 *
 * <h3>Ключевые отличия от предыдущей версии</h3>
 * <ul>
 *  <li>Нет sender-треда и MPSC-очереди. Caller пишет прямо в
 *      {@link ConcurrentPublication#tryClaim}. На один hop меньше.</li>
 *  <li>Публикация concurrent (не exclusive) — несколько caller-ов могут
 *      писать одновременно, Aeron справляется через CAS на position.</li>
 *  <li>Handler-ы всегда OFFLOAD (или DIRECT_EXECUTOR). Нет INLINE.</li>
 *  <li>Удалён wire-batching (он имел смысл только с sender-тредом).</li>
 * </ul>
 */
public final class RpcChannel implements AutoCloseable {

    private static final int MIN_USER_MESSAGE_TYPE_ID = 1;

    // ---- handler entry types ----

    private static abstract class HandlerEntry {
    }

    private static final class HighLevelEntry extends HandlerEntry {
        final MessageCodec<Object> reqCodec;
        final MessageCodec<Object> respCodec;
        final int responseMessageTypeId;
        final RequestHandler<Object, Object> handler;

        @SuppressWarnings("unchecked")
        HighLevelEntry(final MessageCodec<?> reqCodec,
                       final MessageCodec<?> respCodec,
                       final int responseMessageTypeId,
                       final RequestHandler<?, ?> handler) {
            this.reqCodec = (MessageCodec<Object>) reqCodec;
            this.respCodec = (MessageCodec<Object>) respCodec;
            this.responseMessageTypeId = responseMessageTypeId;
            this.handler = (RequestHandler<Object, Object>) handler;
        }
    }

    private static final class RawEntry extends HandlerEntry {
        final int responseMessageTypeId;
        final RawRequestHandler handler;

        RawEntry(final int responseMessageTypeId, final RawRequestHandler handler) {
            this.responseMessageTypeId = responseMessageTypeId;
            this.handler = handler;
        }
    }

    // ---- fields ----

    private final ChannelConfig config;
    private final ExecutorService offloadExecutor;
    private final boolean directExecutor;

    private final ConcurrentPublication publication;
    private final Subscription subscription;

    /**
     * Read-only after start().
     */
    private final Int2ObjectHashMap<HandlerEntry> handlers = new Int2ObjectHashMap<>();

    // Pending RPC
    private final PendingCallPool pendingPool;
    private final PendingCallRegistry pendingRegistry;
    private final CorrelationIdGenerator correlations = new CorrelationIdGenerator();
    private final SyncWaiter waiter = new SyncWaiter();

    // TX: per-thread staging buffer. ExclusivePublication нам не нужен, но
    // каждому потоку нужен свой staging buffer для encode → put → tryClaim.
    // Используем ThreadLocal direct-буферы.
    private final ThreadLocal<UnsafeBuffer> txStaging;

    // Offload infra
    private final OffloadTask.Pool offloadTaskPool;
    private final ConcurrentLinkedQueue<UnsafeBuffer> offloadCopyPool;
    private final int offloadCopyBufferSize;

    // Reusable BufferClaim для tryClaim. Разные caller-ы не могут шарить
    // один BufferClaim — каждому нужен свой. Используем ThreadLocal.
    private final ThreadLocal<BufferClaim> bufferClaimTl = ThreadLocal.withInitial(BufferClaim::new);

    // Reusable idle strategy для tryClaim back-pressure waits. Thread-local
    // потому что несколько caller-ов могут тут крутиться одновременно.
    private final ThreadLocal<IdleStrategy> txIdleTl =
            ThreadLocal.withInitial(YieldingIdleStrategy::new);

    // RX
    private final Thread rxThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Heartbeat
    private final HeartbeatManager heartbeat;

    // Stable body for offload tasks — one allocation, reused for all requests.
    private final OffloadTask.Body offloadBody = this::runOffload;

    public RpcChannel(
            final ChannelConfig config,
            final Aeron aeron,
            final ExecutorService nodeDefaultExecutor
    ) {
        this.config = config;

        final boolean direct = config.isDirectExecutor();
        this.directExecutor = direct;
        if (direct) {
            this.offloadExecutor = null;  // unused
        } else {
            this.offloadExecutor = config.offloadExecutor() != null
                    ? config.offloadExecutor()
                    : nodeDefaultExecutor;
        }

        // Aeron channels
        final ChannelUriStringBuilder outbound = new ChannelUriStringBuilder()
                .media("udp")
                .endpoint(config.remoteEndpoint())
                .mtu(config.mtuLength())
                .termLength(config.termLength())
                .socketSndbufLength(config.socketSndBuf())
                .socketRcvbufLength(config.socketRcvBuf())
                .reliable(true);
        if (config.sessionId() != 0) outbound.sessionId(config.sessionId());
        // ConcurrentPublication: несколько caller-ов могут tryClaim/offer
        // одновременно без нашего локинга.
        this.publication = aeron.addPublication(outbound.build(), config.streamId());

        final String inbound = new ChannelUriStringBuilder()
                .media("udp")
                .endpoint(config.localEndpoint())
                .reliable(true)
                .build();
        this.subscription = aeron.addSubscription(inbound, config.streamId());

        // Pools
        this.pendingPool = new PendingCallPool(config.pendingPoolCapacity());
        this.pendingRegistry = new PendingCallRegistry(config.registryInitialCapacity());

        final int staging = Math.min(config.maxMessageSize(), 4096);
        this.txStaging = ThreadLocal.withInitial(
                () -> new UnsafeBuffer(ByteBuffer.allocateDirect(staging)));

        this.offloadTaskPool = new OffloadTask.Pool(config.offloadTaskPoolSize());
        this.offloadCopyBufferSize = config.offloadCopyBufferSize();
        this.offloadCopyPool = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < config.offloadCopyPoolSize(); i++) {
            offloadCopyPool.offer(new UnsafeBuffer(ByteBuffer.allocateDirect(offloadCopyBufferSize)));
        }

        // Rx thread
        this.rxThread = new Thread(this::rxLoop,
                "rpc-rx-" + config.localEndpoint() + "-s" + config.streamId());
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
    //                       handler registration
    // ================================================================

    public void onRaw(final int requestMessageTypeId,
                      final int responseMessageTypeId,
                      final RawRequestHandler handler) {
        validateUserMessageTypeId(requestMessageTypeId);
        validateUserMessageTypeId(responseMessageTypeId);
        ensureNotStarted();
        if (handlers.put(requestMessageTypeId, new RawEntry(responseMessageTypeId, handler)) != null)
            throw new IllegalStateException(
                    "handler already registered for messageTypeId=" + requestMessageTypeId);
    }

    public <Req, Resp> void onRequest(final int requestMessageTypeId,
                                      final int responseMessageTypeId,
                                      final MessageCodec<Req> reqCodec,
                                      final MessageCodec<Resp> respCodec,
                                      final RequestHandler<Req, Resp> handler) {
        validateUserMessageTypeId(requestMessageTypeId);
        validateUserMessageTypeId(responseMessageTypeId);
        ensureNotStarted();
        if (handlers.put(requestMessageTypeId,
                new HighLevelEntry(reqCodec, respCodec, responseMessageTypeId, handler)) != null)
            throw new IllegalStateException(
                    "handler already registered for messageTypeId=" + requestMessageTypeId);
    }

    // ================================================================
    //                             lifecycle
    // ================================================================

    public void start() {
        if (!running.compareAndSet(false, true))
            throw new IllegalStateException("already started");
        rxThread.start();
        heartbeat.start();
    }

    @Override
    public void close() {
        running.set(false);
        heartbeat.close();
        try {
            rxThread.join(2000);
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

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
    //                          client API: call()
    // ================================================================

    public <Req, Resp> Resp call(final int requestMessageTypeId,
                                 final int expectedResponseTypeId,
                                 final Req request,
                                 final MessageCodec<Req> reqCodec,
                                 final MessageCodec<Resp> respCodec) {
        return call(requestMessageTypeId, expectedResponseTypeId, request, reqCodec, respCodec,
                config.defaultTimeout().toNanos(), config.backpressurePolicy());
    }

    public <Req, Resp> Resp call(final int requestMessageTypeId,
                                 final int expectedResponseTypeId,
                                 final Req request,
                                 final MessageCodec<Req> reqCodec,
                                 final MessageCodec<Resp> respCodec,
                                 final long timeout,
                                 final TimeUnit unit) {
        return call(requestMessageTypeId, expectedResponseTypeId, request, reqCodec, respCodec,
                unit.toNanos(timeout), config.backpressurePolicy());
    }

    public <Req, Resp> Resp call(final int requestMessageTypeId,
                                 final int expectedResponseTypeId,
                                 final Req request,
                                 final MessageCodec<Req> reqCodec,
                                 final MessageCodec<Resp> respCodec,
                                 final long timeoutNs,
                                 final BackpressurePolicy policy) {
        validateUserMessageTypeId(requestMessageTypeId);
        validateUserMessageTypeId(expectedResponseTypeId);
        if (!isConnected())
            throw new NotConnectedException("channel not connected: " + config.remoteEndpoint());

        final PendingCall call = pendingPool.acquire();
        final long correlationId = correlations.next();
        call.prepare(Thread.currentThread(), correlationId);
        pendingRegistry.register(correlationId, call);

        try {
            // 1. Encode в staging.
            final UnsafeBuffer staging = txStaging.get();
            final int payloadLen = reqCodec.encode(request, staging, Envelope.LENGTH);
            final int totalLen = Envelope.LENGTH + payloadLen;

            if (totalLen > config.maxMessageSize())
                throw new PayloadTooLargeException(totalLen, config.maxMessageSize());
            if (totalLen > staging.capacity())
                throw new RpcException(
                        "Encoded request does not fit staging buffer (totalLen=" + totalLen +
                        ", capacity=" + staging.capacity() + "). Enlarge via maxMessageSize or use smaller payload.");

            EnvelopeCodec.encode(staging, 0,
                    requestMessageTypeId, correlationId,
                    Envelope.FLAG_IS_REQUEST, payloadLen);

            // 2. Send direct. tryClaim fast-path + offer fallback.
            publishBytes(staging, 0, totalLen, policy);

            // 3. Await reply.
            final boolean ok = waiter.await(call, timeoutNs);
            if (!ok) throw new RpcTimeoutException(correlationId, timeoutNs);
            if (call.isFailed()) throw new RpcException("RPC failed: " + call.failureReason());

            return respCodec.decode(call.responseBuffer(), 0, call.responseLength());

        } finally {
            pendingRegistry.remove(correlationId);
            pendingPool.release(call);
        }
    }

    /**
     * Публикация байт в publication.
     *
     * <p>Fast-path: {@link ConcurrentPublication#tryClaim} для размера
     * &le; maxPayloadLength — один wire-фрагмент, zero-copy в log buffer.</p>
     *
     * <p>Fallback: {@link ConcurrentPublication#offer} для больших
     * сообщений (Aeron сам фрагментирует).</p>
     *
     * <p>Для BACK_PRESSURED применяется backpressurePolicy.</p>
     */
    private void publishBytes(final DirectBuffer src, final int offset, final int length,
                              final BackpressurePolicy policy) {
        final long deadline = System.nanoTime() + config.offerTimeout().toNanos();
        final IdleStrategy idle = txIdleTl.get();
        idle.reset();

        final int maxPayload = publication.maxPayloadLength();
        if (length <= maxPayload) {
            // tryClaim fast-path
            final BufferClaim claim = bufferClaimTl.get();
            while (true) {
                final long r = publication.tryClaim(length, claim);
                if (r > 0) {
                    final MutableDirectBuffer buf = claim.buffer();
                    buf.putBytes(claim.offset(), src, offset, length);
                    claim.commit();
                    return;
                }
                handlePublishError(r, policy, deadline, idle);
            }
        } else {
            // offer fallback для больших сообщений
            while (true) {
                final long r = publication.offer(src, offset, length);
                if (r > 0) return;
                handlePublishError(r, policy, deadline, idle);
            }
        }
    }

    /**
     * Centralized error handling для tryClaim/offer.
     * Либо бросает типизированное exception, либо idle (и продолжаем retry).
     */
    private void handlePublishError(final long result, final BackpressurePolicy policy,
                                    final long deadline, final IdleStrategy idle) {
        if (result == Publication.NOT_CONNECTED)
            throw new NotConnectedException("publication not connected");
        if (result == Publication.CLOSED)
            throw new RpcException("publication is closed");
        if (result == Publication.MAX_POSITION_EXCEEDED)
            throw new RpcException("publication max position exceeded");
        if (result == Publication.ADMIN_ACTION || result == Publication.BACK_PRESSURED) {
            if (policy == BackpressurePolicy.FAIL_FAST)
                throw new BackpressureException("publication back-pressured (FAIL_FAST)");
            if (System.nanoTime() >= deadline)
                throw new BackpressureException(
                        "publication back-pressured beyond offerTimeout (result=" + result + ")");
            idle.idle();
            return;
        }
        // unknown — retry with idle
        if (System.nanoTime() >= deadline)
            throw new RpcException("publication returned unexpected code=" + result);
        idle.idle();
    }

    // ================================================================
    //                               RX
    // ================================================================

    private void rxLoop() {
        final FragmentHandler dispatcher = this::dispatch;
        final FragmentAssembler assembler = new FragmentAssembler(dispatcher);
        final IdleStrategy idle = createIdleStrategy(config.rxIdleStrategy());

        while (running.get()) {
            final int fragments = subscription.poll(assembler, 16);
            idle.idle(fragments);
        }
    }

    private static IdleStrategy createIdleStrategy(final IdleStrategyKind kind) {
        switch (kind) {
            case BUSY_SPIN:
                return new BusySpinIdleStrategy();
            case BACKOFF:
                return new BackoffIdleStrategy(
                        100, 10,
                        TimeUnit.NANOSECONDS.toNanos(1),
                        TimeUnit.MILLISECONDS.toNanos(1));
            case YIELDING:
            default:
                return new YieldingIdleStrategy();
        }
    }

    private void dispatch(final DirectBuffer buffer, final int offset, final int length,
                          final Header header) {
        // Одиночный envelope (wire-batching удалён). Но формально парсим
        // циклом — вдруг когда-нибудь вернём batching.
        int cursor = 0;
        while (cursor < length) {
            if (length - cursor < Envelope.LENGTH) return;
            final int abs = offset + cursor;

            if (EnvelopeCodec.magic(buffer, abs) != Envelope.MAGIC) return;
            if (EnvelopeCodec.version(buffer, abs) != Envelope.VERSION) return;

            final int msgTypeId = EnvelopeCodec.messageTypeId(buffer, abs);
            final long correlationId = EnvelopeCodec.correlationId(buffer, abs);
            final int flags = EnvelopeCodec.flags(buffer, abs);
            final int payloadLen = EnvelopeCodec.payloadLength(buffer, abs);
            final int totalLen = Envelope.LENGTH + payloadLen;
            if (totalLen < Envelope.LENGTH || cursor + totalLen > length) return;

            if (EnvelopeCodec.isHeartbeat(flags) || msgTypeId == Envelope.RESERVED_HEARTBEAT) {
                heartbeat.onHeartbeatReceived();
            } else {
                final int payloadOffset = abs + Envelope.LENGTH;
                if (EnvelopeCodec.isRequest(flags)) {
                    handleRequest(msgTypeId, correlationId, buffer, payloadOffset, payloadLen);
                } else {
                    handleResponse(correlationId, buffer, payloadOffset, payloadLen);
                }
            }
            cursor += totalLen;
        }
    }

    private void handleRequest(final int messageTypeId, final long correlationId,
                               final DirectBuffer buffer, final int payloadOffset, final int payloadLen) {
        final HandlerEntry entry = handlers.get(messageTypeId);
        if (entry == null) return;

        if (directExecutor) {
            // Exec в rx-треде, без копирования и offload. Минимум latency.
            invokeHandler(entry, messageTypeId, correlationId, buffer, payloadOffset, payloadLen);
            return;
        }

        // OFFLOAD: копируем payload (чтобы rx-буфер не пропал) и сабмитим.
        if (payloadLen > offloadCopyBufferSize) {
            // Защита: payload не влезает в offload-буфер. Exec inline
            // (потеряем latency rx но не сломаемся). В проде это worth warning.
            invokeHandler(entry, messageTypeId, correlationId, buffer, payloadOffset, payloadLen);
            return;
        }

        final UnsafeBuffer copy = acquireCopy();
        copy.putBytes(0, buffer, payloadOffset, payloadLen);

        final OffloadTask task = offloadTaskPool.acquire();
        task.init(offloadBody, messageTypeId, correlationId, copy, payloadLen, entry, offloadTaskPool);
        offloadExecutor.execute(task);
    }

    /**
     * Body для OffloadTask (выполняется в executor-е).
     */
    private void runOffload(final int messageTypeId, final long correlationId,
                            final UnsafeBuffer copy, final int length, final Object entryObj) {
        try {
            invokeHandler((HandlerEntry) entryObj, messageTypeId, correlationId, copy, 0, length);
        } finally {
            releaseCopy(copy);
        }
    }

    private void invokeHandler(final HandlerEntry entry, final int requestMessageTypeId,
                               final long correlationId, final DirectBuffer buffer,
                               final int payloadOffset, final int payloadLen) {
        try {
            if (entry instanceof RawEntry raw) invokeRaw(raw, correlationId, buffer, payloadOffset, payloadLen);
            else invokeHighLevel((HighLevelEntry) entry, correlationId, buffer, payloadOffset, payloadLen);
        } catch (final Throwable t) {
            t.printStackTrace(System.err);
        }
    }

    private void invokeRaw(final RawEntry entry, final long correlationId,
                           final DirectBuffer buffer, final int payloadOffset, final int payloadLen) {
        // Serverside TX: staging из ThreadLocal (для handler-а который в
        // rx-треде или в offload-треде — всё равно каждому свой).
        final UnsafeBuffer staging = txStaging.get();
        final int responseOffset = Envelope.LENGTH;
        final int responseCapacity = staging.capacity() - responseOffset;

        final int written = entry.handler.handle(
                buffer, payloadOffset, payloadLen,
                staging, responseOffset, responseCapacity);

        if (written <= 0) return;
        if (written > responseCapacity)
            throw new PayloadTooLargeException(Envelope.LENGTH + written, config.maxMessageSize());

        EnvelopeCodec.encode(staging, 0, entry.responseMessageTypeId, correlationId, 0, written);
        publishBytes(staging, 0, Envelope.LENGTH + written, BackpressurePolicy.BLOCK);
    }

    private void invokeHighLevel(final HighLevelEntry entry, final long correlationId,
                                 final DirectBuffer buffer, final int payloadOffset, final int payloadLen) {
        final Object req = entry.reqCodec.decode(buffer, payloadOffset, payloadLen);
        final Object resp = entry.handler.handle(req);
        if (resp == null) return;

        final UnsafeBuffer staging = txStaging.get();
        final int respLen = entry.respCodec.encode(resp, staging, Envelope.LENGTH);
        final int totalLen = Envelope.LENGTH + respLen;
        if (totalLen > config.maxMessageSize())
            throw new PayloadTooLargeException(totalLen, config.maxMessageSize());

        EnvelopeCodec.encode(staging, 0, entry.responseMessageTypeId, correlationId, 0, respLen);
        publishBytes(staging, 0, totalLen, BackpressurePolicy.BLOCK);
    }

    private void handleResponse(final long correlationId, final DirectBuffer buffer,
                                final int payloadOffset, final int payloadLen) {
        final PendingCall call = pendingRegistry.remove(correlationId);
        if (call == null) return;
        call.completeOk(buffer, payloadOffset, payloadLen);
    }

    // ================================================================
    //                          heartbeat + copy pool
    // ================================================================
    private void emitHeartbeat(final long nowNanos) {
        final UnsafeBuffer staging = txStaging.get();
        EnvelopeCodec.encode(staging, 0, Envelope.RESERVED_HEARTBEAT, 0L, Envelope.FLAG_IS_HEARTBEAT, 0);
        // FAIL_FAST: пропустим тик если publication под нагрузкой.
        try {
            publishBytes(staging, 0, Envelope.LENGTH, BackpressurePolicy.FAIL_FAST);
        } catch (final Throwable t) {
            // ignore — следующий тик перекроет
        }
    }

    private void onChannelDown() {
        System.err.println("RPC: channel DOWN -> failfast " + pendingRegistry.size() + " pending");
        pendingRegistry.forEachAndClear(pc -> {
            pc.completeFail("channel DOWN (heartbeat missed)");
            pendingPool.release(pc);
        });
    }

    private void onChannelUp() {
        System.err.println("RPC: channel UP");
    }

    private UnsafeBuffer acquireCopy() {
        final UnsafeBuffer b = offloadCopyPool.poll();
        return b != null ? b : new UnsafeBuffer(ByteBuffer.allocateDirect(offloadCopyBufferSize));
    }

    private void releaseCopy(final UnsafeBuffer b) {
        offloadCopyPool.offer(b);
    }

    private void validateUserMessageTypeId(final int id) {
        if (id < MIN_USER_MESSAGE_TYPE_ID)
            throw new IllegalArgumentException("messageTypeId must be >= " + MIN_USER_MESSAGE_TYPE_ID + " (got " + id + ")");
    }

    private void ensureNotStarted() {
        if (running.get()) throw new IllegalStateException("register handlers before start()");
    }
}