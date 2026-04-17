package ru.pathcreator.pyc.codec;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

/**
 * Абстракция сериализации payload-а для RPC-ядра.
 * <p>
 * Реализация пользовательская — под SBE, Kryo, Protobuf, FlatBuffers, or plain
 * POJO с ручной сериализацией. Ядро не знает и не хочет знать подробностей.
 * <p>
 * Контракт:
 * - encode() пишет сериализованное представление {@code message} в
 * {@code buffer} начиная с {@code offset}. Возвращает число записанных байт.
 * Должен быть идемпотентен, не должен аллоцировать на hot-path.
 * - decode() читает из {@code buffer[offset..offset+length)} и возвращает
 * объект. Может аллоцировать — обычно аллоцирует (создаёт POJO); для
 * zero-alloc реализуй flyweight pattern (decode не создаёт объект,
 * а "wrap"-ит reusable instance).
 * <p>
 * ВНИМАНИЕ: decoder-ы обычно НЕ thread-safe (state в полях). Если один
 * codec-экземпляр используется из нескольких потоков, либо оберни его в
 * ThreadLocal, либо сделай реализацию stateless.
 */
public interface MessageCodec<T> {

    /**
     * Сериализация. @return число записанных байт.
     * Должно быть &le; (buffer.capacity() - offset).
     */
    int encode(T message, MutableDirectBuffer buffer, int offset);

    /**
     * Десериализация. Вызывается из RX-треда (в INLINE режиме) или из
     * virtual thread executor (в OFFLOAD режиме). В OFFLOAD режиме buffer
     * — уже скопированная копия, валиден всё время жизни handler-а. В
     * INLINE режиме buffer валиден только пока handler не вернул управление.
     */
    T decode(DirectBuffer buffer, int offset, int length);
}