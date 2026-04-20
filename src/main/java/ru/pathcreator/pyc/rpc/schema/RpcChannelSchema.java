package ru.pathcreator.pyc.rpc.schema;

import ru.pathcreator.pyc.rpc.core.ChannelConfig;

import java.util.List;

/**
 * Immutable channel-level schema snapshot used by {@link RpcServiceRegistry}.
 */
public final class RpcChannelSchema {
    private final String name;
    private final ChannelConfig config;
    private final List<RpcMethodSchema> methods;

    RpcChannelSchema(final String name, final ChannelConfig config, final List<RpcMethodSchema> methods) {
        this.name = name;
        this.config = config;
        this.methods = List.copyOf(methods);
    }

    /**
     * Returns the logical channel name used inside the registry.
     *
     * @return logical channel name
     */
    public String name() {
        return name;
    }

    /**
     * Returns the channel configuration associated with this schema entry.
     *
     * @return channel configuration
     */
    public ChannelConfig config() {
        return config;
    }

    /**
     * Returns the immutable method list registered for this logical channel.
     *
     * @return immutable channel method list
     */
    public List<RpcMethodSchema> methods() {
        return methods;
    }
}