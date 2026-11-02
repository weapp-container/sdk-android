package com.sonofmagic.weapp.container.sdk

internal object NativeBridge {
    init { System.loadLibrary("weapp_container_android") }
    external fun create(config: String): Long
    external fun command(handle: Long, operation: String, payload: String): String
    external fun destroy(handle: Long): String
    external fun glesAttach(handle: Long): String
    external fun glesPresent(handle: Long, width: Int, height: Int): String
    external fun glesDetach(handle: Long, abandon: Boolean): String
    external fun snapshot(handle: Long): ByteArray
    /** Same flock protocol as the Rust synchronous FileSystemManager provider. */
    external fun filesLock(path: String): Long
    external fun filesUnlock(token: Long)
}
