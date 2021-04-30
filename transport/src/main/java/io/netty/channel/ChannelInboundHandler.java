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
package io.netty.channel;

/** {@link ChannelHandler}会添加状态更改的回调。这使用户可以轻松加入状态更改。 （更多的是事件触发的机制，这是被动的）
 * {@link ChannelHandler} which adds callbacks for state changes. This allows the user
 * to hook in to state changes easily.
 */
public interface ChannelInboundHandler extends ChannelHandler {

    /** {@link ChannelHandlerContext}的{@link Channel}已通过其{@link EventLoop}注册 --- Channel注册到NioEventLoop的selector上之后，会回调对应channelHandler的该方法
     * The {@link Channel} of the {@link ChannelHandlerContext} was registered with its {@link EventLoop}
     */
    void channelRegistered(ChannelHandlerContext ctx) throws Exception;

    /** {@link ChannelHandlerContext}的{@link Channel}已从其{@link EventLoop}中取消注册
     * The {@link Channel} of the {@link ChannelHandlerContext} was unregistered from its {@link EventLoop}
     */
    void channelUnregistered(ChannelHandlerContext ctx) throws Exception;

    /** {@link ChannelHandlerContext}的{@link Channel}现在处于活动状态（激活）
     * The {@link Channel} of the {@link ChannelHandlerContext} is now active
     */
    void channelActive(ChannelHandlerContext ctx) throws Exception;

    /** {@link ChannelHandlerContext}的{@link Channel}已注册，现已停用，并且已达到生命周期的尽头。（失效）
     * The {@link Channel} of the {@link ChannelHandlerContext} was registered is now inactive and reached its
     * end of lifetime.
     */
    void channelInactive(ChannelHandlerContext ctx) throws Exception;

    /** 当前的{@link Channel}已从对方读取到消息 时 调用。（读到了一些数据或者接收了一些连接，服务端Channel的时候msg表示为连接，客户端Channel的时候msg表示为消息（ByteBuf））
     * Invoked when the current {@link Channel} has read a message from the peer.
     */
    void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception;

    /** 读完之后的回调
     * Invoked when the last message read by the current read operation has been consumed by
     * {@link #channelRead(ChannelHandlerContext, Object)}.  If {@link ChannelOption#AUTO_READ} is off, no further
     * attempt to read an inbound data from the current {@link Channel} will be made until
     * {@link ChannelHandlerContext#read()} is called.
     */
    void channelReadComplete(ChannelHandlerContext ctx) throws Exception;

    /** 用户可以triggered一些自定义的事件
     * Gets called if an user event was triggered. 如果触发了用户事件，则调用该方法。
     */
    void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception;

    /**  {@link Channel}的可写状态更改后被调用。您可以使用{@link Channel＃isWritable（）}检查状态。
     * Gets called once the writable state of a {@link Channel} changed. You can check the state with
     * {@link Channel#isWritable()}.  channel的可写状态进行了一些改变
     */
    void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception;

    /** 异常捕获的回调
     * Gets called if a {@link Throwable} was thrown.
     */
    @Override
    @SuppressWarnings("deprecation")
    void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception;
}
