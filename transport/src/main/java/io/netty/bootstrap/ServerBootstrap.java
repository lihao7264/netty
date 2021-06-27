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
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.util.AttributeKey;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** 参考博客：https://blog.csdn.net/mrathena/article/details/113102800
 * {@link Bootstrap} sub-class which allows easy bootstrap of {@link ServerChannel}
 *
 */
public class ServerBootstrap extends AbstractBootstrap<ServerBootstrap, ServerChannel> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ServerBootstrap.class);
    // 在 Server 接受一个 Client 的连接后，会创建一个对应的 Channel 对象。因此，我们看到 ServerBootstrap 的 childOptions、childAttrs、childGroup、childHandler 属性，都是这种 Channel 的可选项集合、属性集合、EventLoopGroup 对象、处理器。
    // The order in which child ChannelOptions are applied is important they may depend on each other for validation
    // purposes.
    private final Map<ChannelOption<?>, Object> childOptions = new LinkedHashMap<ChannelOption<?>, Object>(); // 子 Channel 的可选项集合
    private final Map<AttributeKey<?>, Object> childAttrs = new ConcurrentHashMap<AttributeKey<?>, Object>(); // 子 Channel 的属性集合
    private final ServerBootstrapConfig config = new ServerBootstrapConfig(this); // 启动类配置对象
    private volatile EventLoopGroup childGroup; // 子 Channel 的 EventLoopGroup 对象
    private volatile ChannelHandler childHandler; // 子 Channel 的处理器

    public ServerBootstrap() { }

    private ServerBootstrap(ServerBootstrap bootstrap) {
        super(bootstrap);
        childGroup = bootstrap.childGroup;
        childHandler = bootstrap.childHandler;
        synchronized (bootstrap.childOptions) {
            childOptions.putAll(bootstrap.childOptions);
        }
        childAttrs.putAll(bootstrap.childAttrs);
    }

    /** 设置 EventLoopGroup 到 group、childGroup 中。(该方法，group 和 childGroup 使用同一个)
     * Specify the {@link EventLoopGroup} which is used for the parent (acceptor) and the child (client).
     */
    @Override
    public ServerBootstrap group(EventLoopGroup group) {
        return group(group, group);
    }

    /**
     * Set the {@link EventLoopGroup} for the parent (acceptor) and the child (client). These
     * {@link EventLoopGroup}'s are used to handle all the events and IO for {@link ServerChannel} and
     * {@link Channel}'s.
     */
    public ServerBootstrap group(EventLoopGroup parentGroup, EventLoopGroup childGroup) {
        super.group(parentGroup);
        if (this.childGroup != null) {
            throw new IllegalStateException("childGroup set already");
        }
        this.childGroup = ObjectUtil.checkNotNull(childGroup, "childGroup");
        return this;
    }

    /** 设置子 Channel 的可选项。
     * Allow to specify a {@link ChannelOption} which is used for the {@link Channel} instances once they get created
     * (after the acceptor accepted the {@link Channel}). Use a value of {@code null} to remove a previous set
     * {@link ChannelOption}.
     */
    public <T> ServerBootstrap childOption(ChannelOption<T> childOption, T value) {
        ObjectUtil.checkNotNull(childOption, "childOption");
        synchronized (childOptions) {
            if (value == null) { // 空，意味着移除
                childOptions.remove(childOption);
            } else { // 非空，进行修改
                childOptions.put(childOption, value);
            }
        }
        return this;
    }

    /** 设置子 Channel 的属性。
     * Set the specific {@link AttributeKey} with the given value on every child {@link Channel}. If the value is
     * {@code null} the {@link AttributeKey} is removed
     */
    public <T> ServerBootstrap childAttr(AttributeKey<T> childKey, T value) {
        ObjectUtil.checkNotNull(childKey, "childKey");
        if (value == null) {
            childAttrs.remove(childKey); // 空，意味着移除
        } else { // 非空，进行修改
            childAttrs.put(childKey, value);
        }
        return this;
    }

    /** 设置子 Channel 的处理器。
     * Set the {@link ChannelHandler} which is used to serve the request for the {@link Channel}'s.
     */
    public ServerBootstrap childHandler(ChannelHandler childHandler) {
        this.childHandler = ObjectUtil.checkNotNull(childHandler, "childHandler");
        return this;
    }
    // 初始化 Channel 配置。（入参为ServerSocketChannel）
    @Override
    void init(Channel channel) { // 1、配置用户自定义的ChannelOptions、ChannelAttrs
        setChannelOptions(channel, newOptionsArray(), logger); // // 初始化 Channel 的可选项集合 (将启动器配置的可选项集合)
        setAttributes(channel, newAttributesArray());  // 初始化 Channel 的属性集合
        // 拿到服务端的pipeline
        ChannelPipeline p = channel.pipeline();
        // 记录当前的属性 (记录启动器配置的子 Channel的属性)
        final EventLoopGroup currentChildGroup = childGroup;
        final ChannelHandler currentChildHandler = childHandler;
        final Entry<ChannelOption<?>, Object>[] currentChildOptions = newOptionsArray(childOptions); // 为通过服务端Channel创建出来的新连接的channel创建的
        final Entry<AttributeKey<?>, Object>[] currentChildAttrs = newAttributesArray(childAttrs); // 为通过服务端Channel创建出来的新连接的channel创建的
        // 添加 ChannelInitializer 对象到 pipeline 中，用于后续初始化 ChannelHandler 到 pipeline 中。
        p.addLast(new ChannelInitializer<Channel>() {
            @Override
            public void initChannel(final Channel ch) { // 从 io.netty.channel.DefaultChannelPipeline.invokeHandlerAddedIfNeeded 进入执行
                final ChannelPipeline pipeline = ch.pipeline();
                ChannelHandler handler = config.handler(); // 添加启动器配置的 ChannelHandler 到 pipeline 中。（配置服务端pipeline）
                if (handler != null) {
                    pipeline.addLast(handler); // 用户自定义的服务端启动过程中执行的处理器添加到处理链中
                }
                // 添加 ServerBootstrapAcceptor 到 pipeline 中。(使用 EventLoop 执行的原因，参见 https://github.com/lightningMan/netty/commit/4638df20628a8987c8709f0f8e5f3679a914ce1a)
                ch.eventLoop().execute(new Runnable() { // 为什么使用 EventLoop 执行添加的过程？ 如果启动器配置的处理器，并且 ServerBootstrapAcceptor 不使用 EventLoop 添加，则会导致 ServerBootstrapAcceptor 添加到配置的处理器之前。
                    @Override
                    public void run() {
                        pipeline.addLast(new ServerBootstrapAcceptor( // ServerBootstrapAcceptor 也是一个 ChannelHandler 实现类，用于接受客户端的连接请求。
                                ch, currentChildGroup, currentChildHandler, currentChildOptions, currentChildAttrs)); // 创建 ServerBootstrapAcceptor 对象(连接器)。 （ServerBootstrapAcceptor作用就是给accept到的新连接分配一个NIO的线程）
                    }
                });
            }
        });
    }
    //  校验配置是否正确。
    @Override
    public ServerBootstrap validate() {
        super.validate();
        if (childHandler == null) {
            throw new IllegalStateException("childHandler not set");
        }
        if (childGroup == null) {
            logger.warn("childGroup is not set. Using parentGroup instead.");
            childGroup = config.group();
        }
        return this;
    }
    // 这是一个特殊的ChannelHandler --- 服务端连接器
    private static class ServerBootstrapAcceptor extends ChannelInboundHandlerAdapter {

        private final EventLoopGroup childGroup; // 用户自定义的childGroup（创建 worker 线程组 用于进行 SocketChannel 的数据读写）
        private final ChannelHandler childHandler; // 用户自定义的childHandler（通过io.netty.bootstrap.ServerBootstrap.childHandler(io.netty.channel.ChannelHandler)添加）
        private final Entry<ChannelOption<?>, Object>[] childOptions; // 用户自定义的childOptions
        private final Entry<AttributeKey<?>, Object>[] childAttrs; // 用户自定义的childAttrs
        private final Runnable enableAutoReadTask;

        ServerBootstrapAcceptor(
                final Channel channel, EventLoopGroup childGroup, ChannelHandler childHandler,
                Entry<ChannelOption<?>, Object>[] childOptions, Entry<AttributeKey<?>, Object>[] childAttrs) {
            this.childGroup = childGroup;
            this.childHandler = childHandler;
            this.childOptions = childOptions;
            this.childAttrs = childAttrs;

            // Task which is scheduled to re-enable auto-read.
            // It's important to create this Runnable before we try to submit it as otherwise the URLClassLoader may
            // not be able to load the class because of the file limit it already reached.
            //
            // See https://github.com/netty/netty/issues/1328
            enableAutoReadTask = new Runnable() {
                @Override
                public void run() {
                    channel.config().setAutoRead(true);
                }
            };
        }

        @Override
        @SuppressWarnings("unchecked")
        public void channelRead(ChannelHandlerContext ctx, Object msg) { // 通过pipeline的 fireChannelRead方法传播进来
            final Channel child = (Channel) msg;

            child.pipeline().addLast(childHandler); // 添加childHandler
            // childOptions、childAttrs 服务端启动的时候设置的
            setChannelOptions(child, childOptions, logger); // 设置channel的options（主要是跟底层tcp读写相关的参数）
            setAttributes(child, childAttrs); // childAttrs主要就是为了可以在客户端channel上自定义一些属性（密钥、存活时间等）

            try { // childGroup就是 workerGroup(一个NioEventLoopGroup)
                childGroup.register(child).addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {
                        if (!future.isSuccess()) {
                            forceClose(child, future.cause());
                        }
                    }
                }); // 选择
            } catch (Throwable t) {
                forceClose(child, t);
            }
        }

        private static void forceClose(Channel child, Throwable t) {
            child.unsafe().closeForcibly();
            logger.warn("Failed to register an accepted channel: {}", child, t);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            final ChannelConfig config = ctx.channel().config();
            if (config.isAutoRead()) {
                // stop accept new connections for 1 second to allow the channel to recover
                // See https://github.com/netty/netty/issues/1328
                config.setAutoRead(false);
                ctx.channel().eventLoop().schedule(enableAutoReadTask, 1, TimeUnit.SECONDS);
            }
            // still let the exceptionCaught event flow through the pipeline to give the user
            // a chance to do something with it
            ctx.fireExceptionCaught(cause);
        }
    }
    // 克隆 ServerBootstrap 对象
    @Override
    @SuppressWarnings("CloneDoesntCallSuperClone")
    public ServerBootstrap clone() {
        return new ServerBootstrap(this); // 调用参数为 bootstrap 为 ServerBootstrap 构造方法，克隆一个 ServerBootstrap 对象。(部分数据深克隆，部分数据浅克隆)
    }

    /**
     * Return the configured {@link EventLoopGroup} which will be used for the child channels or {@code null}
     * if non is configured yet.
     *
     * @deprecated Use {@link #config()} instead.
     */
    @Deprecated
    public EventLoopGroup childGroup() {
        return childGroup;
    }

    final ChannelHandler childHandler() {
        return childHandler;
    }

    final Map<ChannelOption<?>, Object> childOptions() {
        synchronized (childOptions) {
            return copiedMap(childOptions);
        }
    }

    final Map<AttributeKey<?>, Object> childAttrs() {
        return copiedMap(childAttrs);
    }

    @Override
    public final ServerBootstrapConfig config() {
        return config;
    }
}
