package kotlin

/**
 * Adds all elements of the given *iterable* to this [[MutableCollection]]
 */
public fun <T> MutableCollection<in T>.addAll(iterable: Iterable<T>): Unit {
    when (iterable) {
        is Collection -> addAll(iterable)
        else -> for (item in iterable) add(item)
    }
}

/**
 * Adds all elements of the given *stream* to this [[MutableCollection]]
 */
public fun <T> MutableCollection<in T>.addAll(stream: Stream<T>): Unit {
    for (item in stream) add(item)
}

/**
 * Adds all elements of the given *array* to this [[MutableCollection]]
 */
public fun <T> MutableCollection<in T>.addAll(array: Array<T>): Unit {
    for (item in array) add(item)
}

/**
 * Removes all elements of the given *iterable* from this [[MutableCollection]]
 */
public fun <T> MutableCollection<in T>.removeAll(iterable: Iterable<T>): Unit {
    when (iterable) {
        is Collection -> removeAll(iterable)
        else -> for (item in iterable) remove(item)
    }
}

/**
 * Removes all elements of the given *stream* from this [[MutableCollection]]
 */
public fun <T> MutableCollection<in T>.removeAll(stream: Stream<T>): Unit {
    for (item in stream) remove(item)
}

/**
 * Removes all elements of the given *array* from this [[MutableCollection]]
 */
public fun <T> MutableCollection<in T>.removeAll(array: Array<T>): Unit {
    for (item in array) remove(item)
}

/**
 * Retains only elements of the given *iterable* in this [[MutableCollection]]
 */
public fun <T> MutableCollection<in T>.retainAll(iterable: Iterable<T>): Unit {
    when (iterable) {
        is Collection -> retainAll(iterable)
        else -> retainAll(iterable.toSet())

    }
}

/**
 * Retains only elements of the given *array* in this [[MutableCollection]]
 */
public fun <T> MutableCollection<in T>.retainAll(array: Array<T>): Unit {
    retainAll(array.toSet())
}
