package com.sonofmagic.weapp.container.sdk

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/** A real app/account file namespace. Host paths and synchronous fake persistence are never exposed. */
class AndroidManagedFiles(context: Context) : AutoCloseable {
    private val scopes = AndroidManagedFileScopes()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private val objects = mutableMapOf<String, JSONObject>()
    @Volatile private var generation = 0L
    private var closed = false
    val supportedApis = setOf("wx.getFileSystemManager",
        "object:FileSystemManager.access", "object:FileSystemManager.readFile", "object:FileSystemManager.writeFile", "object:FileSystemManager.appendFile", "object:FileSystemManager.mkdir", "object:FileSystemManager.readdir", "object:FileSystemManager.rmdir", "object:FileSystemManager.unlink", "object:FileSystemManager.rename", "object:FileSystemManager.copyFile", "object:FileSystemManager.getFileInfo", "object:FileSystemManager.getSavedFileList", "object:FileSystemManager.removeSavedFile", "object:FileSystemManager.saveFile")
    fun install(session: WeappContainerSession): CompletableFuture<Void> {
        scopes.bind(session)
        return CompletableFuture.allOf(
            session.registerHostResourceCleanup("files-${UUID.randomUUID()}") { generation++; synchronized(lock) { objects.clear(); scopes.clear() } },
            *supportedApis.map { api -> session.registerApiHandler(api) { call, reply ->
                val accepted = generation
                prepareScope(requireNotNull(call.context)).whenComplete { _, preparationError ->
                    if (call.isCancelled || accepted != generation) return@whenComplete
                    if (preparationError != null) { reply.reject(HostErrorCode.PERMISSION_DENIED, preparationError.message ?: "Cannot lease managed files"); return@whenComplete }
                    worker.execute {
                        val result = runCatching { synchronized(lock) { check(!closed && accepted == generation && !call.isCancelled) { "File owner retired" }; withOperationLock(requireNotNull(call.context)) { invoke(api, call, reply) } } }
                        main.post { if (!call.isCancelled && accepted == generation) result.fold(reply::resolve, { error -> reply.reject(HostErrorCode.INVALID_REQUEST, error.message ?: "Native file operation failed", false, JSONObject().put("errCode", 1300002).put("errMsg", "$api:fail ${error.message}")) }) }
                    }
                }
            } }.toTypedArray())
    }
    internal fun prepareScope(context: JSONObject) = scopes.prepare(context)
    internal fun auxiliaryRoot(context: JSONObject) = scopes.auxiliaryRoot(context)
    private fun directory(context: JSONObject) = scopes.root(context)
    fun resolve(path: String, context: JSONObject): File = synchronized(lock) {
        require(path == "wxfile://user" || path.startsWith("wxfile://user/")) { "Only managed wxfile://user paths are accepted" }
        val suffix = if (path == "wxfile://user") "" else path.removePrefix("wxfile://user/")
        require(suffix.length <= 1024 && (suffix.isEmpty() || suffix.split('/').all { it.isNotEmpty() && it != "." && it != ".." && !it.startsWith(".weapp-") && !it.contains('\\') && !it.contains('\u0000') })) { "Unsafe managed file path" }
        val root = directory(context); val target = if (suffix.isEmpty()) root else File(root, suffix).canonicalFile
        require(target == root || target.path.startsWith(root.path + File.separator)) { "Path escaped account namespace" }; target
    }
    fun importTemporary(bytes: ByteArray, extension: String, context: JSONObject): String = synchronized(lock) {
        require(extension.matches(Regex("[a-z0-9]{1,8}")))
        withOperationLock(context) { val path = "wxfile://user/tmp/${UUID.randomUUID()}.$extension"; write(bytes, path, context); path }
    }
    private fun <T> withOperationLock(context: JSONObject, action: () -> T): T {
        val token = NativeBridge.filesLock(File(directory(context), ".weapp-operations.lock").absolutePath)
        try { return action() } finally { NativeBridge.filesUnlock(token) }
    }
    private fun write(bytes: ByteArray, path: String, context: JSONObject) {
        require(bytes.size <= 8 * 1048576) { "File exceeds 8 MiB" }
        val destination = resolve(path, context); val root = directory(context)
        val total = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        require(total - (if (destination.isFile) destination.length() else 0) + bytes.size <= 32 * 1048576) { "Account file quota exceeded" }
        check(destination.parentFile!!.mkdirs() || destination.parentFile!!.isDirectory)
        val temporary = File(destination.parentFile, ".write-${UUID.randomUUID()}")
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
    private fun hash(algorithm: String, data: ByteArray) = MessageDigest.getInstance(algorithm).digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun bytes(value: Any, encoding: String): ByteArray = when (value) {
        is JSONObject -> {
            val binary = value.getJSONObject("__weappBinary"); require(binary.getString("type") == "ArrayBuffer")
            val values = binary.getJSONArray("bytes"); require(values.length() <= 1048576)
            ByteArray(values.length()) { index -> val number = values.getDouble(index); require(number.isFinite() && number == kotlin.math.floor(number) && number in 0.0..255.0); number.toInt().toByte() }
        }
        is String -> when (encoding) { "utf8", "utf-8" -> value.toByteArray(Charsets.UTF_8); "base64" -> android.util.Base64.decode(value, android.util.Base64.NO_WRAP); else -> error("Unsupported file encoding") }
        else -> error("String or ArrayBuffer required")
    }
    private fun invoke(api: String, call: HostCall, reply: HostReply): JSONObject {
        val context = requireNotNull(call.context); val value = call.payload as? JSONObject ?: JSONObject()
        val result = JSONObject().put("errMsg", "$api:ok")
        if (api == "wx.getFileSystemManager") {
            val id = value.getString("objectId"); require(objects.size < 64); objects[id] = JSONObject(context.toString())
            reply.retainUntilCancelled(); call.onCancel = { synchronized(lock) { objects.remove(id) } }; return result.put("objectId", id)
        }
        if (api.startsWith("object:")) {
            val owner = requireNotNull(objects[value.getString("objectId")]) { "File manager retired" }
            require(owner.getString("sessionId") == context.getString("sessionId") && owner.getJSONObject("account").get("epoch").toString() == context.getJSONObject("account").get("epoch").toString() && listOf("hostAppId", "tenantId", "publisherId", "appId", "environment", "version", "packRoot").all { owner.getJSONObject("application").get(it) == context.getJSONObject("application").get(it) })
        }
        fun path(name: String = "filePath") = resolve(value.getString(name), context)
        when (val action = api.substringAfterLast('.')) {
            "access" -> require(path("path").exists()) { "File does not exist" }
            "writeFile", "appendFile" -> {
                var data = bytes(value.get("data"), value.optString("encoding", "utf8")); require(data.size <= 1048576) { "Write chunk exceeds 1 MiB" }
                if (action == "appendFile") data = path().readBytes() + data
                write(data, value.getString("filePath"), context)
            }
            "readFile" -> {
                val file = path(); val position = value.optLong("position", 0); val length = value.optInt("length", 1048576)
                require(position in 0..(8 * 1048576).toLong() && length in 0..1048576); require(value.has("length") || file.length() - position <= 1048576) { "Read requires a bounded length" }
                val data = RandomAccessFile(file, "r").use { input -> input.seek(position); val bytes = ByteArray(minOf(length.toLong(), maxOf(0, input.length() - position)).toInt()); input.readFully(bytes); bytes }
                result.put("data", when (value.optString("encoding")) {
                    "" -> JSONObject().put("__weappBinary", JSONObject().put("type", "ArrayBuffer").put("bytes", JSONArray().apply { data.forEach { put(it.toInt() and 255) } }))
                    "utf8", "utf-8" -> Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(data)).toString()
                    "base64" -> android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
                    else -> error("Unsupported read encoding")
                })
            }
            "mkdir" -> { val target = path("dirPath"); check(if (value.optBoolean("recursive")) target.mkdirs() || target.isDirectory else target.mkdir()) }
            "readdir" -> result.put("files", JSONArray(requireNotNull(path("dirPath").list()).filter { !it.startsWith(".weapp-") }.sorted()))
            "unlink", "removeSavedFile", "rmdir" -> {
                val file = path(if (action == "rmdir") "dirPath" else "filePath"); require(file != directory(context))
                if (action == "rmdir") { require(file.isDirectory); check(if (value.optBoolean("recursive")) file.deleteRecursively() else file.delete()) }
                else { require(file.isFile); check(file.delete()) }
            }
            "copyFile" -> write(path("srcPath").readBytes(), value.getString("destPath"), context)
            "rename" -> Files.move(path("oldPath").toPath(), path("newPath").toPath(), StandardCopyOption.ATOMIC_MOVE)
            "saveFile" -> { val destination = value.optString("filePath", "wxfile://user/saved/${UUID.randomUUID()}"); write(path("tempFilePath").readBytes(), destination, context); result.put("savedFilePath", destination) }
            "getFileInfo" -> { val bytes = path().readBytes(); require(bytes.size <= 8 * 1048576); val algorithm = value.optString("digestAlgorithm", "md5"); require(algorithm in setOf("md5", "sha1")); result.put("size", bytes.size).put("digest", hash(if (algorithm == "md5") "MD5" else "SHA-1", bytes)) }
            "getSavedFileList" -> result.put("fileList", JSONArray((resolve("wxfile://user/saved", context).listFiles() ?: emptyArray()).filter { it.isFile }.map { JSONObject().put("filePath", "wxfile://user/saved/${it.name}").put("size", it.length()).put("createTime", Files.readAttributes(it.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).creationTime().toMillis() / 1000) }))
            else -> error("File operation not installed")
        }
        return result
    }
    override fun close() { generation++; synchronized(lock) { closed = true; objects.clear(); scopes.clear() }; worker.shutdownNow() }
}
