package ru.pathcreator.pyc.rpc.core;

import ru.pathcreator.pyc.rpc.core.codec.MessageCodec;

import java.util.concurrent.TimeUnit;

/**
 * Optional typed method facade on top of the low-level {@link RpcChannel} API.
 *
 * <p>This class does not change the core transport behavior. It is a thin
 * convenience wrapper that keeps the raw message-type and codec path available
 * underneath.</p>
 *
 * @param <Req>  request type
 * @param <Resp> response type
 */
public final class RpcMethod<Req, Resp> {
    private final int requestMessageTypeId;
    private final int responseMessageTypeId;
    private final MessageCodec<Req> requestCodec;
    private final MessageCodec<Resp> responseCodec;

    private RpcMethod(
            final int requestMessageTypeId,
            final int responseMessageTypeId,
            final MessageCodec<Req> requestCodec,
            final MessageCodec<Resp> responseCodec
    ) {
        this.requestMessageTypeId = requestMessageTypeId;
        this.responseMessageTypeId = responseMessageTypeId;
        this.requestCodec = requestCodec;
        this.responseCodec = responseCodec;
    }

    public static <Req, Resp> RpcMethod<Req, Resp> of(
            final int requestMessageTypeId,
            final int responseMessageTypeId,
            final MessageCodec<Req> requestCodec,
            final MessageCodec<Resp> responseCodec
    ) {
        return new RpcMethod<>(requestMessageTypeId, responseMessageTypeId, requestCodec, responseCodec);
    }

    public void register(final RpcChannel channel, final RequestHandler<Req, Resp> handler) {
        channel.onRequest(requestMessageTypeId, responseMessageTypeId, requestCodec, responseCodec, handler);
    }

    public Resp call(final RpcChannel channel, final Req request) {
        return channel.call(requestMessageTypeId, responseMessageTypeId, request, requestCodec, responseCodec);
    }

    public Resp call(final RpcChannel channel, final Req request, final long timeout, final TimeUnit unit) {
        return channel.call(requestMessageTypeId, responseMessageTypeId, request, requestCodec, responseCodec, timeout, unit);
    }

    public int requestMessageTypeId() {
        return requestMessageTypeId;
    }

    public int responseMessageTypeId() {
        return responseMessageTypeId;
    }

    public MessageCodec<Req> requestCodec() {
        return requestCodec;
    }

    public MessageCodec<Resp> responseCodec() {
        return responseCodec;
    }
}