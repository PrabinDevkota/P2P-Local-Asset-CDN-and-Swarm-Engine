package com.prabin.swarmedge.peer.net;

import com.prabin.swarmedge.common.id.AssetId;
import com.prabin.swarmedge.common.id.PeerId;
import com.prabin.swarmedge.peer.chunk.BlockPlan;
import com.prabin.swarmedge.peer.chunk.BlockSource;
import com.prabin.swarmedge.peer.chunk.ChunkAssembler;
import com.prabin.swarmedge.peer.chunk.ChunkInventory;
import com.prabin.swarmedge.peer.session.LeecherHandler;
import com.prabin.swarmedge.peer.session.SessionEvents;
import com.prabin.swarmedge.protocol.ProtocolLimits;
import com.prabin.swarmedge.protocol.codec.FrameEncoder;
import com.prabin.swarmedge.protocol.codec.StreamingFrameDecoder;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Dials one seeder and fetches the chunks this peer is missing.
 *
 * <p>The disk executor is single-threaded on purpose: block writes and the commit that
 * follows them are ordered by the order they are submitted, which is only true with one
 * thread behind the queue.
 */
public final class LeecherClient implements AutoCloseable {

    private final EventLoopGroup ioGroup;
    private final ExecutorService diskExecutor;

    public LeecherClient() {
        this.ioGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        this.diskExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "leecher-disk");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Start a transfer. The returned future completes when every chunk in the manifest
     * is verified on disk, and fails closed with the reason otherwise.
     */
    public CompletableFuture<LeecherHandler.Result> fetch(InetSocketAddress seeder, Request request) {
        return open(seeder, request).completion();
    }

    /**
     * Start one session of a swarm. The caller keeps the handler because a swarm has to
     * be able to announce a HAVE to peers other than the one that supplied the chunk.
     *
     * <p>The returned future completes when this session has nothing left to do, which
     * is not the same as the asset being complete.
     */
    public LeecherHandler open(InetSocketAddress seeder, Request request) {
        Objects.requireNonNull(seeder, "seeder");
        Objects.requireNonNull(request, "request");

        LeecherHandler handler = new LeecherHandler(request.assetId(), request.peerId(), request.token(),
                request.inventory(), request.assembler(), diskExecutor, request.settings(),
                request.sources(), request.events());
        ChannelFuture connect = new Bootstrap()
                .group(ioGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) request.connectTimeout().toMillis())
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(
                                new StreamingFrameDecoder(ProtocolLimits.MAX_FRAME_LENGTH,
                                        request.settings().blockSize()),
                                new FrameEncoder(),
                                handler);
                    }
                })
                .connect(seeder);

        CompletableFuture<LeecherHandler.Result> completion = handler.completion();
        connect.addListener(future -> {
            if (!future.isSuccess()) {
                completion.completeExceptionally(future.cause());
            }
        });
        completion.whenComplete((result, failure) -> {
            Channel channel = connect.channel();
            if (channel != null) {
                channel.close();
            }
        });
        return handler;
    }

    @Override
    public void close() {
        ioGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly(5, TimeUnit.SECONDS);
        diskExecutor.shutdownNow();
    }

    /**
     * @param token   opaque bytes forwarded in HELLO; see {@code PeerAuthPolicy} for what
     *                the seeder currently does with it
     * @param sources where this session takes blocks from: its own queue, or a swarm's
     * @param events  what this session reports back, which a swarm needs and one peer does not
     */
    public record Request(
            AssetId assetId,
            PeerId peerId,
            byte[] token,
            ChunkInventory inventory,
            ChunkAssembler assembler,
            LeecherHandler.Settings settings,
            Duration connectTimeout,
            BlockSource.Factory sources,
            SessionEvents events) {

        public Request {
            Objects.requireNonNull(assetId, "assetId");
            Objects.requireNonNull(peerId, "peerId");
            token = Objects.requireNonNull(token, "token").clone();
            Objects.requireNonNull(inventory, "inventory");
            Objects.requireNonNull(assembler, "assembler");
            Objects.requireNonNull(settings, "settings");
            Objects.requireNonNull(connectTimeout, "connectTimeout");
            Objects.requireNonNull(sources, "sources");
            Objects.requireNonNull(events, "events");
        }

        /** One peer and one queue: the plan is this session's alone. */
        public static Request singlePeer(AssetId assetId, PeerId peerId, byte[] token,
                                        ChunkInventory inventory, ChunkAssembler assembler,
                                        LeecherHandler.Settings settings, Duration connectTimeout) {
            return new Request(assetId, peerId, token, inventory, assembler, settings, connectTimeout,
                    negotiated -> new BlockPlan(inventory, negotiated), SessionEvents.NONE);
        }

        @Override
        public byte[] token() {
            return token.clone();
        }
    }
}
