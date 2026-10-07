package android.content.res

import java.io.InputStream

/** Stub mínimo de AssetManager (el arnés no usa load(); sólo debe compilar). */
open class AssetManager {
    open fun open(fileName: String): InputStream =
        throw UnsupportedOperationException("stub AssetManager")
}
