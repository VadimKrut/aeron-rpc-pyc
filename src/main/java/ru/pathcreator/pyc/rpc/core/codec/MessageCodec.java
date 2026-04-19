package ru.pathcreator.pyc.rpc.core.codec;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

/**
 * Абстракция сериализации payload-а для RPC-ядра.
 *
 * <p>Реализация предоставляется пользователем и может работать с любым форматом:
 * SBE, Kryo, Protocol Buffers, FlatBuffers, plain bytes или ручной сериализацией
 * POJO. RPC-ядро не зависит от конкретного формата payload.</p>
 *
 * <p>Контракт кодека:</p>
 * <ul>
 *     <li>{@link #encode(Object, MutableDirectBuffer, int)} записывает
 *     сериализованное представление сообщения в переданный буфер и возвращает
 *     количество записанных байт;</li>
 *     <li>{@link #decode(DirectBuffer, int, int)} читает payload из переданного
 *     диапазона буфера и возвращает объект сообщения;</li>
 *     <li>реализация должна учитывать, что один экземпляр кодека может быть
 *     использован несколькими потоками только если она stateless или защищена
 *     пользователем, например через {@link ThreadLocal}.</li>
 * </ul>
 *
 * <p>Payload serialization abstraction used by the RPC core. User code provides
 * the concrete implementation, while the core remains independent of the binary
 * payload format.</p>
 *
 * @param <T> тип сообщения, который кодирует и декодирует этот кодек /
 *            message type encoded and decoded by this codec
 */
public interface MessageCodec<T> {

    /**
     * Кодирует сообщение в переданный буфер.
     *
     * <p>Метод должен записать payload начиная с {@code offset} и вернуть число
     * записанных байт. Запись не должна выходить за пределы доступной емкости
     * буфера.</p>
     *
     * <p>Encodes a message into the provided buffer starting at {@code offset}
     * and returns the number of bytes written.</p>
     *
     * @param message сообщение для кодирования / message to encode
     * @param buffer  буфер назначения / destination buffer
     * @param offset  смещение начала записи payload / payload write offset
     * @return число записанных байт / number of bytes written
     */
    int encode(T message, MutableDirectBuffer buffer, int offset);

    /**
     * Декодирует сообщение из диапазона буфера.
     *
     * <p>Метод читает payload из диапазона
     * {@code buffer[offset..offset + length)}. Реализация может создавать новый
     * объект сообщения или использовать flyweight-подход, если нужен zero-alloc
     * decode.</p>
     *
     * <p>Decodes a message from the buffer range
     * {@code buffer[offset..offset + length)}.</p>
     *
     * @param buffer буфер-источник с payload / source buffer containing the payload
     * @param offset смещение начала payload / payload start offset
     * @param length длина payload в байтах / payload length in bytes
     * @return декодированное сообщение / decoded message
     */
    T decode(DirectBuffer buffer, int offset, int length);
}