package dev.artzig.system

import android.os.IInterface
import android.util.ArtzigTestDiagnostics
import java.lang.reflect.Proxy

/**
 * Failing default for the portion of a hosted Binder service that the host
 * does not implement. Keeping this behavior in an interface delegate lets a
 * service override only its served subset without silently inheriting the
 * no-op and zero-valued answers from an AIDL `Default` implementation.
 */
@Suppress("UNCHECKED_CAST")
inline fun <reified T : IInterface> unsupported(): T =
    Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, method, _ ->
        throw ArtzigTestDiagnostics.unsupported(
            T::class.java.simpleName,
            method.name,
            "not hosted",
        )
    } as T
