/*
 * Copyright 2013 The Netty Project
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

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.ObjectPool;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import static io.netty.util.internal.MathUtil.safeFindNextPositivePowerOfTwo;
import static java.lang.Math.max;
import static java.lang.Math.min;

/** Recycler 是用户直接接触的对象，它是我们构建一个简易的对象池的入口。(Recycler中将主线程和非主线程回收对象划分到不同的存储空间中（stack#elements和WeakOrderQueue.Link#elements），并且对于WeakOrderQueue.Link#elements，存取操作划分到两端进行（非主线程从尾端存入，主线程从首部开始读取），从而减少同步操作，并保证线程安全。)  -- https://www.jianshu.com/p/3be546862afb
 * Light-weight object pool based on a thread-local stack.(基于线程本地堆栈的轻量级对象池。)
 * 该类的主要功能是读取配置信息并初始化，并构建了两个非常重要的 FastThreadLocal 对象，分别是 FastThreadLocal<Stack<T>> 以及 FastThreadLocal<Map<Stack<?>, WeakOrderQueue>>
 * @param <T> the type of the pooled object
 */
public abstract class Recycler<T> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(Recycler.class);

    @SuppressWarnings("rawtypes")
    private static final Handle NOOP_HANDLE = new Handle() {
        @Override
        public void recycle(Object object) {
            // NOOP
        }
    };
    private static final AtomicInteger ID_GENERATOR = new AtomicInteger(Integer.MIN_VALUE);// 全局唯一ID，会有两个地方使用到，一个是每个Recycler初始化OWN_THREAD_ID,另一个是每个WeakOrderQueue初始化id
    private static final int OWN_THREAD_ID = ID_GENERATOR.getAndIncrement();    // 全局ID生成器
    private static final int DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD = 4 * 1024; // Use 4k instances as default. // 每线程最大可缓存对象容量大小，默认值:4096
    private static final int DEFAULT_MAX_CAPACITY_PER_THREAD; // 每线程最大可缓存对象大小，默认值:4096
    private static final int INITIAL_CAPACITY; // 初始容量，默认值:256
    private static final int MAX_SHARED_CAPACITY_FACTOR; // 最大共享容量因子，默认值: 2
    private static final int MAX_DELAYED_QUEUES_PER_THREAD;  // 每线程最多延迟回收队列，默认值: 8
    private static final int LINK_CAPACITY; // Link用来存储异线程回收的对象，内部有一个数组，数组长度=LINK_CAPACITY,默认值: 16
    private static final int RATIO; // 异线程丢弃对象比例，默认值:8,表示在异线程中，每8个回收对象只回收其中一个，其余丢弃
    private static final int DELAYED_QUEUE_RATIO;

    static {
        // In the future, we might have different maxCapacity for different object types.
        // e.g. io.netty.recycler.maxCapacity.writeTask
        //      io.netty.recycler.maxCapacity.outboundBuffer
        int maxCapacityPerThread = SystemPropertyUtil.getInt("io.netty.recycler.maxCapacityPerThread",
                SystemPropertyUtil.getInt("io.netty.recycler.maxCapacity", DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD));    // 每个线程所持有的State<T> 数据结构内的数组的最大长度
        if (maxCapacityPerThread < 0) {
            maxCapacityPerThread = DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD;
        }

        DEFAULT_MAX_CAPACITY_PER_THREAD = maxCapacityPerThread;

        MAX_SHARED_CAPACITY_FACTOR = max(2,
                SystemPropertyUtil.getInt("io.netty.recycler.maxSharedCapacityFactor",
                        2));

        MAX_DELAYED_QUEUES_PER_THREAD = max(0,
                SystemPropertyUtil.getInt("io.netty.recycler.maxDelayedQueuesPerThread",
                        // We use the same value as default EventLoop number
                        NettyRuntime.availableProcessors() * 2));

        LINK_CAPACITY = safeFindNextPositivePowerOfTwo(
                max(SystemPropertyUtil.getInt("io.netty.recycler.linkCapacity", 16), 16));

        // By default we allow one push to a Recycler for each 8th try on handles that were never recycled before.
        // This should help to slowly increase the capacity of the recycler while not be too sensitive to allocation
        // bursts.
        RATIO = max(0, SystemPropertyUtil.getInt("io.netty.recycler.ratio", 8));
        DELAYED_QUEUE_RATIO = max(0, SystemPropertyUtil.getInt("io.netty.recycler.delayedQueue.ratio", RATIO));

        INITIAL_CAPACITY = min(DEFAULT_MAX_CAPACITY_PER_THREAD, 256);

        if (logger.isDebugEnabled()) {
            if (DEFAULT_MAX_CAPACITY_PER_THREAD == 0) {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: disabled");
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: disabled");
                logger.debug("-Dio.netty.recycler.linkCapacity: disabled");
                logger.debug("-Dio.netty.recycler.ratio: disabled");
                logger.debug("-Dio.netty.recycler.delayedQueue.ratio: disabled");
            } else {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: {}", DEFAULT_MAX_CAPACITY_PER_THREAD);
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: {}", MAX_SHARED_CAPACITY_FACTOR);
                logger.debug("-Dio.netty.recycler.linkCapacity: {}", LINK_CAPACITY);
                logger.debug("-Dio.netty.recycler.ratio: {}", RATIO);
                logger.debug("-Dio.netty.recycler.delayedQueue.ratio: {}", DELAYED_QUEUE_RATIO);
            }
        }
    }

    private final int maxCapacityPerThread; // 每个线程所持有的State<T> 数据结构内的数组的最大长度
    private final int maxSharedCapacityFactor;// 共享容量因子。该值越大，在非本线程之外的待回收对象总数越小。因此 非本线程之外的待回收对象总数 = maxCapacityPerThread/maxSharedCapacityFactor
    private final int interval;// 对于从未回收过的对象，Netty选择按一定比例（当）抛弃，避免池内缓存对象速度增长过快，从而影响主线程业务功能。默认值: 8
    private final int maxDelayedQueuesPerThread; // 每线程对象池最多可缓存多少实例对象
    private final int delayedQueueInterval;
    //  每个线程有对应的「Stack」
    private final FastThreadLocal<Stack<T>> threadLocal = new FastThreadLocal<Stack<T>>() {
        @Override
        protected Stack<T> initialValue() {
            return new Stack<T>(Recycler.this, Thread.currentThread(), maxCapacityPerThread, maxSharedCapacityFactor,
                    interval, maxDelayedQueuesPerThread, delayedQueueInterval);
        }
        // 移除后回调方法
        @Override
        protected void onRemoval(Stack<T> value) {
            // Let us remove the WeakOrderQueue from the WeakHashMap directly if its safe to remove some overhead
            if (value.threadRef.get() == Thread.currentThread()) {
               if (DELAYED_RECYCLED.isSet()) {
                   DELAYED_RECYCLED.get().remove(value);  // 安全移除「WeakOrderQueue」
               }
            }
        }
    };

    protected Recycler() {
        this(DEFAULT_MAX_CAPACITY_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread) {
        this(maxCapacityPerThread, MAX_SHARED_CAPACITY_FACTOR);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor) {
        this(maxCapacityPerThread, maxSharedCapacityFactor, RATIO, MAX_DELAYED_QUEUES_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor,
                       int ratio, int maxDelayedQueuesPerThread) {
        this(maxCapacityPerThread, maxSharedCapacityFactor, ratio, maxDelayedQueuesPerThread,
                DELAYED_QUEUE_RATIO);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor,
                       int ratio, int maxDelayedQueuesPerThread, int delayedQueueRatio) {
        interval = max(0, ratio);
        delayedQueueInterval = max(0, delayedQueueRatio);
        if (maxCapacityPerThread <= 0) {
            this.maxCapacityPerThread = 0;
            this.maxSharedCapacityFactor = 1;
            this.maxDelayedQueuesPerThread = 0;
        } else {
            this.maxCapacityPerThread = maxCapacityPerThread;
            this.maxSharedCapacityFactor = max(1, maxSharedCapacityFactor);
            this.maxDelayedQueuesPerThread = max(0, maxDelayedQueuesPerThread);
        }
    }
    // 从对象池获取池化对象: 这个方法是 Netty 是轻量级对象池的入口，主要逻辑是获取线程私有对象 Stack，通过它来获取对象池内的 DefaultHandle 对象。如果从 Stack 获取对象为空，则通过 newObject(Default) 方法新建一个目标对象。newObject(Default) 这个方法需要用户自己实现。
    @SuppressWarnings("unchecked")
    public final T get() {
        if (maxCapacityPerThread == 0) {// 每个线程所持有的State<T> 数据结构内的数组的最大长度，则直接创建一个目标对象
            return newObject((Handle<T>) NOOP_HANDLE);
        }
        Stack<T> stack = threadLocal.get(); //1、获取当前线程缓存的「Stack」对象。
        DefaultHandle<T> handle = stack.pop();//2、从Stack中弹出一个DefaultHandle对象
        if (handle == null) { //3、如果弹出的对象为空，表明内部没有缓存好的对象，需要创建新的handle以及新的Object()，newObject(Handler): 这个方法是抽象方法，需要用户自定义实现
            handle = stack.newHandle();
            handle.value = newObject(handle);// Handle和对象之间互相持有对方的引用,更重要的是Handle对象持有Stack引用，因此在进行回收时就可以把对象直接push到Stack里 （创建的对象并保存到 DefaultHandle）
        }
        return (T) handle.value; // 5、返回
    }

    /**
     * @deprecated use {@link Handle#recycle(Object)}.
     */
    @Deprecated
    public final boolean recycle(T o, Handle<T> handle) {
        if (handle == NOOP_HANDLE) {
            return false;
        }

        DefaultHandle<T> h = (DefaultHandle<T>) handle;
        if (h.stack.parent != this) {
            return false;
        }

        h.recycle(o);
        return true;
    }

    final int threadLocalCapacity() {
        return threadLocal.get().elements.length;
    }

    final int threadLocalSize() {
        return threadLocal.get().size;
    }

    protected abstract T newObject(Handle<T> handle);

    public interface Handle<T> extends ObjectPool.Handle<T>  { }
    // io.netty.util.Recycler.DefaultHandle: 它是Recycler 对回收对象的包装类，Recycler 底层操作的对象是 DefaultHandle，而非直接的回收的对象。它实现 Handle 接口，里面包含 recycle(Object obj) 回收方法。
    @SuppressWarnings("unchecked")
    private static final class DefaultHandle<T> implements Handle<T> {
        private static final AtomicIntegerFieldUpdater<DefaultHandle<?>> LAST_RECYCLED_ID_UPDATER; // 整型字段原子更新器:https://blog.csdn.net/qq_37212210/article/details/92829814
        static {
            AtomicIntegerFieldUpdater<?> updater = AtomicIntegerFieldUpdater.newUpdater(
                    DefaultHandle.class, "lastRecycledId");
            LAST_RECYCLED_ID_UPDATER = (AtomicIntegerFieldUpdater<DefaultHandle<?>>) updater;
        }

        volatile int lastRecycledId; // 上次回收此Handle的RecycleId (用来存储最后一次回收对象的线程ID)
        int recycleId;// 创建此Handle的RecycleId。和 lastRecycledId 配合使用进行重复回收检测

        boolean hasBeenRecycled; // 该对象是否被回收过

        Stack<?> stack; // 创建「DefaultHandle」的Stack对象
        Object value;  // 待回收对象

        DefaultHandle(Stack<?> stack) {
            this.stack = stack;
        }
        //  回收此「Handle」所持有的对象「value」,如果对象不相等，抛出异常
        @Override
        public void recycle(Object object) {
            if (object != value) {
                throw new IllegalArgumentException("object does not belong to handle");
            }

            Stack<?> stack = this.stack;
            if (lastRecycledId != recycleId || stack == null) {
                throw new IllegalStateException("recycled already");
            }
            // 将回收对象入栈，将自己push给Stack，剩下的交给Stack就好了
            stack.push(this);
        }

        public boolean compareAndSetLastRecycledId(int expectLastRecycledId, int updateLastRecycledId) {
            // Use "weak…" because we do not need synchronize-with ordering, only atomicity.
            // Also, spurious failures are fine, since no code should rely on recycling for correctness.
            return LAST_RECYCLED_ID_UPDATER.weakCompareAndSet(this, expectLastRecycledId, updateLastRecycledId);
        }
    }
    // 用于异线程回收，每个线程保存其他线程的「WeakOrderQueue」
    private static final FastThreadLocal<Map<Stack<?>, WeakOrderQueue>> DELAYED_RECYCLED =
            new FastThreadLocal<Map<Stack<?>, WeakOrderQueue>>() {
        @Override
        protected Map<Stack<?>, WeakOrderQueue> initialValue() {// 为每个线程初始化一个「WeakHashMap」对象，保证在没有强引用的情况下能回收对象
            return new WeakHashMap<Stack<?>, WeakOrderQueue>(); // key=>Stack，value=>WeakOrderQueue
        }
    };
    // WeakOrderQueue 用于存储异线程回收本线程所分配的对象(WeakOrderQueue 继承 WeakReference，当所属线程被回收时，相应的 WeakOrderQueue 也会被回收。)
    // a queue that makes only moderate guarantees about visibility: items are seen in the correct order,
    // but we aren't absolutely guaranteed to ever see anything at all, thereby keeping the queue cheap to maintain
    private static final class WeakOrderQueue extends WeakReference<Thread> {// 「WorkOrderQueue」 内部链表是由「Head」和「tail」节点组成的。队内数据并非立即对其他线程可见，采用最终一致性思想。不需要进行保证立即可见，只需要保证最终可见就好了（WeakOrderQueue也是属于某个线程，并且WeakOrderQueue继承了WeakReference<Thread>，当所属线程消亡时，对应WeakOrderQueue也可以被垃圾回收。）
        // 每个WeakOrderQueue都只属于一个Stack，并且只属于一个非主线程。
        static final WeakOrderQueue DUMMY = new WeakOrderQueue(); // 表示当前线程无法再帮助该 Stack 回收对象
        // LINK 节点继承「AtomicInteger」，内部还有一个「readIndex」指针
        // Let Link extend AtomicInteger for intrinsics. The Link itself will be used as writerIndex.
        @SuppressWarnings("serial")
        static final class Link extends AtomicInteger { // 通过 Link 对象构成链表结构，Link 内部维护一个 DefaultHandle[] 数组用来暂存异线程回收对象。添加时会判断是否会超出设置的阈值（默认值: 16），没有则添加成功，否则创建一个新的 Link 节点并添加回收对象，接着更新链表结构，让 tail 指针指向新建的 Link 对象。由于线程不止一个，所以对应的 WeakOrderQueue 也会有多个，WeakOrderQueue 之间则构成链表结构。（Link继承了AtomicInteger）
            final DefaultHandle<?>[] elements = new DefaultHandle[LINK_CAPACITY];

            int readIndex;
            Link next;
        }

        // Its important this does not hold any reference to either Stack or WeakOrderQueue. 重要的是这不包含对 Stack 或 WeakOrderQueue 的任何引用。
        private static final class Head {
            private final AtomicInteger availableSharedCapacity;

            Link link;  //

            Head(AtomicInteger availableSharedCapacity) {
                this.availableSharedCapacity = availableSharedCapacity;
            }

            /** 如果 WeakOrderQueue 所绑定的线程已经死亡，意味着对应的 WeakOrderQueue 不会新增回收对象了，所以需要对这个 WeakOrderQueue 进行相应清理，保证能被 GC，避免内存泄漏，对应的清理方法 cursor.reclaimAllSpaceAndUnlink()。
             * Reclaim all used space and also unlink the nodes to prevent GC nepotism.  回收所有已用空间并取消链接节点以防止 GC 裙带关系。
             */
            void reclaimAllSpaceAndUnlink() {
                Link head = link;
                link = null;
                int reclaimSpace = 0;
                while (head != null) {
                    reclaimSpace += LINK_CAPACITY;
                    Link next = head.next;
                    // Unlink to help GC and guard against GC nepotism.
                    head.next = null;
                    head = next;
                }
                if (reclaimSpace > 0) {
                    reclaimSpace(reclaimSpace);
                }
            }

            private void reclaimSpace(int space) {
                availableSharedCapacity.addAndGet(space);
            }

            void relink(Link link) {
                reclaimSpace(LINK_CAPACITY);
                this.link = link;
            }

            /**
             * Creates a new {@link} and returns it if we can reserve enough space for it, otherwise it
             * returns {@code null}.
             */
            Link newLink() {
                return reserveSpaceForLink(availableSharedCapacity) ? new Link() : null;
            }

            static boolean reserveSpaceForLink(AtomicInteger availableSharedCapacity) {
                for (;;) {
                    int available = availableSharedCapacity.get();
                    if (available < LINK_CAPACITY) {
                        return false;
                    }
                    if (availableSharedCapacity.compareAndSet(available, available - LINK_CAPACITY)) {
                        return true;
                    }
                }
            }
        }

        // chain of data items
        private final Head head;// Head节点管理「Link」对象的创建。内部next指向下一个「Link」节点，构成链表结构 （Head#link指向Link链表首对象）
        private Link tail;    // 数据存储节点 （指向Link链表尾对象）
        // pointer to another queue of delayed items for the same stack
        private WeakOrderQueue next;     // 指向其他异线程的WorkOrderQueue链表（指向WeakOrderQueue链表下一对象）
        private final int id = ID_GENERATOR.getAndIncrement();     // 唯一ID
        private final int interval;// 可以理解为对回收动作限流。默认值: 8,并非到阻塞时才限流，而是一开始就这样做(变量 interval 作用是回收限流，它从一开始就限制回收速率，每经过8 个对象才会回收一个，其余则丢弃。)
        private int handleRecycleCount;// 已丢弃回收对象数量

        private WeakOrderQueue() {
            super(null);
            head = new Head(null);
            interval = 0;
        }

        private WeakOrderQueue(Stack<?> stack, Thread thread) {
            super(thread);
            tail = new Link();

            // Its important that we not store the Stack itself in the WeakOrderQueue as the Stack also is used in
            // the WeakHashMap as key. So just store the enclosed AtomicInteger which should allow to have the
            // Stack itself GCed.
            head = new Head(stack.availableSharedCapacity);
            head.link = tail;
            interval = stack.delayedQueueInterval;
            handleRecycleCount = interval; // Start at interval so the first one will be recycled.
        }

        static WeakOrderQueue newQueue(Stack<?> stack, Thread thread) {
            // We allocated a Link so reserve the space
            if (!Head.reserveSpaceForLink(stack.availableSharedCapacity)) {
                return null;
            }
            final WeakOrderQueue queue = new WeakOrderQueue(stack, thread);
            // Done outside of the constructor to ensure WeakOrderQueue.this does not escape the constructor and so
            // may be accessed while its still constructed.
            stack.setHead(queue);

            return queue;
        }

        WeakOrderQueue getNext() {
            return next;
        }

        void setNext(WeakOrderQueue next) {
            assert next != this;
            this.next = next;
        }

        void reclaimAllSpaceAndUnlink() {
            head.reclaimAllSpaceAndUnlink();
            next = null;
        }

        void add(DefaultHandle<?> handle) {
            if (!handle.compareAndSetLastRecycledId(0, id)) {
                // Separate threads could be racing to add the handle to each their own WeakOrderQueue.
                // We only add the handle to the queue if we win the race and observe that lastRecycledId is zero.
                return;
            }

            // While we also enforce the recycling ratio when we transfer objects from the WeakOrderQueue to the Stack
            // we better should enforce it as well early. Missing to do so may let the WeakOrderQueue grow very fast
            // without control
            if (handleRecycleCount < interval) {  // 控制回收频率，避免WeakOrderQueue增长过快。每8个对象都会抛弃7个，回收一个
                handleRecycleCount++;
                // Drop the item to prevent recycling to aggressive.
                return;
            }
            handleRecycleCount = 0;

            Link tail = this.tail;
            int writeIndex;
            if ((writeIndex = tail.get()) == LINK_CAPACITY) { // 当前Link#elements已全部使用，创建一个新的Link ( 如果链表尾部的 Link 已经写满，那么再新建一个 Link 追加到链表尾部)
                Link link = head.newLink();  // 检查是否超过对应 Stack 可以存放的其他线程帮助回收的最大对象数
                if (link == null) {
                    // Drop it.
                    return;
                }
                // We allocate a Link so reserve the space
                this.tail = tail = tail.next = link;

                writeIndex = tail.get();
            }
            tail.elements[writeIndex] = handle; //  存入缓存对象 (添加对象到 Link 尾部)
            handle.stack = null;  // handle 的 stack 属性赋值为 null
            // we lazy set to ensure that setting stack to null appears before we unnull it in the owning thread;
            // this also means we guarantee visibility of an element in the queue if we see the index updated
            tail.lazySet(writeIndex + 1); //  延迟设置Link#elements的最新索引（Link继承了AtomicInteger），这样在该stack主线程通过该索引获取elements缓存对象时，保证elements中元素已经可见。
        }

        boolean hasFinalData() {
            return tail.readIndex != tail.get();
        }

        // transfer as many items as we can from this queue to the stack, returning true if any were transferred
        @SuppressWarnings("rawtypes")
        boolean transfer(Stack<?> dst) { // 把WeakOrderQueue中的对象迁移到Stack中。
            Link head = this.head.link;
            if (head == null) {
                return false;
            }
            //  head.readIndex 标志现在已迁移对象下标,head.readIndex == LINK_CAPACITY，表示当前Link已全部移动，查找下一个Link
            if (head.readIndex == LINK_CAPACITY) {// 容量
                if (head.next == null) {
                    return false;
                }
                head = head.next;
                this.head.relink(head);
            }
            // 计算待迁移对象数量。 注意:Link继承了AtomicInteger
            final int srcStart = head.readIndex;
            int srcEnd = head.get();
            final int srcSize = srcEnd - srcStart;
            if (srcSize == 0) {
                return false;
            }
            // 计算Stack#elements数组长度，不够则扩容
            final int dstSize = dst.size;
            final int expectedCapacity = dstSize + srcSize;

            if (expectedCapacity > dst.elements.length) {
                final int actualCapacity = dst.increaseCapacity(expectedCapacity);
                srcEnd = min(srcStart + actualCapacity - dstSize, srcEnd);
            }

            if (srcStart != srcEnd) {
                final DefaultHandle[] srcElems = head.elements;
                final DefaultHandle[] dstElems = dst.elements;
                int newDstSize = dstSize; // // 遍历待迁移的对象
                for (int i = srcStart; i < srcEnd; i++) {
                    DefaultHandle<?> element = srcElems[i];
                    if (element.recycleId == 0) {
                        element.recycleId = element.lastRecycledId;
                    } else if (element.recycleId != element.lastRecycledId) {
                        throw new IllegalStateException("recycled already");
                    }
                    srcElems[i] = null;
                    // 控制回收频率
                    if (dst.dropHandle(element)) {
                        // Drop the object.
                        continue;
                    }
                    element.stack = dst;
                    dstElems[newDstSize ++] = element;
                }
                // 当前Link对象已全部移动，修改 WeakOrderQueue#head 的link属性，指向下一Link，这样前面的Link就可以被垃圾回收了。
                if (srcEnd == LINK_CAPACITY && head.next != null) {
                    // Add capacity back as the Link is GCed.
                    this.head.relink(head.next);
                }
                // dst.size == newDstSize 表示并没有对象移动，返回false,否则更新dst.size
                head.readIndex = srcEnd;
                if (dst.size == newDstSize) {
                    return false;
                }
                dst.size = newDstSize;
                return true;
            } else {
                // The destination stack is full already.
                return false;
            }
        }
    }
    // 它是 Netty 对象池的核心类，内部定义了对象池数组结构以及获取、回收（包含本线程回收和异线程回收方法）核心方法。
    private static final class Stack<T> {// Stack 对象是从 Recycle 内部的 FastThreadLocal 对象中获得，因此每个线程拥有属于自己的 Stack 对象，创造了无锁的环境，并通过 weakOrderQueue 与其他线程建立沟通的桥梁。

        // we keep a queue of per-thread queues, which is appended to once only, each time a new thread other
        // than the stack owner recycles: when we run out of items in our stack we iterate this collection
        // to scavenge those that can be reused. this permits us to incur minimal thread synchronisation whilst
        // still recycling all items.
        final Recycler<T> parent; // Stack是被哪个「Recycler」创建的

        // We store the Thread in a WeakReference as otherwise we may be the only ones that still hold a strong
        // Reference to the Thread itself after it died because DefaultHandle will hold a reference to the Stack.
        //
        // The biggest issue is if we do not use a WeakReference the Thread may not be able to be collected at all if
        // the user will store a reference to the DefaultHandle somewhere and never clear this reference (or not clear
        // it in a timely manner).
        final WeakReference<Thread> threadRef;   // 将线程存储在一个 WeakReference 中，否则我们可能是唯一一个在线程死亡后仍然持有对线程本身的强引用的引用，因为 DefaultHandle 将持有对栈的引用。最大的问题是，如果我们不使用 WeakReference，那么如果用户在某个地方存储了对 DefaultHandle 的引用，并且永远不会清除这个引用(或者不能及时清除它) ，那么 Thread 可能根本不能被收集。(主线程)
        final AtomicInteger availableSharedCapacity;  // 该「Stack」所对应其它线程剩余可缓存对象实例个数（就是其它线程此时可缓存对象数是多少）,默认值: 2048
        private final int maxDelayedQueues;// 一个线程可同时缓存多少个「Stack」对象。这个「Stack」可以理解为其它线程的「Stack」毕竟不可能缓存所有的「Stack」吧，所以需要做一点限制，默认值: 8

        private final int maxCapacity; // 数组最大容量。默认值: 4096
        private final int interval;// 可以理解为对回收动作限流。默认值: 8，并非到阻塞时才限流，而是一开始就这样做
        private final int delayedQueueInterval;
        DefaultHandle<?>[] elements;// 存储缓存数据的数组（主线程回收的对象）。默认值: 256
        int size; // 数组中非空元素数量。默认值: 0
        private int handleRecycleCount; // 跳过回收对象数量。从0开始计数，每跳过一个回收对象+1。当handleRecycleCount>interval时重置handleRecycleCount=0，默认值: 8。初始值和「interval」，以便第一个元素能被回收
        private WeakOrderQueue cursor, prev;     // 与异线程回收的相关三个节点
        private volatile WeakOrderQueue head; // 非主线程回收的对象（指向的WeakOrderQueue，用于存放其他线程的对象）

        Stack(Recycler<T> parent, Thread thread, int maxCapacity, int maxSharedCapacityFactor,
              int interval, int maxDelayedQueues, int delayedQueueInterval) {
            this.parent = parent;
            threadRef = new WeakReference<Thread>(thread);
            this.maxCapacity = maxCapacity;
            availableSharedCapacity = new AtomicInteger(max(maxCapacity / maxSharedCapacityFactor, LINK_CAPACITY));
            elements = new DefaultHandle[min(INITIAL_CAPACITY, maxCapacity)];
            this.interval = interval;
            this.delayedQueueInterval = delayedQueueInterval;
            handleRecycleCount = interval; // Start at interval so the first one will be recycled.
            this.maxDelayedQueues = maxDelayedQueues;
        }

        // Marked as synchronized to ensure this is serialized.
        synchronized void setHead(WeakOrderQueue queue) {
            queue.setNext(head);
            head = queue;
        }

        int increaseCapacity(int expectedCapacity) {
            int newCapacity = elements.length;
            int maxCapacity = this.maxCapacity;
            do {
                newCapacity <<= 1;
            } while (newCapacity < expectedCapacity && newCapacity < maxCapacity);

            newCapacity = min(newCapacity, maxCapacity);
            if (newCapacity != elements.length) {
                elements = Arrays.copyOf(elements, newCapacity);
            }

            return newCapacity;
        }
        // 从栈中弹出一个「DefaultHandler」对象。① 如果size>0，则弹出elements[]数组中的元素。 ② 如果size=0，尝试从其他线程缓存对象中窃取部分缓存对象
        @SuppressWarnings({ "unchecked", "rawtypes" })
        DefaultHandle<T> pop() {
            int size = this.size;
            if (size == 0) { // 当前缓存的对象数为0，尝试从异线程相关的「WeakOrderQueue」队列中获取可回收对象  （ 就尝试从其他线程回收的对象中转移一些到 elements 数组当中）  --  elements没有可用对象时，将WeakOrderQueue中的对象迁移到elements
                if (!scavenge()) {
                    return null; // 异线程也没有可回收对象，返回null
                }
                size = this.size;
                if (size <= 0) {
                    // double check, avoid races
                    return null;
                }
            }
            size --; // 栈中有缓存对象 (从elements中取出一个缓存对象)
            DefaultHandle ret = elements[size];// 内部使用数组模拟栈 （将实例从栈顶弹出）
            elements[size] = null;
            // As we already set the element[size] to null we also need to store the updated size before we do
            // any validation. Otherwise we may see a null value when later try to pop again without a new element
            // added before.
            this.size = size;// 更新size

            if (ret.lastRecycledId != ret.recycleId) {// 重复回收校验，如果回收ID不相等，表示不是同一个
                throw new IllegalStateException("recycled multiple times");// 如果上一次对象回收ID和这次的不一样，抛出异常
            }
            ret.recycleId = 0;  // 重置「DefaultHandler」回收ID信息
            ret.lastRecycledId = 0;
            return ret;
        }
        //  scavenge有清扫的意思，这个方法用于从「WeakOrderQueue」中回收部分对象
        private boolean scavenge() {
            // continue an existing scavenge, if any
            if (scavengeSome()) { // 尝试从「WeakOrderQueue」中获取部分待回收对象并转换到「Stack#DefaultHandler[]」数组中 (尝试从 WeakOrderQueue 中转移对象实例到 Stack 中)
                return true; // 转移成功
            }
            // 获取失败，重置prev、cursor指针  (如果迁移失败，就会重置 cursor 指针到 head 节点)
            // reset our scavenge cursor
            prev = null;
            cursor = head;
            return false;
        }
        // 尝试从异线程中回收对象  注意:这个方法是在 Stack 对象中执行的，而 Stack 保存了与异线程相关的 WeakOrderQueue 链表，所以可以通过遍历链表寻找回收对象。
        private boolean scavengeSome() {// scavengeSome() 只要成功数量大于 1 就直接返回 true，否则继续遍历其他异线程的 WeakOrderQueue，直接遍历所有的 WeakOrderQueue 或找到可用的回收对象为止。
            WeakOrderQueue prev;
            WeakOrderQueue cursor = this.cursor; // cursor 指针指向当前 WeakorderQueue 链表的读取位置
            if (cursor == null) { // 当前cursor指针为空 (如果 cursor 指针为 null, 则是第一次从 WeakorderQueue 链表中获取对象)
                prev = null;
                cursor = head;// 指向头结点
                if (cursor == null) {
                    return false; // 头结点为空，表明没有任何异线程存入，直接返回false
                }
            } else {
                prev = this.prev;
            }

            boolean success = false;
            do { // 存在异线程「WeakOrderQueue」，准备回收对象 ( 不断循环从 WeakOrderQueue 链表中找到一个可用的对象实例)
                if (cursor.transfer(this)) {// 尝试从异线程「WeakOrderQueue」尽可能多地转移回收对象。但并不会更新指针,当转移对象数量>0，就返回true  (尝试迁移 WeakOrderQueue 中部分对象实例到 Stack 中)
                    success = true;// 转移成功，跳出循环
                    break;
                }
                WeakOrderQueue next = cursor.getNext();  // 保存下一个「WeakOrderQueue」的引用，因为本次的「WeakOrderQueue」可能会被回收
                if (cursor.get() == null) {
                    // If the thread associated with the queue is gone, unlink it, after // 如果与队列相关的线程终结了（cursor.get()==null），需要判断是否还有可回收对象，若有转移后解除它的连接,我们永远不会断开第一个连接，因为不想在更新头部时进行同步
                    // performing a volatile read to confirm there is no data left to collect.
                    // We never unlink the first queue, as we don't want to synchronize on updating the head.
                    if (cursor.hasFinalData()) { // 如果已退出的线程还有数据
                        for (;;) { // 按一定比例转移对象
                            if (cursor.transfer(this)) {
                                success = true;
                            } else {
                                break;
                            }
                        }
                    }
                    // 将已退出的线程从 WeakOrderQueue 链表中移除
                    if (prev != null) {
                        // Ensure we reclaim all space before dropping the WeakOrderQueue to be GC'ed.
                        cursor.reclaimAllSpaceAndUnlink();// 确保在删除 WeakOrderQueue 以便进行GC之前收回所有空间。
                        prev.setNext(next);// 更新节点信息，删除cursor节点，
                    }
                } else {
                    prev = cursor;
                }

                cursor = next;// 更新cursor指针，指向「WeakOrderQueue」下一个对象 (将 cursor 指针指向下一个 WeakOrderQueue)

            } while (cursor != null && !success);

            this.prev = prev;
            this.cursor = cursor;
            return success;
        }

        void push(DefaultHandle<?> item) {
            Thread currentThread = Thread.currentThread(); // 同线程回收和异线程回收
            if (threadRef.get() == currentThread) { // 当前线程是主线程，直接将对象加入到Stack#elements中。
                // The current Thread is the thread that belongs to the Stack, we can try to push the object now.
                pushNow(item);
            } else {
                // The current Thread is not the one that belongs to the Stack
                // (or the Thread that belonged to the Stack was collected already), we need to signal that the push
                // happens later.
                pushLater(item, currentThread); //  当前线程非主线程，需要将对象放到对应的WeakOrderQueue中
            }
        }

        private void pushNow(DefaultHandle<?> item) { // 将对象加入到Stack#elements中
            if (item.recycleId != 0 || !item.compareAndSetLastRecycledId(0, OWN_THREAD_ID)) { // 防止被多次回收
                throw new IllegalStateException("recycled already");
            }
            item.recycleId = OWN_THREAD_ID;

            int size = this.size;
            if (size >= maxCapacity || dropHandle(item)) { // 1. 超出最大容量 2. 控制回收速率.
                // Hit the maximum capacity or should drop - drop the possibly youngest object.  达到最大容量或应该丢弃 - 丢弃可能最年轻的对象。
                return;
            }
            if (size == elements.length) { // 缩容
                elements = Arrays.copyOf(elements, min(size << 1, maxCapacity));
            }

            elements[size] = item;
            this.size = size + 1;
        }

        private void pushLater(DefaultHandle<?> item, Thread thread) { // 将对象放到对应的WeakOrderQueue中
            if (maxDelayedQueues == 0) {
                // We don't support recycling across threads and should just drop the item on the floor.
                return;
            }
            //  DELAYED_RECYCLED是一个FastThreadLocal，可以理解为Netty中的ThreadLocal优化类。它为每个线程维护了一个Map，存储每个Stack和对应WeakOrderQueue。所有这里获取的delayedRecycled变量是仅用于当前线程的。而delayedRecycled.get获取的WeakOrderQueue，是以Thread + Stack作为维度区分的，只能是一个线程操作。
            // we don't want to have a ref to the queue as the value in our weak map
            // so we null it out; to ensure there are no races with restoring it later
            // we impose a memory ordering here (no-op on x86)
            Map<Stack<?>, WeakOrderQueue> delayedRecycled = DELAYED_RECYCLED.get(); // 当前线程帮助其他线程回收对象的缓存
            WeakOrderQueue queue = delayedRecycled.get(this); // 取出对象绑定的 Stack 对应的 WeakOrderQueue
            if (queue == null) { // 当前WeakOrderQueue数量超出限制，添加WeakOrderQueue.DUMMY作为标记
                if (delayedRecycled.size() >= maxDelayedQueues) { // 最多帮助 2*CPU 核数的线程回收线程
                    // Add a dummy queue so we know we should drop the object
                    delayedRecycled.put(this, WeakOrderQueue.DUMMY); // 添加一个虚拟队列，以便我们知道应该删除该对象 ( WeakOrderQueue.DUMMY 表示当前线程无法再帮助该 Stack 回收对象)
                    return;
                }
                // Check if we already reached the maximum number of delayed queues and if we can allocate at all.
                if ((queue = newWeakOrderQueue(thread)) == null) { // 构造一个WeakOrderQueue，加入到Stack#head指向的WeakOrderQueue链表中，并放入DELAYED_RECYCLED。这时是需要一下同步操作的。
                    // drop object
                    return;
                }
                delayedRecycled.put(this, queue);
            } else if (queue == WeakOrderQueue.DUMMY) {
                // drop object
                return; // 遇到WeakOrderQueue.DUMMY标记对象，直接抛弃对象
            }

            queue.add(item); //  将缓存对象添加到WeakOrderQueue中。( 添加对象到 WeakOrderQueue 的 Link 链表中)
        }

        /**
         * Allocate a new {@link WeakOrderQueue} or return {@code null} if not possible.
         */
        private WeakOrderQueue newWeakOrderQueue(Thread thread) {
            return WeakOrderQueue.newQueue(this, thread);
        }

        boolean dropHandle(DefaultHandle<?> handle) {
            if (!handle.hasBeenRecycled) { // 从每 8 个未被收回的对象中选取一个进行回收，其他的都被丢弃掉
                if (handleRecycleCount < interval) {
                    handleRecycleCount++;
                    // Drop the object.
                    return true;
                }
                handleRecycleCount = 0;
                handle.hasBeenRecycled = true;
            }
            return false;
        }

        DefaultHandle<T> newHandle() {
            return new DefaultHandle<T>(this);
        }
    }
}
