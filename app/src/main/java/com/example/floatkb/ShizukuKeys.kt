package com.example.floatkb

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import android.view.KeyEvent
import rikka.shizuku.Shizuku

/** App-side helper that talks to Shizuku and our KeyServer. */
object ShizukuKeys {

    private var binder: IBinder? = null
    private var binding = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service
            binding = false
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
            binding = false
        }
    }

    fun isRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
    }

    fun hasPermission(): Boolean = try {
        !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    fun requestPermission() {
        try {
            Shizuku.requestPermission(100)
        } catch (t: Throwable) {
        }
    }

    fun isConnected(): Boolean = binder?.pingBinder() == true

    fun connect(ctx: Context) {
        if (isConnected() || binding) return
        if (!isRunning() || !hasPermission()) return
        try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(ctx.packageName, KeyServer::class.java.name)
            ).daemon(false).processNameSuffix("keys").debuggable(false).version(1)
            binding = true
            Shizuku.bindUserService(args, connection)
        } catch (t: Throwable) {
            binding = false
        }
    }

    fun describe(): String = when {
        !isRunning() -> "Shizuku is not running. Start it first."
        !hasPermission() -> "Open the FLOAT app and tap Allow Shizuku."
        !isConnected() -> "Connecting to key service... tap a key again in a moment."
        else -> "Ready"
    }

    fun key(keyCode: Int, down: Boolean, meta: Int): Boolean {
        val b = binder ?: return false
        if (!b.pingBinder()) {
            binder = null
            return false
        }
        val d = Parcel.obtain()
        return try {
            d.writeInt(keyCode)
            d.writeInt(if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP)
            d.writeInt(meta)
            b.transact(1, d, null, IBinder.FLAG_ONEWAY)
        } catch (t: Throwable) {
            false
        } finally {
            d.recycle()
        }
    }
}
