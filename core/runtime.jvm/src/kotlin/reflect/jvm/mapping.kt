/*
 * Copyright 2010-2014 JetBrains s.r.o.
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

package kotlin.reflect.jvm

import java.lang.reflect.*
import kotlin.reflect.jvm.internal.*

// Kotlin reflection -> Java reflection


public val <T> KClass<T>.java: Class<T>
    get() = (this as KClassImpl<T>).jClass

public val KPackage.java: Class<*>
    get() = (this as KPackageImpl).jClass



public val KTopLevelProperty<*>.javaGetter: Method
    get() = (this as KTopLevelPropertyImpl<*>).getter

public val KMutableTopLevelProperty<*>.javaSetter: Method
    get() = (this as KMutableTopLevelPropertyImpl<*>).setter


public val KExtensionProperty<*, *>.javaGetter: Method
    get() = (this as KExtensionPropertyImpl<*, *>).getter

public val KMutableExtensionProperty<*, *>.javaSetter: Method
    get() = (this as KMutableExtensionPropertyImpl<*, *>).setter


public val KMemberProperty<*, *>.javaGetter: Method?
    get() = (this as KMemberPropertyImpl<*, *>).getter

public val KMutableMemberProperty<*, *>.javaSetter: Method?
    get() = (this as KMutableMemberPropertyImpl<*, *>).setter

public val KMemberProperty<*, *>.javaField: Field?
    get() = (this as KMemberPropertyImpl<*, *>).field


// Java reflection -> Kotlin reflection


public val <T> Class<T>.kotlinClass: KClass<T>?
    get() = kotlin.kClass as KClass<T>?

public val Class<*>.kotlinPackage: KPackage?
    get() = kotlin.kPackage

private val Class<*>.kotlin: KClassOrPackage
    get() {
        for (annotation in getDeclaredAnnotations()) {
            val name = (annotation as java.lang.annotation.Annotation?)?.annotationType()?.getName()
            if (name == "kotlin.jvm.internal.KotlinPackage") {
                return KClassOrPackage(null, kPackage(this))
            }
            else if (name == "kotlin.jvm.internal.KotlinClass") {
                return KClassOrPackage(kClass(this as Class<Any?>), null)
            }
        }
        return KClassOrPackage(kClass(this as Class<Any?>), null)
    }

private data class KClassOrPackage(val kClass: KClass<*>?, val kPackage: KPackage?)



public val Field.kotlin: KProperty<*>
    get() {
        val (kClass, kPackage) = getDeclaringClass().kotlin
        val name = getName()!!
        if (kPackage != null) {
            kPackage as KPackageImpl
            return if (Modifier.isFinal(getModifiers())) topLevelProperty(name, kPackage)
                    else mutableTopLevelProperty(name, kPackage)
        }
        else if (kClass != null) {
            kClass as KClassImpl<Any?>
            return if (Modifier.isFinal(getModifiers())) memberProperty(name, kClass)
                    else mutableMemberProperty(name, kClass)
        }
        else {
            throw IllegalArgumentException("$this is not available to Kotlin reflection")
        }
    }



