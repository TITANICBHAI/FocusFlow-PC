package kotlinx.coroutines.flow

// Minimal compile-only stand-ins so the REAL Database.kt can be built without kotlinx-coroutines
// (Maven Central is blocked in this sandbox). Semantics used by Database.kt: .value get/set only.
interface StateFlow<out T> { val value: T }
interface MutableStateFlow<T> : StateFlow<T> { override var value: T }
fun <T> MutableStateFlow(value: T): MutableStateFlow<T> = object : MutableStateFlow<T> {
    @Volatile override var value: T = value
}
fun <T> MutableStateFlow<T>.asStateFlow(): StateFlow<T> = this
