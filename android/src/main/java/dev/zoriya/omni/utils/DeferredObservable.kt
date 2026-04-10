package dev.zoriya.omni.utils

import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

inline fun <T : Any> deferredObservable(
    crossinline onChange: (thisRef: Any?, oldValue: T?, newValue: T) -> Unit
): ReadWriteProperty<Any?, T> =
    object : ReadWriteProperty<Any?, T> {
        private var value: T? = null

        override fun getValue(thisRef: Any?, property: KProperty<*>): T {
            return value ?: throw IllegalStateException("Property ${property.name} should be initialized before get.")
        }

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            val old = this.value
            this.value = value
            onChange(old, value)
        }
    }
