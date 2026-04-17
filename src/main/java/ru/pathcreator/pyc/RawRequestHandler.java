package ru.pathcreator.pyc;

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
 * - в режиме INLINE request buffer валиден только пока rawHandle()
 * не вернулся; в OFFLOAD — всё время жизни вызова (копия из пула);
 * - в responseBuffer писать начиная с responseOffset и не дальше
 * responseOffset + responseCapacity.
 */
@FunctionalInterface
public interface RawRequestHandler {

    /**
     * @return число записанных байт в responseBuffer, ИЛИ &lt;= 0 чтобы
     * не отвечать (one-way).
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