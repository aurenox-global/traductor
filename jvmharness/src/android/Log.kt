package android.util

/** Stub mínimo de android.util.Log (imprime a stdout) para el arnés JVM. */
object Log {
    @JvmStatic fun v(tag: String, msg: String): Int { println("[V/$tag] $msg"); return 0 }
    @JvmStatic fun d(tag: String, msg: String): Int { println("[D/$tag] $msg"); return 0 }
    @JvmStatic fun i(tag: String, msg: String): Int { println("[I/$tag] $msg"); return 0 }
    @JvmStatic fun w(tag: String, msg: String): Int { println("[W/$tag] $msg"); return 0 }
    @JvmStatic fun e(tag: String, msg: String): Int { println("[E/$tag] $msg"); return 0 }
}
