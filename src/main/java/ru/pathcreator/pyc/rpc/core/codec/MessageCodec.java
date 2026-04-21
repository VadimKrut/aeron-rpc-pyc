package ru.pathcreator.pyc.rpc.core.codec;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

/**
 * Абстракция сериализации payload для RPC-ядра.
 *
 * <p>Реализация предоставляется пользователем и может работать с любым
 * форматом: SBE, Protocol Buffers, plain bytes, ручным binary layout или
 * любым другим представлением, которое умеет читать и писать байты в Agrona
 * buffer-ы. Само RPC-ядро не зависит от конкретного payload-формата.</p>
 *
 * <p>Payload serialization abstraction used by the RPC core. Implementations
 * are provided by user code and may use any format that can read and write
 * bytes through Agrona buffers.</p>
 *
 * <p>Контракт codec-а / Codec contract:</p>
 * <ul>
 *   <li>{@link #encode(Object, MutableDirectBuffer, int)} пишет payload в
 *   переданный buffer начиная с {@code offset} и возвращает число записанных
 *   байт / writes the payload into the provided buffer and returns the number
 *   of bytes written;</li>
 *   <li>{@link #decode(DirectBuffer, int, int)} читает payload из указанного
 *   диапазона buffer-а и возвращает объект сообщения / reads the payload from
 *   the specified buffer range and returns a decoded message object;</li>
 *   <li>если реализация stateful, пользователь сам отвечает за ее
 *   потокобезопасность / if an implementation is stateful, the caller is
 *   responsible for its thread-safety.</li>
 * </ul>
 *
 * @param <T> тип сообщения, который codec кодирует и декодирует /
 *            message type encoded and decoded by this codec
 */
public interface MessageCodec<T> {

    /**
     * Кодирует сообщение в переданный buffer.
     *
     * <p>Encodes a message into the provided buffer.</p>
     *
     * @param message сообщение для кодирования / message to encode
     * @param buffer  буфер назначения / destination buffer
     * @param offset  смещение начала записи payload / payload write offset
     * @return число записанных байт / number of bytes written
     */
    int encode(T message, MutableDirectBuffer buffer, int offset);

    /**
     * Декодирует сообщение из диапазона buffer-а.
     *
     * <p>Decodes a message from the provided buffer range.</p>
     *
     * @param buffer буфер-источник / source buffer
     * @param offset смещение начала payload / payload start offset
     * @param length длина payload в байтах / payload length in bytes
     * @return декодированное сообщение / decoded message
     */
    T decode(DirectBuffer buffer, int offset, int length);
}