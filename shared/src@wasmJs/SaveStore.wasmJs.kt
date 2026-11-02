@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.pelotasplus.ishar.data.store

private fun lsGet(key: String): String? = js("localStorage.getItem(key)")

private fun lsSet(key: String, value: String) {
    js("localStorage.setItem(key, value)")
}

private fun lsRemove(key: String) {
    js("localStorage.removeItem(key)")
}

actual fun platformSaveStore(): SaveStore = KeyValueSaveStore(::lsGet, ::lsSet, ::lsRemove)
