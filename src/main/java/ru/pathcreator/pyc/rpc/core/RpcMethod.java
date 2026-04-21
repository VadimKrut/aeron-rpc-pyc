package ru.pathcreator.pyc.rpc.core;

import ru.pathcreator.pyc.rpc.core.codec.MessageCodec;

import java.util.concurrent.TimeUnit;

/**
 * Опциональная typed-обёртка над низкоуровневым API {@link RpcChannel}.
 *
 * <p>Optional typed method facade on top of the low-level {@link RpcChannel}
 * API.</p>
 *
 * <p>Этот класс не меняет поведение core transport-а. Это тонкая convenience
 * wrapper-обёртка, которая оставляет снизу тот же raw путь через
 * messageTypeId и codec.</p>
 *
 * <p>This class does not change the core transport behavior. It is a thin
 * convenience wrapper that keeps the raw message-type and codec path available
 * underneath.</p>
 *
 * @param <Req>  тип запроса / request type
 * @param <Resp> тип ответа / response type
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

    /**
     * Создаёт typed-описание одного RPC метода.
     *
     * <p>Creates a typed description of one RPC method.</p>
     *
     * @param <Req>                 request type
     * @param <Resp>                response type
     * @param requestMessageTypeId  request message type id
     * @param responseMessageTypeId response message type id
     * @param requestCodec          request codec
     * @param responseCodec         response codec
     * @return typed method descriptor
     */
    public static <Req, Resp> RpcMethod<Req, Resp> of(
            final int requestMessageTypeId,
            final int responseMessageTypeId,
            final MessageCodec<Req> requestCodec,
            final MessageCodec<Resp> responseCodec
    ) {
        return new RpcMethod<>(requestMessageTypeId, responseMessageTypeId, requestCodec, responseCodec);
    }

    /**
     * Регистрирует handler этого метода на указанном канале.
     *
     * <p>Registers this method's handler on the provided channel.</p>
     *
     * @param channel channel to register on
     * @param handler typed request handler
     */
    public void register(final RpcChannel channel, final RequestHandler<Req, Resp> handler) {
        channel.onRequest(requestMessageTypeId, responseMessageTypeId, requestCodec, responseCodec, handler);
    }

    /**
     * Вызывает метод через указанный канал с default timeout канала.
     *
     * <p>Calls the method through the provided channel using the channel
     * default timeout.</p>
     *
     * @param channel channel used for the call
     * @param request request payload
     * @return decoded response
     */
    public Resp call(final RpcChannel channel, final Req request) {
        return channel.call(requestMessageTypeId, responseMessageTypeId, request, requestCodec, responseCodec);
    }

    /**
     * Вызывает метод через указанный канал с явным timeout.
     *
     * <p>Calls the method through the provided channel using an explicit
     * timeout.</p>
     *
     * @param channel channel used for the call
     * @param request request payload
     * @param timeout timeout value
     * @param unit    timeout unit
     * @return decoded response
     */
    public Resp call(final RpcChannel channel, final Req request, final long timeout, final TimeUnit unit) {
        return channel.call(requestMessageTypeId, responseMessageTypeId, request, requestCodec, responseCodec, timeout, unit);
    }

    /**
     * Возвращает messageTypeId запроса.
     *
     * <p>Returns the request message type id.</p>
     *
     * @return request message type id
     */
    public int requestMessageTypeId() {
        return requestMessageTypeId;
    }

    /**
     * Возвращает messageTypeId ответа.
     *
     * <p>Returns the response message type id.</p>
     *
     * @return response message type id
     */
    public int responseMessageTypeId() {
        return responseMessageTypeId;
    }

    /**
     * Возвращает codec запроса.
     *
     * <p>Returns the request codec.</p>
     *
     * @return request codec
     */
    public MessageCodec<Req> requestCodec() {
        return requestCodec;
    }

    /**
     * Возвращает codec ответа.
     *
     * <p>Returns the response codec.</p>
     *
     * @return response codec
     */
    public MessageCodec<Resp> responseCodec() {
        return responseCodec;
    }
}