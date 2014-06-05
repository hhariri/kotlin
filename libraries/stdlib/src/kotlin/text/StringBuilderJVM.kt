package kotlin

val LINE_SEPARATOR: String = System.getProperty("line.separator")!!
public fun Appendable.appendln(): Appendable = append(LINE_SEPARATOR)
public fun Appendable.appendln(value : StringBuffer?): Appendable = append(value).append(LINE_SEPARATOR)
public fun Appendable.appendln(value : CharSequence?): Appendable = append(value).append(LINE_SEPARATOR)
public fun Appendable.appendln(value : Char): Appendable = append(value).append(LINE_SEPARATOR)
public fun Appendable.appendln(value : String?): Appendable = append(value).append(LINE_SEPARATOR)

public fun StringBuilder.appendln(): StringBuilder = append(LINE_SEPARATOR)
public fun StringBuilder.appendln(value : StringBuffer?): StringBuilder = append(value).append(LINE_SEPARATOR)
public fun StringBuilder.appendln(value : CharSequence?): StringBuilder = append(value).append(LINE_SEPARATOR)
public fun StringBuilder.appendln(value : Char): StringBuilder = append(value).append(LINE_SEPARATOR)
public fun StringBuilder.appendln(value : String?): StringBuilder = append(value).append(LINE_SEPARATOR)
public fun StringBuilder.appendln(value : Any?): StringBuilder = append(value).append(LINE_SEPARATOR)
public fun StringBuilder.appendln(value : StringBuilder?): StringBuilder = append(value).append(LINE_SEPARATOR)
public fun StringBuilder.appendln(value : CharArray?): StringBuilder = append(value).append(LINE_SEPARATOR)
public fun StringBuilder.appendln(value : Boolean): StringBuilder = append(value).append(LINE_SEPARATOR)
public fun StringBuilder.appendln(value : Int): StringBuilder = append(value).append(LINE_SEPARATOR)
public fun StringBuilder.appendln(value : Long): StringBuilder = append(value).append(LINE_SEPARATOR)
public fun StringBuilder.appendln(value : Float): StringBuilder = append(value).append(LINE_SEPARATOR)
public fun StringBuilder.appendln(value : Double): StringBuilder = append(value).append(LINE_SEPARATOR)
