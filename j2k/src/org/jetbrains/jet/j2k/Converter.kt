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

public trait ConversionScope {
    public fun contains(element: PsiElement): Boolean
}

public class FilesConversionScope(val files: Collection<PsiJavaFile>) : ConversionScope {
    override fun contains(element: PsiElement) = files.any { element.getContainingFile() == it }
}

public class Converter private(val project: Project, val settings: ConverterSettings, val conversionScope: ConversionScope, val state: Converter.State) {
    private class State(val typeConverter: TypeConverter,
                        val methodReturnType: PsiType?,
                        val expressionVisitorFactory: (Converter) -> ExpressionVisitor,
                        val statementVisitorFactory: (Converter) -> StatementVisitor)

    val typeConverter: TypeConverter get() = state.typeConverter
    val methodReturnType: PsiType? get() = state.methodReturnType

    private val expressionVisitor = state.expressionVisitorFactory(this)
    private val statementVisitor = state.statementVisitorFactory(this)

    class object {
        public fun create(project: Project, settings: ConverterSettings, conversionScope: ConversionScope): Converter
                = Converter(project, settings, conversionScope, State(TypeConverter(settings, conversionScope), null, { ExpressionVisitor(it) }, { StatementVisitor(it) }))
    }

    fun withMethodReturnType(methodReturnType: PsiType?): Converter
            = Converter(project, settings, conversionScope, State(typeConverter, methodReturnType, state.expressionVisitorFactory, state.statementVisitorFactory))

    fun withExpressionVisitor(factory: (Converter) -> ExpressionVisitor): Converter
            = Converter(project, settings, conversionScope, State(typeConverter, state.methodReturnType, factory, state.statementVisitorFactory))

    fun withStatementVisitor(factory: (Converter) -> StatementVisitor): Converter
            = Converter(project, settings, conversionScope, State(typeConverter, state.methodReturnType, state.expressionVisitorFactory, factory))

    public fun elementToKotlin(element: PsiElement): String
            = convertTopElement(element)?.toKotlin(CommentConverter(element)) ?: ""

    private fun convertTopElement(element: PsiElement?): Element? = when(element) {
        is PsiJavaFile -> convertFile(element)
        is PsiClass -> convertClass(element)
        is PsiMethod -> convertMethod(element, HashSet())
        is PsiField -> convertField(element)
        is PsiStatement -> convertStatement(element)
        is PsiExpression -> convertExpression(element)
        is PsiComment -> Comment(element.getText()!!/*, listOf(element)*/)
        is PsiImportList -> convertImportList(element)
        is PsiImportStatementBase -> convertImport(element, false)
        is PsiAnnotation -> convertAnnotation(element, false)
        is PsiPackageStatement -> PackageStatement(quoteKeywords(element.getPackageName() ?: ""))
        is PsiWhiteSpace -> WhiteSpace(element.getText()!!)
        else -> null
    }

    fun convertFile(javaFile: PsiJavaFile): File {
        var convertedChildren = javaFile.getChildren().map {
            if (it is PsiImportList) {
                val importList = convertImportList(it)
                typeConverter.importList = importList
                importList
            }
            else {
                convertTopElement(it)
            }
        }.filterNotNull()

        typeConverter.importList = null
        if (typeConverter.importsToAdd.isNotEmpty()) {
            val importList = convertedChildren.filterIsInstance(javaClass<ImportList>()).first()
            val newImportList = ImportList(importList.imports + typeConverter.importsToAdd)
            convertedChildren = convertedChildren.map { if (it == importList) newImportList else it }
        }

        return File(FileMemberList(convertedChildren), createMainFunction(javaFile))
    }

    fun convertAnonymousClassBody(anonymousClass: PsiAnonymousClass): AnonymousClassBody {
        return AnonymousClassBody(convertClassBody(anonymousClass), anonymousClass.getBaseClassType().resolve()?.isInterface() ?: false)
    }

    private fun convertClassBody(psiClass: PsiClass): ClassBody {
        val membersToRemove = HashSet<PsiMember>()
        val convertedElements = LinkedHashMap<PsiElement, Element>()
        var inBody = false
        val lBrace = psiClass.getLBrace()
        for (element in psiClass.getChildren()) {
            if (element == lBrace) inBody = true
            if (inBody) {
                convertedElements.put(element, convertMember(element, membersToRemove))
            }
        }

        for (member in membersToRemove) {
            convertedElements.remove(member)
        }

        val membersMap = splitIntoMembers(convertedElements)

        val constructors = membersMap.values().filter { it.member is Constructor }
        val primaryConstructor = constructors.map { it.member }.filterIsInstance(javaClass<PrimaryConstructor>()).firstOrNull()
        val secondaryConstructors = constructors.filter { it.member is SecondaryConstructor }

        // do not convert private static methods into class object if possible
        val useClassObject = if (psiClass.isEnum()) {
            false
        }
        else {
            val members = membersMap.keySet().filter { it !is PsiMethod || !it.isConstructor() }
            val classObjectMembers = members.filter { it !is PsiClass && it.hasModifierProperty(PsiModifier.STATIC) }
            val nestedClasses = members.filterIsInstance(javaClass<PsiClass>()).filter { it.hasModifierProperty(PsiModifier.STATIC) }
            if (classObjectMembers.all { it is PsiMethod && it.hasModifierProperty(PsiModifier.PRIVATE) }) {
                nestedClasses.any { nestedClass -> classObjectMembers.any { findMethodCalls(it as PsiMethod, nestedClass).isNotEmpty() } }
            }
            else {
                true
            }
        }

        val normalMembers = ArrayList<MemberWithComments>()
        val classObjectMembers = ArrayList<MemberWithComments>()
        for ((psiMember, member) in membersMap) {
            if (member.member is Constructor) continue
            if (useClassObject && psiMember !is PsiClass && psiMember.hasModifierProperty(PsiModifier.STATIC)) {
                classObjectMembers.add(member)
            }
            else {
                normalMembers.add(member)
            }
        }

        return ClassBody(primaryConstructor,
                         secondaryConstructors.toMemberList(),
                         normalMembers.toMemberList(),
                         classObjectMembers.toMemberList())

    }

    private fun List<MemberWithComments>.toMemberList() = MemberList(flatMap { it.elements })

    private fun splitIntoMembers(elements: Map<PsiElement, Element>): Map<PsiMember, MemberWithComments> {
        val result = LinkedHashMap<PsiMember, MemberWithComments>()
        var currentGroup = ArrayList<Element>()
        var lastMember: PsiMember? = null
        for ((psiElement, element) in elements) {
            currentGroup.add(element)
            if (element is Member) {
                result.put(psiElement as PsiMember, MemberWithComments(element, currentGroup))
                currentGroup = ArrayList()
                lastMember = psiElement
            }
        }
        if (lastMember != null) {
            (result[lastMember]!!.elements as MutableList).addAll(currentGroup)
        }
        return result
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
        val annotations = convertAnnotations(psiClass)
        val modifiers = convertModifiers(psiClass)
        val typeParameters = convertTypeParameterList(psiClass.getTypeParameterList())
        val implementsTypes = convertToNotNullableTypes(psiClass.getImplementsListTypes())
        val extendsTypes = convertToNotNullableTypes(psiClass.getExtendsListTypes())
        val name = Identifier(psiClass.getName()!!)
        var classBody = convertClassBody(psiClass)

        when {
            psiClass.isInterface() -> return Trait(name, getComments(psiClass), annotations, modifiers, typeParameters, extendsTypes, listOf(), implementsTypes, classBody)

            psiClass.isEnum() -> return Enum(name, getComments(psiClass), annotations, modifiers, typeParameters, listOf(), listOf(), implementsTypes, classBody)

            else -> {
                if (psiClass.getPrimaryConstructor() == null && psiClass.getConstructors().size > 1) {
                    classBody = generateArtificialPrimaryConstructor(name, classBody)
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

                if (settings.openByDefault && !psiClass.hasModifierProperty(PsiModifier.FINAL)) {
                    modifiers.add(Modifier.OPEN)
                }

                if (psiClass.getContainingClass() != null && !psiClass.hasModifierProperty(PsiModifier.STATIC)) {
                    modifiers.add(Modifier.INNER)
                }

                return Class(name, getComments(psiClass), annotations, modifiers, typeParameters, extendsTypes, baseClassParams, implementsTypes, classBody)
            }
        }
    }

    private fun generateArtificialPrimaryConstructor(className: Identifier, classBody: ClassBody): ClassBody {
        assert(classBody.primaryConstructor == null)

        val finalOrWithEmptyInitializerFields = classBody.normalMembers.members.filterIsInstance(javaClass<Field>()).filter { it.isVal || it.initializer.isEmpty }
        val initializers = HashMap<Field, Expression>()
        for (constructor in classBody.secondaryConstructors.members) {
            constructor as SecondaryConstructor

            for (field in finalOrWithEmptyInitializerFields) {
                initializers.put(field, getDefaultInitializer(field))
            }

            val newStatements = ArrayList<Statement>()
            for (statement in constructor.block!!.statements) {
                var keepStatement = true
                if (statement is AssignmentExpression) {
                    val assignee = statement.left
                    if (assignee is QualifiedExpression && (assignee.qualifier as? Identifier)?.name == SecondaryConstructor.tempValIdentifier.name) {
                        val name = (assignee.identifier as Identifier).name
                        for (field in finalOrWithEmptyInitializerFields) {
                            if (name == field.identifier.name) {
                                initializers.put(field, statement.right)
                                keepStatement = false
                            }

                        }
                    }

                }

                if (keepStatement) {
                    newStatements.add(statement)
                }

            }

            val initializer = MethodCallExpression.buildNotNull(null, className.name, finalOrWithEmptyInitializerFields.map { initializers[it]!! })
            val localVar = LocalVariable(SecondaryConstructor.tempValIdentifier,
                                         Annotations.Empty,
                                         setOf(),
                                         { ClassType(className, listOf(), Nullability.NotNull, settings) },
                                         initializer,
                                         true,
                                         settings)
            newStatements.add(0, DeclarationStatement(listOf(localVar)))
            constructor.block = Block(newStatements)
        }

        //TODO: comments?
        val parameters = finalOrWithEmptyInitializerFields.map { field ->
            val varValModifier = if (field.isVal) Parameter.VarValModifier.Val else Parameter.VarValModifier.Var
            Parameter(field.identifier, field.`type`, varValModifier, field.annotations, field.modifiers.filter { ACCESS_MODIFIERS.contains(it) })
        }

        val primaryConstructor = PrimaryConstructor(this, MemberComments.Empty, Annotations.Empty, setOf(Modifier.PRIVATE), ParameterList(parameters), Block.Empty)
        val updatedMembers = MemberList(classBody.normalMembers.elements.filter { !finalOrWithEmptyInitializerFields.contains(it) })
        return ClassBody(primaryConstructor, classBody.secondaryConstructors, updatedMembers, classBody.classObjectMembers)
    }

    private fun convertInitializer(initializer: PsiClassInitializer): Initializer {
        return Initializer(convertBlock(initializer.getBody()), convertModifiers(initializer))
    }

    private fun convertField(field: PsiField): Field {
        val annotations = convertAnnotations(field)
        val modifiers = convertModifiers(field)
        if (field is PsiEnumConstant) {
            return EnumConstant(Identifier(field.getName()!!),
                                getComments(field),
                                annotations,
                                modifiers,
                                typeConverter.convertType(field.getType(), Nullability.NotNull),
                                convertElement(field.getArgumentList()))
        }

        return Field(Identifier(field.getName()!!),
                     getComments(field),
                     annotations,
                     modifiers,
                     typeConverter.convertVariableType(field),
                     convertExpression(field.getInitializer(), field.getType()),
                     field.hasModifierProperty(PsiModifier.FINAL),
                     field.hasWriteAccesses(field.getContainingClass()))
    }

    private fun convertMethod(method: PsiMethod, membersToRemove: MutableSet<PsiMember>): Function {
        return withMethodReturnType(method.getReturnType()).doConvertMethod(method, membersToRemove)
    }

    private fun doConvertMethod(method: PsiMethod, membersToRemove: MutableSet<PsiMember>): Function {
        val returnType = typeConverter.convertMethodReturnType(method)

        val annotations = convertAnnotations(method) + convertThrows(method)
        val modifiers = convertModifiers(method)

        val comments = getComments(method)

        if (method.isConstructor()) {
            if (method.isPrimaryConstructor()) {
                return convertPrimaryConstructor(method, annotations, modifiers, comments, membersToRemove)
            }
            else {
                val params = convertParameterList(method.getParameterList())
                return SecondaryConstructor(this, comments, annotations, modifiers, params, convertBlock(method.getBody()))
            }
        }
        else {
            val isOverride = isOverride(method)
            if (isOverride) {
                modifiers.add(Modifier.OVERRIDE)
            }

            val containingClass = method.getContainingClass()

            if (settings.openByDefault) {
                val isEffectivelyFinal = method.hasModifierProperty(PsiModifier.FINAL) ||
                        containingClass != null && (containingClass.hasModifierProperty(PsiModifier.FINAL) || containingClass.isEnum())
                if (!isEffectivelyFinal && !modifiers.contains(Modifier.ABSTRACT) && !modifiers.contains(Modifier.PRIVATE)) {
                    modifiers.add(Modifier.OPEN)
                }
            }

            var params = convertParameterList(method.getParameterList())

            // if we override equals from Object, change parameter type to nullable
            if (isOverride && method.getName() == "equals") {
                val superSignatures = method.getHierarchicalMethodSignature().getSuperSignatures()
                val overridesMethodFromObject = superSignatures.any {
                    it.getMethod().getContainingClass()?.getQualifiedName() == JAVA_LANG_OBJECT
                }
                if (overridesMethodFromObject) {
                    val correctedParameter = Parameter(Identifier("other"),
                                                       ClassType(Identifier("Any"), listOf(), Nullability.Nullable, settings),
                                                       Parameter.VarValModifier.None,
                                                       params.parameters.single().annotations,
                                                       listOf())
                    params = ParameterList(listOf(correctedParameter))
                }
            }

            val typeParameterList = convertTypeParameterList(method.getTypeParameterList())
            val block = convertBlock(method.getBody())
            return Function(this, Identifier(method.getName()), comments, annotations, modifiers, returnType, typeParameterList, params, block, containingClass?.isInterface() ?: false)
        }
    }

    /**
     * Overrides of methods from Object should not be marked as overrides in Kotlin unless the class itself has java ancestors
     */
    private fun isOverride(method: PsiMethod): Boolean {
        val superSignatures = method.getHierarchicalMethodSignature().getSuperSignatures()

        val overridesMethodNotFromObject = superSignatures.any {
            it.getMethod().getContainingClass()?.getQualifiedName() != JAVA_LANG_OBJECT
        }
        if (overridesMethodNotFromObject) return true

        val overridesMethodFromObject = superSignatures.any {
            it.getMethod().getContainingClass()?.getQualifiedName() == JAVA_LANG_OBJECT
        }
        if (overridesMethodFromObject) {
            when(method.getName()) {
                "equals", "hashCode", "toString" -> return true // these methods from java.lang.Object exist in kotlin.Any

                else -> {
                    val containing = method.getContainingClass()
                    if (containing != null) {
                        val hasOtherJavaSuperclasses = containing.getSuperTypes().any {
                            //TODO: correctly check for kotlin class
                            val `class` = it.resolve()
                            `class` != null && `class`.getQualifiedName() != JAVA_LANG_OBJECT && !conversionScope.contains(`class`)
                        }
                        if (hasOtherJavaSuperclasses) return true
                    }
                }
            }
        }

        return false
    }

    private fun convertPrimaryConstructor(constructor: PsiMethod,
                                          annotations: Annotations,
                                          modifiers: Set<Modifier>,
                                          comments: MemberComments,
                                          membersToRemove: MutableSet<PsiMember>): PrimaryConstructor {
        val params = constructor.getParameterList().getParameters()
        val parameterToField = HashMap<PsiParameter, Pair<PsiField, Type>>()
        val body = constructor.getBody()
        val block = if (body != null) {
            val statementsToRemove = HashSet<PsiStatement>()
            val usageReplacementMap = HashMap<PsiVariable, String>()
            for (parameter in params) {
                val (field, initializationStatement) = findBackingFieldForConstructorParameter(parameter, constructor) ?: continue

                val fieldType = typeConverter.convertVariableType(field)
                val parameterType = typeConverter.convertVariableType(parameter)
                // types can be different only in nullability
                val `type` = if (fieldType == parameterType) {
                    fieldType
                }
                else if (fieldType.toNotNullType() == parameterType.toNotNullType()) {
                    if (fieldType.isNullable) fieldType else parameterType // prefer nullable one
                }
                else {
                    continue
                }

                parameterToField.put(parameter, field to `type`)
                statementsToRemove.add(initializationStatement)
                membersToRemove.add(field)

                if (field.getName() != parameter.getName()) {
                    usageReplacementMap.put(parameter, field.getName()!!)
                }
            }

            withExpressionVisitor { ExpressionVisitor(it, usageReplacementMap) }.convertBlock(body, false, { !statementsToRemove.contains(it) })
        }
        else {
            Block.Empty
        }

        val parameterList = ParameterList(params.map {
            if (!parameterToField.containsKey(it)) {
                convertParameter(it)
            }
            else {
                val (field, `type`) = parameterToField[it]!!
                Parameter(Identifier(field.getName()!!),
                          `type`,
                          if (field.hasModifierProperty(PsiModifier.FINAL)) Parameter.VarValModifier.Val else Parameter.VarValModifier.Var,
                          convertAnnotations(it) + convertAnnotations(field),
                          convertModifiers(field).filter { ACCESS_MODIFIERS.contains(it) })
            }
        })
        return PrimaryConstructor(this, comments, annotations, modifiers, parameterList, block)
    }

    private fun findBackingFieldForConstructorParameter(parameter: PsiParameter, constructor: PsiMethod): Pair<PsiField, PsiStatement>? {
        val body = constructor.getBody() ?: return null

        val refs = findVariableUsages(parameter, body)

        if (refs.any { PsiUtil.isAccessedForWriting(it) }) return null

        for(ref in refs) {
            val assignment = ref.getParent() as? PsiAssignmentExpression ?: continue
            if (assignment.getOperationSign().getTokenType() != JavaTokenType.EQ) continue
            val assignee = assignment.getLExpression() as? PsiReferenceExpression ?: continue
            if (!isQualifierEmptyOrThis(assignee)) continue
            val field = assignee.resolve() as? PsiField ?: continue
            if (field.getContainingClass() != constructor.getContainingClass()) continue
            if (field.hasModifierProperty(PsiModifier.STATIC)) continue
            if (field.getInitializer() != null) continue

            // assignment should be a top-level statement
            val statement = assignment.getParent() as? PsiExpressionStatement ?: continue
            if (statement.getParent() != body) continue

            // and no other assignments to field should exist in the constructor
            if (findVariableUsages(field, body).any { it != assignee && PsiUtil.isAccessedForWriting(it) && isQualifierEmptyOrThis(it) }) continue
            //TODO: check access to field before assignment

            return field to statement
        }

        return null
    }

    fun convertBlock(block: PsiCodeBlock?, notEmpty: Boolean = true, statementFilter: (PsiStatement) -> Boolean = {true}): Block {
        if (block == null) return Block.Empty

        val filteredChildren = block.getChildren().filter { it !is PsiStatement || statementFilter(it) }
        val statementList = StatementList(filteredChildren.map { if (it is PsiStatement) convertStatement(it) else convertElement(it) })
        return Block(statementList, notEmpty)
    }

    fun convertStatement(statement: PsiStatement?): Statement {
        if (statement == null) return Statement.Empty

        statementVisitor.reset()
        statement.accept(statementVisitor)
        return statementVisitor.result
    }

    fun convertExpressions(expressions: Array<PsiExpression>): List<Expression>
            = expressions.map { convertExpression(it) }

    fun convertExpression(expression: PsiExpression?): Expression {
        if (expression == null) return Expression.Empty

        expressionVisitor.reset()
        expression.accept(expressionVisitor)
        return expressionVisitor.result
    }

    fun convertElement(element: PsiElement?): Element {
        if (element == null) return Element.Empty

        val elementVisitor = ElementVisitor(this)
        element.accept(elementVisitor)
        return elementVisitor.result
    }

    fun convertTypeElement(element: PsiTypeElement?): TypeElement
            = TypeElement(if (element == null) Type.Empty else typeConverter.convertType(element.getType()))

    private fun convertToNotNullableTypes(types: Array<out PsiType?>): List<Type>
            = types.map { typeConverter.convertType(it, Nullability.NotNull) }

    fun convertParameterList(parameterList: PsiParameterList): ParameterList
            = ParameterList(parameterList.getParameters().map { convertParameter(it) })

    fun convertParameter(parameter: PsiParameter,
                                nullability: Nullability = Nullability.Default,
                                varValModifier: Parameter.VarValModifier = Parameter.VarValModifier.None,
                                modifiers: Collection<Modifier> = listOf()): Parameter {
        var `type` = typeConverter.convertVariableType(parameter)
        when (nullability) {
            Nullability.NotNull -> `type` = `type`.toNotNullType()
            Nullability.Nullable -> `type` = `type`.toNullableType()
        }
        return Parameter(Identifier(parameter.getName()!!), `type`, varValModifier, convertAnnotations(parameter), modifiers)
    }

    fun convertExpression(argument: PsiExpression?, expectedType: PsiType?): Expression {
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
                    expression = MethodCallExpression.buildNotNull(expression, conversion)
                }
            }

        }

        return expression
    }

    fun convertIdentifier(identifier: PsiIdentifier?): Identifier {
        if (identifier == null) return Identifier.Empty

        return Identifier(identifier.getText()!!)
    }

    fun convertModifiers(owner: PsiModifierListOwner): MutableSet<Modifier>
            = HashSet(MODIFIERS_MAP.filter { owner.hasModifierProperty(it.first) }.map { it.second })

    private val MODIFIERS_MAP = listOf(
            PsiModifier.ABSTRACT to Modifier.ABSTRACT,
            PsiModifier.PUBLIC to Modifier.PUBLIC,
            PsiModifier.PROTECTED to Modifier.PROTECTED,
            PsiModifier.PRIVATE to Modifier.PRIVATE
    )

    fun convertAnnotations(owner: PsiModifierListOwner): Annotations {
        val modifierList = owner.getModifierList()
        val annotations = modifierList?.getAnnotations()?.filter { it.getQualifiedName() !in ANNOTATIONS_TO_REMOVE }
        if (annotations == null || annotations.isEmpty()) return Annotations.Empty

        val newLines = run {
            if (!modifierList!!.isInSingleLine()) {
                true
            }
            else {
                var child: PsiElement? = modifierList
                while(true) {
                    child = child!!.getNextSibling()
                    if (child == null || child!!.getTextLength() != 0) break
                }
                if (child is PsiWhiteSpace) !child!!.isInSingleLine() else false
            }
        }

        val list = annotations.map { convertAnnotation(it, owner is PsiLocalVariable) }.filterNotNull() //TODO: brackets are also needed for local classes
        return Annotations(list, newLines)
    }

    private fun convertAnnotation(annotation: PsiAnnotation, brackets: Boolean): Annotation? {
        val qualifiedName = annotation.getQualifiedName()
        if (qualifiedName == CommonClassNames.JAVA_LANG_DEPRECATED && annotation.getParameterList().getAttributes().isEmpty()) {
            return Annotation(Identifier("deprecated"), listOf(null to LiteralExpression("\"\"")), brackets) //TODO: insert comment
        }

        val name = Identifier((annotation.getNameReferenceElement() ?: return null).getText()!!)
        val annotationClass = annotation.getNameReferenceElement()?.resolve() as? PsiClass
        val lastMethod = annotationClass?.getMethods()?.lastOrNull()
        val arguments = annotation.getParameterList().getAttributes().flatMap {
            val method = annotationClass?.findMethodsByName(it.getName() ?: "value", false)?.firstOrNull()
            val expectedType = method?.getReturnType()

            val attrName = it.getName()?.let { Identifier(it) }
            val value = it.getValue()

            val isVarArg = method == lastMethod /* converted to vararg in Kotlin */
            val attrValues = convertAttributeValue(value, expectedType, isVarArg, it.getName() == null)

            attrValues.map { attrName to it }
        }
        return Annotation(name, arguments, brackets)
    }

    private fun convertAttributeValue(value: PsiAnnotationMemberValue?, expectedType: PsiType?, isVararg: Boolean, isUnnamed: Boolean): List<Expression> {
        return when(value) {
            is PsiExpression -> listOf(convertExpression(value as? PsiExpression, expectedType))

            is PsiArrayInitializerMemberValue -> {
                val componentType = (expectedType as? PsiArrayType)?.getComponentType()
                val componentsConverted = value.getInitializers().map { convertAttributeValue(it, componentType, false, true).single() }
                if (isVararg && isUnnamed) {
                    componentsConverted
                }
                else {
                    val expectedTypeConverted = typeConverter.convertType(expectedType)
                    if (expectedTypeConverted is ArrayType) {
                        val array = createArrayInitializerExpression(expectedTypeConverted, componentsConverted, needExplicitType = false)
                        listOf(if (isVararg) StarExpression(array) else array)
                    }
                    else {
                        listOf(DummyStringExpression(value.getText()!!))
                    }
                }
            }

            else -> listOf(DummyStringExpression(value?.getText() ?: ""))
        }
    }

    private fun convertThrows(method: PsiMethod): Annotations {
        val types = method.getThrowsList().getReferencedTypes()
        if (types.isEmpty()) return Annotations.Empty
        return Annotations(listOf(Annotation(Identifier("throws"),
                                             types.map { null to MethodCallExpression.buildNotNull(null, "javaClass", listOf(), listOf(typeConverter.convertType(it, Nullability.NotNull))) },
                                             false)),
                           true)
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
val NULLABLE_ANNOTATIONS: Set<String> = setOf("org.jetbrains.annotations.Nullable", "com.sun.istack.internal.Nullable", "javax.annotation.Nullable")
val ANNOTATIONS_TO_REMOVE: Set<String> = HashSet(NOT_NULL_ANNOTATIONS + NULLABLE_ANNOTATIONS + listOf(CommonClassNames.JAVA_LANG_OVERRIDE))

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
