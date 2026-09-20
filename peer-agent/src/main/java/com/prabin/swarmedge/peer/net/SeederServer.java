package com.prabin.swarmedge.peer.net;

import com.prabin.swarmedge.common.id.AssetId;
import com.prabin.swarmedge.common.id.PeerId;
import com.prabin.swarmedge.manifest.ChunkStore;
import com.prabin.swarmedge.peer.chunk.ChunkInventory;
import com.prabin.swarmedge.peer.session.BlockSender;
import com.prabin.swarmedge.peer.session.PeerAuthPolicy;
import com.prabin.swarmedge.peer.session.SeederHandler;
import com.prabin.swarmedge.protocol.ProtocolLimits;
import com.prabin.swarmedge.protocol.codec.FrameEncoder;
import com.prabin.swarmedge.protocol.codec.StreamingFrameDecoder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Listens for leechers and serves verified chunks of one asset.
 *
 * <p>Disk reads run on their own single thread, so hashing and file access never sit on
 * a Netty event loop (blueprint §21.2). Write watermarks are set low enough that a slow
 * leecher makes the channel unwritable, which is the signal the seeder uses to stop
 * pulling more blocks off disk.
 */
public final class SeederServer implements AutoCloseable {

    private final EventLoopGroup acceptGroup;
    private final EventLoopGroup ioGroup;
    private final ExecutorService diskExecutor;
    private final Channel channel;

    private volatile SeederHandler lastSession;

    public SeederServer(Config config) throws IOException {
        Objects.requireNonNull(config, "config");
        this.acceptGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        this.ioGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
        this.diskExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "seeder-disk");
            thread.setDaemon(true);
            return thread;
        });
        try {
            this.channel = new ServerBootstrap()
                    .group(acceptGroup, ioGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 64)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
                            new WriteBufferWaterMark(config.lowWaterMark(), config.highWaterMark()))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            SeederHandler handler = new SeederHandler(
                                    config.assetId(), config.peerId(), config.inventory(), config.store(),
                                    new BlockSender(config.sendMode()), diskExecutor, config.authPolicy(),
                                    config.maxBlockSize(), config.handshakeTimeout(), config.upload());
                            lastSession = handler;
                            ch.pipeline().addLast(
                                    new StreamingFrameDecoder(ProtocolLimits.MAX_FRAME_LENGTH, config.maxBlockSize()),
                                    new FrameEncoder(),
                                    handler);
                        }
                    })
                    .bind(config.port())
                    .sync()
                    .channel();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            shutdown();
            throw new IOException("interrupted while binding the seeder", e);
        } catch (RuntimeException e) {
            shutdown();
            throw e;
        }
    }

    public int port() {
        return ((InetSocketAddress) channel.localAddress()).getPort();
    }

    public InetSocketAddress address() {
        return new InetSocketAddress("127.0.0.1", port());
    }

    /** The most recently accepted session, for tests and diagnostics. */
    public Optional<SeederHandler> lastSession() {
        return Optional.ofNullable(lastSession);
    }

    @Override
    public void close() {
        channel.close().awaitUninterruptibly(5, TimeUnit.SECONDS);
        shutdown();
    }

    private void shutdown() {
        acceptGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        ioGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        diskExecutor.shutdownNow();
    }

    /**
     * @param port             0 binds an ephemeral port, which is what tests and local runs want
     * @param handshakeTimeout how long a connection may sit below ACTIVE before it is dropped
     */
    public record Config(
            int port,
            AssetId assetId,
            PeerId peerId,
            ChunkInventory inventory,
            ChunkStore store,
            BlockSender.Mode sendMode,
            PeerAuthPolicy authPolicy,
            int maxBlockSize,
            int lowWaterMark,
            int highWaterMark,
            Duration handshakeTimeout,
            SeederHandler.Settings upload) {

        public static final Duration DEFAULT_HANDSHAKE_TIMEOUT = Duration.ofSeconds(10);

        public Config {
            Objects.requireNonNull(assetId, "assetId");
            Objects.requireNonNull(peerId, "peerId");
            Objects.requireNonNull(inventory, "inventory");
            Objects.requireNonNull(store, "store");
            Objects.requireNonNull(sendMode, "sendMode");
            Objects.requireNonNull(authPolicy, "authPolicy");
            Objects.requireNonNull(handshakeTimeout, "handshakeTimeout");
            upload = upload == null ? SeederHandler.Settings.desktop() : upload;
            if (maxBlockSize <= 0 || maxBlockSize > ProtocolLimits.absoluteMaxBlockSize()) {
                throw new IllegalArgumentException("maxBlockSize out of range: " + maxBlockSize);
            }
            if (lowWaterMark <= 0 || highWaterMark < lowWaterMark) {
                throw new IllegalArgumentException("water marks must be positive and ordered");
            }
        }

        /** Desktop upload budget, which is what every existing caller asked for. */
        public Config(int port, AssetId assetId, PeerId peerId, ChunkInventory inventory, ChunkStore store,
                      BlockSender.Mode sendMode, PeerAuthPolicy authPolicy, int maxBlockSize,
                      int lowWaterMark, int highWaterMark, Duration handshakeTimeout) {
            this(port, assetId, peerId, inventory, store, sendMode, authPolicy, maxBlockSize,
                    lowWaterMark, highWaterMark, handshakeTimeout, SeederHandler.Settings.desktop());
        }

        public static Config of(AssetId assetId, PeerId peerId, ChunkInventory inventory, ChunkStore store,
                                BlockSender.Mode sendMode, int maxBlockSize) {
            return new Config(0, assetId, peerId, inventory, store, sendMode,
                    PeerAuthPolicy.ACCEPT_ANY_TOKEN, maxBlockSize, maxBlockSize, maxBlockSize * 4,
                    DEFAULT_HANDSHAKE_TIMEOUT, SeederHandler.Settings.desktop());
        }
    }
}
