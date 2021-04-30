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
package io.netty.util;

/**
 * Holds {@link Attribute}s which can be accessed via {@link AttributeKey}.
 * 拥有{@link Attribute}，可以通过{@link AttributeKey}进行访问。
 * Implementations must be Thread-safe. 实现必须是线程安全的。
 */
public interface AttributeMap {
    /** 获取给定{@link AttributeKey}的{@link Attribute}。此方法永远不会返回null，但可能会返回尚未设置值的{@link Attribute}。
     * Get the {@link Attribute} for the given {@link AttributeKey}. This method will never return null, but may return
     * an {@link Attribute} which does not have a value set yet.
     */
    <T> Attribute<T> attr(AttributeKey<T> key);

    /** 判断是否有这个属性
     * Returns {@code true} if and only if the given {@link Attribute} exists in this {@link AttributeMap}.
     */
    <T> boolean hasAttr(AttributeKey<T> key);
}
