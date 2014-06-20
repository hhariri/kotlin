import mapping as A

import java.lang.reflect.*
import kotlin.reflect.jvm.*

// TODO: split to several testcases?

fun testJavaFields() {
    val i = A::i
    val s = A::s

    // Check that correct reflection objects are created
    assert(i.javaClass.getSimpleName() == "KMemberPropertyImpl", "Fail i class")
    assert(s.javaClass.getSimpleName() == "KMutableMemberPropertyImpl", "Fail s class")

    // Check that no Method objects are created for such properties
    assert(i.javaGetter == null, "Fail i getter")
    assert(s.javaGetter == null, "Fail s getter")
    assert(s.javaSetter == null, "Fail s setter")

    // Check that correct Field objects are created
    val ji = i.javaField!!
    val js = s.javaField!!
    assert(Modifier.isFinal(ji.getModifiers()), "Fail i final")
    assert(!Modifier.isFinal(js.getModifiers()), "Fail s final")

    // Check that those Field objects work as expected
    val a = A(42, "abc")
    assert(ji.get(a) == 42, "Fail ji get")
    assert(js.get(a) == "abc", "Fail js get")
    js.set(a, "def")
    assert(js.get(a) == "def", "Fail js set")

    // Check that valid Kotlin reflection objects are created by those Field objects
    val ki = ji.kotlin as KMemberProperty<A, Int>
    val ks = js.kotlin as KMutableMemberProperty<A, String>
    assert(ki.get(a) == 42, "Fail ki get")
    assert(ks.get(a) == "def", "Fail ks get")
    ks.set(a, "ghi")
    assert(ks.get(a) == "ghi", "Fail ks set")
}

fun testJavaClass() {
    // Check that KClass is created for Java class, not KPackage
    assert(javaClass<A>().kotlinClass != null, "Fail A class")
    assert(javaClass<A>().kotlinPackage == null, "Fail A package")

    // Check that KPackage is created for Kotlin package, not KClass
    val defaultPackage = Class.forName("_DefaultPackage")!! as Class<Any?>
    assert(defaultPackage.kotlinClass == null, "Fail _DefaultPackage class")
    assert(defaultPackage.kotlinPackage != null, "Fail _DefaultPackage package")
}

class K(var value: Long)

fun testKotlinMemberProperty() {
    val p = K::value

    assert(p.javaField == null, "Fail p field")

    val getter = p.javaGetter!!
    val setter = p.javaSetter!!
    val k = K(42L)
    assert(getter.invoke(k) == 42L, "Fail k getter")
    setter.invoke(k, -239L)
    assert(getter.invoke(k) == -239L, "Fail k setter")
}

var K.ext: Double
    get() = value.toDouble()
    set(value) {
        this.value = value.toLong()
    }

fun testKotlinExtensionProperty() {
    val p = K::ext

    val getter = p.javaGetter!!
    val setter = p.javaSetter!!
    val k = K(42L)
    assert(getter.invoke(null, k) == 42.0, "Fail k getter")
    setter.invoke(null, k, -239.0)
    assert(getter.invoke(null, k) == -239.0, "Fail k setter")
}

var topLevel = "123"

fun testKotlinTopLevelProperty() {
    val p = ::topLevel

    val getter = p.javaGetter!!
    val setter = p.javaSetter!!
    assert(getter.invoke(null) == "123", "Fail k getter")
    setter.invoke(null, "456")
    assert(getter.invoke(null) == "456", "Fail k setter")
}

fun box(): String {
    testJavaFields()
    testJavaClass()
    testKotlinMemberProperty()
    testKotlinExtensionProperty()
    testKotlinTopLevelProperty()
    return "OK"
}
