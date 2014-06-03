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

package org.jetbrains.jet.j2k

import com.intellij.psi.*
import org.jetbrains.jet.j2k.ast.*
import org.jetbrains.jet.j2k.visitors.*
import java.util.*
import com.intellij.psi.CommonClassNames.*
import org.jetbrains.jet.lang.types.expressions.OperatorConventions.*
import com.intellij.openapi.project.Project
import com.intellij.psi.util.PsiUtil

public class Converter(val project: Project, val settings: ConverterSettings) {

    private var classIdentifiersSet: MutableSet<String> = HashSet()

    private val dispatcher: Dispatcher = Dispatcher(this)

    public var methodReturnType: PsiType? = null
        private set

    public fun setClassIdentifiers(identifiers: MutableSet<String>) {
        classIdentifiersSet = identifiers
    }

    public fun getClassIdentifiers(): Set<String> {
        return Collections.unmodifiableSet(classIdentifiersSet)
    }

    public fun clearClassIdentifiers() {
        classIdentifiersSet.clear()
    }

    public fun elementToKotlin(element: PsiElement): String
            = convertTopElement(element)?.toKotlin() ?: ""

    private fun convertTopElement(element: PsiElement?): Element? = when(element) {
        is PsiJavaFile -> convertFile(element)
        is PsiClass -> convertClass(element)
        is PsiMethod -> convertMethod(element, HashSet())
        is PsiField -> convertField(element)
        is PsiStatement -> convertStatement(element)
        is PsiExpression -> convertExpression(element)
        is PsiComment -> Comment(element.getText()!!)
        is PsiImportList -> convertImportList(element)
        is PsiImportStatementBase -> convertImport(element)
        is PsiPackageStatement -> PackageStatement(quoteKeywords(element.getPackageName() ?: ""))
        is PsiWhiteSpace -> WhiteSpace(element.getText()!!)
        else -> null
    }

    public fun convertFile(javaFile: PsiJavaFile): File {
        val fileMembers = FileMemberList(javaFile.getChildren().map { convertTopElement(it) }.filterNotNull())
        return File(fileMembers, createMainFunction(javaFile))
    }

    public fun convertAnonymousClass(anonymousClass: PsiAnonymousClass): AnonymousClass {
        return AnonymousClass(this, convertClassBody(anonymousClass))
    }

    private fun convertClassBody(psiClass: PsiClass): List<Element> {
        val membersToRemove = HashSet<PsiMember>()
        val convertedMembers = LinkedHashMap<PsiElement, Element>()
        var inBody = false
        val lBrace = psiClass.getLBrace()
        for (element in psiClass.getChildren()) {
            if (element == lBrace) inBody = true
            if (inBody) {
                convertedMembers.put(element, convertMember(element, membersToRemove))
            }
        }
        return convertedMembers.keySet().filter { !membersToRemove.contains(it) }.map { convertedMembers[it]!! }
    }

    private fun convertMember(element: PsiElement, membersToRemove: MutableSet<PsiMember>): Element = when(element) {
        is PsiMethod -> convertMethod(element, membersToRemove)
        is PsiField -> convertField(element)
        is PsiClass -> convertClass(element)
        is PsiClassInitializer -> convertInitializer(element)
        else -> convertElement(element)
    }

    private fun getComments(member: PsiMember): MemberComments {
        var relevantChildren = member.getChildren().toList()
        if (member is PsiClass) {
            val leftBraceIndex = relevantChildren.indexOf(member.getLBrace())
            relevantChildren = relevantChildren.subList(0, leftBraceIndex)
        }
        val whiteSpacesAndComments = relevantChildren
                .filter { it is PsiWhiteSpace || it is PsiComment }
                .map { convertElement(it) }
        return MemberComments(whiteSpacesAndComments)
    }

    private fun convertClass(psiClass: PsiClass): Class {
        val modifiers = convertModifierList(psiClass.getModifierList())
        val typeParameters = convertTypeParameterList(psiClass.getTypeParameterList())
        val implementsTypes = convertToNotNullableTypes(psiClass.getImplementsListTypes())
        val extendsTypes = convertToNotNullableTypes(psiClass.getExtendsListTypes())
        val name = Identifier(psiClass.getName()!!)
        val classBodyElements = ArrayList(convertClassBody(psiClass))

        when {
            psiClass.isInterface() -> return Trait(this, name, getComments(psiClass), modifiers, typeParameters, extendsTypes, listOf(), implementsTypes, classBodyElements)

            psiClass.isEnum() -> return Enum(this, name, getComments(psiClass), modifiers, typeParameters, listOf(), listOf(), implementsTypes, classBodyElements)

            else -> {
                if (psiClass.getPrimaryConstructor() == null && psiClass.getConstructors().size > 1) {
                    val finalOrWithEmptyInitializerFields = classBodyElements.filterIsInstance(javaClass<Field>()).filter { it.isVal() || it.initializer.toKotlin().isEmpty() }
                    val initializers = HashMap<String, String>()
                    for (element in classBodyElements) {
                        if (element is SecondaryConstructor) {
                            for (field in finalOrWithEmptyInitializerFields) {
                                initializers.put(field.identifier.toKotlin(), getDefaultInitializer(field))
                            }

                            val newStatements = ArrayList<Statement>()
                            for (statement in element.block!!.statements) {
                                var keepStatement = true
                                if (statement is AssignmentExpression) {
                                    val assignee = statement.left
                                    if (assignee is CallChainExpression) {
                                        for (field in finalOrWithEmptyInitializerFields) {
                                            val id = field.identifier.toKotlin()
                                            if (assignee.identifier.toKotlin().endsWith("." + id)) {
                                                initializers.put(id, statement.right.toKotlin())
                                                keepStatement = false
                                            }

                                        }
                                    }

                                }

                                if (keepStatement) {
                                    newStatements.add(statement)
                                }

                            }
                            newStatements.add(0, DummyStringExpression("val __ = " + createPrimaryConstructorInvocation(name.toKotlin(), finalOrWithEmptyInitializerFields, initializers)))
                            element.block = Block(newStatements)
                        }
                    }

                    //TODO: comments?
                    val parameters = finalOrWithEmptyInitializerFields.map { field ->
                        val varValModifier = if (field.modifiers.contains(Modifier.FINAL)) Parameter.VarValModifier.Val else Parameter.VarValModifier.Var
                        Parameter(field.identifier, field.`type`, varValModifier, field.modifiers.filter { ACCESS_MODIFIERS.contains(it) })
                    }
                    classBodyElements.add(PrimaryConstructor(this,
                                                             MemberComments.Empty,
                                                             setOf(Modifier.PRIVATE),
                                                             ParameterList(parameters),
                                                             Block.Empty))
                    classBodyElements.removeAll(finalOrWithEmptyInitializerFields)
                }

                val baseClassParams: List<Expression> = run {
                    val superVisitor = SuperVisitor()
                    psiClass.accept(superVisitor)
                    val resolvedSuperCallParameters = superVisitor.resolvedSuperCallParameters
                    if (resolvedSuperCallParameters.size() == 1) {
                        convertExpressions(resolvedSuperCallParameters.single().getExpressions())
                    }
                    else {
                        listOf()
                    }
                }

                return Class(this, name, getComments(psiClass), modifiers, typeParameters, extendsTypes, baseClassParams, implementsTypes, classBodyElements)
            }
        }
    }

    private fun findBackingFieldForConstructorParameter(parameter: PsiParameter, constructor: PsiMethod): Pair<PsiField, PsiStatement>? {
        val body = constructor.getBody() ?: return null

        val refs = findExpressionReferences(parameter, body)

        if (refs.any { PsiUtil.isAccessedForWriting(it) }) return null

        for(ref in refs) {
            val assignment = ref.getParent() as? PsiAssignmentExpression ?: continue
            if (assignment.getOperationSign().getTokenType() != JavaTokenType.EQ) continue
            val assignee = assignment.getLExpression() as? PsiReferenceExpression ?: continue
            if (!isQualifierEmptyOrThis(assignee)) continue
            val field = assignee.resolve() as? PsiField ?: continue
            if (field.getContainingClass() != constructor.getContainingClass()) continue
            if (field.getInitializer() != null) continue

            // assignment should be a top-level statement
            val statement = assignment.getParent() as? PsiExpressionStatement ?: continue
            if (statement.getParent() != body) continue

            // and no other assignments to field should exist in the constructor
            if (findExpressionReferences(field, body).any { it != assignee && PsiUtil.isAccessedForWriting(it) && isQualifierEmptyOrThis(it) }) continue
            //TODO: check access to field before assignment

            return field to statement
        }

        return null
    }

    private fun convertInitializer(initializer: PsiClassInitializer): Initializer {
        return Initializer(convertBlock(initializer.getBody()), convertModifierList(initializer.getModifierList()))
    }

    private fun convertField(field: PsiField): Field {
        val modifiers = convertModifierList(field.getModifierList())
        if (field is PsiEnumConstant) {
            return EnumConstant(Identifier(field.getName()!!),
                                getComments(field),
                                modifiers,
                                convertType(field.getType()),
                                convertElement(field.getArgumentList()))
        }

        var kType = convertVariableType(field)
        if (field.hasModifierProperty(PsiModifier.FINAL) && field.getInitializer().isDefinitelyNotNull()) {
            kType = kType.toNotNullType();
        }

        return Field(Identifier(field.getName()!!),
                     getComments(field),
                     modifiers,
                     kType,
                     convertExpression(field.getInitializer(), field.getType()),
                     field.countWriteAccesses(field.getContainingClass()))
    }

    private fun convertMethod(method: PsiMethod, membersToRemove: MutableSet<PsiMember>): Function {
        if (directlyOverridesMethodFromObject(method)) {
            dispatcher.expressionVisitor = ExpressionVisitorForDirectObjectInheritors(this)
        }
        else {
            dispatcher.expressionVisitor = ExpressionVisitor(this)
        }

        try {
            methodReturnType = method.getReturnType()
            val returnType = convertType(method.getReturnType(), method.isAnnotatedAsNotNull())

            val modifiers = HashSet(convertModifierList(method.getModifierList()))

            val containingClass = method.getContainingClass()
            if (containingClass != null && containingClass.isInterface()) {
                modifiers.remove(Modifier.ABSTRACT)
            }
            if (containingClass != null && (containingClass.hasModifierProperty(PsiModifier.FINAL) || containingClass.isEnum())) {
                modifiers.add(Modifier.FINAL)
            }

            if (settings.openByDefault &&
                    !modifiers.contains(Modifier.ABSTRACT) &&
                    !modifiers.contains(Modifier.FINAL) &&
                    !modifiers.contains(Modifier.PRIVATE)) {
                modifiers.add(Modifier.OPEN)
            }

            if (isOverride(method)) {
                modifiers.add(Modifier.OVERRIDE)
                modifiers.remove(Modifier.OPEN)
            }

            val comments = getComments(method)

            if (method.isConstructor()) {
                if (method.isPrimaryConstructor()) {
                    val params = method.getParameterList().getParameters()
                    val parameterToField = HashMap<PsiParameter, PsiField>()
                    val body = method.getBody()
                    val block = if (body != null) {
                        val statementsToRemove = HashSet<PsiStatement>()
                        val usageReplacementMap = HashMap<PsiVariable, String>()
                        for (parameter in params) {
                            val (field, initializationStatement) = findBackingFieldForConstructorParameter(parameter, method) ?: continue
                            if (membersToRemove.contains(field)) continue // already used as backing field
                            if (convertVariableType(field) != convertVariableType(parameter)) continue

                            parameterToField.put(parameter, field)
                            statementsToRemove.add(initializationStatement)

                            if (field.getName() != parameter.getName()) {
                                usageReplacementMap.put(parameter, field.getName()!!)
                            }
                        }
                        dispatcher.expressionVisitor = ExpressionVisitor(this, usageReplacementMap)
                        Block(convertStatements(body.getStatements().filter{ !statementsToRemove.contains(it) }), false)
                    }
                    else {
                        Block.Empty
                    }

                    val parameterList = ParameterList(params.map {
                        val field = parameterToField[it]
                        if (field == null) {
                            convertParameter(it)
                        }
                        else {
                            membersToRemove.add(field)
                            Parameter(Identifier(field.getName()!!),
                                      convertVariableType(it),
                                      if (field.hasModifierProperty(PsiModifier.FINAL)) Parameter.VarValModifier.Val else Parameter.VarValModifier.Var,
                                      convertModifierList(field.getModifierList()).filter { ACCESS_MODIFIERS.contains(it) })
                        }
                    })
                    return PrimaryConstructor(this, comments, modifiers, parameterList, block)
                }
                else {
                    val params = convertParameterList(method.getParameterList())
                    return SecondaryConstructor(this, comments, modifiers, params, convertBlock(method.getBody()))
                }
            }
            else {
                val params = convertParameterList(method.getParameterList())
                val typeParameterList = convertTypeParameterList(method.getTypeParameterList())
                val block = convertBlock(method.getBody())
                return Function(this, Identifier(method.getName()), comments, modifiers, returnType, typeParameterList, params, block)
            }
        }
        finally {
            dispatcher.expressionVisitor = ExpressionVisitor(this)
        }
    }

    public fun convertBlock(block: PsiCodeBlock?): Block {
        if (block == null) return Block.Empty

        return Block(convertStatements(block.getChildren().toList()), true)
    }

    public fun convertStatements(statements: List<PsiElement>): StatementList {
        return StatementList(statements.map { if (it is PsiStatement) convertStatement(it) else convertElement(it) })
    }

    public fun convertStatement(statement: PsiStatement?): Statement {
        if (statement == null) return Statement.Empty

        val statementVisitor: StatementVisitor = StatementVisitor(this)
        statement.accept(statementVisitor)
        return statementVisitor.result
    }

    public fun convertExpressions(expressions: Array<PsiExpression>): List<Expression>
            = expressions.map { convertExpression(it) }

    public fun convertExpression(expression: PsiExpression?): Expression {
        if (expression == null) return Expression.Empty

        val expressionVisitor = dispatcher.expressionVisitor
        expression.accept(expressionVisitor)
        return expressionVisitor.result
    }

    public fun convertElement(element: PsiElement?): Element {
        if (element == null) return Element.Empty

        val elementVisitor = ElementVisitor(this)
        element.accept(elementVisitor)
        return elementVisitor.result
    }

    fun convertElements(elements: Array<out PsiElement?>): List<Element> {
        val result = ArrayList<Element>()
        for (element in elements) {
            result.add(convertElement(element))
        }
        return result
    }

    public fun convertTypeElement(element: PsiTypeElement?): TypeElement {
        return TypeElement(if (element == null)
                               Type.Empty
                           else
                               convertType(element.getType()))
    }

    public fun convertType(`type`: PsiType?): Type {
        if (`type` == null) return Type.Empty

        return `type`.accept<Type>(TypeVisitor(this))!!
    }

    public fun convertTypes(types: Array<PsiType>): List<Type> {
        return types.map { convertType(it) }
    }

    public fun convertType(`type`: PsiType?, notNull: Boolean): Type {
        val result = convertType(`type`)
        if (notNull) {
            return result.toNotNullType()
        }

        return result
    }

    public fun convertVariableType(variable: PsiVariable): Type
            = convertType(variable.getType(), variable.isAnnotatedAsNotNull())

    private fun convertToNotNullableTypes(types: Array<out PsiType?>): List<Type>
            = types.map { convertType(it).toNotNullType() }

    public fun convertParameterList(parameters: PsiParameterList): ParameterList
            = ParameterList(parameters.getParameters().map { convertParameter(it) })

    public fun convertParameter(parameter: PsiParameter,
                                forceNotNull: Boolean = false,
                                varValModifier: Parameter.VarValModifier = Parameter.VarValModifier.None,
                                modifiers: Collection<Modifier> = listOf()): Parameter {
        var `type` = convertVariableType(parameter)
        if (forceNotNull) {
            `type` = `type`.toNotNullType()
        }
        return Parameter(Identifier(parameter.getName()!!), `type`, varValModifier, modifiers)
    }

    public fun convertArguments(expression: PsiCallExpression): List<Expression> {
        val arguments = expression.getArgumentList()?.getExpressions() ?: array()
        val resolved = expression.resolveMethod()
        val expectedTypes = if (resolved != null)
            resolved.getParameterList().getParameters().map { it.getType() }
        else
            listOf()

        return if (arguments.size == expectedTypes.size())
            (0..expectedTypes.lastIndex).map { i -> convertExpression(arguments[i], expectedTypes[i]) }
        else
            arguments.map { convertExpression(it) }
    }

    public fun convertExpression(argument: PsiExpression?, expectedType: PsiType?): Expression {
        if (argument == null) return Identifier.Empty

        var expression = convertExpression(argument)
        val actualType = argument.getType()
        val isPrimitiveTypeOrNull = actualType == null || actualType is PsiPrimitiveType
        if (isPrimitiveTypeOrNull && expression.isNullable) {
            expression = BangBangExpression(expression)
        }
        else if (expectedType is PsiPrimitiveType && actualType is PsiClassType) {
            if (PsiPrimitiveType.getUnboxedType(actualType) == expectedType) {
                expression = BangBangExpression(expression)
            }
        }

        if (actualType != null) {
            if (isConversionNeeded(actualType, expectedType) && expression !is LiteralExpression) {
                val conversion = PRIMITIVE_TYPE_CONVERSIONS[expectedType?.getCanonicalText()]
                if (conversion != null) {
                    expression = MethodCallExpression.build(expression, conversion)
                }
            }

        }

        return expression
    }

    private fun createPrimaryConstructorInvocation(s: String, fields: List<Field>, initializers: Map<String, String>): String {
        return s + "(" + fields.map { initializers[it.identifier.toKotlin()] }.makeString(", ") + ")"
    }

    public fun convertIdentifier(identifier: PsiIdentifier?): Identifier {
        if (identifier == null) return Identifier.Empty

        return Identifier(identifier.getText()!!)
    }

    public fun convertModifierList(modifierList: PsiModifierList?): Set<Modifier> {
        if (modifierList == null) return setOf()

        val modifiersSet = HashSet<Modifier>()

        if (modifierList.hasExplicitModifier(PsiModifier.ABSTRACT))
            modifiersSet.add(Modifier.ABSTRACT)

        if (modifierList.hasModifierProperty(PsiModifier.FINAL))
            modifiersSet.add(Modifier.FINAL)

        if (modifierList.hasModifierProperty(PsiModifier.STATIC))
            modifiersSet.add(Modifier.STATIC)

        if (modifierList.hasExplicitModifier(PsiModifier.PUBLIC))
            modifiersSet.add(Modifier.PUBLIC)

        if (modifierList.hasExplicitModifier(PsiModifier.PROTECTED))
            modifiersSet.add(Modifier.PROTECTED)

        if (modifierList.hasExplicitModifier(PsiModifier.PACKAGE_LOCAL))
            modifiersSet.add(Modifier.INTERNAL)

        if (modifierList.hasExplicitModifier(PsiModifier.PRIVATE))
            modifiersSet.add(Modifier.PRIVATE)

        return modifiersSet
    }

    private val TYPE_MAP: Map<String, String> = mapOf(
            JAVA_LANG_BYTE to "byte",
            JAVA_LANG_SHORT to "short",
            JAVA_LANG_INTEGER to "int",
            JAVA_LANG_LONG to "long",
            JAVA_LANG_FLOAT to "float",
            JAVA_LANG_DOUBLE to "double",
            JAVA_LANG_CHARACTER to "char"
    )

    private fun isConversionNeeded(actual: PsiType?, expected: PsiType?): Boolean {
        if (actual == null || expected == null) return false

        val expectedStr = expected.getCanonicalText()
        val actualStr = actual.getCanonicalText()
        if (expectedStr == actualStr) return false
        val o1 = expectedStr == TYPE_MAP[actualStr]
        val o2 = actualStr == TYPE_MAP[expectedStr]
        return o1 == o2
    }
}

val NOT_NULL_ANNOTATIONS: Set<String> = setOf("org.jetbrains.annotations.NotNull", "com.sun.istack.internal.NotNull", "javax.annotation.Nonnull")

val PRIMITIVE_TYPE_CONVERSIONS: Map<String, String> = mapOf(
        "byte" to BYTE.asString(),
        "short" to SHORT.asString(),
        "int" to INT.asString(),
        "long" to LONG.asString(),
        "float" to FLOAT.asString(),
        "double" to DOUBLE.asString(),
        "char" to CHAR.asString(),
        JAVA_LANG_BYTE to BYTE.asString(),
        JAVA_LANG_SHORT to SHORT.asString(),
        JAVA_LANG_INTEGER to INT.asString(),
        JAVA_LANG_LONG to LONG.asString(),
        JAVA_LANG_FLOAT to FLOAT.asString(),
        JAVA_LANG_DOUBLE to DOUBLE.asString(),
        JAVA_LANG_CHARACTER to CHAR.asString()
)
