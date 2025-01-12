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
package io.netty.example.echo;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;

/**
 * Echoes back any received data from a client. 回显从客户端收到的所有数据。
 */
public final class EchoServer {

    static final boolean SSL = System.getProperty("ssl") != null;
    static final int PORT = Integer.parseInt(System.getProperty("port", "8007"));

    public static void main(String[] args) throws Exception {
        // Configure SSL.
        final SslContext sslCtx; // 配置 SSL
        if (SSL) {
            SelfSignedCertificate ssc = new SelfSignedCertificate();
            sslCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
        } else {
            sslCtx = null;
        }

        // Configure the server. 创建两个 EventLoopGroup 对象
        EventLoopGroup bossGroup = new NioEventLoopGroup(1); // 创建 boss 线程组 用于服务端接受客户端的连接(boss 线程组：用于服务端接受客户端的连接。)
        EventLoopGroup workerGroup = new NioEventLoopGroup();  // 创建 worker 线程组 用于进行 SocketChannel 的数据读写(worker 线程组：用于进行客户端的 SocketChannel 的数据读写。)
        final EchoServerHandler serverHandler = new EchoServerHandler();// 创建 EchoServerHandler 对象
        try {
            ServerBootstrap b = new ServerBootstrap();  // 创建 ServerBootstrap 对象
            b.group(bossGroup, workerGroup) // 设置使用的 EventLoopGroup
             .channel(NioServerSocketChannel.class) // 设置要被实例化的为 NioServerSocketChannel 类
             .option(ChannelOption.SO_BACKLOG, 100) // 设置 NioServerSocketChannel 的可选项
             .handler(new LoggingHandler(LogLevel.INFO)) // 设置 NioServerSocketChannel 的处理器。(LoggingHandler用于打印服务端的每个事件)
             .childHandler(new ChannelInitializer<SocketChannel>() { // 设置连入服务端的 Client 的 SocketChannel 的处理器。(使用 ChannelInitializer 来初始化连入服务端的 Client 的 SocketChannel 的处理器。)
                 @Override // ChannelInitializer每次添加到handler之后，都会调用initChannel方法，在该方法中，用户可以获取到channel的pipeline，然后向pipeline中添加自定义的handler，调用完initChannel方法之后，会将自身删除
                 public void initChannel(SocketChannel ch) throws Exception { // 设置连入服务端的 Client 的 SocketChannel 的处理器
                     ChannelPipeline p = ch.pipeline();
                     if (sslCtx != null) {
                         p.addLast(sslCtx.newHandler(ch.alloc()));
                     }
                     //p.addLast(new LoggingHandler(LogLevel.INFO));
                     p.addLast(serverHandler);
                 }
             });

            // Start the server.
            ChannelFuture f = b.bind(PORT).sync();  // 绑定端口，并同步(阻塞)等待成功，即启动服务端
            //
            // Wait until the server socket is closed.
            f.channel().closeFuture().sync(); // 监听服务端关闭，并阻塞等待成功
        } finally {
            // Shut down all event loops to terminate all threads.
            bossGroup.shutdownGracefully(); // 优雅关闭两个 EventLoopGroup 对象
            workerGroup.shutdownGracefully();
        }
    }
}
