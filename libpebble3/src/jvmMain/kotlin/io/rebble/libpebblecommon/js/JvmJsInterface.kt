package io.rebble.libpebblecommon.js

/**
 * JVM twin of the iOS RegisterableJsInterface. Each instance backs one JS global object;
 * all its method calls arrive through the runner's single __nativeDispatch host function.
 */
internal interface JvmJsInterface : JsEngineInterface, AutoCloseable {
    /** Called after the proxy is installed. [evalNow] evaluates on the current JS thread. */
    fun onRegister(evalNow: (String) -> Unit) {}

    override fun close() {}
}
