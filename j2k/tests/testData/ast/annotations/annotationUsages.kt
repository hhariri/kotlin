import javaApi.*

Anon1(value = array<String>("a"), stringArray = array<String>("b"), intArray = intArray(1, 2), string = "x")
Anon2(value = "a", intValue = 1, charValue = 'a')
Anon3(e = E.A, stringArray = array<String>(), value = *array<String>("a", "b"))
Anon4("x", "y")
Anon5(1)
Anon6(array<String>("x", "y"))
class C() {
    Anon5(1) Deprecated private var field1: Int = 0

    Anon5(1)
    private var field2: Int = 0

    Anon5(1) var field3: Int = 0

    Anon5(1)
    var field4: Int = 0

    Anon6(array<String>())
    fun foo(Deprecated p1: Int, Deprecated Anon5(2) p2: Char) {
        [Deprecated] [Anon5(3)] val c = 'a'
    }

    Anon5(1) fun bar() {
    }
}
