package ru.pathcreator.pyc.rpc.schema;

import ru.pathcreator.pyc.rpc.core.ChannelConfig;
import ru.pathcreator.pyc.rpc.core.RpcMethod;

import java.util.*;

/**
 * Optional startup-time registry for channel and method definitions.
 *
 * <p>The registry is intentionally separate from {@code rpc.core} transport
 * classes. It is meant for service bootstrapping, validation, and reporting:
 * conflict detection, channel and method inventory, protocol visibility, and
 * startup-time analysis. It does not participate in the request/response hot
 * path.</p>
 */
public final class RpcServiceRegistry {
    private final List<RpcChannelSchema> channels;

    private RpcServiceRegistry(final List<RpcChannelSchema> channels) {
        this.channels = List.copyOf(channels);
    }

    /**
     * Creates a new registry builder.
     *
     * @return empty registry builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the immutable list of registered channel schema entries.
     *
     * @return immutable channel schema list
     */
    public List<RpcChannelSchema> channels() {
        return channels;
    }

    /**
     * Returns the number of logical channels present in the registry.
     *
     * @return channel count
     */
    public int channelCount() {
        return channels.size();
    }

    /**
     * Returns the total number of registered methods across all channels.
     *
     * @return total method count
     */
    public int methodCount() {
        int count = 0;
        for (final RpcChannelSchema channel : channels) {
            count += channel.methods().size();
        }
        return count;
    }

    /**
     * Renders a readable multi-line report for logs, startup diagnostics, or
     * deployment review.
     *
     * @return human-readable registry report
     */
    public String renderTextReport() {
        final StringBuilder sb = new StringBuilder(512);
        sb.append("rpc-core service registry").append(System.lineSeparator());
        sb.append("Channels: ").append(channelCount()).append(System.lineSeparator());
        sb.append("Methods: ").append(methodCount()).append(System.lineSeparator());
        sb.append(System.lineSeparator());

        for (final RpcChannelSchema channel : channels) {
            final ChannelConfig config = channel.config();
            sb.append("Channel: ").append(channel.name()).append(System.lineSeparator());
            sb.append("  Stream: ").append(config.streamId()).append(System.lineSeparator());
            sb.append("  Local endpoint: ").append(config.localEndpoint()).append(System.lineSeparator());
            sb.append("  Remote endpoint: ").append(config.remoteEndpoint()).append(System.lineSeparator());
            sb.append("  RX idle strategy: ").append(config.rxIdleStrategy()).append(System.lineSeparator());
            sb.append("  Reconnect strategy: ").append(config.reconnectStrategy()).append(System.lineSeparator());
            sb.append("  Protocol handshake: ").append(config.protocolHandshakeEnabled()).append(System.lineSeparator());
            sb.append("  Protocol version: ").append(config.protocolVersion()).append(System.lineSeparator());
            sb.append("  Protocol capabilities: 0x").append(Long.toHexString(config.protocolCapabilities())).append(System.lineSeparator());
            sb.append("  Required remote capabilities: 0x").append(Long.toHexString(config.requiredRemoteCapabilities())).append(System.lineSeparator());
            sb.append("  Listener count: ").append(config.listeners().length).append(System.lineSeparator());
            sb.append("  Methods: ").append(channel.methods().size()).append(System.lineSeparator());
            for (final RpcMethodSchema method : channel.methods()) {
                sb.append("    - ").append(method.name())
                        .append(" [req=").append(method.requestMessageTypeId())
                        .append(", resp=").append(method.responseMessageTypeId())
                        .append(", requestType=").append(method.requestTypeName())
                        .append(", responseType=").append(method.responseTypeName());
                if (method.version() != null) {
                    sb.append(", version=").append(method.version());
                }
                sb.append("]");
                if (method.description() != null) {
                    sb.append(" - ").append(method.description());
                }
                sb.append(System.lineSeparator());
            }
            sb.append(System.lineSeparator());
        }

        return sb.toString();
    }

    public static final class Builder {
        private final LinkedHashMap<String, ChannelBuilder> channels = new LinkedHashMap<>();

        /**
         * Adds a logical channel entry to the registry.
         *
         * @param name   logical channel name used in reports and validation
         * @param config channel configuration associated with the logical channel
         * @return builder for adding methods to that channel entry
         */
        public ChannelBuilder channel(final String name, final ChannelConfig config) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("channel name must not be blank");
            }
            if (config == null) {
                throw new IllegalArgumentException("channel config must not be null");
            }
            if (channels.containsKey(name)) {
                throw new IllegalArgumentException("duplicate channel name: " + name);
            }
            final ChannelBuilder builder = new ChannelBuilder(name, config);
            channels.put(name, builder);
            return builder;
        }

        /**
         * Builds an immutable registry snapshot after validating the collected
         * channel entries.
         *
         * @return immutable registry snapshot
         */
        public RpcServiceRegistry build() {
            final List<RpcChannelSchema> builtChannels = new ArrayList<>(channels.size());
            for (final ChannelBuilder channel : channels.values()) {
                builtChannels.add(channel.build());
            }
            return new RpcServiceRegistry(builtChannels);
        }
    }

    public static final class ChannelBuilder {
        private final String name;
        private final ChannelConfig config;
        private final List<RpcMethodSchema> methods = new ArrayList<>();
        private final HashSet<String> methodNames = new HashSet<>();
        private final HashMap<Integer, String> requestTypeOwners = new HashMap<>();

        private ChannelBuilder(final String name, final ChannelConfig config) {
            this.name = name;
            this.config = config;
        }

        /**
         * Adds a typed method definition with explicit request and response type
         * names.
         *
         * @param methodName   logical method name
         * @param method       typed method descriptor
         * @param requestType  request Java type
         * @param responseType response Java type
         * @param <Req>        request type
         * @param <Resp>       response type
         * @return this channel builder
         */
        public <Req, Resp> ChannelBuilder method(
                final String methodName,
                final RpcMethod<Req, Resp> method,
                final Class<Req> requestType,
                final Class<Resp> responseType
        ) {
            return method(methodName, method, requestType, responseType, null, null);
        }

        /**
         * Adds a typed method definition with optional version and description
         * metadata.
         *
         * @param methodName   logical method name
         * @param method       typed method descriptor
         * @param requestType  request Java type
         * @param responseType response Java type
         * @param version      optional logical method version
         * @param description  optional human-readable description
         * @param <Req>        request type
         * @param <Resp>       response type
         * @return this channel builder
         */
        public <Req, Resp> ChannelBuilder method(
                final String methodName,
                final RpcMethod<Req, Resp> method,
                final Class<Req> requestType,
                final Class<Resp> responseType,
                final String version,
                final String description
        ) {
            if (methodName == null || methodName.isBlank()) {
                throw new IllegalArgumentException("method name must not be blank");
            }
            if (method == null) {
                throw new IllegalArgumentException("method must not be null");
            }
            if (!methodNames.add(methodName)) {
                throw new IllegalArgumentException("duplicate method name in channel '" + name + "': " + methodName);
            }
            final int requestMessageTypeId = method.requestMessageTypeId();
            if (requestMessageTypeId < 1) {
                throw new IllegalArgumentException("requestMessageTypeId must be >= 1 for method '" + methodName + "'");
            }
            final String previousOwner = requestTypeOwners.putIfAbsent(requestMessageTypeId, methodName);
            if (previousOwner != null) {
                throw new IllegalArgumentException(
                        "duplicate requestMessageTypeId " + requestMessageTypeId +
                        " in channel '" + name + "' for methods '" + previousOwner + "' and '" + methodName + "'");
            }
            methods.add(new RpcMethodSchema(
                    methodName,
                    method,
                    requestType != null ? requestType.getName() : "?",
                    responseType != null ? responseType.getName() : "?",
                    version,
                    description));
            return this;
        }

        /**
         * Adds a method definition when explicit request and response Java type
         * names are not needed.
         *
         * @param methodName logical method name
         * @param method     typed method descriptor
         * @return this channel builder
         */
        public ChannelBuilder method(final String methodName, final RpcMethod<?, ?> method) {
            if (methodName == null || methodName.isBlank()) {
                throw new IllegalArgumentException("method name must not be blank");
            }
            if (method == null) {
                throw new IllegalArgumentException("method must not be null");
            }
            if (!methodNames.add(methodName)) {
                throw new IllegalArgumentException("duplicate method name in channel '" + name + "': " + methodName);
            }
            final int requestMessageTypeId = method.requestMessageTypeId();
            if (requestMessageTypeId < 1) {
                throw new IllegalArgumentException("requestMessageTypeId must be >= 1 for method '" + methodName + "'");
            }
            final String previousOwner = requestTypeOwners.putIfAbsent(requestMessageTypeId, methodName);
            if (previousOwner != null) {
                throw new IllegalArgumentException(
                        "duplicate requestMessageTypeId " + requestMessageTypeId +
                        " in channel '" + name + "' for methods '" + previousOwner + "' and '" + methodName + "'");
            }
            methods.add(new RpcMethodSchema(methodName, method, "?", "?", null, null));
            return this;
        }

        private RpcChannelSchema build() {
            return new RpcChannelSchema(name, config, methods);
        }
    }
}