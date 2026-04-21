package ru.pathcreator.pyc.rpc.core;

import io.aeron.Aeron;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import org.agrona.CloseHelper;
import org.agrona.concurrent.BackoffIdleStrategy;

import java.io.File;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Корневой RPC-объект на один процесс.
 *
 * <p>Root RPC object for one process.</p>
 *
 * <p>Node владеет:
 * embedded {@link MediaDriver} при необходимости,
 * одним {@link Aeron} client-ом,
 * общим offload executor-ом,
 * общим receive poller-ом, если он включён,
 * и списком каналов, созданных через этот node.</p>
 *
 * <p>The node owns:
 * an embedded {@link MediaDriver} when needed,
 * a single {@link Aeron} client,
 * a shared offload executor,
 * a shared receive poller when enabled,
 * and the list of channels created through the node.</p>
 */
public final class RpcNode implements AutoCloseable {

    private final Aeron aeron;
    private final MediaDriver mediaDriver;
    private final ExecutorService offloadExecutor;
    private final SharedReceivePoller receivePoller;
    private final CopyOnWriteArrayList<RpcChannel> channels = new CopyOnWriteArrayList<>();

    private RpcNode(
            final MediaDriver driver,
            final Aeron aeron,
            final ExecutorService exec,
            final SharedReceivePoller receivePoller
    ) {
        this.mediaDriver = driver;
        this.aeron = aeron;
        this.offloadExecutor = exec;
        this.receivePoller = receivePoller;
    }

    /**
     * Запускает RPC node с заданной конфигурацией.
     *
     * <p>Starts an RPC node using the provided configuration.</p>
     *
     * @param config конфигурация node-а / node configuration
     * @return запущенный RPC node / started RPC node
     */
    public static RpcNode start(final NodeConfig config) {
        ensureAeronDirParent(config.aeronDir());
        final MediaDriver driver;
        final String aeronDirName;
        if (config.embeddedDriver()) {
            final MediaDriver.Context driverCtx = new MediaDriver.Context()
                    .aeronDirectoryName(config.aeronDir())
                    .dirDeleteOnStart(true)
                    .dirDeleteOnShutdown(true)
                    .threadingMode(ThreadingMode.SHARED)
                    .termBufferSparseFile(true)
                    .sharedIdleStrategy(new BackoffIdleStrategy(
                            100, 10,
                            1L,
                            1_000_000L))
                    .useWindowsHighResTimer(true)
                    .warnIfDirectoryExists(true);
            driver = MediaDriver.launchEmbedded(driverCtx);
            aeronDirName = driver.aeronDirectoryName();
        } else {
            driver = null;
            aeronDirName = config.aeronDir();
        }
        final Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(aeronDirName));
        final ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        final SharedReceivePoller receivePoller = config.sharedReceivePoller()
                ? new SharedReceivePoller(config.sharedReceivePollerThreads(), config.sharedReceivePollerFragmentLimit())
                : null;
        return new RpcNode(driver, aeron, exec, receivePoller);
    }

    /**
     * Создаёт новый канал и добавляет его в node-owned список каналов.
     *
     * <p>Creates a new channel and adds it to the node-owned channel list.</p>
     *
     * @param channelConfig конфигурация канала / channel configuration
     * @return новый RPC-канал / new RPC channel
     */
    public RpcChannel channel(final ChannelConfig channelConfig) {
        final RpcChannel ch = new RpcChannel(channelConfig, aeron, offloadExecutor, receivePoller);
        channels.add(ch);
        return ch;
    }

    /**
     * Закрывает каналы, Aeron client, optional MediaDriver и shared executor.
     *
     * <p>Closes all channels, the Aeron client, the optional MediaDriver, and
     * the shared executor.</p>
     */
    @Override
    public void close() {
        for (final RpcChannel ch : channels) {
            try {
                ch.close();
            } catch (final Throwable t) {
                // keep going
            }
        }
        channels.clear();
        if (receivePoller != null) {
            receivePoller.close();
        }
        offloadExecutor.shutdown();
        CloseHelper.closeAll(aeron, mediaDriver);
    }

    private static void ensureAeronDirParent(final String aeronDir) {
        final File dir = new File(aeronDir).getAbsoluteFile();
        final File parent = dir.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("cannot create parent dir for Aeron dir: " + parent);
        }
    }
}