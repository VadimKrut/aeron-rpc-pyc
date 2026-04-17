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
 */
public final class LargePayloadRpcChannel {
    private LargePayloadRpcChannel() {
        throw new UnsupportedOperationException("not implemented yet");
    }
}