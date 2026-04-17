package ru.pathcreator.pyc.exceptions;

/**
 * Payload превышает maxMessageSize текущего канала.
 * <p>
 * Hard-limit по умолчанию 16 MiB. Для больших payload-ов (файлы, стримы)
 * предполагается отдельный класс канала — см. {@code LargePayloadRpcChannel}
 * (zero-stub, будущая работа). Его логика сильно отличается: chunk-based
 * передача, flow-control, resume. В текущий RpcChannel это не вмешиваем.
 */
public final class PayloadTooLargeException extends RpcException {
    private final int actual;
    private final int limit;

    public PayloadTooLargeException(final int actual, final int limit) {
        super("Payload too large: " + actual + " bytes > limit " + limit +
              " bytes. For large payloads use LargePayloadRpcChannel (not implemented yet).");
        this.actual = actual;
        this.limit = limit;
    }

    public int actual() {
        return actual;
    }

    public int limit() {
        return limit;
    }
}