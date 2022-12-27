/*
 * Tencent is pleased to support the open source community by making wechat-matrix available.
 * Copyright (C) 2018 THL A29 Limited, a Tencent company. All rights reserved.
 * Licensed under the BSD 3-Clause License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tencent.matrix.plugin.caster

import com.tencent.caster.transform.gradle.CasterContext
import com.tencent.caster.transform.transformer.AsmCoreApiTransformer
import com.tencent.caster.transformer.TransformerInput
import com.tencent.matrix.javalib.util.Log
import com.tencent.matrix.plugin.caster.MatrixCoreCaster.TraceMethodAdapter.Companion.traceMethodCount
import com.tencent.matrix.plugin.compat.AgpCompat
import com.tencent.matrix.trace.TraceBuildConstants
import com.tencent.matrix.trace.item.TraceMethod
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.commons.AdviceAdapter
import java.util.concurrent.atomic.AtomicInteger


/**
 * 第二步骤、指定方法插桩
 *
 * Core 遍历过程中会依托于 MatrixTreeCaster 遍历收集的方法信息进行过滤插桩。
 */
class MatrixCoreCaster() : AsmCoreApiTransformer() {

    private val TAG = "MatrixCoreCaster"

    override fun getName(): String = "MatrixCoreCaster"

    override fun onInit(context: CasterContext) {
        super.onInit(context)
        Log.i(TAG, "is init.")
    }

    override fun onDestroy(context: CasterContext) {
        super.onDestroy(context)
        Log.i(TAG, "is destroy. traced method size: $traceMethodCount")
    }

    override fun doTransform(context: CasterContext, node: ClassVisitor, input: TransformerInput): ClassVisitor {
        return TraceMethodVisitor(node, input.className)
    }


    internal class TraceMethodVisitor(cv: ClassVisitor, private val className: String) : ClassVisitor(AgpCompat.asmApi, cv) {
        override fun visitMethod(access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<String>?): MethodVisitor {
            val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
            val methodName = TraceMethod.create(0, access, className, name, descriptor).getMethodName()
            val tracedMethod = MethodCollector.collectedMethodMap[methodName]

            // 需要插桩的方法会收录在 collectedMethodMap 中，不会为空
            if (tracedMethod != null) {
                return TraceMethodAdapter(mv, AgpCompat.asmApi, access, name, descriptor, tracedMethod.id)
            } else {
                return mv
            }
        }
    }

    internal class TraceMethodAdapter(
        mv: MethodVisitor, api: Int, access: Int, name: String?, desc: String?, private val traceId: Int
    ) : AdviceAdapter(api, mv, access, name, desc) {

        companion object {
            @JvmStatic
            val traceMethodCount = AtomicInteger()
        }

        override fun onMethodEnter() {
            traceMethodCount.incrementAndGet()
            visitLdcInsn(traceId)
            visitMethodInsn(INVOKESTATIC, TraceBuildConstants.MATRIX_TRACE_CLASS, "i", "(I)V", false)
        }

        override fun onMethodExit(opcode: Int) {
            traceMethodCount.incrementAndGet()
            visitLdcInsn(traceId)
            visitMethodInsn(INVOKESTATIC, TraceBuildConstants.MATRIX_TRACE_CLASS, "o", "(I)V", false)
        }
    }
}
