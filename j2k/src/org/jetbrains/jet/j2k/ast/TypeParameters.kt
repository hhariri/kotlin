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

import com.intellij.psi.PsiTypeParameter
import org.jetbrains.jet.j2k.Converter
import com.intellij.psi.PsiTypeParameterList
import java.util.ArrayList
import org.jetbrains.jet.j2k.CommentConverter

class TypeParameter(val name: Identifier, val extendsTypes: List<Type>) : Element() {
    fun hasWhere(): Boolean = extendsTypes.size() > 1

    fun whereToKotlin(commentConverter: CommentConverter): String {
        if (hasWhere()) {
            return name.toKotlin(commentConverter) + " : " + extendsTypes[1].toKotlin(commentConverter)
        }

        return ""
    }

    override fun toKotlinImpl(commentConverter: CommentConverter): String {
        if (extendsTypes.size() > 0) {
            return name.toKotlin(commentConverter) + " : " + extendsTypes[0].toKotlin(commentConverter)
        }

        return name.toKotlin(commentConverter)
    }
}

class TypeParameterList(val parameters: List<TypeParameter>) : Element() {
    override fun toKotlinImpl(commentConverter: CommentConverter): String {
        return if (parameters.isNotEmpty())
            parameters.map { it.toKotlin(commentConverter) }.makeString(", ", "<", ">")
        else
            ""
    }

    fun whereToKotlin(commentConverter: CommentConverter): String {
        if (hasWhere()) {
            val wheres = parameters.map { it.whereToKotlin(commentConverter) }
            return "where " + wheres.makeString(", ")
        }
        return ""
    }


    override val isEmpty: Boolean
        get() = parameters.isEmpty()

    private fun hasWhere(): Boolean = parameters.any { it.hasWhere() }

    class object {
        val Empty = TypeParameterList(ArrayList())
    }
}

fun Converter.convertTypeParameter(psiTypeParameter: PsiTypeParameter): TypeParameter {
    return convertElement(psiTypeParameter) as TypeParameter
}

fun Converter.convertTypeParameterList(typeParameterList: PsiTypeParameterList?): TypeParameterList {
    return if (typeParameterList != null)
        TypeParameterList(typeParameterList.getTypeParameters()!!.toList().map { convertTypeParameter(it) })
    else
        TypeParameterList.Empty
}