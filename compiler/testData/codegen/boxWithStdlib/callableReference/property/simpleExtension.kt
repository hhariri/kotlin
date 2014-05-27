val String.id: String
    get() = this

fun box(): String {
    val pr = String::id

    if (pr["123"] != "123") return "Fail: ${pr["123"]}"

    return pr.get("OK")
}
