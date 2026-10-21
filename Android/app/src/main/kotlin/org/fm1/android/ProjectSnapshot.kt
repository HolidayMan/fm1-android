package org.fm1.android

import android.util.Base64
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Compatible musical workspace envelope. Projects omit personal global settings. */
internal class ProjectSnapshot {
    var master = 716
    val parts = linkedMapOf<String, ByteArray>()

    fun validate() {
        val names =
            setOf("flash", "arrangement") + (0..11).map { "object$it" } + (0..3).map { "sample$it" }
        if (master !in 0..1023 || parts.keys != names)
            throw IOException("Unsupported or incomplete SLOOP workspace.")
        if (parts.getValue("flash").size != 1048576 || parts.getValue("object0").isEmpty())
            throw IOException("The workspace is incomplete.")
        if (parts.getValue("arrangement").size !in 13..43)
            throw IOException("Invalid song arrangement.")
        for ((name, data) in parts) {
            val limit =
                when {
                    name == "flash" -> 1048576
                    name.startsWith("sample") -> 81920
                    else -> 65536
                }
            if (data.size > limit) throw IOException("Oversized workspace component.")
        }
        for (i in 0..3) if (parts.getValue("sample$i").size != 81920)
            throw IOException("Incomplete user sample.")
    }

    fun copy() =
        ProjectSnapshot().also { copy ->
            copy.master = master
            parts.forEach { (name, data) -> copy.parts[name] = data.clone() }
        }

    fun projectPayload() =
        copy().also { copy ->
            copy.master = 716
            copy.parts["flash"] = ByteArray(1048576) { 0xff.toByte() }
            for (i in intArrayOf(1, 10, 11)) copy.parts["object$i"] = byteArrayOf()
        }

    fun overlay(current: ProjectSnapshot): ProjectSnapshot {
        validate()
        current.validate()
        return current.copy().also { copy ->
            parts.forEach { (name, data) ->
                if (
                    name == "object0" ||
                        name == "arrangement" ||
                        name.startsWith("sample") ||
                        name.matches(Regex("object[2-9]"))
                )
                    copy.parts[name] = data.clone()
            }
        }
    }

    fun envelope(name: String, backup: Boolean): ByteArray {
        if (name.trim().isEmpty() || name.length > 120)
            throw IOException("Give this project a name up to 120 characters.")
        val snapshot = if (backup) this else projectPayload()
        snapshot.validate()
        val array = JSONArray()
        snapshot.parts.forEach { (partName, data) ->
            array.put(
                JSONObject()
                    .put("name", partName)
                    .put("bytes", Base64.encodeToString(data, Base64.NO_WRAP))
                    .put("sha256", digest(data))
            )
        }
        val body =
            JSONObject()
                .put("version", 1)
                .put("packageSHA256", PACKAGE)
                .put("master", snapshot.master)
                .put("parts", array)
        return JSONObject()
            .put("format", "FM1 SLOOP workspace")
            .put("version", 1)
            .put("id", UUID.randomUUID().toString())
            .put("name", name.trim())
            .put("createdAt", System.currentTimeMillis() / 1000.0 - 978307200)
            .put("kind", if (backup) "backup" else "project")
            .put("snapshot", body)
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    fun create(packageBytes: ByteArray): Long {
        validate()
        if (digest(packageBytes) != PACKAGE) throw IOException("Wrong SLOOP package.")
        val machine = NativeEngine.create(packageBytes, 2)
        try {
            if (!NativeEngine.restore(machine, parts.getValue("flash")))
                throw IOException("Workspace storage could not be restored.")
            NativeEngine.transport(machine, false)
            for (id in (1..11).toList() + 0) if (
                NativeEngine.writePart(machine, 0, id, parts.getValue("object$id")) != 0
            )
                throw IOException("SLOOP rejected a project object.")
            if (NativeEngine.commit(machine) != 0) throw IOException("Project commit failed.")
            if (NativeEngine.writePart(machine, 1, 0, parts.getValue("arrangement")) != 0)
                throw IOException("SLOOP rejected the song arrangement.")
            for (i in 0..3) {
                val data = parts.getValue("sample$i")
                if (
                    NativeEngine.writePart(machine, 2, i, data) != 0 ||
                        !data.contentEquals(NativeEngine.readPart(machine, 2, i))
                )
                    throw IOException("Sample verification failed.")
            }
            if (NativeEngine.commit(machine) != 0) throw IOException("Project commit failed.")
            NativeEngine.volume(machine, master)
            return machine
        } catch (error: Exception) {
            NativeEngine.destroy(machine)
            throw error
        }
    }

    companion object {
        const val LEGACY_PACKAGE =
            "7c9d269d15d1c1a48e0ebada7ffa2c2aa196c19d51056fd3f760d322322b335f"
        const val PACKAGE = "fb69b4311790eb7ec09498be16ae2b71bd681840e221c0d8cfdb26fb5f9a2928"

        fun digest(bytes: ByteArray) =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(Locale.ROOT, it.toInt() and 255)
            }

        fun capture(machine: Long, master: Int): ProjectSnapshot {
            if (NativeEngine.kind(machine) != 2) throw IOException("SLOOP 2.8 is required.")
            return ProjectSnapshot().also { snapshot ->
                snapshot.master = master
                snapshot.parts["flash"] = NativeEngine.flash(machine)
                val arrangement = NativeEngine.readPart(machine, 1, 0)
                if (
                    arrangement.size < 13 ||
                        arrangement[4].toInt() != 0 ||
                        arrangement[5].toInt() != 0
                )
                    throw IOException(
                        "Stop the song and finish SONG REC before saving or exporting."
                    )
                for (i in intArrayOf(1, 4, 5, 6, 7)) arrangement[i] = 0
                snapshot.parts["arrangement"] = arrangement
                for (i in 0..11) snapshot.parts["object$i"] = NativeEngine.readPart(machine, 0, i)
                for (i in 0..3) snapshot.parts["sample$i"] = NativeEngine.readPart(machine, 2, i)
                snapshot.validate()
            }
        }

        fun fromEnvelope(bytes: ByteArray, filename: String): ProjectSnapshot {
            if (bytes.size !in 1..4 * 1024 * 1024) throw IOException("Invalid workspace file size.")
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            val kind = root.getString("kind")
            if (
                root.getString("format") != "FM1 SLOOP workspace" ||
                    root.getInt("version") != 1 ||
                    root.getString("name").isEmpty() ||
                    root.getString("name").length > 120 ||
                    kind !in setOf("project", "backup") ||
                    !filename
                        .lowercase(Locale.ROOT)
                        .endsWith(if (kind == "backup") ".fm1backup" else ".fm1project")
            )
                throw IOException("The file type does not match its project or backup contents.")
            val snapshot = root.getJSONObject("snapshot")
            if (
                snapshot.getInt("version") != 1 ||
                    snapshot.getString("packageSHA256") !in setOf(PACKAGE, LEGACY_PACKAGE)
            )
                throw IOException("This workspace needs a supported SLOOP 2.7.1 or 2.8 release.")
            return ProjectSnapshot().also { result ->
                result.master = snapshot.getInt("master")
                val array = snapshot.getJSONArray("parts")
                if (array.length() != 18) throw IOException("Incomplete workspace components.")
                for (i in 0 until array.length()) {
                    val part = array.getJSONObject(i)
                    val name = part.getString("name")
                    val data = Base64.decode(part.getString("bytes"), Base64.DEFAULT)
                    if (result.parts.containsKey(name) || digest(data) != part.getString("sha256"))
                        throw IOException("A workspace checksum failed.")
                    result.parts[name] = data
                }
                result.validate()
            }
        }
    }
}
