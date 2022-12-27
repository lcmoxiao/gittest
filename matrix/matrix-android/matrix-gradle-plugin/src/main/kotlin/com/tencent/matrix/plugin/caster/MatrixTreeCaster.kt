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

import com.android.builder.model.AndroidProject.FD_OUTPUTS
import com.google.common.base.Joiner
import com.tencent.caster.transform.gradle.CasterContext
import com.tencent.caster.transform.transformer.AsmTreeApiTransformer
import com.tencent.caster.transformer.JarClassTransformerInput
import com.tencent.caster.transformer.TransformerInput
import com.tencent.matrix.javalib.util.Log
import com.tencent.matrix.plugin.caster.MethodCollector.collectMethodInfo
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import java.io.File
import java.io.PrintWriter

/**
 * 第一步、方法信息收集
 *
 * Tree 遍历过程中会收集所有需要插桩的方法信息。
 */
class MatrixTreeCaster : AsmTreeApiTransformer() {

    companion object {
        const val TAG = "MatrixTreeCaster"

        lateinit var ignoreMethodPrinter: PrintWriter
        lateinit var methodPrinter: PrintWriter
    }

    override fun onInit(context: CasterContext) {
        super.onInit(context)

        val buildDir = context.project.buildDir.absolutePath
        val dirName = context.context.variantName

        val mappingOut = Joiner.on(File.separatorChar).join(
            buildDir,
            FD_OUTPUTS,
            "mapping",
            dirName
        )

        val methodMapFile = File("$mappingOut/methodMapping.txt")
        if (methodMapFile.exists()) {
            methodMapFile.delete()
        }
        if (!methodMapFile.parentFile.exists()) {
            methodMapFile.parentFile.mkdirs()
        }
        methodMapFile.createNewFile()
        methodPrinter = PrintWriter(methodMapFile)

        val ignoreMethodMapFilePath = File("$mappingOut/ignoreMethodMapping.txt")
        if (ignoreMethodMapFilePath.exists()) {
            ignoreMethodMapFilePath.delete()
        }
        if (!ignoreMethodMapFilePath.parentFile.exists()) {
            ignoreMethodMapFilePath.parentFile.mkdirs()
        }
        ignoreMethodMapFilePath.createNewFile()
        ignoreMethodPrinter = PrintWriter(ignoreMethodMapFilePath)
        Log.i(TAG, "is init.")
    }

    override fun getName(): String {
        return "MatrixTraceCaster"
    }

    override fun doTransform(context: CasterContext, node: ClassNode, input: TransformerInput): ClassNode {
        if (node.access and Opcodes.ACC_ABSTRACT > 0 || node.access and Opcodes.ACC_INTERFACE > 0) {
            return node
        }
        // 遍历收集方法信息
        node.methods.forEach { method ->
            collectMethodInfo(method.instructions, node.name, method.name, method.access, method.desc)
        }
        return node
    }

    override fun onDestroy(context: CasterContext) {
        super.onDestroy(context)
        methodPrinter.flush()
        ignoreMethodPrinter.flush()
        Log.i(TAG, "is destroy. collect method size: ${MethodCollector.incrementCount}")
    }
}
