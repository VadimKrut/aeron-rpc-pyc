package ru.pathcreator.pyc.rpc.core;

/**
 * High-level handler, который получает уже декодированный запрос и возвращает
 * объект ответа.
 *
 * <p>High-level handler that receives a decoded request object and returns a
 * response object.</p>
 *
 * <p>Ядро само занимается encode/decode и отправкой ответа. Если handler
 * возвращает {@code null}, ответ не отправляется. Если handler выбрасывает
 * исключение, оно обрабатывается transport-слоем как structured remote error
 * или локальная ошибка, в зависимости от контекста.</p>
 *
 * <p>The core performs encode/decode and sends the response itself. If the
 * handler returns {@code null}, no response is sent. If the handler throws an
 * exception, it is handled by the transport layer as a structured remote error
 * or a local failure depending on context.</p>
 *
 * @param <Req>  тип запроса / request type
 * @param <Resp> тип ответа / response type
 */
@FunctionalInterface
public interface RequestHandler<Req, Resp> {

    /**
     * Обрабатывает запрос и возвращает ответ.
     *
     * <p>Handles a request and returns a response.</p>
     *
     * @param request объект запроса / request object
     * @return объект ответа или {@code null}, если ответ отправлять не нужно /
     * response object or {@code null} when no response should be sent
     */
    Resp handle(Req request);
}