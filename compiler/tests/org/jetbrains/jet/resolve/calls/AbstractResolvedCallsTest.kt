/*
 * Copyright 2010-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.jet.resolve.calls

import org.jetbrains.jet.ConfigurationKind
import org.jetbrains.jet.JetLiteFixture
import org.jetbrains.jet.JetTestUtils
import org.jetbrains.jet.cli.jvm.compiler.JetCoreEnvironment
import org.jetbrains.jet.lang.psi.JetElement
import org.jetbrains.jet.lang.resolve.BindingContext
import org.jetbrains.jet.lang.resolve.calls.model.ResolvedCall
import org.jetbrains.jet.lang.resolve.scopes.receivers.AbstractReceiverValue
import org.jetbrains.jet.lang.resolve.scopes.receivers.ExpressionReceiver
import org.jetbrains.jet.lang.resolve.scopes.receivers.ReceiverValue

import java.io.File
import kotlin.test.assertTrue
import org.jetbrains.jet.lang.resolve.calls.model.VariableAsFunctionResolvedCall
import org.jetbrains.jet.lang.resolve.lazy.JvmResolveUtil
import org.jetbrains.jet.lang.resolve.calls.model.ArgumentMapping
import org.jetbrains.jet.lang.resolve.calls.model.ArgumentMatch
import org.jetbrains.jet.lang.resolve.calls.util.getAllValueArguments
import org.jetbrains.jet.renderer.DescriptorRenderer
import com.intellij.openapi.util.io.FileUtil
import org.jetbrains.jet.lang.psi.ValueArgument

public abstract class AbstractResolvedCallsTest() : JetLiteFixture() {
    override fun createEnvironment(): JetCoreEnvironment = createEnvironmentWithMockJdk(ConfigurationKind.JDK_ONLY)

    public fun doTest(filePath: String) {
        val file = File(filePath)
        val text = JetTestUtils.doLoadFile(file)
        val directives = JetTestUtils.parseDirectives(text)

        val callName = directives["CALL"]

        fun analyzeFileAndGetResolvedCallEntries(): Map<JetElement, ResolvedCall<*>> {
            val psiFile = JetTestUtils.loadJetFile(getProject(), file)!!
            val analyzeExhaust = JvmResolveUtil.analyzeOneFileWithJavaIntegrationAndCheckForErrors(psiFile)
            val bindingContext = analyzeExhaust.getBindingContext()
            return bindingContext.getSliceContents(BindingContext.RESOLVED_CALL)
        }

        var callFound = false
        fun tryCall(resolvedCall: ResolvedCall<*>, actualName: String?) {
            if (callName == null || callName != actualName) return
            callFound = true

            val resolvedCallInfoFileName = FileUtil.getNameWithoutExtension(filePath) + ".txt"
            JetTestUtils.assertEqualsToFile(File(resolvedCallInfoFileName), resolvedCall.renderToText())
        }

        for ((element, resolvedCall) in analyzeFileAndGetResolvedCallEntries()) {
            if (resolvedCall is VariableAsFunctionResolvedCall) {
                tryCall(resolvedCall.functionCall, "invoke")
                tryCall(resolvedCall.variableCall, element.getText())
            }
            else {
                tryCall(resolvedCall, element.getText())
            }
        }
        assertTrue(callFound, "Resolved call for $callName was not found")
    }
}

private fun ReceiverValue.getText() = when (this) {
    is ExpressionReceiver -> getExpression().getText()
    is AbstractReceiverValue -> getType().toString()
    else -> toString()
}

private fun ValueArgument.getText() = this.getArgumentExpression()?.getText()?.replace("\n", " ") ?: ""

private fun ArgumentMapping.getText() = when (this) {
    is ArgumentMatch -> {
        val parameterType = DescriptorRenderer.SHORT_NAMES_IN_TYPES.renderType(valueParameter.getType())
        "${valueParameter.getName()} : ${parameterType}, ${status.name()}"
    }
    else -> "argument unmapped"
}

private fun ResolvedCall<*>.renderToText(): String {
    val result = StringBuilder()
    fun addLine(line: String) = result.append(line).append("\n")

    addLine("${getCall().getCallElement().getText()}\n")

    addLine("This object = ${getThisObject().getText()}")
    addLine("Receiver argument = ${getReceiverArgument().getText()}")
    addLine("Explicit receiver kind = ${getExplicitReceiverKind()}")

    val valueArguments = getCall().getAllValueArguments()
    if (valueArguments.isEmpty()) return result.toString()

    addLine("\nValue arguments mapping:")
    val padding = valueArguments.map { it.getText().size }.filter { it < 20 }.max() ?: 20
    for (valueArgument in valueArguments) {
        val argumentText = valueArgument.getText()
        val argumentMappingText = getArgumentMapping(valueArgument).getText()
        if (argumentText.size <= padding) {
            addLine("${"%-${padding}s".format(argumentText)} - $argumentMappingText")
        }
        else {
            addLine(argumentText)
            addLine("${"%-${padding}s".format("")}   ${argumentMappingText}")
        }
    }
    return result.toString()
}