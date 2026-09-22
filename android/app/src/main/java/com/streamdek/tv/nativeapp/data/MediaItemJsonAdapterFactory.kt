package com.streamdek.tv.nativeapp.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter

/** Gson bypasses Kotlin defaults. Apply the optional headers default before constructing the item. */
class MediaItemJsonAdapterFactory : TypeAdapterFactory {
    override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        if (type.rawType != MediaItem::class.java) return null
        val delegate = gson.getDelegateAdapter(this, type)
        val treeAdapter = gson.getAdapter(com.google.gson.JsonElement::class.java)
        return object : TypeAdapter<T>() {
            override fun read(reader: JsonReader): T {
                val tree = treeAdapter.read(reader)
                if (!tree.isJsonObject) throw JsonParseException("MediaItem must be an object")
                val obj = tree.asJsonObject
                for (field in listOf("id", "title")) {
                    if (!obj.has(field) || obj.get(field).isJsonNull) {
                        throw JsonParseException("MediaItem missing required field: $field")
                    }
                }
                if ((!obj.has("type") || obj.get("type").isJsonNull) &&
                    (!obj.has("mediaType") || obj.get("mediaType").isJsonNull)) {
                    throw JsonParseException("MediaItem missing required field: type")
                }
                // Remove null aliases so they cannot overwrite the valid discriminator.
                for (field in listOf("type", "mediaType")) {
                    if (obj.get(field)?.isJsonNull == true) obj.remove(field)
                }
                if (!obj.has("requestHeaders") || obj.get("requestHeaders").isJsonNull) {
                    obj.add("requestHeaders", JsonObject())
                }
                return delegate.fromJsonTree(obj)
            }

            override fun write(writer: JsonWriter, value: T) = delegate.write(writer, value)
        }
    }
}
