/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel.socket.nio;

import io.netty.channel.ChannelException;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.util.internal.SocketUtils;
import io.netty.channel.nio.AbstractNioMessageChannel;
import io.netty.channel.socket.DefaultServerSocketChannelConfig;
import io.netty.channel.socket.ServerSocketChannelConfig;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.SuppressJava6Requirement;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.List;
import java.util.Map;

/**
 * A {@link io.netty.channel.socket.ServerSocketChannel} implementation which uses
 * NIO selector based implementation to accept new connections.
 */
public class NioServerSocketChannel extends AbstractNioMessageChannel
                             implements io.netty.channel.socket.ServerSocketChannel {

    private static final ChannelMetadata METADATA = new ChannelMetadata(false, 16);
    private static final SelectorProvider DEFAULT_SELECTOR_PROVIDER = SelectorProvider.provider(); // 默认的 SelectorProvider 实现类。

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(NioServerSocketChannel.class);
    // 创建 NIO 的 ServerSocketChannel 对象(效果和 ServerSocketChannel#open() 方法创建 ServerSocketChannel 对象是一致。)
    private static ServerSocketChannel newSocket(SelectorProvider provider) {
        try {
            /**
             *  Use the {@link SelectorProvider} to open {@link SocketChannel} and so remove condition in
             *  {@link SelectorProvider#provider()} which is called by each ServerSocketChannel.open() otherwise.
             *
             *  See <a href="https://github.com/netty/netty/issues/2308">#2308</a>.
             */
            return provider.openServerSocketChannel(); // 创建jdk的ServerSocketChannel
        } catch (IOException e) {
            throw new ChannelException(
                    "Failed to open a server socket.", e);
        }
    }
    // Channel 对应的配置对象。(每种 Channel 实现类，也会对应一个 ChannelConfig 实现类。例如:NioServerSocketChannel类，对应 ServerSocketChannelConfig 配置类。)
    private final ServerSocketChannelConfig config;

    /**
     * Create a new instance
     */
    public NioServerSocketChannel() {
        this(newSocket(DEFAULT_SELECTOR_PROVIDER));
    }

    /**
     * Create a new instance using the given {@link SelectorProvider}.
     */
    public NioServerSocketChannel(SelectorProvider provider) {
        this(newSocket(provider));
    }

    /**
     * Create a new instance using the given {@link ServerSocketChannel}.
     */
    public NioServerSocketChannel(ServerSocketChannel channel) { // javaChannel()就是获取jdk的ServerSocketChannel
        super(null, channel, SelectionKey.OP_ACCEPT); // 调用父 AbstractNioMessageChannel 的构造方法。(传入的 SelectionKey 的值为 OP_ACCEPT 。) ---  AbstractNioMessageChannel 是 SelectionKey.OP_ACCEPT
        config = new NioServerSocketChannelConfig(this, javaChannel().socket()); // 初始化 config 属性，创建 NioServerSocketChannelConfig 对象。（tcp参数配置类，其实就是ServerSocket配置的抽象）
    }

    @Override
    public InetSocketAddress localAddress() {
        return (InetSocketAddress) super.localAddress();
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    @Override
    public ServerSocketChannelConfig config() {
        return config;
    }
    // 获得 Channel 是否激活( active )。
    @Override
    public boolean isActive() {
        // As java.nio.ServerSocketChannel.isBound() will continue to return true even after the channel was closed
        // we will also need to check if it is open.
        return isOpen() && javaChannel().socket().isBound(); // 判断 ServerSocketChannel 是否绑定端口。
    }

    @Override
    public InetSocketAddress remoteAddress() {
        return null;
    }

    @Override
    protected ServerSocketChannel javaChannel() { // 获取服务端启动过程中创建的ServerSocketChannel
        return (ServerSocketChannel) super.javaChannel();
    }

    @Override
    protected SocketAddress localAddress0() {
        return SocketUtils.localSocketAddress(javaChannel().socket());
    }
    //  绑定 Channel 的端口。 （服务端的 Java 原生 NIO ServerSocketChannel 终于绑定端口）
    @SuppressJava6Requirement(reason = "Usage guarded by java version check")
    @Override
    protected void doBind(SocketAddress localAddress) throws Exception { // 调用jdk底层channel进行端口绑定
        if (PlatformDependent.javaVersion() >= 7) {
            javaChannel().bind(localAddress, config.getBacklog()); // java.nio.channels.NetworkChannel类出现之后才能使用该方法
        } else {
            javaChannel().socket().bind(localAddress, config.getBacklog()); // java 7以下
        }
    }

    @Override
    protected void doClose() throws Exception {
        javaChannel().close();
    }

    @Override
    protected int doReadMessages(List<Object> buf) throws Exception { // 服务端的该方法其实就是 doAcceptChannels
        SocketChannel ch = SocketUtils.accept(javaChannel()); // ServerSocketChannel的accept，获取到一个连接的SocketChannel

        try {
            if (ch != null) { // this为服务端的channel、ch为客户端的jdk channel（SocketChannel）
                buf.add(new NioSocketChannel(this, ch)); // 把 NIO的channel（SocketChannel） 封装成 Netty的channel（NioSocketChannel），放入buf（也就是一个容器）中
                return 1;
            }
        } catch (Throwable t) {
            logger.warn("Failed to create a new channel from an accepted socket.", t);

            try {
                ch.close();
            } catch (Throwable t2) {
                logger.warn("Failed to close a socket.", t2);
            }
        }

        return 0; // 没有读取到连接，就直接返回0
    }

    // Unnecessary stuff
    @Override
    protected boolean doConnect(
            SocketAddress remoteAddress, SocketAddress localAddress) throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    protected void doFinishConnect() throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return null;
    }

    @Override
    protected void doDisconnect() throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    protected boolean doWriteMessage(Object msg, ChannelOutboundBuffer in) throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    protected final Object filterOutboundMessage(Object msg) throws Exception {
        throw new UnsupportedOperationException();
    }

    private final class NioServerSocketChannelConfig extends DefaultServerSocketChannelConfig {
        private NioServerSocketChannelConfig(NioServerSocketChannel channel, ServerSocket javaSocket) {
            super(channel, javaSocket);
        }

        @Override
        protected void autoReadCleared() {
            clearReadPending();
        }

        @Override
        public <T> boolean setOption(ChannelOption<T> option, T value) {
            if (PlatformDependent.javaVersion() >= 7 && option instanceof NioChannelOption) {
                return NioChannelOption.setOption(jdkChannel(), (NioChannelOption<T>) option, value);
            }
            return super.setOption(option, value);
        }

        @Override
        public <T> T getOption(ChannelOption<T> option) {
            if (PlatformDependent.javaVersion() >= 7 && option instanceof NioChannelOption) {
                return NioChannelOption.getOption(jdkChannel(), (NioChannelOption<T>) option);
            }
            return super.getOption(option);
        }

        @Override
        public Map<ChannelOption<?>, Object> getOptions() {
            if (PlatformDependent.javaVersion() >= 7) {
                return getOptions(super.getOptions(), NioChannelOption.getOptions(jdkChannel()));
            }
            return super.getOptions();
        }

        private ServerSocketChannel jdkChannel() {
            return ((NioServerSocketChannel) channel).javaChannel();
        }
    }

    // Override just to to be able to call directly via unit tests.
    @Override
    protected boolean closeOnReadError(Throwable cause) {
        return super.closeOnReadError(cause);
    }
}
