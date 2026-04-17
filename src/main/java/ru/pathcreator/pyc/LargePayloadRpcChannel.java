package ru.pathcreator.pyc;

/**
 * Стаб. Канал для передачи payload &gt; 16 MiB.
 * <p>
 * Обычный RpcChannel намеренно hard-cap-ит размер сообщения (см. ChannelConfig.
 * DEFAULT_MAX_MESSAGE_SIZE). Это оптимизация: для маленьких сообщений ядро
 * может держать буферы в TLS и пулах фиксированного размера, zero-alloc.
 * <p>
 * Для больших payload-ов (файлы, стримы) логика принципиально другая:
 * - chunk-based передача (многофрагментные сообщения пользовательского уровня);
 * - flow-control (back-pressure от consumer-а);
 * - опциональный resume после разрыва;
 * - отдельный буферный пул большего размера;
 * - heartbeat на уровне чанка.
 * <p>
 * Это заслуживает отдельного класса, чтобы не смешивать hot-path оптимизации
 * маленьких сообщений с тяжёлой механикой больших.
 * <p>
 * Сейчас — не реализован. Намеренно оставлен как маркер-namespace.
 *
 * <p>Stub namespace for a future channel dedicated to payloads larger than
 * 16 MiB. It is intentionally not implemented yet.</p>
 */
public final class LargePayloadRpcChannel {
    /**
     * Запрещает создание экземпляров, пока канал больших payload не реализован.
     *
     * <p>Prevents instantiation until the large payload channel is implemented.</p>
     */
    private LargePayloadRpcChannel() {
        throw new UnsupportedOperationException("not implemented yet");
    }
}