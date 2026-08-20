package io.rebble.libpebblecommon.notification

import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.database.dao.NotificationAppRealDao
import io.rebble.libpebblecommon.database.entity.MuteState
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NotificationApiJvmTest {
    @Test
    fun forgetNotificationAppAwaitsDaoTombstone() = runTest {
        val forgotten = mutableListOf<String>()
        val api = notificationApi(
            proxy { method, args ->
                when (method.name) {
                    "markForDeletion" -> {
                        forgotten += args!!.first() as String
                        Unit
                    }
                    else -> error("unexpected notification app DAO call: ${method.name}")
                }
            }
        )

        api.forgetNotificationApp("org.example.app")

        assertEquals(listOf("org.example.app"), forgotten)
    }

    @Test
    fun blankSourceIsRejectedWithoutCallingDao() = runTest {
        var calls = 0
        val api = notificationApi(
            proxy { method, _ ->
                calls++
                error("unexpected notification app DAO call: ${method.name}")
            }
        )

        assertFailsWith<IllegalArgumentException> {
            api.forgetNotificationApp("   ")
        }
        assertEquals(0, calls)
    }

    @Test
    fun persistNotificationAppMuteStateDelegatesToAwaitedDaoMutation() = runTest {
        val calls = mutableListOf<Pair<String, MuteState>>()
        val api = notificationApi(
            proxy { method, args ->
                when (method.name) {
                    "updateAppMuteState" -> {
                        calls += args!![0] as String to args[1] as MuteState
                        Unit
                    }
                    else -> error("unexpected notification app DAO call: ${method.name}")
                }
            }
        )

        api.persistNotificationAppMuteState("org.example.app", MuteState.Always)

        assertEquals(listOf("org.example.app" to MuteState.Always), calls)
    }

    @Test
    fun reconcileNotificationAppMuteStatesDelegatesCompleteSnapshot() = runTest {
        val expected = mapOf(
            "org.example.enabled" to MuteState.Never,
            "org.example.disabled" to MuteState.Always,
        )
        var actual: Map<String, MuteState>? = null
        val api = notificationApi(
            proxy { method, args ->
                when (method.name) {
                    "reconcileAppMuteStates" -> {
                        @Suppress("UNCHECKED_CAST")
                        run { actual = args!!.first() as Map<String, MuteState> }
                        Unit
                    }
                    else -> error("unexpected notification app DAO call: ${method.name}")
                }
            }
        )

        api.reconcileNotificationAppMuteStates(expected)

        assertEquals(expected, actual)
    }

    private fun notificationApi(notificationAppDao: NotificationAppRealDao) = NotificationApi(
        notificationAppsSync = object : NotificationAppsSync {
            override fun init() = Unit
        },
        notificationAppDao = notificationAppDao,
        notificationsDao = proxy { method, _ ->
            error("unexpected notification DAO call: ${method.name}")
        },
        libPebbleCoroutineScope = LibPebbleCoroutineScope(EmptyCoroutineContext),
        appContext = AppContext(),
        vibePatternDao = proxy { method, _ ->
            error("unexpected vibe pattern DAO call: ${method.name}")
        },
        notificationRuleDao = proxy { method, _ ->
            error("unexpected notification rule DAO call: ${method.name}")
        },
    )

    private inline fun <reified T : Any> proxy(
        crossinline handler: (java.lang.reflect.Method, Array<out Any?>?) -> Any?,
    ): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.firstOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "Test${T::class.java.simpleName}"
            else -> handler(method, args)
        }
    } as T
}
