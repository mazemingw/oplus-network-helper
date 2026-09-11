package com.nvmex.networkhelper.xposed.handler


import com.nvmex.networkhelper.xposed.logger.Logger
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

object EngineerAmbrHooks {
    fun install(classLoader: ClassLoader) {
        Logger.log("[ENG-AMBR-HOOKS] install started")

        try {
            // 方法1：Hook NRSessionInfo 构造方法
            hookNRSessionInfoConstructor(classLoader)

            // 方法2：Hook NRSessionInfo.update() 方法
            hookNRSessionInfoUpdateMethod(classLoader)

            // 方法3：Hook MDMComponent.getComponents() 工厂方法
            hookMdmComponentFactory(classLoader)

        } catch (e: Throwable) {
            Logger.log("[ENG-AMBR-HOOKS] Install error: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun hookNRSessionInfoConstructor(classLoader: ClassLoader) {
        try {
            val nrSessionClass = XposedHelpers.findClass(
                "com.oplus.engineernetwork.register.mdmcomponent.NRSessionInfo",
                classLoader
            )

            Logger.log("[ENG-AMBR-HOOKS] ✓ Found NRSessionInfo class")

            val shadowActivityClass = XposedHelpers.findClass(
                "com.tencent.shadow.core.runtime.ShadowActivity",
                classLoader
            )
            val ctor = nrSessionClass.getDeclaredConstructor(shadowActivityClass).apply { isAccessible = true }
            XposedBridge.hookMethodNative(ctor) { chain ->
                val result = chain.proceed()
                val instance = chain.thisObject
                val shadowActivity = chain.args.getOrNull(0)

                Logger.log("[ENG-AMBR-HOOKS] NRSessionInfo instance created!")
                Logger.log("[ENG-AMBR-HOOKS] ShadowActivity: $shadowActivity")
                Logger.log("[ENG-AMBR-HOOKS] Instance: $instance")

                // 在这里可以存储引用，后续修改 AMBR 数据
//                        interceptAndModifyAmbrData(instance, classLoader)
                result
            }

            Logger.log("[ENG-AMBR-HOOKS] Hooked NRSessionInfo constructor")

        } catch (e: ClassNotFoundException) {
            Logger.log("[ENG-AMBR-HOOKS] NRSessionInfo class not found: ${e.message}")
        } catch (e: Throwable) {
            Logger.log("[ENG-AMBR-HOOKS] hookNRSessionInfoConstructor error: ${e.message}")
        }
    }

    private fun hookNRSessionInfoUpdateMethod(classLoader: ClassLoader) {
        try {
            val nrSessionClass = XposedHelpers.findClass(
                "com.oplus.engineernetwork.register.mdmcomponent.NRSessionInfo",
                classLoader
            )

            val updateMethod = nrSessionClass.getDeclaredMethod(
                "update",
                String::class.java,
                Object::class.java
            ).apply { isAccessible = true }
            XposedBridge.hookMethodNative(updateMethod) { chain ->
                val args = chain.args.toTypedArray()
                val sessionId = args.getOrNull(0) as? String
                val dataObj = args.getOrNull(1)

                Logger.log("[ENG-AMBR-HOOKS] NRSessionInfo.update called")
                Logger.log("[ENG-AMBR-HOOKS] Session ID: $sessionId")
                Logger.log("[ENG-AMBR-HOOKS] Data object: ${dataObj?.javaClass?.name}")

                if (dataObj is ByteBuffer) {
                    // 可以在这里修改 ByteBuffer 数据
                    modifyAmbrByteBuffer(dataObj, classLoader)
                }

                val result = chain.proceed(args)
                val instance = chain.thisObject
                Logger.log("[ENG-AMBR-HOOKS] NRSessionInfo.update completed")

                // 可以在这里读取修改后的 AMBR 值
                if (instance != null) readAmbrValues(instance, classLoader)
                result
            }

            Logger.log("[ENG-AMBR-HOOKS] Hooked NRSessionInfo.update method")

        } catch (e: Throwable) {
            Logger.log("[ENG-AMBR-HOOKS] hookNRSessionInfoUpdateMethod error: ${e.message}")
        }
    }

    private fun hookMdmComponentFactory(classLoader: ClassLoader) {
        try {
            val mdmComponentClass = XposedHelpers.findClass(
                "com.oplus.engineernetwork.register.mdmcomponent.MDMComponent",
                classLoader
            )

            val shadowActivityClass = XposedHelpers.findClass(
                "com.tencent.shadow.core.runtime.ShadowActivity",
                classLoader
            )
            val getComponentsMethod = mdmComponentClass.getDeclaredMethod(
                "getComponents",
                shadowActivityClass
            ).apply { isAccessible = true }
            XposedBridge.hookMethodNative(getComponentsMethod) { chain ->
                val proceedResult = chain.proceed()
                val result = proceedResult as? List<*>

                Logger.log("[ENG-AMBR-HOOKS] MDMComponent.getComponents() called")
                Logger.log("[ENG-AMBR-HOOKS] Components count: ${result?.size}")

                result?.forEachIndexed { index, component ->
                    if (component != null) {
                        val className = component.javaClass.name
                        Logger.log("[ENG-AMBR-HOOKS] Component[$index]: $className")

                        if (className.contains("NRSessionInfo")) {
                            Logger.log("[ENG-AMBR-HOOKS] ✓ Found target NRSessionInfo at index $index")
                            // 可以替换为自定义的组件
                            // result = replaceWithCustomComponent(result, component, classLoader)
                        }
                    }
                }
                proceedResult
            }

            Logger.log("[ENG-AMBR-HOOKS] Hooked MDMComponent.getComponents() factory method")

        } catch (e: Throwable) {
            Logger.log("[ENG-AMBR-HOOKS] hookMdmComponentFactory error: ${e.message}")
        }
    }

    // 修改 AMBR ByteBuffer 数据
    private fun modifyAmbrByteBuffer(buffer: ByteBuffer, classLoader: ClassLoader) {
        try {
            // 根据 NRSessionInfo 中的地址偏移修改数据
            // 这是关键部分，需要根据实际的 ICD 结构修改

            // 示例：修改 DL AMBR 值（需要实际调试确定偏移）
            // val dlAmbrOffset = 1240 // 需要根据实际情况确定
            // buffer.putInt(dlAmbrOffset, 999999) // 修改为 999Mbps

            Logger.log("[ENG-AMBR-HOOKS] ByteBuffer position: ${buffer.position()}, limit: ${buffer.limit()}")

        } catch (e: Throwable) {
            Logger.log("[ENG-AMBR-HOOKS] modifyAmbrByteBuffer error: ${e.message}")
        }
    }

    // 读取 AMBR 值
    private fun readAmbrValues(instance: Any, classLoader: ClassLoader) {
        try {
            // 使用反射读取 ambrArrays 字段
            val ambrArrays = XposedHelpers.getObjectField(instance, "ambrArrays") as? Array<Array<String>>

            if (ambrArrays != null) {
                Logger.log("[ENG-AMBR-HOOKS] AMBR arrays found, size: ${ambrArrays.size}")

                ambrArrays.forEachIndexed { index, sessionArray ->
                    if (sessionArray != null && sessionArray.size >= 3) {
                        Logger.log("[ENG-AMBR-HOOKS] Session[$index]: " +
                                "ID=${sessionArray[0]}, " +
                                "DL=${sessionArray[1]}, " +
                                "UL=${sessionArray[2]}")
                    }
                }
            }

        } catch (e: Throwable) {
            Logger.log("[ENG-AMBR-HOOKS] readAmbrValues error: ${e.message}")
        }
    }

    // 修改 AMBR 显示值（在数据已经计算好后）
    private fun modifyDisplayedAmbrValues(instance: Any, classLoader: ClassLoader) {
        try {
            // 这里可以修改 ambrArrays 中的显示值
            // 注意：这只会修改显示，不会影响实际网络数据

            val ambrArrays = XposedHelpers.getObjectField(instance, "ambrArrays") as? Array<Array<String>>

            if (ambrArrays != null) {
                for (i in ambrArrays.indices) {
                    val session = ambrArrays[i]
                    if (session != null && session.size >= 3) {
                        // 修改显示值
                        session[1] = "999.99"  // DL AMBR
                        session[2] = "999.99"  // UL AMBR

                        Logger.log("[ENG-AMBR-HOOKS] Modified session $i display values")
                    }
                }

                // 更新字段
                XposedHelpers.setObjectField(instance, "ambrArrays", ambrArrays)
            }

        } catch (e: Throwable) {
            Logger.log("[ENG-AMBR-HOOKS] modifyDisplayedAmbrValues error: ${e.message}")
        }
    }
}
