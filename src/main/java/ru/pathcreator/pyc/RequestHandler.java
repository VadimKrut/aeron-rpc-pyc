package ru.pathcreator.pyc;

/**
 * High-level handler: получает десериализованный request, возвращает
 * response-объект. Ядро само кодирует и отправляет ответ.
 * <p>
 * Если handler бросает исключение — ядро логирует и НЕ отвечает (request
 * на клиентской стороне отвалится по таймауту).
 */
@FunctionalInterface
public interface RequestHandler<Req, Resp> {
    Resp handle(Req request);
}