package dev.pelotasplus.ishar.data.store

import platform.Foundation.NSUserDefaults

actual fun platformSaveStore(): SaveStore {
    val d = NSUserDefaults.standardUserDefaults
    return KeyValueSaveStore(
        get = { d.stringForKey(it) },
        set = { k, v -> d.setObject(v, forKey = k) },
        remove = { d.removeObjectForKey(it) },
    )
}
