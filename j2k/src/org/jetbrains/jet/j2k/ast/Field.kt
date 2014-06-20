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

import org.jetbrains.jet.j2k.*
import java.util.ArrayList

open class Field(
        val identifier: Identifier,
        comments: MemberComments,
        modifiers: Set<Modifier>,
        val `type`: Type,
        val initializer: Element,
        val isVal: Boolean,
        private val hasWriteAccesses: Boolean
) : Member(comments, modifiers) {

    override fun toKotlin(): String {
        val declaration = commentsToKotlin() + modifiersToKotlin() + (if (isVal) "val " else "var ") + identifier.toKotlin() + " : " + `type`.toKotlin()
        return if (initializer.isEmpty)
            declaration + (if (isVal && hasWriteAccesses) "" else " = " + getDefaultInitializer(this))
        else
            declaration + " = " + initializer.toKotlin()
    }

    private fun modifiersToKotlin(): String {
        val modifierList = ArrayList<Modifier>()
        if (modifiers.contains(Modifier.ABSTRACT)) {
            modifierList.add(Modifier.ABSTRACT)
        }

        modifiers.accessModifier()?.let { modifierList.add(it) }

        return modifierList.toKotlin()
    }
}
