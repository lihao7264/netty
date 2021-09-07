/*
 * Copyright 2014 The Netty Project
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
package io.netty.util.concurrent;

import io.netty.util.internal.InternalThreadLocalMap;
import io.netty.util.internal.PlatformDependent;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * A special variant of {@link ThreadLocal} that yields higher access performance when accessed from a
 * {@link FastThreadLocalThread}.
 * <p>
 * Internally, a {@link FastThreadLocal} uses a constant index in an array, instead of using hash code and hash table,
 * to look for a variable.  Although seemingly very subtle, it yields slight performance advantage over using a hash
 * table, and it is useful when accessed frequently.
 * </p><p>
 * To take advantage of this thread-local variable, your thread must be a {@link FastThreadLocalThread} or its subtype.
 * By default, all threads created by {@link DefaultThreadFactory} are {@link FastThreadLocalThread} due to this reason.
 * </p><p>
 * Note that the fast path is only possible on threads that extend {@link FastThreadLocalThread}, because it requires
 * a special field to store the necessary state.  An access by any other kind of thread falls back to a regular
 * {@link ThreadLocal}.
 * </p>
 *
 * @param <V> the type of the thread-local variable
 * @see ThreadLocal
 */
public class FastThreadLocal<V> {
    // 共同的不可变int值（ variablesToRemoveIndex 的值为 0，它属于常量赋值，第一次调用时 nextIndex 的值为 0 。）
    private static final int variablesToRemoveIndex = InternalThreadLocalMap.nextVariableIndex();// 静态常量是跟着类的，一个类只会有一个

    /**
     * Removes all {@link FastThreadLocal} variables bound to the current thread.  This operation is useful when you
     * are in a container environment, and you don't want to leave the thread local variables in the threads you do not
     * manage.
     */
    public static void removeAll() {
        InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.getIfSet(); // 获取到当前线程的 InternalThreadLocalMap
        if (threadLocalMap == null) {
            return;
        }

        try {
            Object v = threadLocalMap.indexedVariable(variablesToRemoveIndex);// 得到下标为0的V（也就是保存所有FastThreadLocal的Set）
            if (v != null && v != InternalThreadLocalMap.UNSET) {
                @SuppressWarnings("unchecked")
                Set<FastThreadLocal<?>> variablesToRemove = (Set<FastThreadLocal<?>>) v;
                FastThreadLocal<?>[] variablesToRemoveArray =
                        variablesToRemove.toArray(new FastThreadLocal[0]);// 将Set转为数组遍历
                for (FastThreadLocal<?> tlv: variablesToRemoveArray) {
                    tlv.remove(threadLocalMap);// 调用remove方法
                }
            }
        } finally {
            InternalThreadLocalMap.remove();// 将线程里面的map置空，完成整体移除
        }
    }

    /**
     * Returns the number of thread local variables bound to the current thread.
     */
    public static int size() {
        InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.getIfSet();
        if (threadLocalMap == null) {
            return 0;
        } else {
            return threadLocalMap.size();
        }
    }

    /**
     * Destroys the data structure that keeps all {@link FastThreadLocal} variables accessed from
     * non-{@link FastThreadLocalThread}s.  This operation is useful when you are in a container environment, and you
     * do not want to leave the thread local variables in the threads you do not manage.  Call this method when your
     * application is being unloaded from the container.
     */
    public static void destroy() {
        InternalThreadLocalMap.destroy();
    }

    @SuppressWarnings("unchecked")
    private static void addToVariablesToRemove(InternalThreadLocalMap threadLocalMap, FastThreadLocal<?> variable) {
        Object v = threadLocalMap.indexedVariable(variablesToRemoveIndex);// variablesToRemoveIndex为上面说到的常量，值为0（从数组的0下标处获取一个v对象）
        Set<FastThreadLocal<?>> variablesToRemove;
        if (v == InternalThreadLocalMap.UNSET || v == null) {// 如果v 是默认值 或 v==null
            variablesToRemove = Collections.newSetFromMap(new IdentityHashMap<FastThreadLocal<?>, Boolean>());
            threadLocalMap.setIndexedVariable(variablesToRemoveIndex, variablesToRemove);// 将这个Set塞到Object数组的第0个位置
        } else {
            variablesToRemove = (Set<FastThreadLocal<?>>) v;// 将v强转为Set
        }

        variablesToRemove.add(variable);// 将传入的FastThreadLocal塞到这个set中
    }

    private static void removeFromVariablesToRemove(
            InternalThreadLocalMap threadLocalMap, FastThreadLocal<?> variable) {

        Object v = threadLocalMap.indexedVariable(variablesToRemoveIndex);// 获取下标为0的Set（保存所有FastTreadLocal的Set）

        if (v == InternalThreadLocalMap.UNSET || v == null) { // set为空或者为默认值的话，则跳过
            return;
        }

        @SuppressWarnings("unchecked")
        Set<FastThreadLocal<?>> variablesToRemove = (Set<FastThreadLocal<?>>) v;
        variablesToRemove.remove(variable);// 调用set的remove
    }
    // 当前FastThreadLocal的唯一下标：在 FastThreadLocal 构造的时候就被赋值
    private final int index;// 常量变量是跟着对象的，每个对象的值都不一样

    public FastThreadLocal() {
        index = InternalThreadLocalMap.nextVariableIndex();  // 初始化唯一下标
    }

    /**
     * Returns the current value for the current thread
     */
    @SuppressWarnings("unchecked")
    public final V get() {
        InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.get();// 获取线程中的InternalThreadLocalMap
        Object v = threadLocalMap.indexedVariable(index);// 获取当前FastThreadLocal对应的唯一下标的值
        if (v != InternalThreadLocalMap.UNSET) {// 值不是初始值，就直接返回
            return (V) v;
        }

        return initialize(threadLocalMap);//如果是初始值，就执行初始化赋值操作
    }

    /**
     * Returns the current value for the current thread if it exists, {@code null} otherwise.
     */
    @SuppressWarnings("unchecked")
    public final V getIfExists() {
        InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.getIfSet();
        if (threadLocalMap != null) {
            Object v = threadLocalMap.indexedVariable(index);
            if (v != InternalThreadLocalMap.UNSET) {
                return (V) v;
            }
        }
        return null;
    }

    /**
     * Returns the current value for the specified thread local map.
     * The specified thread local map must be for the current thread.
     */
    @SuppressWarnings("unchecked")
    public final V get(InternalThreadLocalMap threadLocalMap) {
        Object v = threadLocalMap.indexedVariable(index);
        if (v != InternalThreadLocalMap.UNSET) {
            return (V) v;
        }

        return initialize(threadLocalMap);
    }

    private V initialize(InternalThreadLocalMap threadLocalMap) {
        V v = null;
        try {
            v = initialValue();
        } catch (Exception e) {
            PlatformDependent.throwException(e);
        }

        threadLocalMap.setIndexedVariable(index, v);
        addToVariablesToRemove(threadLocalMap, this);
        return v;
    }

    /**
     * Set the value for the current thread.
     */
    public final void set(V value) {
        if (value != InternalThreadLocalMap.UNSET) {// 如果要塞入的值不是默认值
            InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.get();// 获取当前线程的InternalThreadLocalMap
            setKnownNotUnset(threadLocalMap, value);
        } else {
            remove();//
        }
    }

    /** 设置指定线程本地映射的值。指定的线程本地映射必须是当前线程的。
     * Set the value for the specified thread local map. The specified thread local map must be for the current thread.
     */
    public final void set(InternalThreadLocalMap threadLocalMap, V value) {
        if (value != InternalThreadLocalMap.UNSET) { //设置为非默认值
            setKnownNotUnset(threadLocalMap, value);
        } else {
            remove(threadLocalMap); // 设置为默认值
        }
    }

    /**
     * @see InternalThreadLocalMap#setIndexedVariable(int, Object).
     */
    private void setKnownNotUnset(InternalThreadLocalMap threadLocalMap, V value) {
        if (threadLocalMap.setIndexedVariable(index, value)) { // index就是ThreadLocal构造时被分配得到的下标
            addToVariablesToRemove(threadLocalMap, this); // 添加到待删除列表（this就是FastThreadLocal对象）
        }
    }

    /**
     * Returns {@code true} if and only if this thread-local variable is set.
     */
    public final boolean isSet() {
        return isSet(InternalThreadLocalMap.getIfSet());
    }

    /**
     * Returns {@code true} if and only if this thread-local variable is set.
     * The specified thread local map must be for the current thread.
     */
    public final boolean isSet(InternalThreadLocalMap threadLocalMap) {
        return threadLocalMap != null && threadLocalMap.isIndexedVariableSet(index);
    }
    /**
     * Sets the value to uninitialized for the specified thread local map.
     * After this, any subsequent call to get() will trigger a new call to initialValue().
     */
    public final void remove() {
        remove(InternalThreadLocalMap.getIfSet());
    }

    /**
     * Sets the value to uninitialized for the specified thread local map.
     * After this, any subsequent call to get() will trigger a new call to initialValue().
     * The specified thread local map must be for the current thread.
     */
    @SuppressWarnings("unchecked")
    public final void remove(InternalThreadLocalMap threadLocalMap) {
        if (threadLocalMap == null) {
            return;
        }

        Object v = threadLocalMap.removeIndexedVariable(index); // 移除当前FastThreadLocal的唯一下标的数据，并返回老数组
        removeFromVariablesToRemove(threadLocalMap, this);// 将当前FastThreadLocal从数组的下标为0的Set中移除

        if (v != InternalThreadLocalMap.UNSET) { // 如果移除的下标的值不是默认值的话
            try {
                onRemoval((V) v); // 这是扩展，自行实现（可以释放对象的内存等）
            } catch (Exception e) {
                PlatformDependent.throwException(e);
            }
        }
    }

    /**
     * Returns the initial value for this thread-local variable.
     */
    protected V initialValue() throws Exception {
        return null;
    }

    /**
     * Invoked when this thread local variable is removed by {@link #remove()}. Be aware that {@link #remove()}
     * is not guaranteed to be called when the `Thread` completes which means you can not depend on this for
     * cleanup of the resources in the case of `Thread` completion.
     */
    protected void onRemoval(@SuppressWarnings("UnusedParameters") V value) throws Exception { }
}
