package ru.pathcreator.pyc;

/**
 * High-level handler: получает десериализованный request, возвращает
 * response-объект. Ядро само кодирует и отправляет ответ.
 * <p>
 * Если handler бросает исключение — ядро логирует и НЕ отвечает (request
 * на клиентской стороне отвалится по таймауту).
 *
 * <p>High-level request handler that receives a decoded request object and
 * returns a response object. The RPC core encodes and sends the response.</p>
 *
 * @param <Req>  тип объекта запроса / request object type
 * @param <Resp> тип объекта ответа / response object type
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