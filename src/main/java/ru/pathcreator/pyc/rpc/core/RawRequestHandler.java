package ru.pathcreator.pyc.rpc.core;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

/**
 * Низкоуровневый zero-allocation handler для сырых request/response байтов.
 *
 * <p>Low-level zero-allocation handler for raw request and response bytes.</p>
 *
 * <p>В отличие от {@link RequestHandler}, здесь пользователь работает напрямую
 * с байтами:
 * request приходит как диапазон в {@link DirectBuffer}, а response нужно
 * записать в предоставленный {@link MutableDirectBuffer} начиная с
 * {@code responseOffset}. Возвращаемое значение — длина response payload.</p>
 *
 * <p>Unlike {@link RequestHandler}, this API works directly with bytes:
 * the request arrives as a range inside a {@link DirectBuffer}, and the
 * response must be written into the provided {@link MutableDirectBuffer}
 * starting at {@code responseOffset}. The return value is the response payload
 * length.</p>
 *
 * <p>Значение {@code <= 0} означает, что ответ не отправляется.</p>
 *
 * <p>A return value of {@code <= 0} means that no response should be sent.</p>
 */
@FunctionalInterface
public interface RawRequestHandler {

    /**
     * Обрабатывает raw-запрос и пишет raw-ответ.
     *
     * <p>Handles a raw request and writes a raw response.</p>
     *
     * @param requestBuffer    буфер с payload запроса / buffer containing the request payload
     * @param requestOffset    смещение payload запроса / request payload offset
     * @param requestLength    длина payload запроса в байтах / request payload length in bytes
     * @param responseBuffer   буфер для payload ответа / buffer for the response payload
     * @param responseOffset   смещение, с которого нужно писать ответ / offset where response writing starts
     * @param responseCapacity доступная емкость для payload ответа /
     *                         available response payload capacity
     * @return число записанных байт; {@code <= 0} означает отсутствие ответа /
     * number of bytes written; {@code <= 0} means no response
     */
    int handle(
            DirectBuffer requestBuffer,
            int requestOffset,
            int requestLength,
            MutableDirectBuffer responseBuffer,
            int responseOffset,
            int responseCapacity
    );
}