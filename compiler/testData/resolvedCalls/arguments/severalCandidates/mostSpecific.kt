// !CALL: foo

class A {}

fun foo(a: A) {}
fun foo(a: A, s: String) {}
fun foo(a: A, any: Any) {}

fun bar() {
    foo(A(), "")
}