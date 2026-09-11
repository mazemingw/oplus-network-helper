package com.nvmex.networkhelper.xposed.test

import com.nvmex.networkhelper.xposed.logger.Logger
import de.robv.android.xposed.XposedHelpers

/**
 * 简单NRCA修改器：总是添加N258频段
 */
object NrcaModifier {
    private const val TAG = "NRCA-MOD"

    // 配置
    data class Config(
        var enabled: Boolean = false,
        var bandN258: Int = 258  // N258频段号
    )

    private val config = Config()

    fun enable() {
        config.enabled = true
        Logger.log("$TAG: 已启用 - 将在NRCA中自动添加N258频段")
    }

    fun disable() {
        config.enabled = false
        Logger.log("$TAG: 已禁用")
    }

    /**
     * 修改NRCA数据：添加N258频段
     */
    fun modifyNrca(slot: Int, originalNrca: Any?): Any? {
        if (!config.enabled || originalNrca == null) return originalNrca

        return try {
            Logger.log("$TAG: 开始修改slot=$slot 的NRCA数据，添加N258")

            // 1. 获取当前载波数
            val numCarriers = getNumCarriersSafe(originalNrca)
            Logger.log("$TAG: 当前载波数: $numCarriers")

            // 2. 获取carrierConfig数组
            val carrierConfigArray = XposedHelpers.getObjectField(originalNrca, "carrierConfig") as Array<*>

            // 检查数组是否为空
            if (carrierConfigArray.isEmpty()) {
                Logger.log("$TAG: carrierConfig数组为空，无法添加N258")
                return originalNrca
            }

            // 3. 从现有载波对象获取类
            val firstCarrier = carrierConfigArray[0]
            if (firstCarrier == null) {
                Logger.log("$TAG: 第一个载波为null，无法获取类信息")
                return originalNrca
            }

            val carrierConfigClass = firstCarrier.javaClass
            Logger.log("$TAG: 获取到carrierConfig类: ${carrierConfigClass.name}")

            // 4. 创建新的数组（增加一个位置给N258）
            val newNumCarriers = numCarriers + 1
            val newArray = java.lang.reflect.Array.newInstance(carrierConfigClass, newNumCarriers)

            // 5. 复制原有载波
            for (i in 0 until numCarriers) {
                val existingCarrier = carrierConfigArray[i]
                java.lang.reflect.Array.set(newArray, i, existingCarrier)
            }

            // 6. 创建N258载波
            val n258Carrier = createN258CarrierSafely(carrierConfigClass, newNumCarriers - 1)
            java.lang.reflect.Array.set(newArray, newNumCarriers - 1, n258Carrier)

            // ✅ 7. 安全更新原对象的numCarriers字段（可能是byte类型！）
            safeSetField(originalNrca, "numCarriers", newNumCarriers)
            XposedHelpers.setObjectField(originalNrca, "carrierConfig", newArray)

            Logger.log("$TAG: 修改完成! slot=$slot, 载波数: $numCarriers -> $newNumCarriers")
            originalNrca

        } catch (t: Throwable) {
            Logger.logE("$TAG: 修改NRCA失败", t)
            originalNrca
        }
    }

    /**
     * 安全获取numCarriers字段（可能是byte或int）
     */
    private fun getNumCarriersSafe(nrca: Any): Int {
        return try {
            // 先尝试int
            XposedHelpers.getIntField(nrca, "numCarriers")
        } catch (e: IllegalArgumentException) {
            // 可能是byte类型
            try {
                XposedHelpers.getByteField(nrca, "numCarriers").toInt()
            } catch (e2: Exception) {
                // 尝试直接反射
                val field = nrca.javaClass.getDeclaredField("numCarriers")
                field.isAccessible = true
                when (field.type) {
                    Int::class.javaPrimitiveType -> field.getInt(nrca)
                    Byte::class.javaPrimitiveType -> field.getByte(nrca).toInt()
                    Short::class.javaPrimitiveType -> field.getShort(nrca).toInt()
                    else -> 0
                }
            }
        }
    }

    /**
     * 安全设置字段（根据字段类型自动选择正确的方法）
     */
    private fun safeSetField(obj: Any, fieldName: String, value: Int) {
        try {
            val field = obj.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true

            when (field.type) {
                Int::class.javaPrimitiveType, Integer::class.java -> {
                    field.setInt(obj, value)
                }
                Byte::class.javaPrimitiveType, java.lang.Byte::class.java -> {
                    // 检查值是否在byte范围内
                    val byteValue = if (value in Byte.MIN_VALUE..Byte.MAX_VALUE) {
                        value.toByte()
                    } else {
                        Logger.log("$TAG: 警告: 值 $value 超出byte范围，使用最大值")
                        Byte.MAX_VALUE
                    }
                    field.setByte(obj, byteValue)
                }
                Short::class.javaPrimitiveType, java.lang.Short::class.java -> {
                    field.setShort(obj, value.toShort())
                }
                Long::class.javaPrimitiveType, java.lang.Long::class.java -> {
                    field.setLong(obj, value.toLong())
                }
                else -> {
                    // 尝试通用方法
                    field.set(obj, value)
                }
            }

            Logger.log("$TAG: 设置字段 $fieldName = $value (类型: ${field.type.simpleName})")

        } catch (e: NoSuchFieldException) {
            Logger.log("$TAG: 字段 $fieldName 不存在")
        } catch (t: Throwable) {
            Logger.logE("$TAG: 设置字段 $fieldName 失败", t)
        }
    }

    /**
     * 安全地创建N258载波（检查字段类型）
     */
    private fun createN258CarrierSafely(
        carrierConfigClass: Class<*>,
        ccId: Int
    ): Any {
        val carrier = carrierConfigClass.newInstance()

        // === N258 正确 raw 值（对应 BAND258 -> N258）===
        val MOCKBAND = 257

        // === 200 MHz（NR5G_BW_200MHZ）===
        val bw0 = 18
        val bw100 = 14
        val bw200 = 15
        val bw400 = 16

        // ===  NR-ARFCN ===
        val n0Arfcn = 0
        val n258Arfcn = 2_386_667

        // ===  PCI ===
        val pci = 0


        // ===== DL =====
        safeSetField(carrier, "band", MOCKBAND)
        safeSetField(carrier, "ccId", ccId)
        safeSetField(carrier, "sccId", ccId)
        safeSetField(carrier, "pci", pci)              // mmWave PCI 随意，大值不奇怪
        safeSetField(carrier, "dlEarfcn", n258Arfcn)   // 有些 HAL 仍然叫 dlEarfcn
        safeSetField(carrier, "dlState", 0)            // Configured Activated
        safeSetField(carrier, "dlBandwidth", bw100)

        // ===== UL（mmWave 常见：UI 允许显示，但也可以弱化）=====
        safeSetField(carrier, "ulState", 0)
        safeSetField(carrier, "ulBandwidth", bw100)
        safeSetField(carrier, "ulBandwith", bw100)

        return carrier
    }
}