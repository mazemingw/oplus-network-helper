package de.robv.android.xposed

import java.lang.reflect.Field
import java.lang.reflect.Method

object XposedHelpers {
    fun findClass(className: String, classLoader: ClassLoader?): Class<*> {
        return Class.forName(className, false, classLoader ?: XposedBridge.BOOTCLASSLOADER)
    }

    fun findClassIfExists(className: String, classLoader: ClassLoader?): Class<*>? {
        return runCatching { findClass(className, classLoader) }.getOrNull()
    }

    fun findField(clazz: Class<*>, fieldName: String): Field {
        var c: Class<*>? = clazz
        while (c != null) {
            runCatching {
                c.getDeclaredField(fieldName).apply { isAccessible = true }
            }.onSuccess { return it }
            c = c.superclass
        }
        throw NoSuchFieldException("$fieldName in ${clazz.name}")
    }

    fun findFieldIfExists(clazz: Class<*>, fieldName: String): Field? {
        return runCatching { findField(clazz, fieldName) }.getOrNull()
    }

    fun callMethod(obj: Any?, methodName: String, vararg args: Any?): Any? {
        requireNotNull(obj) { "obj is null for method $methodName" }
        val method = findMethodByCall(obj.javaClass, methodName, args)
        method.isAccessible = true
        return method.invoke(obj, *args)
    }

    fun callStaticMethod(clazz: Class<*>, methodName: String, vararg args: Any?): Any? {
        val method = findMethodByCall(clazz, methodName, args)
        method.isAccessible = true
        return method.invoke(null, *args)
    }

    fun getObjectField(obj: Any?, fieldName: String): Any? {
        requireNotNull(obj) { "obj is null for field $fieldName" }
        return findField(obj.javaClass, fieldName).get(obj)
    }

    fun getStaticObjectField(clazz: Class<*>, fieldName: String): Any? {
        return findField(clazz, fieldName).get(null)
    }

    fun setObjectField(obj: Any?, fieldName: String, value: Any?) {
        requireNotNull(obj) { "obj is null for field $fieldName" }
        findField(obj.javaClass, fieldName).set(obj, value)
    }

    fun getIntField(obj: Any?, fieldName: String): Int {
        requireNotNull(obj) { "obj is null for field $fieldName" }
        return findField(obj.javaClass, fieldName).getInt(obj)
    }

    fun getLongField(obj: Any?, fieldName: String): Long {
        requireNotNull(obj) { "obj is null for field $fieldName" }
        return findField(obj.javaClass, fieldName).getLong(obj)
    }

    fun getByteField(obj: Any?, fieldName: String): Byte {
        requireNotNull(obj) { "obj is null for field $fieldName" }
        return findField(obj.javaClass, fieldName).getByte(obj)
    }

    private fun findMethodByCall(clazz: Class<*>, methodName: String, args: Array<out Any?>): Method {
        var c: Class<*>? = clazz
        while (c != null) {
            c.declaredMethods.firstOrNull { it.name == methodName && parameterTypesMatch(it.parameterTypes, args) }
                ?.let { return it }
            c = c.superclass
        }
        throw NoSuchMethodException("${clazz.name}#$methodName(args=${args.size})")
    }

    private fun parameterTypesMatch(paramTypes: Array<Class<*>>, args: Array<out Any?>): Boolean {
        if (paramTypes.size != args.size) return false
        return paramTypes.indices.all { idx ->
            val arg = args[idx]
            if (arg == null) !paramTypes[idx].isPrimitive
            else isAssignable(paramTypes[idx], arg.javaClass)
        }
    }

    private fun isAssignable(target: Class<*>, source: Class<*>): Boolean {
        if (target.isAssignableFrom(source)) return true
        if (!target.isPrimitive) return boxed(target).isAssignableFrom(boxed(source))
        return boxed(target) == boxed(source)
    }

    private fun boxed(clazz: Class<*>): Class<*> {
        return when (clazz) {
            java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
            java.lang.Byte.TYPE -> java.lang.Byte::class.java
            java.lang.Short.TYPE -> java.lang.Short::class.java
            java.lang.Integer.TYPE -> java.lang.Integer::class.java
            java.lang.Long.TYPE -> java.lang.Long::class.java
            java.lang.Float.TYPE -> java.lang.Float::class.java
            java.lang.Double.TYPE -> java.lang.Double::class.java
            java.lang.Character.TYPE -> java.lang.Character::class.java
            java.lang.Void.TYPE -> java.lang.Void::class.java
            else -> clazz
        }
    }
}
