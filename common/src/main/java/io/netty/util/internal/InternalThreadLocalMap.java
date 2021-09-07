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

package io.netty.util.internal;

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.concurrent.FastThreadLocalThread;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** InternalThreadLocalMap 对标的是问题最多的ThreadLocalMap（用多余的空间去换取不会冲突的 set 和 get ，写入和获取的速度就更快，这就是典型的空间换时间。）
 * The internal data structure that stores the thread-local variables for Netty and all {@link FastThreadLocal}s.
 * Note that this class is for internal use only and is subject to change at any time.  Use {@link FastThreadLocal}
 * unless you know what you are doing.
 */
public final class InternalThreadLocalMap extends UnpaddedInternalThreadLocalMap {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(InternalThreadLocalMap.class);
    private static final ThreadLocal<InternalThreadLocalMap> slowThreadLocalMap =
            new ThreadLocal<InternalThreadLocalMap>();
    private static final AtomicInteger nextIndex = new AtomicInteger(); // 静态原子类（每次调用 nextVariableIndex 就返回且递增）

    private static final int DEFAULT_ARRAY_LIST_INITIAL_CAPACITY = 8;
    private static final int STRING_BUILDER_INITIAL_SIZE;
    private static final int STRING_BUILDER_MAX_SIZE;
    private static final int HANDLER_SHARABLE_CACHE_INITIAL_CAPACITY = 4;
    private static final int INDEXED_VARIABLE_TABLE_INITIAL_SIZE = 32;

    public static final Object UNSET = new Object();// 填充的默认值

    /** Used by {@link FastThreadLocal} */
    private Object[] indexedVariables; // 通过Object数组来实现（数组的第一个位置（index=0）腾出来 放一个 set 保存所有使用的 FastThreadLocal 对象的目的： 用于删除）

    // Core thread-locals
    private int futureListenerStackDepth;
    private int localChannelReaderStackDepth;
    private Map<Class<?>, Boolean> handlerSharableCache;
    private IntegerHolder counterHashCode;
    private ThreadLocalRandom random;
    private Map<Class<?>, TypeParameterMatcher> typeParameterMatcherGetCache;
    private Map<Class<?>, Map<String, TypeParameterMatcher>> typeParameterMatcherFindCache;

    // String-related thread-locals
    private StringBuilder stringBuilder;
    private Map<Charset, CharsetEncoder> charsetEncoderCache;
    private Map<Charset, CharsetDecoder> charsetDecoderCache;

    // ArrayList-related thread-locals
    private ArrayList<Object> arrayList;

    private BitSet cleanerFlags;

    /** @deprecated These padding fields will be removed in the future. */
    public long rp1, rp2, rp3, rp4, rp5, rp6, rp7, rp8, rp9;

    static {
        STRING_BUILDER_INITIAL_SIZE =
                SystemPropertyUtil.getInt("io.netty.threadLocalMap.stringBuilder.initialSize", 1024);
        logger.debug("-Dio.netty.threadLocalMap.stringBuilder.initialSize: {}", STRING_BUILDER_INITIAL_SIZE);

        STRING_BUILDER_MAX_SIZE = SystemPropertyUtil.getInt("io.netty.threadLocalMap.stringBuilder.maxSize", 1024 * 4);
        logger.debug("-Dio.netty.threadLocalMap.stringBuilder.maxSize: {}", STRING_BUILDER_MAX_SIZE);
    }

    public static InternalThreadLocalMap getIfSet() {
        Thread thread = Thread.currentThread();
        if (thread instanceof FastThreadLocalThread) {
            return ((FastThreadLocalThread) thread).threadLocalMap();
        }
        return slowThreadLocalMap.get();
    }

    public static InternalThreadLocalMap get() { // 获取当前线程的InternalThreadLocalMap
        Thread thread = Thread.currentThread();// 获取当前线程
        if (thread instanceof FastThreadLocalThread) {// FastThreadLocalThread相关的类的话，则返回FastThreadLocalThread下的threadLocalMap字段
            return fastGet((FastThreadLocalThread) thread);// 如果当前线程是fastThread类型，就执行fastGet
        } else {
            return slowGet(); // 否则就slowGet
        }
    }

    private static InternalThreadLocalMap fastGet(FastThreadLocalThread thread) {
        InternalThreadLocalMap threadLocalMap = thread.threadLocalMap();// 拿到InternalThreadLocalMap
        if (threadLocalMap == null) {//为空就new一个
            thread.setThreadLocalMap(threadLocalMap = new InternalThreadLocalMap());
        }
        return threadLocalMap;// 直接返回
    }

    private static InternalThreadLocalMap slowGet() { // 从当前线程 中的 threadLocals字段下，通过ThreadLocal获取对应的 InternalThreadLocalMap
        InternalThreadLocalMap ret = slowThreadLocalMap.get();
        if (ret == null) {
            ret = new InternalThreadLocalMap();// new一个新的InternalThreadLocalMap放入到当前线程的threadLocals变量中
            slowThreadLocalMap.set(ret);
        }
        return ret;
    }

    public static void remove() {
        Thread thread = Thread.currentThread();
        if (thread instanceof FastThreadLocalThread) {
            ((FastThreadLocalThread) thread).setThreadLocalMap(null); // 设置FastThreadLocalThread的 threadLocalMap属性
        } else {
            slowThreadLocalMap.remove(); //移除当前线程下的threadLocals下的 InternalThreadLocalMap的 slowThreadLocalMap字段对应的Value（InternalThreadLocalMap）
        }
    }

    public static void destroy() {
        slowThreadLocalMap.remove();
    }

    public static int nextVariableIndex() { // 使用原子类递增实现
        int index = nextIndex.getAndIncrement();
        if (index < 0) {
            nextIndex.decrementAndGet();
            throw new IllegalStateException("too many thread-local indexed variables");
        }
        return index;
    }

    public static int lastVariableIndex() {
        return nextIndex.get() - 1;
    }

    private InternalThreadLocalMap() {
        indexedVariables = newIndexedVariableTable();
    }

    private static Object[] newIndexedVariableTable() {// 构造默认生成32长度的数组，并且填充默认值
        Object[] array = new Object[INDEXED_VARIABLE_TABLE_INITIAL_SIZE];
        Arrays.fill(array, UNSET);
        return array;
    }

    public int size() {
        int count = 0;

        if (futureListenerStackDepth != 0) {
            count ++;
        }
        if (localChannelReaderStackDepth != 0) {
            count ++;
        }
        if (handlerSharableCache != null) {
            count ++;
        }
        if (counterHashCode != null) {
            count ++;
        }
        if (random != null) {
            count ++;
        }
        if (typeParameterMatcherGetCache != null) {
            count ++;
        }
        if (typeParameterMatcherFindCache != null) {
            count ++;
        }
        if (stringBuilder != null) {
            count ++;
        }
        if (charsetEncoderCache != null) {
            count ++;
        }
        if (charsetDecoderCache != null) {
            count ++;
        }
        if (arrayList != null) {
            count ++;
        }

        for (Object o: indexedVariables) {
            if (o != UNSET) {
                count ++;
            }
        }

        // We should subtract 1 from the count because the first element in 'indexedVariables' is reserved
        // by 'FastThreadLocal' to keep the list of 'FastThreadLocal's to remove on 'FastThreadLocal.removeAll()'.
        return count - 1;
    }

    public StringBuilder stringBuilder() {
        StringBuilder sb = stringBuilder;
        if (sb == null) {
            return stringBuilder = new StringBuilder(STRING_BUILDER_INITIAL_SIZE);
        }
        if (sb.capacity() > STRING_BUILDER_MAX_SIZE) {
            sb.setLength(STRING_BUILDER_INITIAL_SIZE);
            sb.trimToSize();
        }
        sb.setLength(0);
        return sb;
    }

    public Map<Charset, CharsetEncoder> charsetEncoderCache() {
        Map<Charset, CharsetEncoder> cache = charsetEncoderCache;
        if (cache == null) {
            charsetEncoderCache = cache = new IdentityHashMap<Charset, CharsetEncoder>();
        }
        return cache;
    }

    public Map<Charset, CharsetDecoder> charsetDecoderCache() {
        Map<Charset, CharsetDecoder> cache = charsetDecoderCache;
        if (cache == null) {
            charsetDecoderCache = cache = new IdentityHashMap<Charset, CharsetDecoder>();
        }
        return cache;
    }

    public <E> ArrayList<E> arrayList() {
        return arrayList(DEFAULT_ARRAY_LIST_INITIAL_CAPACITY);
    }

    @SuppressWarnings("unchecked")
    public <E> ArrayList<E> arrayList(int minCapacity) {
        ArrayList<E> list = (ArrayList<E>) arrayList;
        if (list == null) {
            arrayList = new ArrayList<Object>(minCapacity);
            return (ArrayList<E>) arrayList;
        }
        list.clear();
        list.ensureCapacity(minCapacity);
        return list;
    }

    public int futureListenerStackDepth() {
        return futureListenerStackDepth;
    }

    public void setFutureListenerStackDepth(int futureListenerStackDepth) {
        this.futureListenerStackDepth = futureListenerStackDepth;
    }

    public ThreadLocalRandom random() {
        ThreadLocalRandom r = random;
        if (r == null) {
            random = r = new ThreadLocalRandom();
        }
        return r;
    }

    public Map<Class<?>, TypeParameterMatcher> typeParameterMatcherGetCache() {
        Map<Class<?>, TypeParameterMatcher> cache = typeParameterMatcherGetCache;
        if (cache == null) {
            typeParameterMatcherGetCache = cache = new IdentityHashMap<Class<?>, TypeParameterMatcher>();
        }
        return cache;
    }

    public Map<Class<?>, Map<String, TypeParameterMatcher>> typeParameterMatcherFindCache() {
        Map<Class<?>, Map<String, TypeParameterMatcher>> cache = typeParameterMatcherFindCache;
        if (cache == null) {
            typeParameterMatcherFindCache = cache = new IdentityHashMap<Class<?>, Map<String, TypeParameterMatcher>>();
        }
        return cache;
    }

    @Deprecated
    public IntegerHolder counterHashCode() {
        return counterHashCode;
    }

    @Deprecated
    public void setCounterHashCode(IntegerHolder counterHashCode) {
        this.counterHashCode = counterHashCode;
    }

    public Map<Class<?>, Boolean> handlerSharableCache() {
        Map<Class<?>, Boolean> cache = handlerSharableCache;
        if (cache == null) {
            // Start with small capacity to keep memory overhead as low as possible.
            handlerSharableCache = cache = new WeakHashMap<Class<?>, Boolean>(HANDLER_SHARABLE_CACHE_INITIAL_CAPACITY);
        }
        return cache;
    }

    public int localChannelReaderStackDepth() {
        return localChannelReaderStackDepth;
    }

    public void setLocalChannelReaderStackDepth(int localChannelReaderStackDepth) {
        this.localChannelReaderStackDepth = localChannelReaderStackDepth;
    }

    public Object indexedVariable(int index) {// 直接进行一个数组的下标获取
        Object[] lookup = indexedVariables;
        return index < lookup.length? lookup[index] : UNSET;
    }

    /**{@code true} 当且仅当创建了新的线程局部变量
     * @return {@code true} if and only if a new thread-local variable has been created
     */
    public boolean setIndexedVariable(int index, Object value) {
        Object[] lookup = indexedVariables; // 保存数据的Object数组
        if (index < lookup.length) { //重点：默认数组是32，如果index大0数组长，则需要扩容。（如果第一个塞入的值的线程的index为100，则仅仅只有这么一个值，数组就需要扩容）
            Object oldValue = lookup[index];
            lookup[index] = value;
            return oldValue == UNSET;
        } else {
            expandIndexedVariableTableAndSet(index, value);// 进行扩容
            return true;
        }
    }
    // 数据扩容是基于index而不是原先数组的大小
    private void expandIndexedVariableTableAndSet(int index, Object value) {
        Object[] oldArray = indexedVariables;
        final int oldCapacity = oldArray.length;
        int newCapacity = index;// 基于index的向上2次幂取整（2次幂取整跟HashMap类似）
        newCapacity |= newCapacity >>>  1;
        newCapacity |= newCapacity >>>  2;
        newCapacity |= newCapacity >>>  4;
        newCapacity |= newCapacity >>>  8;
        newCapacity |= newCapacity >>> 16;
        newCapacity ++;

        Object[] newArray = Arrays.copyOf(oldArray, newCapacity);//直接数组拷贝，不需要rehash（优于ThreadLocalMap的地方，ThreadLocalMap需要rehash，也就是重新基于Key的hash值进行位置的分配）
        Arrays.fill(newArray, oldCapacity, newArray.length, UNSET);
        newArray[index] = value;// 设置目标下标的值
        indexedVariables = newArray;
    }

    public Object removeIndexedVariable(int index) { // 把对应下标的值设置为默认值，然后返回原值
        Object[] lookup = indexedVariables;
        if (index < lookup.length) {
            Object v = lookup[index];
            lookup[index] = UNSET;// 找到位置，覆盖成初始化值
            return v;
        } else {
            return UNSET;
        }
    }

    public boolean isIndexedVariableSet(int index) {
        Object[] lookup = indexedVariables;
        return index < lookup.length && lookup[index] != UNSET;
    }

    public boolean isCleanerFlagSet(int index) {
        return cleanerFlags != null && cleanerFlags.get(index);
    }

    public void setCleanerFlag(int index) {
        if (cleanerFlags == null) {
            cleanerFlags = new BitSet();
        }
        cleanerFlags.set(index);
    }
}
