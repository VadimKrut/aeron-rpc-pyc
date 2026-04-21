package ru.pathcreator.pyc.rpc.schema;

import ru.pathcreator.pyc.rpc.core.ChannelConfig;

import java.util.List;

/**
 * Immutable channel-level schema snapshot, используемый в {@link RpcServiceRegistry}.
 *
 * <p>Immutable channel-level schema snapshot used by {@link RpcServiceRegistry}.</p>
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
     * Возвращает логическое имя канала внутри registry.
     *
     * <p>Returns the logical channel name used inside the registry.</p>
     *
     * @return logical channel name
     */
    public String name() {
        return name;
    }

    /**
     * Возвращает конфигурацию канала для этой schema entry.
     *
     * <p>Returns the channel configuration associated with this schema entry.</p>
     *
     * @return channel configuration
     */
    public ChannelConfig config() {
        return config;
    }

    /**
     * Возвращает immutable-список методов, зарегистрированных для канала.
     *
     * <p>Returns the immutable method list registered for this logical channel.</p>
     *
     * @return immutable method list
     */
    public List<RpcMethodSchema> methods() {
        return methods;
    }
}