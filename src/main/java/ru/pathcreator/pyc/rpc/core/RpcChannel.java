package ru.pathcreator.pyc.rpc.core;

import io.aeron.*;
import io.aeron.logbuffer.BufferClaim;
import io.aeron.logbuffer.Header;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.collections.Int2ObjectHashMap;
import org.agrona.concurrent.*;
import ru.pathcreator.pyc.rpc.core.codec.MessageCodec;
import ru.pathcreator.pyc.rpc.core.envelope.Envelope;
import ru.pathcreator.pyc.rpc.core.envelope.EnvelopeCodec;
import ru.pathcreator.pyc.rpc.core.exceptions.*;
import ru.pathcreator.pyc.rpc.core.internal.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Один двунаправленный RPC-канал.
 *
 * <h2>Архитектура</h2>
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
 * <h2>Ключевые свойства</h2>
 * <ul>
 *  <li>Нет sender-треда и MPSC-очереди. Caller пишет прямо в
 *      {@link ConcurrentPublication#tryClaim}. На один hop меньше.</li>
 *  <li>Публикация concurrent (не exclusive) — несколько caller-ов могут
 *      писать одновременно, Aeron справляется через CAS на position.</li>
 *  <li>Handler-ы всегда OFFLOAD (или DIRECT_EXECUTOR). Нет INLINE.</li>
 *  <li>Удалён wire-batching (он имел смысл только с sender-тредом).</li>
 * </ul>
 *
 * <p>Bidirectional RPC channel over Aeron. Client calls are correlated with
 * responses by transport correlation identifiers, while server-side handlers
 * can be executed either in offload executor threads or directly in the receive
 * thread when explicitly configured.</p>
 *
 * <p>A channel is still the unit of logical isolation: it owns its
 * publication, subscription, pending-call registry, correlation flow, and
 * handler registry. Recent changes only affect how receive polling is driven:
 * either by a dedicated RX thread or by a node-level shared receive poller.</p>
 */
public final class RpcChannel implements AutoCloseable {

    /**
     * Логгер для внутренних диагностических сообщений канала.
     *
     * <p>Logger for internal channel diagnostics.</p>
     */
    private static final Logger LOGGER = Logger.getLogger(RpcChannel.class.getName());

    private static final int MIN_USER_MESSAGE_TYPE_ID = 1;
    private static final int ERROR_STATUS_CODE_SIZE = Integer.BYTES;
    private static final int PROTOCOL_INFO_LENGTH = Integer.BYTES + Long.BYTES;

    private static final MessageCodec<ProtocolInfo> PROTOCOL_INFO_CODEC = new MessageCodec<>() {
        @Override
        public int encode(final ProtocolInfo message, final MutableDirectBuffer buffer, final int offset) {
            buffer.putInt(offset, message.version, ByteOrder.LITTLE_ENDIAN);
            buffer.putLong(offset + Integer.BYTES, message.capabilities, ByteOrder.LITTLE_ENDIAN);
            return PROTOCOL_INFO_LENGTH;
        }

        @Override
        public ProtocolInfo decode(final DirectBuffer buffer, final int offset, final int length) {
            if (length != PROTOCOL_INFO_LENGTH) {
                throw new IllegalArgumentException("Invalid protocol info length: " + length);
            }
            return new ProtocolInfo(
                    buffer.getInt(offset, ByteOrder.LITTLE_ENDIAN),
                    buffer.getLong(offset + Integer.BYTES, ByteOrder.LITTLE_ENDIAN));
        }
    };

    private record ProtocolInfo(int version, long capabilities) {
    }

    // ---- handler entry types ----
    private static abstract class HandlerEntry {
    }

    private static final class HighLevelEntry extends HandlerEntry {
        final MessageCodec<Object> reqCodec;
        final MessageCodec<Object> respCodec;
        final int responseMessageTypeId;
        final RequestHandler<Object, Object> handler;

        @SuppressWarnings("unchecked")
        HighLevelEntry(
                final MessageCodec<?> reqCodec,
                final MessageCodec<?> respCodec,
                final int responseMessageTypeId,
                final RequestHandler<?, ?> handler
        ) {
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
    private final Aeron aeron;
    private final boolean directExecutor;
    private final ExecutorService offloadExecutor;
    private final SharedReceivePoller receivePoller;
    private final ReconnectStrategy reconnectStrategy;
    private final boolean recreateTransportOnDisconnect;
    private final boolean protocolHandshakeEnabled;
    private final long offerTimeoutNs;
    private final long protocolHandshakeTimeoutNs;
    private final int maxMessageSize;
    private final int protocolVersion;
    private final long protocolCapabilities;
    private final long requiredRemoteCapabilities;
    private final Object transportLock = new Object();
    private final Object handshakeLock = new Object();
    private final Object drainLock = new Object();
    private final RpcChannelListener[] listeners;
    private final RpcChannelListener singleListener;
    private final boolean hasListeners;
    private final boolean callEventListeners;
    private final boolean remoteErrorListeners;
    private final boolean channelStateListeners;
    private final boolean drainStateListeners;
    private final boolean reconnectStateListeners;
    private final boolean protocolHandshakeListeners;
    private volatile boolean draining;
    private final AtomicInteger inFlightHandlers = new AtomicInteger();
    private volatile boolean protocolHandshakeComplete;
    private volatile int remoteProtocolVersion;
    private volatile long remoteProtocolCapabilities;

    private volatile Subscription subscription;
    private volatile ConcurrentPublication publication;
    private final Subscription steadySubscription;
    private final ConcurrentPublication steadyPublication;
    private final String outboundChannel;
    private final String inboundChannel;

    /**
     * Read-only after start().
     */
    private final Int2ObjectHashMap<HandlerEntry> handlers = new Int2ObjectHashMap<>();

    // Pending RPC
    private final PendingCallPool pendingPool;
    private final PendingCallRegistry pendingRegistry;
    private final SyncWaiter waiter = new SyncWaiter();
    private final CorrelationIdGenerator correlations = new CorrelationIdGenerator();

    // TX: per-thread staging buffer. ExclusivePublication нам не нужен, но
    // каждому потоку нужен свой staging buffer для encode → put → tryClaim.
    // Используем ThreadLocal direct-буферы.
    private final ThreadLocal<UnsafeBuffer> txStaging;

    // Offload infra
    private final int offloadCopyBufferSize;
    private final OffloadTask.Pool offloadTaskPool;
    private final ConcurrentLinkedQueue<UnsafeBuffer> offloadCopyPool;

    // Reusable BufferClaim для tryClaim. Разные caller-ы не могут шарить
    // один BufferClaim — каждому нужен свой. Используем ThreadLocal.
    private final ThreadLocal<BufferClaim> bufferClaimTl = ThreadLocal.withInitial(BufferClaim::new);

    // Reusable idle strategy для tryClaim back-pressure waits. Thread-local
    // потому что несколько caller-ов могут тут крутиться одновременно.
    private final ThreadLocal<IdleStrategy> txIdleTl = ThreadLocal.withInitial(YieldingIdleStrategy::new);

    // RX
    private final Thread rxThread;
    private final FragmentAssembler rxAssembler;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Heartbeat
    private final HeartbeatManager heartbeat;

    // Stable body for offload tasks — one allocation, reused for all requests.
    private final OffloadTask.Body offloadBody = this::runOffload;

    /**
     * Создает RPC-канал поверх указанного Aeron-клиента.
     *
     * <p>Creates an RPC channel over the provided Aeron client.</p>
     *
     * @param config              конфигурация канала / channel configuration
     * @param aeron               Aeron-клиент / Aeron client
     * @param nodeDefaultExecutor executor узла по умолчанию для offload-обработчиков /
     *                            node default executor for offloaded handlers
     */
    public RpcChannel(
            final ChannelConfig config,
            final Aeron aeron,
            final ExecutorService nodeDefaultExecutor
    ) {
        this(config, aeron, nodeDefaultExecutor, null);
    }

    /**
     * Internal constructor used by {@link RpcNode}.
     *
     * <p>If {@code receivePoller} is {@code null}, the channel creates and owns
     * its dedicated RX thread. Otherwise the channel registers into the
     * node-level shared receive poller and does not create its own RX thread.</p>
     *
     * <p>Handler execution mode is resolved once here:
     * {@link ChannelConfig#DIRECT_EXECUTOR} means execute handlers directly in
     * the RX path; otherwise the channel uses the explicitly configured
     * executor, or falls back to the node default executor.</p>
     *
     * @param config              channel configuration
     * @param aeron               Aeron client
     * @param nodeDefaultExecutor node-level fallback executor for offloaded handlers
     * @param receivePoller       shared receive poller, or {@code null} for dedicated RX thread mode
     */
    RpcChannel(
            final ChannelConfig config,
            final Aeron aeron,
            final ExecutorService nodeDefaultExecutor,
            final SharedReceivePoller receivePoller
    ) {
        this.config = config;
        this.aeron = aeron;
        this.receivePoller = receivePoller;
        this.reconnectStrategy = config.reconnectStrategy();
        this.recreateTransportOnDisconnect = reconnectStrategy == ReconnectStrategy.RECREATE_ON_DISCONNECT;
        this.protocolHandshakeEnabled = config.protocolHandshakeEnabled();
        this.offerTimeoutNs = config.offerTimeout().toNanos();
        this.protocolHandshakeTimeoutNs = config.protocolHandshakeTimeout().toNanos();
        this.maxMessageSize = config.maxMessageSize();
        this.protocolVersion = config.protocolVersion();
        this.protocolCapabilities = config.protocolCapabilities();
        this.requiredRemoteCapabilities = config.requiredRemoteCapabilities();
        this.listeners = config.listeners();
        this.singleListener = listeners.length == 1 ? listeners[0] : null;
        this.hasListeners = listeners.length != 0;
        this.callEventListeners = hasListenerOverride("onCallStarted", int.class, long.class)
                                  || hasListenerOverride("onCallSucceeded", int.class, long.class)
                                  || hasListenerOverride("onCallTimedOut", int.class, long.class, long.class)
                                  || hasListenerOverride("onCallFailed", int.class, long.class, RpcException.class);
        this.remoteErrorListeners = hasListenerOverride("onRemoteError", int.class, long.class, RpcException.class);
        this.channelStateListeners = hasListenerOverride("onChannelUp") || hasListenerOverride("onChannelDown");
        this.drainStateListeners = hasListenerOverride("onDrainStarted") || hasListenerOverride("onDrainFinished");
        this.reconnectStateListeners = hasListenerOverride("onReconnectAttempt", ReconnectStrategy.class)
                                       || hasListenerOverride("onReconnectSucceeded", ReconnectStrategy.class)
                                       || hasListenerOverride("onReconnectFailed", ReconnectStrategy.class, Throwable.class);
        this.protocolHandshakeListeners = hasListenerOverride("onProtocolHandshakeStarted")
                                          || hasListenerOverride("onProtocolHandshakeSucceeded", int.class, long.class)
                                          || hasListenerOverride("onProtocolHandshakeFailed", RpcException.class);
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
        this.outboundChannel = outbound.build();
        this.publication = aeron.addPublication(outboundChannel, config.streamId());
        this.steadyPublication = this.publication;
        this.inboundChannel = new ChannelUriStringBuilder()
                .media("udp")
                .endpoint(config.localEndpoint())
                .reliable(true)
                .build();
        this.subscription = aeron.addSubscription(inboundChannel, config.streamId());
        this.steadySubscription = this.subscription;

        // Pools
        this.pendingPool = new PendingCallPool(config.pendingPoolCapacity());
        this.pendingRegistry = new PendingCallRegistry(config.registryInitialCapacity());
        final int staging = Math.min(maxMessageSize, 4096);
        this.txStaging = ThreadLocal.withInitial(() -> new UnsafeBuffer(ByteBuffer.allocateDirect(staging)));
        this.offloadTaskPool = new OffloadTask.Pool(config.offloadTaskPoolSize());
        this.offloadCopyBufferSize = config.offloadCopyBufferSize();
        this.offloadCopyPool = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < config.offloadCopyPoolSize(); i++) {
            offloadCopyPool.offer(new UnsafeBuffer(ByteBuffer.allocateDirect(offloadCopyBufferSize)));
        }

        // Rx
        this.rxAssembler = new FragmentAssembler(this::dispatch);
        if (receivePoller == null) {
            this.rxThread = new Thread(this::rxLoop, "rpc-rx-" + config.localEndpoint() + "-s" + config.streamId());
            this.rxThread.setDaemon(false);
        } else {
            this.rxThread = null;
        }

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

    /**
     * Регистрирует raw-обработчик для указанного типа запроса.
     *
     * <p>Registers a raw handler for the specified request message type.</p>
     *
     * @param requestMessageTypeId  тип входящего запроса / incoming request message type
     * @param responseMessageTypeId тип исходящего ответа / outgoing response message type
     * @param handler               raw-обработчик запроса / raw request handler
     */
    public void onRaw(
            final int requestMessageTypeId,
            final int responseMessageTypeId,
            final RawRequestHandler handler
    ) {
        validateUserMessageTypeId(requestMessageTypeId);
        validateUserMessageTypeId(responseMessageTypeId);
        ensureNotStarted();
        if (handlers.put(requestMessageTypeId, new RawEntry(responseMessageTypeId, handler)) != null) {
            throw new IllegalStateException("handler already registered for messageTypeId=" + requestMessageTypeId);
        }
    }

    /**
     * Регистрирует high-level обработчик с кодеками запроса и ответа.
     *
     * <p>Registers a high-level handler with request and response codecs.</p>
     *
     * @param <Req>                 тип объекта запроса / request object type
     * @param <Resp>                тип объекта ответа / response object type
     * @param requestMessageTypeId  тип входящего запроса / incoming request message type
     * @param responseMessageTypeId тип исходящего ответа / outgoing response message type
     * @param reqCodec              кодек запроса / request codec
     * @param respCodec             кодек ответа / response codec
     * @param handler               обработчик запроса / request handler
     */
    public <Req, Resp> void onRequest(
            final int requestMessageTypeId,
            final int responseMessageTypeId,
            final MessageCodec<Req> reqCodec,
            final MessageCodec<Resp> respCodec,
            final RequestHandler<Req, Resp> handler
    ) {
        validateUserMessageTypeId(requestMessageTypeId);
        validateUserMessageTypeId(responseMessageTypeId);
        ensureNotStarted();
        if (handlers.put(requestMessageTypeId, new HighLevelEntry(reqCodec, respCodec, responseMessageTypeId, handler)) != null) {
            throw new IllegalStateException("handler already registered for messageTypeId=" + requestMessageTypeId);
        }
    }

    // ================================================================
    //                             lifecycle
    // ================================================================

    /**
     * Запускает rx-поток канала и heartbeat.
     *
     * <p>Starts the channel receive thread and heartbeat manager.</p>
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("already started");
        }
        draining = false;
        protocolHandshakeComplete = false;
        remoteProtocolVersion = 0;
        remoteProtocolCapabilities = 0L;
        if (receivePoller == null) {
            rxThread.start();
        } else {
            receivePoller.register(this);
        }
        heartbeat.start();
    }

    /**
     * Закрывает канал, завершает ожидающие вызовы и освобождает Aeron-ресурсы.
     *
     * <p>Closes the channel, completes pending calls, and releases Aeron resources.</p>
     */
    @Override
    public void close() {
        running.set(false);
        draining = true;
        if (receivePoller != null) {
            receivePoller.unregister(this);
        }
        heartbeat.close();
        if (rxThread != null) {
            try {
                rxThread.join(2000);
            } catch (final InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        pendingRegistry.forEachAndClear(pc -> {
            pc.completeFail("channel closed");
            pendingPool.release(pc);
        });
        synchronized (transportLock) {
            CloseHelper.closeAll(subscription, publication);
        }
    }

    /**
     * Enables drain mode for this channel.
     *
     * <p>While draining, new outgoing calls are rejected locally and new
     * incoming user requests are rejected with a structured remote
     * {@link RpcStatus#SERVICE_UNAVAILABLE} error. In-flight work is allowed
     * to finish.</p>
     */
    public void beginDrain() {
        if (!draining) {
            synchronized (drainLock) {
                if (!draining) {
                    draining = true;
                    notifyDrainStarted();
                }
            }
        }
    }

    /**
     * Returns whether the channel is currently in drain mode.
     *
     * @return {@code true} if drain mode is active
     */
    public boolean isDraining() {
        return draining;
    }

    /**
     * Waits until the channel has no in-flight client calls and no in-flight
     * server handlers.
     *
     * @param timeout timeout value
     * @param unit    timeout unit
     * @return {@code true} if the channel drained in time
     */
    public boolean awaitDrained(final long timeout, final TimeUnit unit) {
        final long deadline = System.nanoTime() + unit.toNanos(timeout);
        final IdleStrategy idle = txIdleTl.get();
        idle.reset();
        while (System.nanoTime() < deadline) {
            if (pendingRegistry.size() == 0 && inFlightHandlers.get() == 0) {
                notifyDrainFinished();
                return true;
            }
            idle.idle();
        }
        return pendingRegistry.size() == 0 && inFlightHandlers.get() == 0;
    }

    /**
     * Starts drain mode, waits for in-flight work to finish, and closes the
     * channel.
     *
     * @param timeout timeout value
     * @param unit    timeout unit
     * @return {@code true} if the channel drained before close
     */
    public boolean closeGracefully(final long timeout, final TimeUnit unit) {
        beginDrain();
        final boolean drained = awaitDrained(timeout, unit);
        close();
        return drained;
    }

    /**
     * Проверяет, подключен ли канал с точки зрения Aeron publication и heartbeat.
     *
     * <p>Checks whether the channel is connected according to both Aeron publication
     * state and heartbeat state.</p>
     *
     * @return {@code true}, если канал считается подключенным /
     * {@code true} if the channel is considered connected
     */
    public boolean isConnected() {
        return currentPublication().isConnected() && heartbeat.isConnected();
    }

    /**
     * Returns whether the optional protocol handshake has already completed.
     *
     * @return {@code true} if protocol compatibility is already established
     */
    public boolean isProtocolHandshakeComplete() {
        return protocolHandshakeComplete;
    }

    /**
     * Returns the negotiated remote protocol version, or {@code 0} when the
     * optional handshake has not completed yet.
     *
     * @return remote protocol version
     */
    public int remoteProtocolVersion() {
        return remoteProtocolVersion;
    }

    /**
     * Returns the negotiated remote capability bitmask, or {@code 0} when the
     * optional handshake has not completed yet.
     *
     * @return remote protocol capabilities
     */
    public long remoteProtocolCapabilities() {
        return remoteProtocolCapabilities;
    }

    /**
     * Explicitly triggers reconnect handling according to the configured
     * {@link ReconnectStrategy}.
     *
     * <p>For {@link ReconnectStrategy#WAIT_FOR_CONNECTION} this waits for the
     * current path to reconnect. For
     * {@link ReconnectStrategy#RECREATE_ON_DISCONNECT} this rebuilds the local
     * transport resources first and then waits for the channel to come back.</p>
     *
     * @param timeout timeout value
     * @param unit    timeout unit
     * @return {@code true} if the channel is connected after the reconnect attempt
     */
    public boolean reconnectNow(final long timeout, final TimeUnit unit) {
        if (recreateTransportOnDisconnect) {
            attemptTransportReconnect();
        }
        return awaitConnected(unit.toNanos(timeout));
    }

    // ================================================================
    //                          client API: call()
    // ================================================================

    /**
     * Выполняет синхронный RPC-вызов с таймаутом и backpressure-политикой из конфигурации.
     *
     * <p>Performs a synchronous RPC call using timeout and backpressure policy from
     * the channel configuration.</p>
     *
     * @param <Req>                  тип объекта запроса / request object type
     * @param <Resp>                 тип объекта ответа / response object type
     * @param requestMessageTypeId   тип отправляемого запроса / outgoing request message type
     * @param expectedResponseTypeId ожидаемый тип ответа / expected response message type
     * @param request                объект запроса / request object
     * @param reqCodec               кодек запроса / request codec
     * @param respCodec              кодек ответа / response codec
     * @return декодированный объект ответа / decoded response object
     */
    public <Req, Resp> Resp call(
            final int requestMessageTypeId,
            final int expectedResponseTypeId,
            final Req request,
            final MessageCodec<Req> reqCodec,
            final MessageCodec<Resp> respCodec
    ) {
        return call(requestMessageTypeId, expectedResponseTypeId, request, reqCodec, respCodec,
                config.defaultTimeout().toNanos(), config.backpressurePolicy());
    }

    /**
     * Выполняет синхронный RPC-вызов с явным таймаутом.
     *
     * <p>Performs a synchronous RPC call with an explicit timeout.</p>
     *
     * @param <Req>                  тип объекта запроса / request object type
     * @param <Resp>                 тип объекта ответа / response object type
     * @param requestMessageTypeId   тип отправляемого запроса / outgoing request message type
     * @param expectedResponseTypeId ожидаемый тип ответа / expected response message type
     * @param request                объект запроса / request object
     * @param reqCodec               кодек запроса / request codec
     * @param respCodec              кодек ответа / response codec
     * @param timeout                значение таймаута / timeout value
     * @param unit                   единица измерения таймаута / timeout unit
     * @return декодированный объект ответа / decoded response object
     */
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

    /**
     * Выполняет синхронный RPC-вызов с явным таймаутом в наносекундах и политикой backpressure.
     *
     * <p>Performs a synchronous RPC call with an explicit timeout in nanoseconds
     * and an explicit backpressure policy.</p>
     *
     * @param <Req>                  тип объекта запроса / request object type
     * @param <Resp>                 тип объекта ответа / response object type
     * @param requestMessageTypeId   тип отправляемого запроса / outgoing request message type
     * @param expectedResponseTypeId ожидаемый тип ответа / expected response message type
     * @param request                объект запроса / request object
     * @param reqCodec               кодек запроса / request codec
     * @param respCodec              кодек ответа / response codec
     * @param timeoutNs              таймаут ожидания ответа в наносекундах / response timeout in nanoseconds
     * @param policy                 политика обработки backpressure / backpressure policy
     * @return декодированный объект ответа / decoded response object
     */
    public <Req, Resp> Resp call(
            final int requestMessageTypeId,
            final int expectedResponseTypeId,
            final Req request,
            final MessageCodec<Req> reqCodec,
            final MessageCodec<Resp> respCodec,
            final long timeoutNs,
            final BackpressurePolicy policy
    ) {
        return callInternal(
                requestMessageTypeId,
                expectedResponseTypeId,
                request,
                reqCodec,
                respCodec,
                timeoutNs,
                policy,
                true,
                true);
    }

    private <Req, Resp> Resp callInternal(
            final int requestMessageTypeId,
            final int expectedResponseTypeId,
            final Req request,
            final MessageCodec<Req> reqCodec,
            final MessageCodec<Resp> respCodec,
            final long timeoutNs,
            final BackpressurePolicy policy,
            final boolean validateTypes,
            final boolean ensureProtocolHandshake
    ) {
        if (validateTypes) {
            validateUserMessageTypeId(requestMessageTypeId);
            validateUserMessageTypeId(expectedResponseTypeId);
        }
        if (draining) {
            throw new RpcException("channel is draining");
        }
        if (ensureProtocolHandshake && protocolHandshakeEnabled && !protocolHandshakeComplete) {
            ensureProtocolHandshake(timeoutNs);
        }
        if (!awaitConnected(timeoutNs)) {
            throw new NotConnectedException("channel not connected: " + config.remoteEndpoint());
        }
        final PendingCall call = pendingPool.acquire();
        final long correlationId = correlations.next();
        call.prepare(Thread.currentThread(), correlationId, expectedResponseTypeId);
        if (callEventListeners) {
            notifyCallStarted(requestMessageTypeId, correlationId);
        }
        pendingRegistry.register(correlationId, call);
        try {
            // 1. Encode в staging.
            UnsafeBuffer staging = txStaging.get();
            int payloadLen;
            try {
                payloadLen = reqCodec.encode(request, staging, Envelope.LENGTH);
            } catch (final IndexOutOfBoundsException ex) {
                staging = ensureMaxSizedTxStaging();
                payloadLen = reqCodec.encode(request, staging, Envelope.LENGTH);
            }
            final int totalLen = Envelope.LENGTH + payloadLen;
            if (totalLen > maxMessageSize) {
                throw new PayloadTooLargeException(totalLen, maxMessageSize);
            }
            if (totalLen > staging.capacity()) {
                throw new RpcException(
                        "Encoded request does not fit staging buffer (totalLen=" + totalLen +
                        ", capacity=" + staging.capacity() + "). Enlarge via maxMessageSize or use smaller payload.");
            }
            EnvelopeCodec.encode(staging, 0, requestMessageTypeId, correlationId, Envelope.FLAG_IS_REQUEST, payloadLen);
            // 2. Send direct. tryClaim fast-path + offer fallback.
            publishBytes(staging, 0, totalLen, policy);
            // 3. Await reply.
            final boolean ok = waiter.await(call, timeoutNs);
            if (!ok) {
                final RpcTimeoutException timeout = new RpcTimeoutException(correlationId, timeoutNs);
                if (callEventListeners) {
                    notifyCallTimedOut(requestMessageTypeId, correlationId, timeoutNs);
                }
                throw timeout;
            }
            if (call.isFailed()) {
                if (callEventListeners) {
                    notifyCallFailed(requestMessageTypeId, correlationId, call.failure());
                }
                throw call.failure();
            }
            final Resp response = respCodec.decode(call.responseBuffer(), 0, call.responseLength());
            if (callEventListeners) {
                notifyCallSucceeded(requestMessageTypeId, correlationId);
            }
            return response;
        } finally {
            if (!call.isCompleted()) {
                pendingRegistry.remove(correlationId);
            }
            pendingPool.release(call);
        }
    }

    private void ensureProtocolHandshake(final long timeoutNs) {
        if (!protocolHandshakeEnabled || protocolHandshakeComplete) {
            return;
        }
        synchronized (handshakeLock) {
            if (!protocolHandshakeEnabled || protocolHandshakeComplete) {
                return;
            }
            notifyProtocolHandshakeStarted();
            try {
                final ProtocolInfo remote = callInternal(
                        Envelope.RESERVED_PROTOCOL_HANDSHAKE,
                        Envelope.RESERVED_PROTOCOL_HANDSHAKE,
                        new ProtocolInfo(protocolVersion, protocolCapabilities),
                        PROTOCOL_INFO_CODEC,
                        PROTOCOL_INFO_CODEC,
                        Math.min(timeoutNs, protocolHandshakeTimeoutNs),
                        BackpressurePolicy.BLOCK,
                        false,
                        false);
                if (remote.version() != protocolVersion) {
                    throw new ProtocolMismatchException(
                            "Protocol version mismatch: local=" + protocolVersion + ", remote=" + remote.version());
                }
                if ((remote.capabilities() & requiredRemoteCapabilities) != requiredRemoteCapabilities) {
                    throw new ProtocolMismatchException(
                            "Remote capabilities mismatch: required=" + requiredRemoteCapabilities
                            + ", remote=" + remote.capabilities());
                }
                remoteProtocolVersion = remote.version();
                remoteProtocolCapabilities = remote.capabilities();
                protocolHandshakeComplete = true;
                notifyProtocolHandshakeSucceeded(remote.version(), remote.capabilities());
            } catch (final RpcException exception) {
                notifyProtocolHandshakeFailed(exception);
                throw exception;
            }
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
    private void publishBytes(
            final DirectBuffer src,
            final int offset,
            final int length,
            final BackpressurePolicy policy
    ) {
        final long deadline = System.nanoTime() + offerTimeoutNs;
        final IdleStrategy idle = txIdleTl.get();
        idle.reset();
        final ConcurrentPublication publication = currentPublication();
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
    private void handlePublishError(
            final long result,
            final BackpressurePolicy policy,
            final long deadline,
            final IdleStrategy idle
    ) {
        if (result == Publication.NOT_CONNECTED) {
            if (reconnectStrategy != ReconnectStrategy.FAIL_FAST
                && System.nanoTime() < deadline
                && waitPublicationConnected(deadline, idle)) {
                return;
            }
            throw new NotConnectedException("publication not connected");
        }
        if (result == Publication.CLOSED) {
            throw new RpcException("publication is closed");
        }
        if (result == Publication.MAX_POSITION_EXCEEDED) {
            throw new RpcException("publication max position exceeded");
        }
        if (result == Publication.ADMIN_ACTION || result == Publication.BACK_PRESSURED) {
            if (policy == BackpressurePolicy.FAIL_FAST) {
                throw new BackpressureException("publication back-pressured (FAIL_FAST)");
            }
            if (System.nanoTime() >= deadline) {
                throw new BackpressureException("publication back-pressured beyond offerTimeout (result=" + result + ")");
            }
            idle.idle();
            return;
        }
        // unknown — retry with idle
        if (System.nanoTime() >= deadline) {
            throw new RpcException("publication returned unexpected code=" + result);
        }
        idle.idle();
    }

    // ================================================================
    //                               RX
    // ================================================================
    private void rxLoop() {
        final IdleStrategy idle = createIdleStrategy(config.rxIdleStrategy());
        while (running.get()) {
            final int fragments = pollRx(16);
            idle.idle(fragments);
        }
    }

    /**
     * Polls the channel subscription once.
     *
     * <p>This method is intentionally package-private so a node-level
     * {@link SharedReceivePoller} can drive many channels without exposing the
     * polling API publicly.</p>
     *
     * @param fragmentLimit fragment limit for this poll pass
     * @return number of fragments processed
     */
    int pollRx(final int fragmentLimit) {
        if (!running.get()) {
            return 0;
        }
        return currentSubscription().poll(rxAssembler, fragmentLimit);
    }

    /**
     * Returns the receive idle strategy configured for this channel.
     *
     * <p>Used by the shared receive poller to assign the channel to a poller
     * lane group with matching idle behavior.</p>
     *
     * @return configured receive idle strategy kind
     */
    IdleStrategyKind rxIdleStrategyKind() {
        return config.rxIdleStrategy();
    }

    private static IdleStrategy createIdleStrategy(final IdleStrategyKind kind) {
        return switch (kind) {
            case BUSY_SPIN -> new BusySpinIdleStrategy();
            case BACKOFF -> new BackoffIdleStrategy(
                    100, 10,
                    TimeUnit.NANOSECONDS.toNanos(1),
                    TimeUnit.MILLISECONDS.toNanos(1));
            default -> new YieldingIdleStrategy();
        };
    }

    /**
     * Waits for the channel to become connected according to both the Aeron
     * publication and the heartbeat state.
     *
     * <p>With {@link ReconnectStrategy#FAIL_FAST} this returns immediately when
     * the channel is disconnected. With
     * {@link ReconnectStrategy#WAIT_FOR_CONNECTION} it spins/yields until the
     * channel reconnects or the timeout expires.</p>
     *
     * @param timeoutNs maximum time to wait in nanoseconds
     * @return {@code true} if the channel becomes connected in time
     */
    private boolean awaitConnected(final long timeoutNs) {
        if (isConnected()) {
            return true;
        }
        if (reconnectStrategy == ReconnectStrategy.FAIL_FAST) {
            return false;
        }
        final long deadline = System.nanoTime() + timeoutNs;
        final IdleStrategy idle = txIdleTl.get();
        idle.reset();
        while (running.get() && System.nanoTime() < deadline) {
            if (isConnected()) {
                return true;
            }
            if (recreateTransportOnDisconnect && !currentPublication().isConnected()) {
                attemptTransportReconnect();
            }
            idle.idle();
        }
        return isConnected();
    }

    /**
     * Waits only for the Aeron publication to report a connected state.
     *
     * <p>This is used on the publish path after
     * {@link Publication#NOT_CONNECTED} is returned. It is intentionally
     * narrower than {@link #awaitConnected(long)} because it is retrying a
     * concrete publication operation that has already entered the send path.</p>
     *
     * @param deadline absolute deadline in {@link System#nanoTime()} units
     * @param idle     idle strategy used while waiting
     * @return {@code true} if the publication reconnects before the deadline
     */
    private boolean waitPublicationConnected(final long deadline, final IdleStrategy idle) {
        while (running.get() && System.nanoTime() < deadline) {
            if (currentPublication().isConnected()) {
                return true;
            }
            if (recreateTransportOnDisconnect) {
                attemptTransportReconnect();
            }
            idle.idle();
        }
        return currentPublication().isConnected();
    }

    private void attemptTransportReconnect() {
        if (!recreateTransportOnDisconnect || !running.get()) {
            return;
        }
        notifyReconnectAttempt();
        try {
            recreateTransport();
            notifyReconnectSucceeded();
        } catch (final Throwable t) {
            notifyReconnectFailed(t);
        }
    }

    private void recreateTransport() {
        synchronized (transportLock) {
            if (!running.get()) {
                return;
            }
            final Subscription oldSubscription = subscription;
            final ConcurrentPublication oldPublication = publication;
            final ConcurrentPublication newPublication = aeron.addPublication(outboundChannel, config.streamId());
            final Subscription newSubscription = aeron.addSubscription(inboundChannel, config.streamId());
            publication = newPublication;
            subscription = newSubscription;
            CloseHelper.closeAll(oldSubscription, oldPublication);
            protocolHandshakeComplete = false;
        }
    }

    private ConcurrentPublication currentPublication() {
        return recreateTransportOnDisconnect ? publication : steadyPublication;
    }

    private Subscription currentSubscription() {
        return recreateTransportOnDisconnect ? subscription : steadySubscription;
    }

    private void dispatch(
            final DirectBuffer buffer,
            final int offset,
            final int length,
            final Header header
    ) {
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
                    handleResponse(msgTypeId, correlationId, flags, buffer, payloadOffset, payloadLen);
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
        if (messageTypeId == Envelope.RESERVED_PROTOCOL_HANDSHAKE) {
            respondToProtocolHandshake(correlationId);
            return;
        }
        final HandlerEntry entry = handlers.get(messageTypeId);
        if (entry == null) return;
        if (draining) {
            tryPublishDrainError(entry, correlationId);
            return;
        }
        inFlightHandlers.incrementAndGet();
        if (directExecutor) {
            // Exec в rx-треде, без копирования и offload. Минимум latency.
            try {
                invokeHandler(entry, messageTypeId, correlationId, buffer, payloadOffset, payloadLen);
            } finally {
                inFlightHandlers.decrementAndGet();
            }
            return;
        }
        // OFFLOAD: копируем payload (чтобы rx-буфер не пропал) и сабмитим.
        if (payloadLen > offloadCopyBufferSize) {
            // Защита: payload не влезает в offload-буфер. Exec inline
            // (потеряем latency rx но не сломаемся). В проде это worth warning.
            try {
                invokeHandler(entry, messageTypeId, correlationId, buffer, payloadOffset, payloadLen);
            } finally {
                inFlightHandlers.decrementAndGet();
            }
            return;
        }
        final UnsafeBuffer copy = acquireCopy();
        copy.putBytes(0, buffer, payloadOffset, payloadLen);
        final OffloadTask task = offloadTaskPool.acquire();
        task.init(offloadBody, messageTypeId, correlationId, copy, payloadLen, entry, offloadTaskPool);
        try {
            offloadExecutor.execute(task);
        } catch (final RuntimeException e) {
            inFlightHandlers.decrementAndGet();
            releaseCopy(copy);
            throw e;
        }
    }

    /**
     * Body для OffloadTask (выполняется в executor-е).
     */
    private void runOffload(
            final int messageTypeId,
            final long correlationId,
            final UnsafeBuffer copy,
            final int length,
            final Object entryObj
    ) {
        try {
            invokeHandler((HandlerEntry) entryObj, messageTypeId, correlationId, copy, 0, length);
        } finally {
            inFlightHandlers.decrementAndGet();
            releaseCopy(copy);
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
            if (entry instanceof RawEntry raw) invokeRaw(raw, correlationId, buffer, payloadOffset, payloadLen);
            else invokeHighLevel((HighLevelEntry) entry, correlationId, buffer, payloadOffset, payloadLen);
        } catch (final Throwable t) {
            LOGGER.log(Level.WARNING, "RPC handler failed", t);
            tryPublishError(entry, correlationId, t);
        }
    }

    /**
     * Invokes a raw request handler and publishes its response.
     *
     * <p>The raw handler writes response bytes directly into the channel's
     * thread-local staging buffer. This keeps the raw path allocation-free on
     * the hot path, while still preserving per-thread buffer isolation for
     * both dedicated RX threads and offloaded handler threads.</p>
     *
     * @param entry         handler entry with raw request logic
     * @param correlationId transport correlation id of the request
     * @param buffer        source buffer containing the request payload
     * @param payloadOffset payload start offset inside {@code buffer}
     * @param payloadLen    payload length in bytes
     */
    private void invokeRaw(
            final RawEntry entry,
            final long correlationId,
            final DirectBuffer buffer,
            final int payloadOffset,
            final int payloadLen
    ) {
        // Serverside TX: staging из ThreadLocal (для handler-а который в
        // rx-треде или в offload-треде — всё равно каждому свой).
        UnsafeBuffer staging = txStaging.get();
        final int responseOffset = Envelope.LENGTH;
        final int responseCapacity = staging.capacity() - responseOffset;
        final int written = entry.handler.handle(buffer, payloadOffset, payloadLen, staging, responseOffset, responseCapacity);
        if (written <= 0) return;
        if (written > responseCapacity) {
            throw new PayloadTooLargeException(Envelope.LENGTH + written, maxMessageSize);
        }
        EnvelopeCodec.encode(staging, 0, entry.responseMessageTypeId, correlationId, 0, written);
        publishBytes(staging, 0, Envelope.LENGTH + written, BackpressurePolicy.BLOCK);
    }

    /**
     * Invokes a high-level typed handler and publishes its encoded response.
     *
     * <p>If the current thread-local staging buffer is too small for the
     * encoded response, the buffer is expanded lazily up to
     * {@link ChannelConfig#maxMessageSize()} for this thread and the encode is
     * retried.</p>
     *
     * @param entry         typed handler entry
     * @param correlationId transport correlation id of the request
     * @param buffer        source buffer containing the request payload
     * @param payloadOffset payload start offset inside {@code buffer}
     * @param payloadLen    payload length in bytes
     */
    private void invokeHighLevel(
            final HighLevelEntry entry,
            final long correlationId,
            final DirectBuffer buffer,
            final int payloadOffset,
            final int payloadLen
    ) {
        final Object req = entry.reqCodec.decode(buffer, payloadOffset, payloadLen);
        final Object resp = entry.handler.handle(req);
        if (resp == null) return;
        UnsafeBuffer staging = txStaging.get();
        int respLen;
        try {
            respLen = entry.respCodec.encode(resp, staging, Envelope.LENGTH);
        } catch (final IndexOutOfBoundsException ex) {
            staging = ensureMaxSizedTxStaging();
            respLen = entry.respCodec.encode(resp, staging, Envelope.LENGTH);
        }
        final int totalLen = Envelope.LENGTH + respLen;
        if (totalLen > maxMessageSize) {
            throw new PayloadTooLargeException(totalLen, maxMessageSize);
        }
        EnvelopeCodec.encode(staging, 0, entry.responseMessageTypeId, correlationId, 0, respLen);
        publishBytes(staging, 0, totalLen, BackpressurePolicy.BLOCK);
    }

    /**
     * Completes a pending synchronous call when a matching response arrives.
     *
     * <p>If the correlation id is unknown, the response is ignored. This can
     * happen for late packets that arrive after a timeout or after the pending
     * entry has already been completed and removed.</p>
     *
     * @param correlationId response correlation id
     * @param buffer        source buffer containing the response payload
     * @param payloadOffset payload start offset inside {@code buffer}
     * @param payloadLen    payload length in bytes
     */
    private void handleResponse(
            final int messageTypeId,
            final long correlationId,
            final int flags,
            final DirectBuffer buffer,
            final int payloadOffset,
            final int payloadLen
    ) {
        final PendingCall call = pendingRegistry.remove(correlationId);
        if (call == null) return;
        if (messageTypeId != call.expectedResponseTypeId()) {
            call.completeFail(new RpcException(
                    "Unexpected response messageTypeId: expected " + call.expectedResponseTypeId() + ", got " + messageTypeId));
            return;
        }
        if (EnvelopeCodec.isError(flags)) {
            final RemoteRpcException error = decodeRemoteError(buffer, payloadOffset, payloadLen);
            notifyRemoteError(messageTypeId, correlationId, error);
            call.completeFail(error);
            return;
        }
        call.completeOk(buffer, payloadOffset, payloadLen);
    }

    // ================================================================
    //                          heartbeat + copy pool
    // ================================================================

    /**
     * Emits one heartbeat frame for this channel.
     *
     * <p>Heartbeat publication is intentionally {@link BackpressurePolicy#FAIL_FAST}:
     * if the publication is temporarily busy, the current heartbeat tick is
     * skipped and a later tick will refresh liveness.</p>
     *
     * @param nowNanos current time supplied by the heartbeat manager
     */
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

    /**
     * Fails all currently pending calls after heartbeat-based liveness loss.
     */
    private void onChannelDown() {
        LOGGER.warning("RPC channel DOWN -> failfast " + pendingRegistry.size() + " pending");
        notifyChannelDown();
        pendingRegistry.forEachAndClear(pc -> {
            pc.completeFail("channel DOWN (heartbeat missed)");
            pendingPool.release(pc);
        });
    }

    /**
     * Called when the heartbeat manager observes that the channel is alive
     * again.
     */
    private void onChannelUp() {
        LOGGER.info("RPC channel UP");
        notifyChannelUp();
    }

    /**
     * Acquires a buffer used to copy request payload bytes for offloaded
     * handler execution.
     *
     * <p>If the pool is empty, a new direct buffer is allocated.</p>
     *
     * @return pooled or newly allocated copy buffer
     */
    private UnsafeBuffer acquireCopy() {
        final UnsafeBuffer b = offloadCopyPool.poll();
        return b != null ? b : new UnsafeBuffer(ByteBuffer.allocateDirect(offloadCopyBufferSize));
    }

    /**
     * Ensures that the current thread has a staging buffer large enough for
     * the configured channel maximum message size.
     *
     * <p>This is the slow path for large messages. Small-message callers keep
     * using the original compact thread-local staging buffer.</p>
     *
     * @return thread-local staging buffer with capacity at least
     * {@link ChannelConfig#maxMessageSize()}
     */
    private UnsafeBuffer ensureMaxSizedTxStaging() {
        UnsafeBuffer staging = txStaging.get();
        if (staging.capacity() >= maxMessageSize) {
            return staging;
        }
        staging = new UnsafeBuffer(ByteBuffer.allocateDirect(maxMessageSize));
        txStaging.set(staging);
        return staging;
    }

    /**
     * Returns an offload copy buffer back to the pool.
     *
     * @param b buffer to release
     */
    private void releaseCopy(final UnsafeBuffer b) {
        offloadCopyPool.offer(b);
    }

    private void tryPublishError(final HandlerEntry entry, final long correlationId, final Throwable failure) {
        final RpcException error = mapRemoteError(failure);
        try {
            final UnsafeBuffer staging = txStaging.get();
            final int payloadLen = encodeRemoteError(staging, Envelope.LENGTH, error);
            final int responseMessageTypeId = responseMessageTypeId(entry);
            EnvelopeCodec.encode(staging, 0, responseMessageTypeId, correlationId, Envelope.FLAG_IS_ERROR, payloadLen);
            publishBytes(staging, 0, Envelope.LENGTH + payloadLen, BackpressurePolicy.BLOCK);
        } catch (final Throwable publishFailure) {
            LOGGER.log(Level.WARNING, "RPC error response publish failed", publishFailure);
        }
    }

    private static int responseMessageTypeId(final HandlerEntry entry) {
        if (entry instanceof RawEntry raw) {
            return raw.responseMessageTypeId;
        }
        return ((HighLevelEntry) entry).responseMessageTypeId;
    }

    private static RpcException mapRemoteError(final Throwable failure) {
        if (failure instanceof RpcApplicationException applicationException) {
            return applicationException;
        }
        if (failure instanceof PayloadTooLargeException payloadTooLargeException) {
            return new RpcApplicationException(RpcStatus.PAYLOAD_TOO_LARGE, payloadTooLargeException.getMessage(), payloadTooLargeException);
        }
        if (failure instanceof IllegalArgumentException illegalArgumentException) {
            return new RpcApplicationException(RpcStatus.BAD_REQUEST, illegalArgumentException.getMessage(), illegalArgumentException);
        }
        if (failure instanceof UnsupportedOperationException unsupportedOperationException) {
            return new RpcApplicationException(RpcStatus.NOT_IMPLEMENTED, unsupportedOperationException.getMessage(), unsupportedOperationException);
        }
        return new RpcApplicationException(RpcStatus.INTERNAL_SERVER_ERROR, failure.toString(), failure);
    }

    private static int encodeRemoteError(final MutableDirectBuffer buffer, final int offset, final RpcException error) {
        final int statusCode = error instanceof RpcApplicationException applicationException
                ? applicationException.statusCode()
                : RpcStatus.INTERNAL_SERVER_ERROR.code();
        final byte[] bytes = error.getMessage().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        buffer.putInt(offset, statusCode, java.nio.ByteOrder.LITTLE_ENDIAN);
        buffer.putBytes(offset + ERROR_STATUS_CODE_SIZE, bytes);
        return ERROR_STATUS_CODE_SIZE + bytes.length;
    }

    private static RemoteRpcException decodeRemoteError(final DirectBuffer buffer, final int offset, final int length) {
        if (length < ERROR_STATUS_CODE_SIZE) {
            return new RemoteRpcException(RpcStatus.INTERNAL_SERVER_ERROR.code(), "Remote RPC failed without valid error payload");
        }
        final int statusCode = buffer.getInt(offset, java.nio.ByteOrder.LITTLE_ENDIAN);
        final int messageLength = length - ERROR_STATUS_CODE_SIZE;
        final byte[] bytes = new byte[Math.max(0, messageLength)];
        if (messageLength > 0) {
            buffer.getBytes(offset + ERROR_STATUS_CODE_SIZE, bytes);
        }
        final String message = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        return new RemoteRpcException(statusCode, message.isEmpty() ? "remote error" : message);
    }

    private void respondToProtocolHandshake(final long correlationId) {
        final UnsafeBuffer staging = txStaging.get();
        final int payloadLen = PROTOCOL_INFO_CODEC.encode(
                new ProtocolInfo(protocolVersion, protocolCapabilities),
                staging,
                Envelope.LENGTH);
        EnvelopeCodec.encode(staging, 0, Envelope.RESERVED_PROTOCOL_HANDSHAKE, correlationId, 0, payloadLen);
        publishBytes(staging, 0, Envelope.LENGTH + payloadLen, BackpressurePolicy.BLOCK);
    }

    private void tryPublishDrainError(final HandlerEntry entry, final long correlationId) {
        try {
            final UnsafeBuffer staging = txStaging.get();
            final RpcApplicationException error = new RpcApplicationException(RpcStatus.SERVICE_UNAVAILABLE, "channel is draining");
            final int payloadLen = encodeRemoteError(staging, Envelope.LENGTH, error);
            EnvelopeCodec.encode(staging, 0, responseMessageTypeId(entry), correlationId, Envelope.FLAG_IS_ERROR, payloadLen);
            publishBytes(staging, 0, Envelope.LENGTH + payloadLen, BackpressurePolicy.BLOCK);
        } catch (final Throwable t) {
            LOGGER.log(Level.FINE, "failed to publish drain error", t);
        }
    }

    private void notifyCallStarted(final int requestMessageTypeId, final long correlationId) {
        if (!callEventListeners) return;
        if (singleListener != null) {
            singleListener.onCallStarted(this, requestMessageTypeId, correlationId);
            return;
        }
        for (final RpcChannelListener listener : listeners) {
            listener.onCallStarted(this, requestMessageTypeId, correlationId);
        }
    }

    private void notifyCallSucceeded(final int requestMessageTypeId, final long correlationId) {
        if (!callEventListeners) return;
        if (singleListener != null) {
            singleListener.onCallSucceeded(this, requestMessageTypeId, correlationId);
            return;
        }
        for (final RpcChannelListener listener : listeners) {
            listener.onCallSucceeded(this, requestMessageTypeId, correlationId);
        }
    }

    private void notifyCallTimedOut(final int requestMessageTypeId, final long correlationId, final long timeoutNs) {
        if (!callEventListeners) return;
        if (singleListener != null) {
            singleListener.onCallTimedOut(this, requestMessageTypeId, correlationId, timeoutNs);
            return;
        }
        for (final RpcChannelListener listener : listeners) {
            listener.onCallTimedOut(this, requestMessageTypeId, correlationId, timeoutNs);
        }
    }

    private void notifyCallFailed(final int requestMessageTypeId, final long correlationId, final RpcException failure) {
        if (!callEventListeners) return;
        if (singleListener != null) {
            singleListener.onCallFailed(this, requestMessageTypeId, correlationId, failure);
            return;
        }
        for (final RpcChannelListener listener : listeners) {
            listener.onCallFailed(this, requestMessageTypeId, correlationId, failure);
        }
    }

    private void notifyRemoteError(final int responseMessageTypeId, final long correlationId, final RpcException failure) {
        if (!remoteErrorListeners) return;
        if (singleListener != null) {
            singleListener.onRemoteError(this, responseMessageTypeId, correlationId, failure);
            return;
        }
        for (final RpcChannelListener listener : listeners) {
            listener.onRemoteError(this, responseMessageTypeId, correlationId, failure);
        }
    }

    private void notifyChannelUp() {
        if (!channelStateListeners) return;
        if (singleListener != null) {
            singleListener.onChannelUp(this);
            return;
        }
        for (final RpcChannelListener listener : listeners) {
            listener.onChannelUp(this);
        }
    }

    private void notifyChannelDown() {
        if (!channelStateListeners) return;
        if (singleListener != null) {
            singleListener.onChannelDown(this);
            return;
        }
        for (final RpcChannelListener listener : listeners) {
            listener.onChannelDown(this);
        }
    }

    private void notifyDrainStarted() {
        if (!drainStateListeners) return;
        if (singleListener != null) {
            singleListener.onDrainStarted(this);
            return;
        }
        for (final RpcChannelListener listener : listeners) {
            listener.onDrainStarted(this);
        }
    }

    private void notifyDrainFinished() {
        if (!drainStateListeners) return;
        if (singleListener != null) {
            singleListener.onDrainFinished(this);
            return;
        }
        for (final RpcChannelListener listener : listeners) {
            listener.onDrainFinished(this);
        }
    }

    private void notifyReconnectAttempt() {
        if (!reconnectStateListeners) return;
        if (singleListener != null) {
            singleListener.onReconnectAttempt(this, reconnectStrategy);
            return;
        }
        for (final RpcChannelListener listener : listeners) {
            listener.onReconnectAttempt(this, reconnectStrategy);
        }
    }

    private void notifyReconnectSucceeded() {
        if (!reconnectStateListeners) return;
        if (singleListener != null) {
            singleListener.onReconnectSucceeded(this, reconnectStrategy);
            return;
        }
        for (final RpcChannelListener listener : listeners) {
            listener.onReconnectSucceeded(this, reconnectStrategy);
        }
    }

    private void notifyReconnectFailed(final Throwable failure) {
        if (!reconnectStateListeners) return;
        if (singleListener != null) {
            singleListener.onReconnectFailed(this, reconnectStrategy, failure);
            return;
        }
        for (final RpcChannelListener listener : listeners) {
            listener.onReconnectFailed(this, reconnectStrategy, failure);
        }
    }

    private void notifyProtocolHandshakeStarted() {
        if (!protocolHandshakeListeners) return;
        if (singleListener != null) {
            singleListener.onProtocolHandshakeStarted(this);
            return;
        }
        for (final RpcChannelListener listener : listeners) {
            listener.onProtocolHandshakeStarted(this);
        }
    }

    private void notifyProtocolHandshakeSucceeded(final int remoteVersion, final long remoteCapabilities) {
        if (!protocolHandshakeListeners) return;
        if (singleListener != null) {
            singleListener.onProtocolHandshakeSucceeded(this, remoteVersion, remoteCapabilities);
            return;
        }
        for (final RpcChannelListener listener : listeners) {
            listener.onProtocolHandshakeSucceeded(this, remoteVersion, remoteCapabilities);
        }
    }

    private void notifyProtocolHandshakeFailed(final RpcException failure) {
        if (!protocolHandshakeListeners) return;
        if (singleListener != null) {
            singleListener.onProtocolHandshakeFailed(this, failure);
            return;
        }
        for (final RpcChannelListener listener : listeners) {
            listener.onProtocolHandshakeFailed(this, failure);
        }
    }

    /**
     * Validates that the message type id is in the user-reserved range.
     *
     * @param id message type id to validate
     */
    private void validateUserMessageTypeId(final int id) {
        if (id < MIN_USER_MESSAGE_TYPE_ID)
            throw new IllegalArgumentException("messageTypeId must be >= " + MIN_USER_MESSAGE_TYPE_ID + " (got " + id + ")");
    }

    private boolean hasListenerOverride(final String methodName, final Class<?>... parameterTypes) {
        if (!hasListeners) {
            return false;
        }
        for (final RpcChannelListener listener : listeners) {
            try {
                if (listener.getClass()
                            .getMethod(methodName, prependChannelParameter(parameterTypes))
                            .getDeclaringClass() != RpcChannelListener.class) {
                    return true;
                }
            } catch (final NoSuchMethodException e) {
                throw new IllegalStateException("Listener method lookup failed: " + methodName, e);
            }
        }
        return false;
    }

    private static Class<?>[] prependChannelParameter(final Class<?>[] parameterTypes) {
        final Class<?>[] signature = new Class<?>[parameterTypes.length + 1];
        signature[0] = RpcChannel.class;
        System.arraycopy(parameterTypes, 0, signature, 1, parameterTypes.length);
        return signature;
    }

    /**
     * Ensures handler registration happens only before the channel is started.
     */
    private void ensureNotStarted() {
        if (running.get()) throw new IllegalStateException("register handlers before start()");
    }
}