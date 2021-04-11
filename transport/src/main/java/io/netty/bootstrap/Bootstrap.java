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
package io.netty.bootstrap;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.DefaultAddressResolverGroup;
import io.netty.resolver.NameResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

/** 实现 AbstractBootstrap 抽象类，用于 Client 的启动器实现类。
 * A {@link Bootstrap} that makes it easy to bootstrap a {@link Channel} to use
 * for clients.
 *
 * <p>The {@link #bind()} methods are useful in combination with connectionless transports such as datagram (UDP).
 * For regular TCP connections, please use the provided {@link #connect()} methods.</p>
 */
public class Bootstrap extends AbstractBootstrap<Bootstrap, Channel> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(Bootstrap.class);
    // 默认地址解析器对象
    private static final AddressResolverGroup<?> DEFAULT_RESOLVER = DefaultAddressResolverGroup.INSTANCE;
    // 启动类配置对象
    private final BootstrapConfig config = new BootstrapConfig(this);
    // 地址解析器对象(绝大多数情况下，使用 DEFAULT_RESOLVER 即可。)
    @SuppressWarnings("unchecked")
    private volatile AddressResolverGroup<SocketAddress> resolver =
            (AddressResolverGroup<SocketAddress>) DEFAULT_RESOLVER;
    private volatile SocketAddress remoteAddress; // 连接地址

    public Bootstrap() { }

    private Bootstrap(Bootstrap bootstrap) {
        super(bootstrap);
        resolver = bootstrap.resolver;
        remoteAddress = bootstrap.remoteAddress;
    }

    /** 设置 resolver 属性
     * Sets the {@link NameResolver} which will resolve the address of the unresolved named address.
     *
     * @param resolver the {@link NameResolver} for this {@code Bootstrap}; may be {@code null}, in which case a default
     *                 resolver will be used
     *
     * @see io.netty.resolver.DefaultAddressResolverGroup
     */
    @SuppressWarnings("unchecked")
    public Bootstrap resolver(AddressResolverGroup<?> resolver) {
        this.resolver = (AddressResolverGroup<SocketAddress>) (resolver == null ? DEFAULT_RESOLVER : resolver);
        return this;
    }
    /*********************** 设置 remoteAddress 属性 ,三个重载的方法***************************/
    /**
     * The {@link SocketAddress} to connect to once the {@link #connect()} method
     * is called.
     */
    public Bootstrap remoteAddress(SocketAddress remoteAddress) {
        this.remoteAddress = remoteAddress;
        return this;
    }

    /**
     * @see #remoteAddress(SocketAddress)
     */
    public Bootstrap remoteAddress(String inetHost, int inetPort) {
        remoteAddress = InetSocketAddress.createUnresolved(inetHost, inetPort);
        return this;
    }

    /**
     * @see #remoteAddress(SocketAddress)
     */
    public Bootstrap remoteAddress(InetAddress inetHost, int inetPort) {
        remoteAddress = new InetSocketAddress(inetHost, inetPort);
        return this;
    }
    /********************** 连接服务端，即启动客户端。(重载5个)  返回的是 ChannelFuture 对象，也就是异步的连接服务端，启动客户端。如果需要同步，则需要调用 ChannelFuture#sync() 方法。********************/
    /**
     * Connect a {@link Channel} to the remote peer.
     */
    public ChannelFuture connect() {
        validate(); // 校验必要参数
        SocketAddress remoteAddress = this.remoteAddress;
        if (remoteAddress == null) {
            throw new IllegalStateException("remoteAddress not set");
        }
        // 解析远程地址，并进行连接
        return doResolveAndConnect(remoteAddress, config.localAddress());
    }

    /**
     * Connect a {@link Channel} to the remote peer.
     */
    public ChannelFuture connect(String inetHost, int inetPort) {
        return connect(InetSocketAddress.createUnresolved(inetHost, inetPort));
    }

    /**
     * Connect a {@link Channel} to the remote peer.
     */
    public ChannelFuture connect(InetAddress inetHost, int inetPort) {
        return connect(new InetSocketAddress(inetHost, inetPort));
    }

    /**
     * Connect a {@link Channel} to the remote peer.
     */
    public ChannelFuture connect(SocketAddress remoteAddress) {
        ObjectUtil.checkNotNull(remoteAddress, "remoteAddress");
        validate(); // 校验必要参数
        return doResolveAndConnect(remoteAddress, config.localAddress());     // 解析远程地址，并进行连接
    }

    /**
     * Connect a {@link Channel} to the remote peer.
     */
    public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress) {
        ObjectUtil.checkNotNull(remoteAddress, "remoteAddress");
        validate(); // 校验必要参数
        return doResolveAndConnect(remoteAddress, localAddress);  // 解析远程地址，并进行连接
    }

    /** resolve 和 connect 的逻辑，执行在 register 的逻辑之后。
     * @see #connect()
     */
    private ChannelFuture doResolveAndConnect(final SocketAddress remoteAddress, final SocketAddress localAddress) {
        final ChannelFuture regFuture = initAndRegister();  // 初始化并注册一个 Channel 对象，因为注册是异步的过程，所以返回一个 ChannelFuture 对象。
        final Channel channel = regFuture.channel();

        if (regFuture.isDone()) { // 注册是异步的过程，注册已完成
            if (!regFuture.isSuccess()) {  // 若执行失败，直接进行返回。
                return regFuture;
            }
            return doResolveAndConnect0(channel, remoteAddress, localAddress, channel.newPromise()); // 解析远程地址，并进行连接
        } else { // 注册是异步的过程，注册未完成
            // Registration future is almost always fulfilled already, but just in case it's not.
            final PendingRegistrationPromise promise = new PendingRegistrationPromise(channel);
            regFuture.addListener(new ChannelFutureListener() { // 调用 ChannelFuture#addListener(ChannelFutureListener) 方法，添加监听器，在注册完成后，进行回调执行 #doResolveAndConnect0(...) 方法的逻辑。
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    // Directly obtain the cause and do a null check so we only need one volatile read in case of a
                    // failure.
                    Throwable cause = future.cause();
                    if (cause != null) {
                        // Registration on the EventLoop failed so fail the ChannelPromise directly to not cause an
                        // IllegalStateException once we try to access the EventLoop of the Channel.
                        promise.setFailure(cause);
                    } else {
                        // Registration was successful, so set the correct executor to use.
                        // See https://github.com/netty/netty/issues/2586
                        promise.registered();
                        doResolveAndConnect0(channel, remoteAddress, localAddress, promise); // 解析远程地址，并进行连接
                    }
                }
            });
            return promise;
        }
    }
    // connect 的逻辑，执行在 resolve 的逻辑之后。
    private ChannelFuture doResolveAndConnect0(final Channel channel, SocketAddress remoteAddress,
                                               final SocketAddress localAddress, final ChannelPromise promise) {
        try {
            final EventLoop eventLoop = channel.eventLoop();
            AddressResolver<SocketAddress> resolver;
            try {
                resolver = this.resolver.getResolver(eventLoop); // 获取地址解析器
            } catch (Throwable cause) {
                channel.close();
                return promise.setFailure(cause);
            }

            if (!resolver.isSupported(remoteAddress) || resolver.isResolved(remoteAddress)) { // 解析器不知道如何处理指定的远程地址，或者它已被解析。
                // Resolver has no idea about what to do with the specified remote address or it's resolved already.
                doConnect(remoteAddress, localAddress, promise);
                return promise;
            }
            // 解析远程地址
            final Future<SocketAddress> resolveFuture = resolver.resolve(remoteAddress); //使用 resolver 解析远程地址。因为解析是异步的过程，所以返回一个 Future 对象。

            if (resolveFuture.isDone()) { // 因为注册是异步的过程，有可能已完成，有可能未完成。
                final Throwable resolveFailureCause = resolveFuture.cause();  // 解析远程地址失败，关闭 Channel ，并回调通知 promise 异常

                if (resolveFailureCause != null) {
                    // Failed to resolve immediately
                    channel.close();
                    promise.setFailure(resolveFailureCause);
                } else {
                    // Succeeded to resolve immediately; cached? (or did a blocking lookup)
                    doConnect(resolveFuture.getNow(), localAddress, promise); // 连接远程地址
                }
                return promise;
            }

            // Wait until the name resolution is finished.
            resolveFuture.addListener(new FutureListener<SocketAddress>() { // 调用 Future#addListener(FutureListener) 方法，添加监听器，在解析完成后，进行回调执行 #doConnect(...) 方法的逻辑。
                @Override
                public void operationComplete(Future<SocketAddress> future) throws Exception {
                    if (future.cause() != null) { // 解析远程地址失败，关闭 Channel ，并回调通知 promise 异常
                        channel.close();
                        promise.setFailure(future.cause());
                    } else {
                        doConnect(future.getNow(), localAddress, promise);  // 解析远程地址成功，连接远程地址
                    }
                }
            });
        } catch (Throwable cause) {
            promise.tryFailure(cause); // 发生异常，并回调通知 promise 异常
        }
        return promise;
    }
    // 执行 Channel 连接远程地址的逻辑。
    private static void doConnect(
            final SocketAddress remoteAddress, final SocketAddress localAddress, final ChannelPromise connectPromise) {

        // This method is invoked before channelRegistered() is triggered.  Give user handlers a chance to set up
        // the pipeline in its channelRegistered() implementation.
        final Channel channel = connectPromise.channel();
        channel.eventLoop().execute(new Runnable() {
            @Override
            public void run() {
                if (localAddress == null) {
                    channel.connect(remoteAddress, connectPromise);
                } else {
                    channel.connect(remoteAddress, localAddress, connectPromise);
                }
                connectPromise.addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
            }
        });
    }

    @Override
    void init(Channel channel) {
        ChannelPipeline p = channel.pipeline();
        p.addLast(config.handler());  // 添加处理器到 pipeline 中

        setChannelOptions(channel, newOptionsArray(), logger); // 初始化 Channel 的可选项集合
        setAttributes(channel, newAttributesArray());  // 初始化 Channel 的属性集合
    }
    // 校验配置是否正确  (在 #connect(...) 方法中，连接服务端时，会调用该方法进行校验。)
    @Override
    public Bootstrap validate() {
        super.validate();      // 父类校验
        if (config.handler() == null) {  // handler 非空
            throw new IllegalStateException("handler not set");
        }
        return this;
    }
    // 克隆 Bootstrap 对象
    @Override
    @SuppressWarnings("CloneDoesntCallSuperClone")
    public Bootstrap clone() {
        return new Bootstrap(this);
    }

    /**
     * Returns a deep clone of this bootstrap which has the identical configuration except that it uses
     * the given {@link EventLoopGroup}. This method is useful when making multiple {@link Channel}s with similar
     * settings.
     */
    public Bootstrap clone(EventLoopGroup group) {
        Bootstrap bs = new Bootstrap(this);
        bs.group = group;
        return bs;
    }

    @Override
    public final BootstrapConfig config() {
        return config;
    }

    final SocketAddress remoteAddress() {
        return remoteAddress;
    }

    final AddressResolverGroup<?> resolver() {
        return resolver;
    }
}
