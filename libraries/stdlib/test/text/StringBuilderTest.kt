package test.text

import kotlin.test.*
import org.junit.Test as test

class StringBuilderTest {

    test fun stringBuild() {
        val s = StringBuilder {
            append("a")
            append(true)
        }.toString()
        assertEquals("atrue", s)
    }

    test fun append() {
        // this test is needed for JS implementation
        assertEquals("em", StringBuilder {
            append("element", 2, 4)
        }.toString())
    }
}
