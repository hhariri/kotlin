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

package org.jetbrains.jet.j2k.ast

import java.util.ArrayList

class MethodCallExpression(
        val methodExpression: Expression,
        val arguments: List<Expression>,
        val typeParameters: List<Type>,
        override val isNullable: Boolean = false
) : Expression() {

    override fun toKotlin(): String {
        return operandToKotlin(methodExpression) +
                typeParameters.toKotlin(", ", "<", ">") +
                "(" +
                arguments.map { it.toKotlin() }.makeString(", ") +
                ")"
    }

    class object {
        public fun build(receiver: Expression, methodName: String, arguments: List<Expression> = ArrayList()): MethodCallExpression {
            return MethodCallExpression(QualifiedExpression(receiver, Identifier(methodName, false)),
                                        arguments,
                                        listOf(),
                                        false)
        }
    }
}
