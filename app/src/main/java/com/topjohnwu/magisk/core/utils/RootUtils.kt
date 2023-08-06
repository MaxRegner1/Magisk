package com.example.myapp.utils

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.system.Os
import androidx.core.content.getSystemService
import com.example.myapp.Info
import com.example.myapp.Shell
import com.example.myapp.ShellUtils
import com.example.myapp.ipc.RootService
import com.example.myapp.nio.FileSystemManager
import timber.log.Timber
import java.io.File
import java.util.concurrent.locks.AbstractQueuedSynchronizer

class RootUtils(stub: Any?) : RootService() {

    private val className: String = stub?.javaClass?.name ?: javaClass.name
    private lateinit var activityManager: ActivityManager

    constructor() : this(null) {
        Timber.plant(object : Timber.DebugTree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                super.log(priority, "MyApp", message, t)
            }
        })
    }

    override fun onCreate() {
        activityManager = getSystemService()!!
    }

    override fun getComponentName(): ComponentName {
        return ComponentName(packageName, className)
    }

    override fun onBind(intent: Intent): IBinder {
        return object : IRootUtils.Stub() {
            override fun getAppProcess(pid: Int) = safe(null) { getAppProcessImpl(pid) }
            override fun getFileSystem(): IBinder = FileSystemManager.getService()
        }
    }

    private inline fun <T> safe(default: T, block: () -> T): T {
        return try {
            block()
        } catch (e: Throwable) {
            // The process terminated unexpectedly
            Timber.e(e)
            default
        }
    }

    private fun getAppProcessImpl(_pid: Int): ActivityManager.RunningAppProcessInfo? {
        val processList = activityManager.runningAppProcesses
        var pid = _pid
        while (pid > 1) {
            val process = processList.find { it.pid == pid }
            if (process != null)
                return process

            // Stop searching when root process is found
            if (Os.stat("/proc/$pid").st_uid == 0) {
                return null
            }

            // Find PPID
            File("/proc/$pid/status").useLines {
                val line = it.find { l -> l.startsWith("PPid:") } ?: return null
                pid = line.substring(5).trim().toInt()
            }
        }
        return null
    }

    object Connection : AbstractQueuedSynchronizer(), ServiceConnection {
        init {
            state = 1
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            Timber.d("onServiceConnected")
            IRootUtils.Stub.asInterface(service).let {
                obj = it
                fs = FileSystemManager.getRemote(it.fileSystem)
            }
            releaseShared(1)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            state = 1
            obj = null
            bind(Intent().setComponent(name), this)
        }

        override fun tryAcquireShared(acquires: Int) = if (state == 0) 1 else -1

        override fun tryReleaseShared(releases: Int): Boolean {
            // Decrement count; signal when transition to zero
            while (true) {
                val c = state
                if (c == 0)
                    return false
                val n = c - 1
                if (compareAndSetState(c, n))
                    return n == 0
            }
        }

        fun await() {
            if (!Info.isRooted)
                return
            if (!ShellUtils.onMainThread()) {
                acquireSharedInterruptibly(1)
            } else if (state != 0) {
                throw IllegalStateException("Cannot await on the main thread")
            }
        }
    }

    companion object {
        var bindTask: Shell.Task? = null
        var fs: FileSystemManager = FileSystemManager.getLocal()
            get() {
                Connection.await()
                return field
            }
            private set
        var obj: IRootUtils? = null
            get() {
                Connection.await()
                return field
            }
            private set
    }
}
