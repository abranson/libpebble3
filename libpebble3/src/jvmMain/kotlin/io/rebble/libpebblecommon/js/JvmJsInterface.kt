package io.rebble.libpebblecommon.js

/**
 * JVM twin of the iOS RegisterableJsInterface. Each instance backs one JS global object;
 * all its method calls arrive through the runner's single __nativeDispatch host function.
 */
internal interface JvmJsInterface : AutoCloseable {
    /** JS global the proxy object is installed as (e.g. "Pebble", "_XMLHTTPRequestManager"). */
    val name: String

    /** JS-facing method names — used only to generate the proxy object. */
    val methods: Set<String>

    /**
     * Invoked on the JS thread. [args] are already converted to host types: JS numbers are
     * [Double] (like JavaScriptCore), strings [String], booleans [Boolean], arrays [List].
     */
    fun dispatch(method: String, args: List<Any?>): Any?

    /**
     * Called after the proxy object is installed. [evalNow] runs JS synchronously on the JS
     * thread (only safe to use inside this callback).
     */
    fun onRegister(evalNow: (String) -> Unit) {}

    override fun close() {}
}
