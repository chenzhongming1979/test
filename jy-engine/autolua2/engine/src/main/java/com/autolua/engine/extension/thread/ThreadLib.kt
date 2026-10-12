package com.autolua.engine.extension.thread

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

/**
 * thread 库 Java 驱动端（与 base/thread_lib.cpp 配对）：
 *
 * - [start] 由 C 层 thread.start 调用：登记协程任务并在线程池中驱动 resume 循环
 * - resume 返回 yield 编码时按 op 分派：sleep（限时等待）/ lock（可中断等待，保持持有）
 * - 任务取消（stop/stopAll）在下一个挂起点生效：不再 resume，释放 registry ref
 * - [joinBlocking] 由 C 层 thread.join 调用（此时 Lua 状态锁已被调用方释放）
 * - 共享数据不落 Java：registry 共享表由 C 层直接维护（任意 Lua 类型、原生并发保护）
 *
 * 状态码与 C 侧对齐：0=running 1=done 2=error 3=cancelled
 * resume 返回编码（与 thread_lib.cpp 对齐）：高 32 位 tag（0=yield 1=done 2=error），
 * 低 32 位 yield 编码（op<<24 | value）
 */
object ThreadLib {

    private const val TAG = "LuaThreadLib"

    // native 方法由 libengine_base 提供（LuaContextImplement 已触发加载；此处幂等）
    private external fun nativeResume(luaPtr: Long, ref: Int, taskId: Int): Long
    private external fun nativeUnref(luaPtr: Long, ref: Int)

    init {
        try {
            System.loadLibrary("engine_base")
        } catch (t: Throwable) {
            Log.w(TAG, "load engine_base failed: $t")
        }
    }

    // ---------------- 编码常量（与 thread_lib.cpp 对齐） ----------------
    private const val TAG_YIELD = 0L
    private const val TAG_DONE = 1L
    private const val TAG_ERROR = 2L
    private const val OP_SLEEP = 1
    private const val OP_LOCK = 2
    private const val YIELD_OP_MASK = 0xFF000000.toInt()
    private const val YIELD_VALUE_MASK = 0x00FFFFFF

    // ---------------- 任务状态 ----------------
    const val ST_RUNNING = 0
    const val ST_DONE = 1
    const val ST_ERROR = 2
    const val ST_CANCELLED = 3

    private class Task(val taskId: Int) {
        // begin/submit 两阶段：登记时 luaPtr/ref 尚未给定
        var luaPtr: Long = 0L
        var ref: Int = 0

        @Volatile
        var status: Int = ST_RUNNING

        @Volatile
        var stopped: Boolean = false

        // 终态时间戳（begin 时做过期清扫，防 fire-and-forget 任务堆积）
        @Volatile
        var doneAt: Long = 0L

        // Kotlin 不可用 Object.wait/notify：以 CountDownLatch 实现终态等待
        val doneLatch = java.util.concurrent.CountDownLatch(1)
    }

    private val tasks = ConcurrentHashMap<Int, Task>()
    private val idGen = AtomicInteger(1000)
    private val lockIdGen = AtomicInteger(1000)
    private val locks = ConcurrentHashMap<Int, ReentrantLock>()

    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "lua-coroutine").apply { isDaemon = true }
    }

    /**
     * C 层 thread.start 第一步：分配 taskId 并登记任务（尚未驱动）。
     * 必须先登记再 submit——否则池线程可能先于 C 侧 gTaskCo 注册执行 nativeResume，
     * 查不到 co 直接 TAG_ERROR，任务瞬间失败（偶发 thread.join false / setTimer 不触发的根因）。
     */
    @JvmStatic
    fun begin(): Int {
        val taskId = idGen.incrementAndGet()
        tasks[taskId] = Task(taskId)
        sweep()
        return taskId
    }

    /** C 层 thread.start 第二步：登记完成后启动 resume 循环 */
    @JvmStatic
    fun submit(luaPtr: Long, ref: Int, taskId: Int) {
        val task = tasks[taskId] ?: return
        task.luaPtr = luaPtr
        task.ref = ref
        executor.execute { runLoop(task) }
    }

    private fun runLoop(task: Task) {
        try {
            while (true) {
                val r = nativeResume(task.luaPtr, task.ref, task.taskId)
                val tag = (r ushr 32)
                val payload = (r and 0xFFFFFFFFL).toInt()
                when (tag) {
                    TAG_DONE -> {
                        finish(task, ST_DONE)
                        return
                    }
                    TAG_ERROR -> {
                        finish(task, ST_ERROR)
                        return
                    }
                    TAG_YIELD -> {
                        if (task.stopped) {
                            finishCancelled(task)
                            return
                        }
                        val op = (payload and YIELD_OP_MASK.toInt()) ushr 24
                        val value = payload and YIELD_VALUE_MASK
                        when (op) {
                            OP_SLEEP -> Thread.sleep(value.toLong())
                            OP_LOCK -> {
                                val lk = locks[value]
                                if (lk == null) {
                                    Log.w(TAG, "acquire: lock $value not found (released?)")
                                } else {
                                    lk.lockInterruptibly() // 中断/停止经异常或停止标志处理
                                }
                            }
                            else -> Log.w(TAG, "unknown yield op $op")
                        }
                        // 挂起等待期间可能被 stop
                        if (task.stopped) {
                            // lock 场景：已 acquire 但要取消 → 释放
                            if (op == OP_LOCK) locks[value]?.unlock()
                            finishCancelled(task)
                            return
                        }
                    }
                    else -> {
                        Log.w(TAG, "unexpected resume tag $tag")
                        finish(task, ST_ERROR)
                        return
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            finish(task, ST_CANCELLED)
            nativeUnref(task.luaPtr, task.ref)
        } catch (t: Throwable) {
            Log.w(TAG, "coroutine loop error: $t")
            finish(task, ST_ERROR)
        }
    }

    /**
     * 终态处理：置状态 + 开闩。不从 tasks 移除——
     * 快速完成的任务在 join 查询前移除会被误判 CANCELLED；
     * 由 C 层 eraseTask 调 [remove] 清理，或 [begin] 时过期清扫兜底。
     */
    private fun finish(task: Task, status: Int) {
        task.status = status
        task.doneAt = System.currentTimeMillis()
        task.doneLatch.countDown()
    }

    private fun finishCancelled(task: Task) {
        finish(task, ST_CANCELLED)
        nativeUnref(task.luaPtr, task.ref)
    }

    /** C 层 thread.stop：置取消标志（协程在下一个挂起点结束） */
    @JvmStatic
    fun stop(taskId: Int) {
        tasks[taskId]?.stopped = true
    }

    /** C 层 eraseTask：join 完成/清理后移除终态任务 */
    @JvmStatic
    fun remove(taskId: Int) {
        tasks.remove(taskId)
    }

    /** 过期清扫：终态任务保留 30s 供 join 查询，超时移除（fire-and-forget 兜底防堆积） */
    private fun sweep() {
        val deadline = System.currentTimeMillis() - 30_000L
        for ((id, t) in tasks) {
            if (t.status != ST_RUNNING && t.doneAt in 1..deadline) {
                tasks.remove(id)
            }
        }
    }

    /** C 层引擎 interrupt：停止该 lua_State 上的全部协程 */
    @JvmStatic
    fun stopAll(luaPtr: Long) {
        for (t in tasks.values) {
            if (t.luaPtr == luaPtr) t.stopped = true
        }
    }

    /** C 层 thread.join：阻塞等待终态（调用方已释放 Lua 状态锁），返回终态状态码 */
    @JvmStatic
    fun joinBlocking(taskId: Int): Int {
        val task = tasks[taskId] ?: return ST_CANCELLED
        task.doneLatch.await()
        return task.status
    }

    // ---------------- lock（Java ReentrantLock 句柄） ----------------

    /** C 层 thread.lock：创建锁句柄 */
    @JvmStatic
    fun createLock(): Int {
        val id = lockIdGen.incrementAndGet()
        locks[id] = ReentrantLock()
        return id
    }

    /** C 层 thread.release：释放锁句柄对应的持有 */
    @JvmStatic
    fun releaseLock(lockId: Int) {
        locks[lockId]?.unlock()
    }
}
