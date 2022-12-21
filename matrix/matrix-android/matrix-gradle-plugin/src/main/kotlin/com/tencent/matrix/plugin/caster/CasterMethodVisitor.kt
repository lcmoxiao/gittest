package com.tencent.matrix.plugin.caster

import com.android.tools.build.jetifier.core.utils.Log
import com.tencent.matrix.plugin.caster.MatrixTraceCaster.Companion.ignoreMethodPrinter
import com.tencent.matrix.plugin.caster.MatrixTraceCaster.Companion.methodPrinter
import com.tencent.matrix.plugin.compat.AgpCompat.Companion.asmApi
import com.tencent.matrix.trace.TraceBuildConstants
import com.tencent.matrix.trace.item.TraceMethod
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.AdviceAdapter
import org.objectweb.asm.tree.MethodNode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal class CasterMethodVisitor(i: Int, classVisitor: ClassVisitor?) : ClassVisitor(i, classVisitor) {

    companion object {
        private val ignoreCount = AtomicInteger()
        private val incrementCount = AtomicInteger()
        private val methodId = AtomicInteger()

        private val collectedMethodMap by lazy {
            val concurrentHashMap = ConcurrentHashMap<String, TraceMethod>()
            val traceMethod = TraceMethod.create(TraceBuildConstants.METHOD_ID_DISPATCH, Opcodes.ACC_PUBLIC, "android.os.Handler", "dispatchMessage", "(Landroid.os.Message;)V")
            concurrentHashMap[traceMethod.className] = traceMethod
            methodPrinter.println(traceMethod)
            concurrentHashMap
        }
        private val collectedIgnoreMethodMap = ConcurrentHashMap<String, TraceMethod>()

        const val TAG = "MethodCollectVisitor"
        fun isWindowFocusChangeMethod(name: String?, desc: String?): Boolean {
            return null != name && null != desc && name == TraceBuildConstants.MATRIX_TRACE_ON_WINDOW_FOCUS_METHOD && desc == TraceBuildConstants.MATRIX_TRACE_ON_WINDOW_FOCUS_METHOD_ARGS
        }

        fun isActivityOrSubClass(className: String?, mCollectedClassExtendMap: ConcurrentHashMap<String?, String?>): Boolean {
            var tmpClassName = className
            tmpClassName = tmpClassName!!.replace(".", "/")
            val isActivity = tmpClassName == TraceBuildConstants.MATRIX_TRACE_ACTIVITY_CLASS || tmpClassName == TraceBuildConstants.MATRIX_TRACE_V4_ACTIVITY_CLASS || tmpClassName == TraceBuildConstants.MATRIX_TRACE_V7_ACTIVITY_CLASS || tmpClassName == TraceBuildConstants.MATRIX_TRACE_ANDROIDX_ACTIVITY_CLASS
            return if (isActivity) {
                true
            } else {
                if (!mCollectedClassExtendMap.containsKey(tmpClassName)) {
                    false
                } else {
                    isActivityOrSubClass(mCollectedClassExtendMap[tmpClassName], mCollectedClassExtendMap)
                }
            }
        }
    }

    private var className: String? = null
    private var superName: String? = null
    private var isABSClass = false
    private var hasWindowFocusMethod = false

    override fun visit(version: Int, access: Int, name: String?, signature: String?, superName: String?, interfaces: Array<String>?) {
        super.visit(version, access, name, signature, superName, interfaces)
        className = name
        this.superName = superName
        if (access and Opcodes.ACC_ABSTRACT > 0 || access and Opcodes.ACC_INTERFACE > 0) {
            isABSClass = true
        }
    }

    override fun visitMethod(access: Int, name: String?, desc: String?, signature: String?, exceptions: Array<String>?): MethodVisitor {
        val methodVisitor = if (isABSClass) {
            super.visitMethod(access, name, desc, signature, exceptions)
        } else {
            if (!hasWindowFocusMethod) {
                hasWindowFocusMethod = isWindowFocusChangeMethod(name, desc)
            }
            val collectMethodNode = CollectMethodNode(className, access, name, desc, signature, exceptions)
            TraceMethodAdapter(api, collectMethodNode, access, name, desc, className)
        }
        return methodVisitor
    }


    private class CollectMethodNode(
        private val className: String?, access: Int, name: String?,
        desc: String?, signature: String?, exceptions: Array<String>?,
        private var isConstructor: Boolean = "<init>" == name
    ) : MethodNode(asmApi, access, name, desc, signature, exceptions) {

        private val methodName: String

        init {
            val traceMethod = TraceMethod.create(0, access, className, name, desc)
            methodName = traceMethod.getMethodName()
        }

        override fun visitEnd() {
            super.visitEnd()
            val traceMethod = TraceMethod.create(0, access, className, name, desc)
            // filter simple methods
            if (isEmptyMethod || isGetSetMethod || isSingleMethod) {
                ignoreCount.incrementAndGet()
                ignoreMethodPrinter.println(traceMethod.toIgnoreString())
                collectedIgnoreMethodMap[traceMethod.className] = traceMethod
                return
            }

            // 未收集的方法，记录 methodId 收集一下
            if (!collectedMethodMap.containsKey(traceMethod.getMethodName())) {
                traceMethod.id = methodId.incrementAndGet()
                incrementCount.incrementAndGet()
                collectedMethodMap[traceMethod.className] = traceMethod
                methodPrinter.println(traceMethod.toString())
            } else if (!collectedIgnoreMethodMap.containsKey(traceMethod.className)) {
                ignoreCount.incrementAndGet()
                collectedIgnoreMethodMap[traceMethod.className] = traceMethod
                ignoreMethodPrinter.println(traceMethod.toIgnoreString())
            }
        }

        private val isGetSetMethod: Boolean
            get() {
                var ignoreCount = 0
                for (insnNode in instructions) {
                    val opcode = insnNode.opcode
                    if (-1 == opcode) {
                        continue
                    }
                    if (opcode != Opcodes.GETFIELD && opcode != Opcodes.GETSTATIC && opcode != Opcodes.H_GETFIELD && opcode != Opcodes.H_GETSTATIC && opcode != Opcodes.RETURN && opcode != Opcodes.ARETURN && opcode != Opcodes.DRETURN && opcode != Opcodes.FRETURN && opcode != Opcodes.LRETURN && opcode != Opcodes.IRETURN && opcode != Opcodes.PUTFIELD && opcode != Opcodes.PUTSTATIC && opcode != Opcodes.H_PUTFIELD && opcode != Opcodes.H_PUTSTATIC && opcode > Opcodes.SALOAD) {
                        if (isConstructor && opcode == Opcodes.INVOKESPECIAL) {
                            ignoreCount++
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
        private val isSingleMethod: Boolean
            get() {
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
        private val isEmptyMethod: Boolean
            get() {
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
    }


    class TraceMethodAdapter(
        api: Int, mv: MethodVisitor?, access: Int, name: String?, desc: String?, private val className: String?
    ) : AdviceAdapter(api, mv, access, name, desc) {
        private val methodName: String

        companion object {
            private val traceMethodCount = AtomicInteger()
        }

        init {
            val traceMethod = TraceMethod.create(0, access, className, name, desc)
            methodName = traceMethod.getMethodName()
        }

        override fun onMethodEnter() {
            val traceMethod = collectedMethodMap[methodName]
            if (traceMethod != null) {
                traceMethodCount.incrementAndGet()
                mv.visitLdcInsn(traceMethod.id)
                mv.visitMethodInsn(INVOKESTATIC, TraceBuildConstants.MATRIX_TRACE_CLASS, "i", "(I)V", false)
            }
        }

        override fun onMethodExit(opcode: Int) {
            val traceMethod = collectedMethodMap[methodName]
            if (traceMethod != null) {
                traceMethodCount.incrementAndGet()
                mv.visitLdcInsn(traceMethod.id)
                mv.visitMethodInsn(INVOKESTATIC, TraceBuildConstants.MATRIX_TRACE_CLASS, "o", "(I)V", false)
            }
        }

    }
}