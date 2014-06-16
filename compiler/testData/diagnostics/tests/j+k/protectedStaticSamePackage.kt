// FILE: JavaClass.java

public class JavaClass {
    protected static int field;

    protected static String method() {
        return "";
    }
}

// FILE: test.kt

fun test() {
    JavaClass.field
    JavaClass.method()
}
