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
import org.jetbrains.jet.j2k.CodeBuilder
import org.jetbrains.jet.j2k.append

class LocalVariable(
        private val identifier: Identifier,
        private val annotations: Annotations,
        private val modifiers: Modifiers,
        private val typeCalculator: () -> Type /* we use lazy type calculation for better performance */,
        private val initializer: Expression,
        private val isVal: Boolean,
        private val settings: ConverterSettings
) : Element() {

    override fun generateCode(builder: CodeBuilder) {
        builder.append(annotations).append(if (isVal) "val " else "var ")
        if (initializer.isEmpty) {
            builder append identifier append ":" append typeCalculator()
        }
        else {
            val shouldSpecifyType = settings.specifyLocalVariableTypeByDefault
            if (shouldSpecifyType)
                builder append identifier append ":" append typeCalculator() append "=" append initializer
            else
                builder append identifier append "=" append initializer
        }
    }
}
