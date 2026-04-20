package ru.pathcreator.pyc.rpc.schema;

import ru.pathcreator.pyc.rpc.core.RpcMethod;

/**
 * Startup-time method metadata used by {@link RpcServiceRegistry}.
 *
 * <p>This class exists purely for validation, reporting, and schema-like
 * analysis before a service starts accepting load. It does not participate in
 * the transport hot path.</p>
 */
public final class RpcMethodSchema {
    private final String name;
    private final RpcMethod<?, ?> method;
    private final String requestTypeName;
    private final String responseTypeName;
    private final String version;
    private final String description;

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
     * Returns the logical method name used in the registry.
     *
     * @return logical method name
     */
    public String name() {
        return name;
    }

    /**
     * Returns the underlying typed RPC method definition.
     *
     * @return typed method descriptor
     */
    public RpcMethod<?, ?> method() {
        return method;
    }

    /**
     * Returns the request message type id.
     *
     * @return request message type id
     */
    public int requestMessageTypeId() {
        return method.requestMessageTypeId();
    }

    /**
     * Returns the response message type id.
     *
     * @return response message type id
     */
    public int responseMessageTypeId() {
        return method.responseMessageTypeId();
    }

    /**
     * Returns the request Java type name when supplied to the registry.
     *
     * @return request type name
     */
    public String requestTypeName() {
        return requestTypeName;
    }

    /**
     * Returns the response Java type name when supplied to the registry.
     *
     * @return response type name
     */
    public String responseTypeName() {
        return responseTypeName;
    }

    /**
     * Returns the optional logical method version.
     *
     * @return method version, or {@code null} when not supplied
     */
    public String version() {
        return version;
    }

    /**
     * Returns the optional human-readable method description.
     *
     * @return description, or {@code null} when not supplied
     */
    public String description() {
        return description;
    }
}