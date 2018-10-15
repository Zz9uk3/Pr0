package com.pr0gramm.app.util

import android.os.Bundle
import android.os.Parcelable
import com.pr0gramm.app.parcel.Freezable
import com.pr0gramm.app.parcel.putFreezable
import java.util.*
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Eases the Fragment.newInstance ceremony by marking the fragment's args with this delegate
 * Just write the property in newInstance and read it like any other property after the fragment has been created
 *
 * Inspired by Jake Wharton, he mentioned it during his IO/17 talk about Kotlin
 */
private class FragmentArgumentDelegate<T : Any>(val nameOverride: String?, val defaultValue: T?) : ReadWriteProperty<androidx.fragment.app.Fragment, T> {
    @Suppress("UNCHECKED_CAST")
    override operator fun getValue(thisRef: androidx.fragment.app.Fragment, property: KProperty<*>): T {
        val name = nameOverride ?: property.name
        val value = thisRef.arguments?.get(name) as T?
        return value ?: defaultValue ?: throw IllegalStateException("Property $name is not set")
    }

    override operator fun setValue(thisRef: androidx.fragment.app.Fragment, property: KProperty<*>, value: T) {
        val args = thisRef.arguments ?: Bundle().also { thisRef.arguments = it }
        setArgumentValue(args, nameOverride ?: property.name, value)
    }
}

private class OptionalFragmentArgumentDelegate<T : Any>(val nameOverride: String?, val default: T?) : ReadWriteProperty<androidx.fragment.app.Fragment, T?> {
    @Suppress("UNCHECKED_CAST")
    override operator fun getValue(thisRef: androidx.fragment.app.Fragment, property: KProperty<*>): T? {
        val name = nameOverride ?: property.name
        val value = thisRef.arguments?.get(name) as T?
        return value ?: default
    }

    override operator fun setValue(thisRef: androidx.fragment.app.Fragment, property: KProperty<*>, value: T?) {
        val args = thisRef.arguments ?: Bundle().also { thisRef.arguments = it }
        setArgumentValue(args, nameOverride ?: property.name, value)
    }
}

private fun setArgumentValue(args: Bundle, key: String, value: Any?) {
    if (value == null) {
        args.remove(key)
        return
    }

    when (value) {
        is String -> args.putString(key, value)
        is Int -> args.putInt(key, value)
        is Short -> args.putShort(key, value)
        is Long -> args.putLong(key, value)
        is Byte -> args.putByte(key, value)
        is ByteArray -> args.putByteArray(key, value)
        is Char -> args.putChar(key, value)
        is CharArray -> args.putCharArray(key, value)
        is CharSequence -> args.putCharSequence(key, value)
        is Float -> args.putFloat(key, value)
        is Double -> args.putDouble(key, value)
        is Bundle -> args.putBundle(key, value)
        is Parcelable -> args.putParcelable(key, value)
        is Freezable -> args.putFreezable(key, value)
        else -> throw IllegalStateException("Type ${value.javaClass.canonicalName} of property $key is not supported")
    }
}

fun <T : Any> fragmentArgument(name: String? = null): ReadWriteProperty<androidx.fragment.app.Fragment, T> {
    return FragmentArgumentDelegate(name, null)
}

fun <T : Any> fragmentArgumentWithDefault(defaultValue: T, name: String? = null): ReadWriteProperty<androidx.fragment.app.Fragment, T> {
    return FragmentArgumentDelegate(name, defaultValue)
}

fun <T : Any> optionalFragmentArgument(default: T? = null, name: String? = null): ReadWriteProperty<androidx.fragment.app.Fragment, T?> {
    return OptionalFragmentArgumentDelegate(name, default)
}

inline fun <reified T : Enum<T>> enumFragmentArgument(): ReadWriteProperty<androidx.fragment.app.Fragment, T> {
    val delegate = fragmentArgument<Int>()
    val values = EnumSet.allOf(T::class.java).sortedBy { it.ordinal }.toTypedArray()

    return object : ReadWriteProperty<androidx.fragment.app.Fragment, T> {
        override fun getValue(thisRef: androidx.fragment.app.Fragment, property: KProperty<*>): T {
            val o = delegate.getValue(thisRef, property)
            return values[o]
        }

        override fun setValue(thisRef: androidx.fragment.app.Fragment, property: KProperty<*>, value: T) {
            delegate.setValue(thisRef, property, value.ordinal)
        }
    }
}
