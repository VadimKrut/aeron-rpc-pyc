package ru.pathcreator.pyc.rpc.schema;

import ru.pathcreator.pyc.rpc.core.RpcMethod;

/**
 * Startup-time metadata одного метода, используемая в {@link RpcServiceRegistry}.
 *
 * <p>Startup-time method metadata used by {@link RpcServiceRegistry}.</p>
 *
 * <p>Класс существует только для validation, reporting и schema-like анализа
 * до старта нагрузки. В hot path транспорта он не участвует.</p>
 *
 * <p>This class exists purely for validation, reporting, and schema-like
 * analysis before a service starts accepting load. It does not participate in
 * the transport hot path.</p>
 */
public final class RpcMethodSchema {

    private final String name;
    private final String version;
    private final String description;
    private final RpcMethod<?, ?> method;
    private final String requestTypeName;
    private final String responseTypeName;

    RpcMethodSchema(
            final String name,
            final RpcMethod<?, ?> method,
            final String requestTypeName,
            final String responseTypeName,
            final String version,
            final String description
    ) {
        this.name = name;
        this.method = method;
        this.requestTypeName = requestTypeName;
        this.responseTypeName = responseTypeName;
        this.version = version;
        this.description = description;
    }

    /**
     * Возвращает логическое имя метода в registry.
     *
     * <p>Returns the logical method name used in the registry.</p>
     *
     * @return logical method name
     */
    public String name() {
        return name;
    }

    /**
     * Возвращает underlying typed RPC method descriptor.
     *
     * <p>Returns the underlying typed RPC method definition.</p>
     *
     * @return underlying method descriptor
     */
    public RpcMethod<?, ?> method() {
        return method;
    }

    /**
     * Возвращает request message type id.
     *
     * <p>Returns the request message type id.</p>
     *
     * @return request message type id
     */
    public int requestMessageTypeId() {
        return method.requestMessageTypeId();
    }

    /**
     * Возвращает response message type id.
     *
     * <p>Returns the response message type id.</p>
     *
     * @return response message type id
     */
    public int responseMessageTypeId() {
        return method.responseMessageTypeId();
    }

    /**
     * Возвращает имя Java-типа запроса, если оно было передано в registry.
     *
     * <p>Returns the request Java type name when supplied to the registry.</p>
     *
     * @return request Java type name
     */
    public String requestTypeName() {
        return requestTypeName;
    }

    /**
     * Возвращает имя Java-типа ответа, если оно было передано в registry.
     *
     * <p>Returns the response Java type name when supplied to the registry.</p>
     *
     * @return response Java type name
     */
    public String responseTypeName() {
        return responseTypeName;
    }

    /**
     * Возвращает optional logical version метода.
     *
     * <p>Returns the optional logical method version.</p>
     *
     * @return logical method version or {@code null}
     */
    public String version() {
        return version;
    }

    /**
     * Возвращает optional human-readable описание метода.
     *
     * <p>Returns the optional human-readable method description.</p>
     *
     * @return method description or {@code null}
     */
    public String description() {
        return description;
    }
}