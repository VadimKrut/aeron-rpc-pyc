package ru.pathcreator.pyc.rpc.core;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

/**
 * Low-level zero-alloc серверный handler.
 * <p>
 * В отличие от {@link RequestHandler}&lt;Req,Resp&gt;, здесь пользователь
 * работает с сырыми байтами:
 * - request буфер и length передаются напрямую;
 * - для response пользователю даётся MutableDirectBuffer с уже
 * зарезервированным местом под envelope (пишем payload начиная с
 * responseOffset). Возвращаем длину payload-а.
 * <p>
 * Возврат 0 или отрицательного числа = ответа не будет (one-way семантика).
 * <p>
 * Эта модель — предпочтительная на очень high-throughput / low-latency
 * серверах: ни декодирования request в POJO, ни encode response-POJO,
 * ни боксинга. SBE / Kryo flyweight декодер пользователь вызывает прямо
 * из своего rawHandle(...).
 * <p>
 * Ответственность пользователя:
 * - не писать в request buffer (он может быть shared rx-buffer-ом);
 * - request buffer валиден только пока handle() не вернулся
 * (в обычном offload-режиме это копия из пула; в direct-executor-режиме
 * это rx-буфер Aeron-а);
 * - в responseBuffer писать начиная с responseOffset и не дальше
 * responseOffset + responseCapacity.
 *
 * <p>Low-level zero-allocation server handler. It receives raw request bytes
 * and writes raw response bytes directly into the provided response buffer.</p>
 */
@FunctionalInterface
public interface RawRequestHandler {

    /**
     * Обрабатывает raw-запрос и записывает payload ответа.
     *
     * <p>Handles a raw request and writes the response payload.</p>
     *
     * @param requestBuffer    буфер с payload запроса / buffer containing the request payload
     * @param requestOffset    смещение payload запроса / request payload offset
     * @param requestLength    длина payload запроса в байтах / request payload length in bytes
     * @param responseBuffer   буфер для payload ответа / buffer for the response payload
     * @param responseOffset   смещение, с которого нужно писать ответ / offset where response writing starts
     * @param responseCapacity доступная емкость для payload ответа / available response payload capacity
     * @return число записанных байт в {@code responseBuffer}; значение {@code <= 0}
     * означает one-way вызов без ответа /
     * number of bytes written to {@code responseBuffer}; {@code <= 0} means no response
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