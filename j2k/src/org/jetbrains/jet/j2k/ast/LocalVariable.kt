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

import org.jetbrains.jet.j2k.ConverterSettings

class LocalVariable(
        private val identifier: Identifier,
        private val annotations: List<Annotation>,
        private val modifiers: Set<Modifier>,
        private val typeCalculator: () -> Type /* we use lazy type calculation for better performance */,
        private val initializer: Expression,
        private val isVal: Boolean,
        private val settings: ConverterSettings
) : Element {

    override fun toKotlin(): String {
        val start = annotations.toKotlin() + if (isVal) "val" else "var"
        return if (initializer.isEmpty) {
            "$start ${identifier.toKotlin()} : ${typeCalculator().toKotlin()}"
        }
        else {
            val shouldSpecifyType = settings.specifyLocalVariableTypeByDefault
            if (shouldSpecifyType)
                "$start ${identifier.toKotlin()} : ${typeCalculator().toKotlin()} = ${initializer.toKotlin()}"
            else
                "$start ${identifier.toKotlin()} = ${initializer.toKotlin()}"
        }
    }
}
