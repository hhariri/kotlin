data class Box(var value: String)

fun box(): String {
    val obj = Box("lorem")
    val prop = Box::value

    if (prop.get(obj) != "lorem") return "Fail 1: ${prop[obj]}"
    prop.set(obj, "ipsum")
    if (prop.get(obj) != "ipsum") return "Fail 2: ${prop[obj]}"
    if ("$obj" != "Box(value=ipsum)") return "Fail 3: $obj"

    return "OK"
}
