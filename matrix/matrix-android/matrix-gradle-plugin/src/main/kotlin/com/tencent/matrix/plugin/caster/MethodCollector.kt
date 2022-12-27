package com.tencent.matrix.plugin.caster

import com.tencent.matrix.plugin.caster.MatrixTreeCaster.Companion.ignoreMethodPrinter
import com.tencent.matrix.plugin.caster.MatrixTreeCaster.Companion.methodPrinter
import com.tencent.matrix.trace.TraceBuildConstants
import com.tencent.matrix.trace.item.TraceMethod
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.InsnList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 筛选需要插桩的方法，并赋予唯一的 Method ID
 */
object MethodCollector {

    private val ignoreCount = AtomicInteger()
    val incrementCount = AtomicInteger()
    private val methodId = AtomicInteger()

    // 存储了所有会被 trace 的方法
    val collectedMethodMap by lazy {
        val concurrentHashMap = ConcurrentHashMap<String, TraceMethod>()
        val traceMethod = TraceMethod.create(TraceBuildConstants.METHOD_ID_DISPATCH, Opcodes.ACC_PUBLIC, "android.os.Handler", "dispatchMessage", "(Landroid.os.Message;)V")
        concurrentHashMap[traceMethod.className] = traceMethod
        methodPrinter.println(traceMethod)
        concurrentHashMap
    }

    // 存储所有不需要被 trace 的方法，如：get、set、空的
    private val collectedIgnoreMethodMap = ConcurrentHashMap<String, TraceMethod>()

    /**
     * 判断方法是否需要 trace，收集、记录相关方法信息
     *
     * 需要的方法保存在 collectedMethodMap 中用于插桩时获取 method ID，并打印到
     *
     * @param instructions 方法体集合
     * @param className 类名
     * @param methodName 方法名
     * @param access 方法访问符号
     * @param desc 方法描述
     * @return 返回构造出的 TraceMethod，非 igonre 方法的会 id 不为 0。
     */
    fun collectMethodInfo(instructions: InsnList, className: String, methodName: String, access: Int, desc: String): TraceMethod {
        val traceMethod = TraceMethod.create(0, access, className, methodName, desc)
        // filter simple methods
        if (isEmptyMethod(instructions) || isGetSetMethod(instructions, isConstructor(methodName)) || isSingleMethod(instructions)) {
            ignoreCount.incrementAndGet()
            ignoreMethodPrinter.println(traceMethod.toIgnoreString())
            collectedIgnoreMethodMap[traceMethod.getMethodName()] = traceMethod
        }

        // 未收集的方法，记录 methodId 收集一下
        if (!collectedMethodMap.containsKey(traceMethod.getMethodName())) {
            traceMethod.id = methodId.incrementAndGet()
            incrementCount.incrementAndGet()
            collectedMethodMap[traceMethod.getMethodName()] = traceMethod
            methodPrinter.println(traceMethod.toString())
        } else if (!collectedIgnoreMethodMap.containsKey(traceMethod.className)) {
            ignoreCount.incrementAndGet()
            collectedIgnoreMethodMap[traceMethod.getMethodName()] = traceMethod
            ignoreMethodPrinter.println(traceMethod.toIgnoreString())
        }

        return traceMethod
    }

    // 是否为纯的只有 get、set、construct 方法。
    private fun isGetSetMethod(instructions: InsnList, isConstructor: Boolean): Boolean {
        var ignoreCount = 0
        for (insnNode in instructions) {
            val opcode = insnNode.opcode
            if (-1 == opcode) {
                continue
            }
            if (opcode != Opcodes.GETFIELD && opcode != Opcodes.GETSTATIC && opcode != Opcodes.H_GETFIELD && opcode != Opcodes.H_GETSTATIC && opcode != Opcodes.RETURN && opcode != Opcodes.ARETURN && opcode != Opcodes.DRETURN && opcode != Opcodes.FRETURN && opcode != Opcodes.LRETURN && opcode != Opcodes.IRETURN && opcode != Opcodes.PUTFIELD && opcode != Opcodes.PUTSTATIC && opcode != Opcodes.H_PUTFIELD && opcode != Opcodes.H_PUTSTATIC && opcode > Opcodes.SALOAD) {
                if (isConstructor && opcode == Opcodes.INVOKESPECIAL) {
                    ignoreCount++
                    // 构造方法中，多次调用了 SPECIAL 方法，如：实例化、私有、父类方法，此处应该是不止调用了 super
                    if (ignoreCount > 1) {
                        return false
                    }
                    continue
                }
                return false
            }
        }
        return true
    }


    // 是否为纯方法体
    private fun isSingleMethod(instructions: InsnList): Boolean {
        for (insnNode in instructions) {
            val opcode = insnNode.opcode
            if (-1 == opcode) {
                continue
            } else if (Opcodes.INVOKEVIRTUAL <= opcode && opcode <= Opcodes.INVOKEDYNAMIC) {
                return false
            }
        }
        return true
    }

    // 是否为空方法
    private fun isEmptyMethod(instructions: InsnList): Boolean {
        for (insnNode in instructions) {
            val opcode = insnNode.opcode
            return if (-1 == opcode) {
                continue
            } else {
                false
            }
        }
        return true
    }

    private fun isConstructor(name: String): Boolean {
        return "<init>" == name
    }
}