package kotlin

public inline fun StringBuilder(body : StringBuilder.()->Unit) : StringBuilder {
    val sb = StringBuilder()
    sb.body()
    return sb
}

