package com.manette.app.protocol

import com.manette.app.json.Json
import java.io.File

/**
 * A tiny JSON reader for the shared test vectors. Android unit tests only get a stubbed org.json, and this
 * avoids adding a dependency just to read one file. Maps are LinkedHashMap, arrays are List, numbers are Long
 * or Double.
 */
/** Typed access to the parsed vectors. */
@Suppress("UNCHECKED_CAST")
class Node(private val v: Any?) {
    operator fun get(key: String): Node = Node((v as Map<String, Any?>).getValue(key))
    operator fun get(index: Int): Node = Node((v as List<Any?>)[index])
    fun has(key: String): Boolean = (v as Map<String, Any?>).containsKey(key)
    val items: List<Node> get() = (v as List<Any?>).map { Node(it) }
    val string: String get() = v as String
    val long: Long get() = v as Long
    val int: Int get() = (v as Long).toInt()
    val boolean: Boolean get() = v as Boolean
    val bytes: ByteArray get() = Hex.decode(string)
}

object Vectors {
    val root: Node by lazy { Node(Json.parse(find().readText())) }

    /** Finds protocol/test-vectors.json by walking up from the working directory (Gradle runs tests in android/app). */
    private fun find(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "protocol/test-vectors.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("protocol/test-vectors.json not found above ${File("").absolutePath}")
    }

    val secret get() = root["fixed_inputs"]["pairing_secret"].bytes
    val deviceId get() = DeviceId(root["fixed_inputs"]["device_id"].bytes)
    val clientNonce get() = root["fixed_inputs"]["client_nonce"].bytes
    val serverNonce get() = root["fixed_inputs"]["server_nonce"].bytes
    val otherClientNonce get() = root["fixed_inputs"]["other_client_nonce"].bytes
    val sessionId: Long get() = root["fixed_inputs"]["session_id"].long
    val deviceKey get() = root["derived_keys"]["device_key"].bytes
    val sessionKey get() = root["derived_keys"]["session_key"].bytes
}
