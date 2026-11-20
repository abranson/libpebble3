package io.rebble.libpebblecommon.js

import co.touchlab.kermit.Logger
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotAccess
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.io.IOAccess
import org.graalvm.polyglot.proxy.ProxyExecutable

/** Bare GraalJS context shared by PKJS and plugin sessions. The caller owns its dispatcher. */
internal class GraalJsRuntime(private val logger: Logger) {
    private var context: Context? = null
    private var interfaces = emptyList<JsEngineInterface>()

    fun start(instances: List<JsEngineInterface>) {
        check(context == null)
        interfaces = instances
        val js = Context.newBuilder("js")
            .allowExperimentalOptions(true)
            .option("js.ecmascript-version", "2022")
            .allowHostAccess(HostAccess.NONE)
            .allowPolyglotAccess(PolyglotAccess.NONE)
            .allowIO(IOAccess.NONE)
            .out(LoggingOutputStream(logger, false))
            .err(LoggingOutputStream(logger, true))
            .build()
        context = js
        try {
            val byName = instances.associateBy { it.name }
            js.getBindings("js").putMember("__nativeDispatch", ProxyExecutable { args ->
                val name = args.getOrNull(0)?.asString() ?: return@ProxyExecutable null
                val method = args.getOrNull(1)?.asString() ?: return@ProxyExecutable null
                val target = byName[name]?.takeIf { method in it.methods } ?: return@ProxyExecutable null
                val values = args.getOrNull(2)?.takeIf { it.hasArrayElements() }?.toKotlinList().orEmpty()
                target.dispatch(method, values).takeUnless { it == Unit }
            })
            for (target in instances) {
                val methods = target.methods.joinToString(",") { "'$it'" }
                eval("""
                    (function () {
                        const target = {};
                        for (const method of [$methods]) {
                            target[method] = function () {
                                return __nativeDispatch('${target.name}', method, Array.from(arguments));
                            };
                        }
                        globalThis['${target.name}'] = target;
                    })();
                """.trimIndent())
                (target as? JvmJsInterface)?.onRegister { eval(it) }
            }
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    fun eval(js: String, name: String = "<native>"): Value? = try {
        context?.eval(Source.newBuilder("js", js, name).buildLiteral())
    } catch (e: Exception) {
        logger.e { "JS exception: ${e.message}" }
        null
    }

    fun stop() {
        val js = context
        context = null
        interfaces.forEach { instance ->
            runCatching { (instance as? AutoCloseable)?.close() }
                .onFailure { logger.w(it) { "Failed to close ${instance.name}" } }
        }
        interfaces = emptyList()
        js?.close(true)
    }
}

/** Convert a polyglot Value to the host types the dispatch tables expect (JSC-compatible). */
internal fun Value.toKotlin(): Any? = when {
    isNull -> null
    isBoolean -> asBoolean()
    isNumber -> asDouble()
    isString -> asString()
    hasArrayElements() -> toKotlinList()
    else -> this
}

private fun Value.toKotlinList(): List<Any?> =
    (0 until arraySize).map { getArrayElement(it).toKotlin() }

private class LoggingOutputStream(
    private val logger: Logger,
    private val isError: Boolean,
) : java.io.OutputStream() {
    private val buffer = StringBuilder()

    override fun write(b: Int) {
        if (b == '\n'.code) {
            flushLine()
        } else {
            buffer.append(b.toChar())
        }
    }

    private fun flushLine() {
        if (buffer.isEmpty()) return
        val line = buffer.toString()
        buffer.clear()
        if (isError) logger.e { "js: $line" } else logger.d { "js: $line" }
    }
}
