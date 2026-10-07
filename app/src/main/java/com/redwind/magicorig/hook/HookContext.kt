package com.redwind.magicorig.hook

import android.content.SharedPreferences
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import com.redwind.magicorig.config.ConfigManager

/**
 * HookContext — 所有 Hook 的基类。
 * 从 HyperOriG 移植，保持相同的 API。
 */
abstract class HookContext {
    lateinit var module: XposedModule
    lateinit var appClassLoader: ClassLoader
    lateinit var prefs: SharedPreferences
    lateinit var packageName: String

    abstract fun onHook()

    fun fakeDeviceId(): String = ConfigManager.fakeDeviceId()

    fun fakeSupport(): String = ConfigManager.fakeSupport()

    fun refreshConfig() {
        ConfigManager.refreshFromPrefs(prefs)
    }

    fun findClass(name: String): Class<*> = Class.forName(name, false, appClassLoader)

    fun findMethod(className: String, methodName: String, vararg parameterTypes: Class<*>): Method =
        findClass(className).getDeclaredMethod(methodName, *parameterTypes).apply { isAccessible = true }

    fun findConstructor(className: String, vararg parameterTypes: Class<*>): Constructor<*> =
        findClass(className).getDeclaredConstructor(*parameterTypes).apply { isAccessible = true }

    fun findMethodByParamCount(className: String, methodName: String, paramCount: Int): Method =
        findClass(className).declaredMethods.first { it.name == methodName && it.parameterTypes.size == paramCount }
            .apply { isAccessible = true }

    fun findConstructorByParamCount(className: String, paramCount: Int): Constructor<*> =
        findClass(className).declaredConstructors.first { it.parameterTypes.size == paramCount }.apply { isAccessible = true }

    fun hookAfter(method: Method, block: HookParam.() -> Unit) {
        module.hook(method).intercept { chain ->
            runCatching {
                val result = chain.proceed()
                try {
                    HookParam(chain, result).apply(block).result
                } catch (error: Throwable) {
                    Log.e("MagicOriG-Hook", "After hook threw in ${method.name}: ${error.message}")
                    result  // 无论如何返回原结果，不让 hook 异常传播
                }
            }.onFailure { error ->
                Log.e("MagicOriG-Hook", "After hook chain failed in ${method.name}: ${error.message}")
                try { chain.proceed() } catch (e: Throwable) { null }  // 兜底
            }
        }
    }

    fun hookBefore(method: Method, block: HookParam.() -> Unit) {
        module.hook(method).intercept { chain ->
            runCatching {
                val param = try {
                    HookParam(chain, null).apply(block)
                } catch (error: Throwable) {
                    Log.e("MagicOriG-Hook", "Before hook threw in ${method.name}: ${error.message}")
                    return@intercept chain.proceed()  // 异常时直接执行原方法
                }
                if (param.hasResult) param.result else chain.proceed()
            }.onFailure { error ->
                Log.e("MagicOriG-Hook", "Before hook chain failed in ${method.name}: ${error.message}")
                try { chain.proceed() } catch (e: Throwable) { null }
            }
        }
    }

    fun hookConstructorAfter(constructor: Constructor<*>, block: HookParam.() -> Unit) {
        module.hook(constructor).intercept { chain ->
            chain.proceed().also {
                try {
                    HookParam(chain, it).apply(block)
                } catch (error: Throwable) {
                    Log.e("MagicOriG-Hook", "Constructor hook failed: $constructor", error)
                }
            }
        }
    }
}

/** Convenience: get a declared method from a Class, making it accessible. */
fun Class<*>.method(name: String, vararg pt: Class<*>): Method =
    getDeclaredMethod(name, *pt).apply { isAccessible = true }

object Log {
    @Volatile
    var module: XposedModule? = null

    fun v(tag: String, message: String) {
        if (ConfigManager.logLevel() < ConfigManager.LOG_LEVEL_DEBUG) return
        module?.log(android.util.Log.VERBOSE, tag, message)
    }

    fun i(tag: String, message: String) {
        if (ConfigManager.logLevel() < ConfigManager.LOG_LEVEL_BASIC) return
        module?.log(android.util.Log.INFO, tag, message)
    }

    fun d(tag: String, message: String) {
        if (ConfigManager.logLevel() < ConfigManager.LOG_LEVEL_DEBUG) return
        module?.log(android.util.Log.DEBUG, tag, message)
    }

    fun d(tag: String, message: String, throwable: Throwable) {
        if (ConfigManager.logLevel() < ConfigManager.LOG_LEVEL_DEBUG) return
        module?.log(android.util.Log.DEBUG, tag, message, throwable)
    }

    fun w(tag: String, message: String) {
        if (ConfigManager.logLevel() < ConfigManager.LOG_LEVEL_BASIC) return
        module?.log(android.util.Log.WARN, tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable) {
        if (ConfigManager.logLevel() < ConfigManager.LOG_LEVEL_BASIC) return
        module?.log(android.util.Log.WARN, tag, message, throwable)
    }

    fun e(tag: String, message: String) {
        if (ConfigManager.logLevel() < ConfigManager.LOG_LEVEL_BASIC) return
        module?.log(android.util.Log.ERROR, tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable) {
        if (ConfigManager.logLevel() < ConfigManager.LOG_LEVEL_BASIC) return
        module?.log(android.util.Log.ERROR, tag, message, throwable)
    }
}

class HookParam(private val chain: XposedInterface.Chain, initialResult: Any?) {
    val args: List<Any?> = chain.args
    val instance: Any? = chain.thisObject
    var hasResult = false
        private set
    var result: Any? = initialResult
        set(value) {
            hasResult = true
            field = value
        }
}

fun getObjectField(instance: Any?, fieldName: String): Any? {
    if (instance == null) return null
    var cls: Class<*>? = instance.javaClass
    while (cls != null) {
        runCatching {
            return cls.getDeclaredField(fieldName).apply { isAccessible = true }.get(instance)
        }
        cls = cls.superclass
    }
    throw NoSuchFieldException(fieldName)
}

fun setObjectField(instance: Any?, fieldName: String, value: Any?) {
    if (instance == null) return
    var cls: Class<*>? = instance.javaClass
    while (cls != null) {
        runCatching {
            cls.getDeclaredField(fieldName).apply { isAccessible = true }.set(instance, value)
            return
        }
        cls = cls.superclass
    }
    throw NoSuchFieldException(fieldName)
}

fun callMethod(instance: Any?, methodName: String, vararg args: Any?): Any? {
    if (instance == null) return null
    var cls: Class<*>? = instance.javaClass
    while (cls != null) {
        cls.declaredMethods.firstOrNull { it.name == methodName && it.parameterTypes.size == args.size }?.let {
            it.isAccessible = true
            return it.invoke(instance, *args)
        }
        cls = cls.superclass
    }
    throw NoSuchMethodException(methodName)
}
