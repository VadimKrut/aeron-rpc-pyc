package ru.pathcreator.pyc.rpc.core.exceptions;

/**
 * Встроенные RPC status code-ы для transport-слоя и общих service-level ошибок.
 *
 * <p>Built-in RPC status codes used by the transport and by shared
 * service-level validation failures.</p>
 *
 * <p>Числовые значения намеренно повторяют знакомую HTTP-style семантику,
 * чтобы логи, метрики, дашборды и client-side обработка оставались
 * интуитивными.</p>
 *
 * <p>The numeric values intentionally follow familiar HTTP-style semantics so
 * logs, metrics, dashboards, and client-side handling remain easy to
 * interpret.</p>
 *
 * <p>Диапазоны 4xx и 5xx зарезервированы библиотекой для built-in transport и
 * system meanings. Application-defined business code-ы должны использовать
 * значения {@code >= 1000}.</p>
 *
 * <p>The library reserves the standard 4xx and 5xx ranges for built-in
 * transport/system meanings. Application-defined business codes must use
 * values {@code >= 1000}.</p>
 */
public enum RpcStatus {
    /**
     * 100 Continue / продолжайте запрос.
     */
    CONTINUE(100),
    /**
     * 101 Switching Protocols / переключение протокола.
     */
    SWITCHING_PROTOCOLS(101),
    /**
     * 102 Processing / запрос обрабатывается.
     */
    PROCESSING(102),
    /**
     * 103 Early Hints / ранние подсказки.
     */
    EARLY_HINTS(103),

    /**
     * 200 OK / успех.
     */
    OK(200),
    /**
     * 201 Created / создано.
     */
    CREATED(201),
    /**
     * 202 Accepted / принято в обработку.
     */
    ACCEPTED(202),
    /**
     * 203 Non-Authoritative Information / неавторитативная информация.
     */
    NON_AUTHORITATIVE_INFORMATION(203),
    /**
     * 204 No Content / нет тела ответа.
     */
    NO_CONTENT(204),
    /**
     * 205 Reset Content / сброс содержимого.
     */
    RESET_CONTENT(205),
    /**
     * 206 Partial Content / частичное содержимое.
     */
    PARTIAL_CONTENT(206),
    /**
     * 207 Multi-Status / множественный статус.
     */
    MULTI_STATUS(207),
    /**
     * 208 Already Reported / уже сообщено.
     */
    ALREADY_REPORTED(208),
    /**
     * 226 IM Used / использован IM.
     */
    IM_USED(226),

    /**
     * 300 Multiple Choices / несколько вариантов.
     */
    MULTIPLE_CHOICES(300),
    /**
     * 301 Moved Permanently / постоянно перемещено.
     */
    MOVED_PERMANENTLY(301),
    /**
     * 302 Found / временно найдено.
     */
    FOUND(302),
    /**
     * 303 See Other / смотри другое.
     */
    SEE_OTHER(303),
    /**
     * 304 Not Modified / не изменено.
     */
    NOT_MODIFIED(304),
    /**
     * 305 Use Proxy / используй proxy.
     */
    USE_PROXY(305),
    /**
     * 307 Temporary Redirect / временный редирект.
     */
    TEMPORARY_REDIRECT(307),
    /**
     * 308 Permanent Redirect / постоянный редирект.
     */
    PERMANENT_REDIRECT(308),

    /**
     * 400 Bad Request / некорректный запрос.
     */
    BAD_REQUEST(400),
    /**
     * 401 Unauthorized / не авторизовано.
     */
    UNAUTHORIZED(401),
    /**
     * 402 Payment Required / требуется оплата.
     */
    PAYMENT_REQUIRED(402),
    /**
     * 403 Forbidden / доступ запрещен.
     */
    FORBIDDEN(403),
    /**
     * 404 Not Found / не найдено.
     */
    NOT_FOUND(404),
    /**
     * 405 Method Not Allowed / метод не поддерживается.
     */
    METHOD_NOT_ALLOWED(405),
    /**
     * 406 Not Acceptable / неприемлемо.
     */
    NOT_ACCEPTABLE(406),
    /**
     * 407 Proxy Authentication Required / нужна proxy-авторизация.
     */
    PROXY_AUTHENTICATION_REQUIRED(407),
    /**
     * 408 Request Timeout / таймаут запроса.
     */
    REQUEST_TIMEOUT(408),
    /**
     * 409 Conflict / конфликт.
     */
    CONFLICT(409),
    /**
     * 410 Gone / ресурс удален.
     */
    GONE(410),
    /**
     * 411 Length Required / нужен Content-Length.
     */
    LENGTH_REQUIRED(411),
    /**
     * 412 Precondition Failed / precondition не выполнена.
     */
    PRECONDITION_FAILED(412),
    /**
     * 413 Payload Too Large / payload слишком большой.
     */
    PAYLOAD_TOO_LARGE(413),
    /**
     * 414 URI Too Long / URI слишком длинный.
     */
    URI_TOO_LONG(414),
    /**
     * 415 Unsupported Media Type / неподдерживаемый media type.
     */
    UNSUPPORTED_MEDIA_TYPE(415),
    /**
     * 416 Range Not Satisfiable / range невозможно удовлетворить.
     */
    RANGE_NOT_SATISFIABLE(416),
    /**
     * 417 Expectation Failed / expectation не выполнена.
     */
    EXPECTATION_FAILED(417),
    /**
     * 418 I'm a Teapot / шуточный HTTP статус.
     */
    I_AM_A_TEAPOT(418),
    /**
     * 421 Misdirected Request / запрос адресован не туда.
     */
    MISDIRECTED_REQUEST(421),
    /**
     * 422 Unprocessable Entity / невозможно обработать сущность.
     */
    UNPROCESSABLE_ENTITY(422),
    /**
     * 423 Locked / заблокировано.
     */
    LOCKED(423),
    /**
     * 424 Failed Dependency / ошибка зависимости.
     */
    FAILED_DEPENDENCY(424),
    /**
     * 425 Too Early / слишком рано.
     */
    TOO_EARLY(425),
    /**
     * 426 Upgrade Required / требуется upgrade.
     */
    UPGRADE_REQUIRED(426),
    /**
     * 428 Precondition Required / требуется precondition.
     */
    PRECONDITION_REQUIRED(428),
    /**
     * 429 Too Many Requests / слишком много запросов.
     */
    TOO_MANY_REQUESTS(429),
    /**
     * 431 Request Header Fields Too Large / заголовки слишком большие.
     */
    REQUEST_HEADER_FIELDS_TOO_LARGE(431),
    /**
     * 451 Unavailable For Legal Reasons / недоступно по юридическим причинам.
     */
    UNAVAILABLE_FOR_LEGAL_REASONS(451),

    /**
     * 500 Internal Server Error / внутренняя ошибка сервера.
     */
    INTERNAL_SERVER_ERROR(500),
    /**
     * 501 Not Implemented / не реализовано.
     */
    NOT_IMPLEMENTED(501),
    /**
     * 502 Bad Gateway / плохой gateway.
     */
    BAD_GATEWAY(502),
    /**
     * 503 Service Unavailable / сервис недоступен.
     */
    SERVICE_UNAVAILABLE(503),
    /**
     * 504 Gateway Timeout / таймаут gateway.
     */
    GATEWAY_TIMEOUT(504),
    /**
     * 505 HTTP Version Not Supported / версия HTTP не поддерживается.
     */
    HTTP_VERSION_NOT_SUPPORTED(505),
    /**
     * 506 Variant Also Negotiates / variant некорректно negotiates.
     */
    VARIANT_ALSO_NEGOTIATES(506),
    /**
     * 507 Insufficient Storage / недостаточно хранилища.
     */
    INSUFFICIENT_STORAGE(507),
    /**
     * 508 Loop Detected / обнаружен цикл.
     */
    LOOP_DETECTED(508),
    /**
     * 510 Not Extended / не продлено.
     */
    NOT_EXTENDED(510),
    /**
     * 511 Network Authentication Required / требуется сетевая авторизация.
     */
    NETWORK_AUTHENTICATION_REQUIRED(511);

    private final int code;

    RpcStatus(final int code) {
        this.code = code;
    }

    /**
     * Returns the numeric status code.
     *
     * @return numeric status code
     */
    public int code() {
        return code;
    }

    /**
     * Returns whether the status belongs to the 1xx informational range.
     *
     * @return {@code true} for informational statuses
     */
    public boolean isInformational() {
        return code >= 100 && code < 200;
    }

    /**
     * Returns whether the status belongs to the 2xx success range.
     *
     * @return {@code true} for success statuses
     */
    public boolean isSuccess() {
        return code >= 200 && code < 300;
    }

    /**
     * Returns whether the status belongs to the 3xx redirection range.
     *
     * @return {@code true} for redirect-like statuses
     */
    public boolean isRedirect() {
        return code >= 300 && code < 400;
    }

    /**
     * Returns whether the status belongs to the 4xx client-error range.
     *
     * @return {@code true} for client errors
     */
    public boolean isClientError() {
        return code >= 400 && code < 500;
    }

    /**
     * Returns whether the status belongs to the 5xx server-error range.
     *
     * @return {@code true} for server errors
     */
    public boolean isServerError() {
        return code >= 500 && code < 600;
    }

    /**
     * Returns whether the status is commonly retryable for transport callers.
     *
     * @return {@code true} for statuses that can reasonably be retried
     */
    public boolean isRetryable() {
        return this == REQUEST_TIMEOUT
               || this == TOO_EARLY
               || this == TOO_MANY_REQUESTS
               || this == BAD_GATEWAY
               || this == SERVICE_UNAVAILABLE
               || this == GATEWAY_TIMEOUT
               || this == INSUFFICIENT_STORAGE;
    }

    /**
     * Resolves a numeric code into a known status when possible.
     *
     * @param code numeric status code
     * @return matching enum value or {@code null} for custom codes
     */
    public static RpcStatus fromCode(final int code) {
        for (final RpcStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return null;
    }
}