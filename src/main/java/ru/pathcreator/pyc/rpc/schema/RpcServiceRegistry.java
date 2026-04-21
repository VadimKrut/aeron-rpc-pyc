package ru.pathcreator.pyc.rpc.schema;

import ru.pathcreator.pyc.rpc.core.ChannelConfig;
import ru.pathcreator.pyc.rpc.core.RpcMethod;
import ru.pathcreator.pyc.rpc.core.envelope.Envelope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
     * Performs non-fatal startup-time analysis and returns advisory issues that
     * may still be worth reviewing before a service starts taking traffic.
     *
     * @return immutable list of validation warnings
     */
    public List<RpcValidationIssue> analyze() {
        final ArrayList<RpcValidationIssue> issues = new ArrayList<>();
        final HashMap<String, String> transportOwners = new HashMap<>();

        for (final RpcChannelSchema channel : channels) {
            final ChannelConfig config = channel.config();

            if (channel.methods().isEmpty()) {
                issues.add(new RpcValidationIssue(
                        "empty-channel",
                        "channel '" + channel.name() + "' has no registered methods"));
            }

            if (config.localEndpoint().equals(config.remoteEndpoint())) {
                issues.add(new RpcValidationIssue(
                        "same-endpoint",
                        "channel '" + channel.name() + "' uses the same local and remote endpoint: " + config.localEndpoint()));
            }

            final String transportKey = config.localEndpoint() + "|" + config.remoteEndpoint() + "|" + config.streamId() + "|" + config.sessionId();
            final String previousTransportOwner = transportOwners.putIfAbsent(transportKey, channel.name());
            if (previousTransportOwner != null) {
                issues.add(new RpcValidationIssue(
                        "duplicate-transport-layout",
                        "channels '" + previousTransportOwner + "' and '" + channel.name() +
                        "' share the same local/remote/stream/session layout"));
            }

            if (!config.protocolHandshakeEnabled()) {
                if (config.protocolVersion() != 1 || config.protocolCapabilities() != 0L || config.requiredRemoteCapabilities() != 0L) {
                    issues.add(new RpcValidationIssue(
                            "handshake-disabled-custom-protocol",
                            "channel '" + channel.name() + "' sets protocol version/capabilities while protocol handshake is disabled"));
                }
            }

            if (config.heartbeatInterval().compareTo(config.defaultTimeout()) >= 0) {
                issues.add(new RpcValidationIssue(
                        "heartbeat-vs-timeout",
                        "channel '" + channel.name() + "' has heartbeatInterval >= defaultTimeout, which may delay channel-down detection relative to call timeout"));
            }

            if (config.offerTimeout().compareTo(config.defaultTimeout()) > 0) {
                issues.add(new RpcValidationIssue(
                        "offer-timeout-longer-than-call-timeout",
                        "channel '" + channel.name() + "' has offerTimeout > defaultTimeout"));
            }

            if (config.pendingPoolCapacity() < channel.methods().size()) {
                issues.add(new RpcValidationIssue(
                        "small-pending-pool",
                        "channel '" + channel.name() + "' has pendingPoolCapacity smaller than method count"));
            }
        }

        return List.copyOf(issues);
    }

    /**
     * Renders a readable multi-line report for logs, startup diagnostics, or
     * deployment review.
     *
     * @return human-readable registry report
     */
    public String renderTextReport() {
        final List<RpcValidationIssue> issues = analyze();
        final StringBuilder sb = new StringBuilder(512);
        sb.append("rpc-core service registry").append(System.lineSeparator());
        sb.append("Channels: ").append(channelCount()).append(System.lineSeparator());
        sb.append("Methods: ").append(methodCount()).append(System.lineSeparator());
        sb.append("Warnings: ").append(issues.size()).append(System.lineSeparator());
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

        if (!issues.isEmpty()) {
            sb.append("Validation warnings").append(System.lineSeparator());
            for (final RpcValidationIssue issue : issues) {
                sb.append("  - ").append(issue.code()).append(": ").append(issue.message()).append(System.lineSeparator());
            }
        }

        return sb.toString();
    }

    /**
     * Renders the registry as a compact JSON document suitable for logging,
     * diagnostics, or writing to a file.
     *
     * @return JSON report
     */
    public String renderJsonReport() {
        final List<RpcValidationIssue> issues = analyze();
        final StringBuilder sb = new StringBuilder(768);
        sb.append('{');
        jsonField(sb, "name", "rpc-core service registry").append(',');
        jsonField(sb, "channelCount", channelCount()).append(',');
        jsonField(sb, "methodCount", methodCount()).append(',');
        jsonField(sb, "warningCount", issues.size()).append(',');
        sb.append("\"channels\":[");
        for (int i = 0; i < channels.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            final RpcChannelSchema channel = channels.get(i);
            final ChannelConfig config = channel.config();
            sb.append('{');
            jsonField(sb, "name", channel.name()).append(',');
            jsonField(sb, "streamId", config.streamId()).append(',');
            jsonField(sb, "sessionId", config.sessionId()).append(',');
            jsonField(sb, "localEndpoint", config.localEndpoint()).append(',');
            jsonField(sb, "remoteEndpoint", config.remoteEndpoint()).append(',');
            jsonField(sb, "rxIdleStrategy", String.valueOf(config.rxIdleStrategy())).append(',');
            jsonField(sb, "reconnectStrategy", String.valueOf(config.reconnectStrategy())).append(',');
            jsonField(sb, "protocolHandshakeEnabled", config.protocolHandshakeEnabled()).append(',');
            jsonField(sb, "protocolVersion", config.protocolVersion()).append(',');
            jsonField(sb, "protocolCapabilitiesHex", "0x" + Long.toHexString(config.protocolCapabilities())).append(',');
            jsonField(sb, "requiredRemoteCapabilitiesHex", "0x" + Long.toHexString(config.requiredRemoteCapabilities())).append(',');
            jsonField(sb, "listenerCount", config.listeners().length).append(',');
            sb.append("\"methods\":[");
            for (int j = 0; j < channel.methods().size(); j++) {
                if (j > 0) {
                    sb.append(',');
                }
                final RpcMethodSchema method = channel.methods().get(j);
                sb.append('{');
                jsonField(sb, "name", method.name()).append(',');
                jsonField(sb, "requestMessageTypeId", method.requestMessageTypeId()).append(',');
                jsonField(sb, "responseMessageTypeId", method.responseMessageTypeId()).append(',');
                jsonField(sb, "requestType", method.requestTypeName()).append(',');
                jsonField(sb, "responseType", method.responseTypeName());
                if (method.version() != null) {
                    sb.append(',');
                    jsonField(sb, "version", method.version());
                }
                if (method.description() != null) {
                    sb.append(',');
                    jsonField(sb, "description", method.description());
                }
                sb.append('}');
            }
            sb.append("]}");
        }
        sb.append("],\"warnings\":[");
        for (int i = 0; i < issues.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            final RpcValidationIssue issue = issues.get(i);
            sb.append('{');
            jsonField(sb, "code", issue.code()).append(',');
            jsonField(sb, "message", issue.message());
            sb.append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    /**
     * Writes the text report to the provided file path, creating parent
     * directories when needed.
     *
     * @param path destination file path
     * @throws IOException when the report cannot be written
     */
    public void writeTextReport(final Path path) throws IOException {
        write(path, renderTextReport());
    }

    /**
     * Writes the JSON report to the provided file path, creating parent
     * directories when needed.
     *
     * @param path destination file path
     * @throws IOException when the report cannot be written
     */
    public void writeJsonReport(final Path path) throws IOException {
        write(path, renderJsonReport());
    }

    private static void write(final Path path, final String content) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, content);
    }

    private static StringBuilder jsonField(final StringBuilder sb, final String name, final String value) {
        return sb.append('"').append(escapeJson(name)).append("\":\"").append(escapeJson(value)).append('"');
    }

    private static StringBuilder jsonField(final StringBuilder sb, final String name, final int value) {
        return sb.append('"').append(escapeJson(name)).append("\":").append(value);
    }

    private static StringBuilder jsonField(final StringBuilder sb, final String name, final long value) {
        return sb.append('"').append(escapeJson(name)).append("\":").append(value);
    }

    private static StringBuilder jsonField(final StringBuilder sb, final String name, final boolean value) {
        return sb.append('"').append(escapeJson(name)).append("\":").append(value);
    }

    private static String escapeJson(final String value) {
        final StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    /**
     * Builder для сборки immutable registry снимка до старта сервиса.
     *
     * <p>Builder used to assemble an immutable registry snapshot before the
     * service starts.</p>
     */
    public static final class Builder {
        private final LinkedHashMap<String, ChannelBuilder> channels = new LinkedHashMap<>();

        /**
         * Создаёт пустой registry builder.
         *
         * <p>Creates an empty registry builder.</p>
         */
        private Builder() {
        }

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
            if (config.streamId() < 1) {
                throw new IllegalArgumentException("streamId must be >= 1 for channel '" + name + "'");
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

    /**
     * Builder одной logical channel entry внутри registry.
     *
     * <p>Builder for one logical channel entry inside the registry.</p>
     */
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
            final int responseMessageTypeId = method.responseMessageTypeId();
            if (responseMessageTypeId < 1) {
                throw new IllegalArgumentException("responseMessageTypeId must be >= 1 for method '" + methodName + "'");
            }
            if (requestMessageTypeId == Envelope.RESERVED_HEARTBEAT || requestMessageTypeId == Envelope.RESERVED_PROTOCOL_HANDSHAKE) {
                throw new IllegalArgumentException("requestMessageTypeId uses a reserved transport id for method '" + methodName + "'");
            }
            if (responseMessageTypeId == Envelope.RESERVED_HEARTBEAT || responseMessageTypeId == Envelope.RESERVED_PROTOCOL_HANDSHAKE) {
                throw new IllegalArgumentException("responseMessageTypeId uses a reserved transport id for method '" + methodName + "'");
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
            final int responseMessageTypeId = method.responseMessageTypeId();
            if (responseMessageTypeId < 1) {
                throw new IllegalArgumentException("responseMessageTypeId must be >= 1 for method '" + methodName + "'");
            }
            if (requestMessageTypeId == Envelope.RESERVED_HEARTBEAT || requestMessageTypeId == Envelope.RESERVED_PROTOCOL_HANDSHAKE) {
                throw new IllegalArgumentException("requestMessageTypeId uses a reserved transport id for method '" + methodName + "'");
            }
            if (responseMessageTypeId == Envelope.RESERVED_HEARTBEAT || responseMessageTypeId == Envelope.RESERVED_PROTOCOL_HANDSHAKE) {
                throw new IllegalArgumentException("responseMessageTypeId uses a reserved transport id for method '" + methodName + "'");
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