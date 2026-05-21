package com.example.heartratecomparison

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import com.example.heartratecomparison.bluetooth.HeartRateService

/**
 * 小米 HyperOS 公平运行内存适配
 * 监听 TRIM 预警广播，释放内存 / 保存现场数据，并回调系统
 */
class MemoryReceiver : IBinder.DeathRecipient {

    companion object {
        private const val TAG = "MemoryReceiver"
        private const val ITGSA_ACTION = "itgsa.intent.action.TRIM"
        private const val TRANSACTION_EXCEPTION_REPLY = IBinder.FIRST_CALL_TRANSACTION

        @Volatile
        private var INSTANCE: MemoryReceiver? = null

        fun getInstance(): MemoryReceiver =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: MemoryReceiver().also { INSTANCE = it }
            }
    }

    private var remote: IBinder? = null
    private var initialized = false
    private var handler: Handler? = null

    override fun binderDied() {
        synchronized(this) {
            try {
                remote?.unlinkToDeath(this, 0)
            } catch (_: Exception) {}
            remote = null
        }
    }

    /**
     * 在 Application 或 Activity.onCreate 中调用一次
     */
    fun initialize(context: Context) {
        synchronized(this) {
            if (initialized) return
            val ht = HandlerThread(TAG)
            ht.start()
            handler = Handler(ht.looper)
            val filter = IntentFilter(ITGSA_ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter, null, handler)
            }
            initialized = true
            Log.i(TAG, "MemoryReceiver initialized")
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ITGSA_ACTION != intent.action) return
            val data = intent.extras ?: return
            val bundle = data.getBundle("common") ?: return

            val notifyType = bundle.getInt("notifyType")
            val notifyId = bundle.getInt("notifyId")
            val reason = bundle.getString("reason") ?: ""
            val callbackBinder = bundle.getBinder("callback")

            val extraData = data.getBundle("extra")
            val pss = extraData?.getInt("pss") ?: 0
            val pssLimit = extraData?.getInt("pssLimit") ?: 0
            val heapAlloc = extraData?.getInt("heapAlloc") ?: 0
            val heapCapacity = extraData?.getInt("heapCapacity") ?: 0

            Log.w(TAG, "收到内存广播 type=$notifyType id=$notifyId reason=$reason " +
                    "pss=${pss}kB/$pssLimit kB heap=${heapAlloc}kB/$heapCapacity kB")

            if (callbackBinder != null) {
                handleReceived(notifyType, notifyId, callbackBinder)
            } else {
                Log.w(TAG, "callback binder is null")
            }
        }
    }

    private fun handleReceived(notifyType: Int, notifyId: Int, callback: IBinder) {
        if (!checkRemote(callback)) return

        // 释放内存：通知 Service 清理缓存
        try {
            HeartRateService.releaseMemory()
        } catch (e: Exception) {
            Log.e(TAG, "releaseMemory failed", e)
        }

        // 回调系统：0 = 成功
        val extra = Bundle().apply {
            putString("reply", "memory_released")
        }
        reply(notifyType, notifyId, 0, extra)
    }

    private fun checkRemote(callback: IBinder): Boolean {
        synchronized(this) {
            if (remote == null) {
                try {
                    remote = callback
                    remote?.linkToDeath(this, 0)
                } catch (e: Exception) {
                    remote = null
                    return false
                }
            }
        }
        return true
    }

    private fun reply(notifyType: Int, notifyId: Int, result: Int, extra: Bundle?) {
        synchronized(this) {
            val r = remote ?: return
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInt(notifyType)
                data.writeInt(notifyId)
                data.writeInt(result)
                data.writeBundle(extra ?: Bundle())
                r.transact(TRANSACTION_EXCEPTION_REPLY, data, reply, IBinder.FLAG_ONEWAY)
                reply.readException()
                Log.i(TAG, "reply success: type=$notifyType id=$notifyId result=$result")
            } catch (e: Exception) {
                Log.e(TAG, "reply failed", e)
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
    }
}
