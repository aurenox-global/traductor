package android.content

import android.content.res.AssetManager

/** Stub mínimo de android.content.Context para ejecutar el motor en el JVM del Mac. */
open class Context {
    open val assets: AssetManager get() = AssetManager()
}
