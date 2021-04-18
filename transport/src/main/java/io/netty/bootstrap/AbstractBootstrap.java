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
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ReflectiveChannelFactory;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.SocketUtils;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.logging.InternalLogger;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link AbstractBootstrap} is a helper class that makes it easy to bootstrap a {@link Channel}. It support
 * method-chaining to provide an easy way to configure the {@link AbstractBootstrap}.
 *
 * <p>When not used in a {@link ServerBootstrap} context, the {@link #bind()} methods are useful for connectionless
 * transports such as datagram (UDP).</p>
 */ //AbstractBootstrap 是个抽象类，并且实现 Cloneable 接口。另外，它声明了 B 、C 两个泛型： 1、B ：继承 AbstractBootstrap 类，用于表示自身的类型。(用于"链式调用") 2、C ：继承 Channel 类，表示表示创建的 Channel 类型。
public abstract class AbstractBootstrap<B extends AbstractBootstrap<B, C>, C extends Channel> implements Cloneable {
    @SuppressWarnings("unchecked")
    private static final Map.Entry<ChannelOption<?>, Object>[] EMPTY_OPTION_ARRAY = new Map.Entry[0];
    @SuppressWarnings("unchecked")
    private static final Map.Entry<AttributeKey<?>, Object>[] EMPTY_ATTRIBUTE_ARRAY = new Map.Entry[0];

    volatile EventLoopGroup group; // EventLoopGroup 对象（创建 boss 线程组 用于接受连接）
    @SuppressWarnings("deprecation")
    private volatile ChannelFactory<? extends C> channelFactory; //  Channel 工厂:用于创建 Channel 对象。
    private volatile SocketAddress localAddress; // 本地地址

    // The order in which ChannelOptions are applied is important they may depend on each other for validation purposes.
    // ChannelOptions的应用顺序很重要，它们可能相互依赖以进行验证。
    private final Map<ChannelOption<?>, Object> options = new LinkedHashMap<ChannelOption<?>, Object>(); // 可选项集合
    private final Map<AttributeKey<?>, Object> attrs = new ConcurrentHashMap<AttributeKey<?>, Object>(); // 属性集合(使用ConcurrentHashMap作为并发容器) -- 可以理解为java.nio.channels.SelectionKey 的 attachment 属性，并且类型为 Map 。
    private volatile ChannelHandler handler; // 处理器

    AbstractBootstrap() { // 禁止从其它程序包扩展。（protected）
        // Disallow extending from a different package.
    }

    AbstractBootstrap(AbstractBootstrap<B, C> bootstrap) {
        group = bootstrap.group;
        channelFactory = bootstrap.channelFactory;
        handler = bootstrap.handler;
        localAddress = bootstrap.localAddress;
        synchronized (bootstrap.options) {  // synchronized 修饰符（因为传入的 bootstrap 参数的 options  属性，可能在另外的线程被修改( 例如，下面会看到的 #option(hannelOption<T> option, T value) 方法)，通过 synchronized 来同步，解决此问题。）
            options.putAll(bootstrap.options);
        }
        attrs.putAll(bootstrap.attrs);
    }

    /**
     * The {@link EventLoopGroup} which is used to handle all the events for the to-be-created {@link Channel}
     * {@link EventLoopGroup}用于处理要创建的{@link Channel}的所有事件 (设置EventLoopGroup到group中)
     */
    public B group(EventLoopGroup group) {
        ObjectUtil.checkNotNull(group, "group");
        if (this.group != null) {
            throw new IllegalStateException("group set already");
        }
        this.group = group;
        return self(); // 最终调用 #self() 方法，返回自己。((用于"链式调用"))
    }
    // 返回自己
    @SuppressWarnings("unchecked")
    private B self() {
        return (B) this;  // 使用到了 AbstractBootstrap 声明的 B 泛型。((用于"链式调用"))
    }

    /**
     * The {@link Class} which is used to create {@link Channel} instances from.
     * You either use this or {@link #channelFactory(io.netty.channel.ChannelFactory)} if your
     * {@link Channel} implementation has no no-args constructor.
     */ // 设置要被实例化的 Channel 的类。
    public B channel(Class<? extends C> channelClass) {
        return channelFactory(new ReflectiveChannelFactory<C>( //虽然传入的 channelClass 参数，但是会使用 io.netty.channel.ReflectiveChannelFactory 进行封装。
                ObjectUtil.checkNotNull(channelClass, "channelClass")
        ));
    }

    /**
     * @deprecated Use {@link #channelFactory(io.netty.channel.ChannelFactory)} instead.
     */ // 设置 channelFactory 属性。 (最初 ChannelFactory 在 bootstrap 中，后重构到 channel 包中。)
    @Deprecated
    public B channelFactory(ChannelFactory<? extends C> channelFactory) {
        ObjectUtil.checkNotNull(channelFactory, "channelFactory");
        if (this.channelFactory != null) { // 不允许重复设置
            throw new IllegalStateException("channelFactory set already");
        }

        this.channelFactory = channelFactory;
        return self();
    }

    /**
     * {@link io.netty.channel.ChannelFactory} which is used to create {@link Channel} instances from
     * when calling {@link #bind()}. This method is usually only used if {@link #channel(Class)}
     * is not working for you because of some more complex needs. If your {@link Channel} implementation
     * has a no-args constructor, its highly recommend to just use {@link #channel(Class)} to
     * simplify your code.
     */
    @SuppressWarnings({ "unchecked", "deprecation" })
    public B channelFactory(io.netty.channel.ChannelFactory<? extends C> channelFactory) {
        return channelFactory((ChannelFactory<C>) channelFactory); //
    }
    /*********************** 设置创建 Channel 的本地地址(四个重载的方法)  ***************************/
    /** 注意：一般不会调用该方法进行配置，而是调用 #bind(...) 方法
     * The {@link SocketAddress} which is used to bind the local "end" to.
     */
    public B localAddress(SocketAddress localAddress) {
        this.localAddress = localAddress;
        return self();
    }

    /**
     * @see #localAddress(SocketAddress)
     */
    public B localAddress(int inetPort) {
        return localAddress(new InetSocketAddress(inetPort));
    }

    /**
     * @see #localAddress(SocketAddress)
     */
    public B localAddress(String inetHost, int inetPort) {
        return localAddress(SocketUtils.socketAddress(inetHost, inetPort));
    }

    /**
     * @see #localAddress(SocketAddress)
     */
    public B localAddress(InetAddress inetHost, int inetPort) {
        return localAddress(new InetSocketAddress(inetHost, inetPort));
    }

    /** 设置创建 Channel 的可选项。(设置要创建的 Channel 的可选项)
     * Allow to specify a {@link ChannelOption} which is used for the {@link Channel} instances once they got
     * created. Use a value of {@code null} to remove a previous set {@link ChannelOption}.
     */
    public <T> B option(ChannelOption<T> option, T value) {
        ObjectUtil.checkNotNull(option, "option");
        synchronized (options) {
            if (value == null) { // 空，意味着移除
                options.remove(option);
            } else { // 非空，进行修改
                options.put(option, value);
            }
        }
        return self();
    }

    /** 设置创建 Channel 的属性。
     * Allow to specify an initial attribute of the newly created {@link Channel}.  If the {@code value} is
     * {@code null}, the attribute of the specified {@code key} is removed.
     */
    public <T> B attr(AttributeKey<T> key, T value) {
        ObjectUtil.checkNotNull(key, "key");
        if (value == null) {  // 空，意味着移除
            attrs.remove(key);
        } else {  // 非空，进行修改
            attrs.put(key, value);
        }
        return self();
    }

    /** 校验配置是否正确。
     * Validate all the parameters. Sub-classes may override this, but should
     * call the super method in that case.
     */ // 在 #bind(...) 方法中，绑定本地地址时，会调用该方法进行校验。
    public B validate() {
        if (group == null) {
            throw new IllegalStateException("group not set");
        }
        if (channelFactory == null) {
            throw new IllegalStateException("channel or channelFactory not set");
        }
        return self();
    }

    /** #clone() 抽象方法，克隆一个 AbstractBootstrap 对象。
     * Returns a deep clone of this bootstrap which has the identical configuration.  This method is useful when making
     * multiple {@link Channel}s with similar settings.  Please note that this method does not clone the
     * {@link EventLoopGroup} deeply but shallowly, making the group a shared resource.
     */ // 来自实现 Cloneable 接口，在子类中实现。这是深拷贝，即创建一个新对象，但不是所有的属性是深拷贝。 浅拷贝属性：group、channelFactory、handler、localAddress 。(这些变量的引用还是一致的) 深拷贝属性：options、attrs 。
    @Override
    @SuppressWarnings("CloneDoesntDeclareCloneNotSupportedException")
    public abstract B clone();

    /**
     * Create a new {@link Channel} and register it with an {@link EventLoop}.
     */
    public ChannelFuture register() {
        validate();
        return initAndRegister();
    }
   /*********************** bind()方法返回的是 ChannelFuture 对象，也就是异步的绑定端口，启动服务端。如果需要同步，则需要调用 ChannelFuture#sync() 方法。***********************************/
    /** #bind(...) 方法，绑定端口，启动服务端。
     * Create a new {@link Channel} and bind it.
     */
    public ChannelFuture bind() {
        validate(); // 校验服务启动需要的必要参数
        SocketAddress localAddress = this.localAddress;
        if (localAddress == null) {
            throw new IllegalStateException("localAddress not set");
        }
        return doBind(localAddress); // 绑定本地地址( 包括端口 )
    }

    /**
     * Create a new {@link Channel} and bind it.
     */
    public ChannelFuture bind(int inetPort) {
        return bind(new InetSocketAddress(inetPort));
    }

    /**
     * Create a new {@link Channel} and bind it.
     */
    public ChannelFuture bind(String inetHost, int inetPort) {
        return bind(SocketUtils.socketAddress(inetHost, inetPort));
    }

    /**
     * Create a new {@link Channel} and bind it.
     */
    public ChannelFuture bind(InetAddress inetHost, int inetPort) {
        return bind(new InetSocketAddress(inetHost, inetPort));
    }

    /** bind 的逻辑，执行在 register 的逻辑之后。
     * Create a new {@link Channel} and bind it.
     */
    public ChannelFuture bind(SocketAddress localAddress) {
        validate(); // 校验服务启动需要的必要参数
        return doBind(ObjectUtil.checkNotNull(localAddress, "localAddress"));   // 绑定本地地址( 包括端口 )
    }

    private ChannelFuture doBind(final SocketAddress localAddress) {
        final ChannelFuture regFuture = initAndRegister();  // 初始化并注册一个 Channel 对象，因为注册是异步的过程，所以返回一个 ChannelFuture 对象。
        final Channel channel = regFuture.channel();
        if (regFuture.cause() != null) { // 若发生异常，直接进行返回。
            return regFuture;
        }
        // 绑定 Channel 的端口，并注册 Channel 到 SelectionKey 中。
        if (regFuture.isDone()) { // 注册已完成
            // At this point we know that the registration was complete and successful.
            ChannelPromise promise = channel.newPromise(); // 至此，我们知道注册已完成且成功。
            doBind0(regFuture, channel, localAddress, promise);  // 绑定
            return promise;
        } else {// 注册未完成
            // Registration future is almost always fulfilled already, but just in case it's not.
            final PendingRegistrationPromise promise = new PendingRegistrationPromise(channel);
            regFuture.addListener(new ChannelFutureListener() { // 如果异步注册对应的 ChanelFuture 未完成，则调用 ChannelFuture#addListener(ChannelFutureListener) 方法，添加监听器，在注册完成后，进行回调执行 #doBind0(...) 方法的逻辑。
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    Throwable cause = future.cause();
                    if (cause != null) {
                        // Registration on the EventLoop failed so fail the ChannelPromise directly to not cause an
                        // IllegalStateException once we try to access the EventLoop of the Channel.
                        promise.setFailure(cause);
                    } else {
                        // Registration was successful, so set the correct executor to use.
                        // See https://github.com/netty/netty/issues/2586
                        promise.registered();
                        // 绑定 Channel 的端口，并注册 Channel 到 SelectionKey 中。
                        doBind0(regFuture, channel, localAddress, promise);
                    }
                }
            });
            return promise;
        }
    }
    // 初始化并注册一个 Channel 对象，并返回一个 ChannelFuture 对象。(Bootstrap 跟 ServerBootstrap流程是一致的，区别就是a、创建的 Channel 对象不同。b、初始化 Channel 配置的代码实现不同。)
    final ChannelFuture initAndRegister() {
        Channel channel = null;
        try {
            channel = channelFactory.newChannel();  // 创建 Channel 对象 (会使用 ReflectiveChannelFactory 创建 NioServerSocketChannel 对象。)
            init(channel); // 初始化 Channel 配置
        } catch (Throwable t) {
            if (channel != null) { // 已创建 Channel 对象 (返回带异常的 DefaultChannelPromise 对象。因为初始化 Channel 对象失败，所以需要调用 #closeForcibly() 方法，强制关闭 Channel 。)
                // channel can be null if newChannel crashed (eg SocketException("too many open files"))
                channel.unsafe().closeForcibly(); // 强制关闭 Channel
                // as the Channel is not registered yet we need to force the usage of the GlobalEventExecutor
                return new DefaultChannelPromise(channel, GlobalEventExecutor.INSTANCE).setFailure(t);
            }
            // as the Channel is not registered yet we need to force the usage of the GlobalEventExecutor
            return new DefaultChannelPromise(new FailedChannel(), GlobalEventExecutor.INSTANCE).setFailure(t);// 返回带异常的 DefaultChannelPromise 对象。(因为创建 Channel 对象失败，所以需要创建一个 FailedChannel 对象，设置到 DefaultChannelPromise 中才可以返回。)
        }
        // 注册 Channel 到 EventLoopGroup 中 (注册 Channel 到 EventLoopGroup 中。实际在方法内部，EventLoopGroup 会分配一个 EventLoop 对象，将 Channel 注册到其上。)
        ChannelFuture regFuture = config().group().register(channel); // 通过接受连接的线程组注册channel
        if (regFuture.cause() != null) {
            if (channel.isRegistered()) {
                channel.close(); // 若发生异常，并且 Channel 已经注册成功，则调用 #close() 方法，正常关闭 Channel 。
            } else { // 若发生异常，并且 Channel 并未注册成功，则调用 #closeForcibly() 方法，强制关闭 Channel
                channel.unsafe().closeForcibly(); // 强制关闭 Channel
            }
        }

        // If we are here and the promise is not failed, it's one of the following cases:
        // 1) If we attempted registration from the event loop, the registration has been completed at this point.
        //    i.e. It's safe to attempt bind() or connect() now because the channel has been registered.
        // 2) If we attempted registration from the other thread, the registration request has been successfully
        //    added to the event loop's task queue for later execution.
        //    i.e. It's safe to attempt bind() or connect() now:
        //         because bind() or connect() will be executed *after* the scheduled registration task is executed
        //         because register(), bind(), and connect() are all bound to the same thread.

        return regFuture;
    }
    // 初始化 Channel 配置。(它是个抽象方法，由子类ServerBootstrap或Bootstrap自己实现。)
    abstract void init(Channel channel) throws Exception;
    // 执行 Channel 的端口绑定逻辑。
    private static void doBind0(
            final ChannelFuture regFuture, final Channel channel,
            final SocketAddress localAddress, final ChannelPromise promise) {
        // 在触发channelRegistered()之前调用此方法。 使用户处理程序有机会在其 channelRegistered() 实现中设置管道。
        // This method is invoked before channelRegistered() is triggered.  Give user handlers a chance to set up
        // the pipeline in its channelRegistered() implementation.
        channel.eventLoop().execute(new Runnable() { // 调用 EventLoop(就是接收连接的线程) 执行 Channel 的端口绑定逻辑。但是，实际上当前线程已经是 EventLoop 所在的线程了。
            @Override
            public void run() {
                if (regFuture.isSuccess()) { // 注册成功，绑定端口(执行 Channel 的端口绑定逻辑。)
                    channel.bind(localAddress, promise).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
                } else { // 注册失败，回调通知 promise 异常(注册失败，回调通知 promise 异常。)
                    promise.setFailure(regFuture.cause());
                }
            }
        });
    }

    /** 设置创建 Channel 的处理器
     * the {@link ChannelHandler} to use for serving the requests.
     */
    public B handler(ChannelHandler handler) {
        this.handler = ObjectUtil.checkNotNull(handler, "handler");
        return self();
    }

    /**
     * Returns the configured {@link EventLoopGroup} or {@code null} if non is configured yet.
     *
     * @deprecated Use {@link #config()} instead.
     */
    @Deprecated
    public final EventLoopGroup group() {
        return group;
    }

    /** #config() 方法，返回当前 AbstractBootstrap 的配置对象
     * Returns the {@link AbstractBootstrapConfig} object that can be used to obtain the current config
     * of the bootstrap.
     */
    public abstract AbstractBootstrapConfig<B, C> config();

    final Map.Entry<ChannelOption<?>, Object>[] newOptionsArray() { // 拿到用户代码设置的options
        return newOptionsArray(options);
    }

    static Map.Entry<ChannelOption<?>, Object>[] newOptionsArray(Map<ChannelOption<?>, Object> options) {
        synchronized (options) {
            return new LinkedHashMap<ChannelOption<?>, Object>(options).entrySet().toArray(EMPTY_OPTION_ARRAY);
        }
    }

    final Map.Entry<AttributeKey<?>, Object>[] newAttributesArray() { // 拿到用户代码设置的attrs
        return newAttributesArray(attrs0());
    }

    static Map.Entry<AttributeKey<?>, Object>[] newAttributesArray(Map<AttributeKey<?>, Object> attributes) {
        return attributes.entrySet().toArray(EMPTY_ATTRIBUTE_ARRAY);
    }

    final Map<ChannelOption<?>, Object> options0() {
        return options;
    }

    final Map<AttributeKey<?>, Object> attrs0() {
        return attrs;
    }

    final SocketAddress localAddress() {
        return localAddress;
    }

    @SuppressWarnings("deprecation")
    final ChannelFactory<? extends C> channelFactory() {
        return channelFactory;
    }

    final ChannelHandler handler() {
        return handler;
    }

    final Map<ChannelOption<?>, Object> options() {
        synchronized (options) {
            return copiedMap(options);
        }
    }

    final Map<AttributeKey<?>, Object> attrs() {
        return copiedMap(attrs);
    }

    static <K, V> Map<K, V> copiedMap(Map<K, V> map) {
        if (map.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new HashMap<K, V>(map));
    }

    static void setAttributes(Channel channel, Map.Entry<AttributeKey<?>, Object>[] attrs) {
        for (Map.Entry<AttributeKey<?>, Object> e: attrs) {
            @SuppressWarnings("unchecked")
            AttributeKey<Object> key = (AttributeKey<Object>) e.getKey();
            channel.attr(key).set(e.getValue()); // attrs绑定到Netty Channel上
        }
    }
    // #setChannelOptions(...) 静态方法，设置传入的 Channel 的多个可选项()
    static void setChannelOptions(
            Channel channel, Map.Entry<ChannelOption<?>, Object>[] options, InternalLogger logger) {
        for (Map.Entry<ChannelOption<?>, Object> e: options) {
            setChannelOption(channel, e.getKey(), e.getValue(), logger);
        }
    }
    // 设置传入的 Channel 的一个可选项。(设置已经创建的 Channel 的可选项。)
    @SuppressWarnings("unchecked")
    private static void setChannelOption(
            Channel channel, ChannelOption<?> option, Object value, InternalLogger logger) {
        try {
            if (!channel.config().setOption((ChannelOption<Object>) option, value)) { // 将options配置到tcp参数配置类（channelConfig类）中
                logger.warn("Unknown channel option '{}' for channel '{}'", option, channel);
            }
        } catch (Throwable t) {
            logger.warn(
                    "Failed to set channel option '{}' with value '{}' for channel '{}'", option, value, channel, t);
        }
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder()
            .append(StringUtil.simpleClassName(this))
            .append('(').append(config()).append(')');
        return buf.toString();
    }

    static final class PendingRegistrationPromise extends DefaultChannelPromise {

        // Is set to the correct EventExecutor once the registration was successful. Otherwise it will
        // stay null and so the GlobalEventExecutor.INSTANCE will be used for notifications.
        private volatile boolean registered;

        PendingRegistrationPromise(Channel channel) {
            super(channel);
        }

        void registered() {
            registered = true;
        }

        @Override
        protected EventExecutor executor() {
            if (registered) {
                // If the registration was a success executor is set.
                //
                // See https://github.com/netty/netty/issues/2586
                return super.executor();
            }
            // The registration failed so we can only use the GlobalEventExecutor as last resort to notify.
            return GlobalEventExecutor.INSTANCE;
        }
    }
}
