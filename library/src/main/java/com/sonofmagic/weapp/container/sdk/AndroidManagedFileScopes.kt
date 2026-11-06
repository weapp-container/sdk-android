package com.sonofmagic.weapp.container.sdk

import org.json.JSONObject
import java.io.File
import java.util.concurrent.CompletableFuture

/** Only core can select/lease the native account directory. Never derive it from guest options. */
internal class AndroidManagedFileScopes {
    private var session: WeappContainerSession? = null
    private val lock = Any()
    private var generation = 0L
    private val scopes = mutableMapOf<String, JSONObject>()
    fun bind(target: WeappContainerSession) { check(session == null); session = target }
    private fun owner(context: JSONObject): String = listOf(context.getString("sessionId"),
        *listOf("hostAppId", "tenantId", "publisherId", "appId", "environment", "version", "packRoot").map { context.getJSONObject("application").getString(it) }.toTypedArray(),
        context.getJSONObject("account").get("principalId").toString(), context.getJSONObject("account").get("epoch").toString()).joinToString("\u0000")
    fun prepare(context: JSONObject): CompletableFuture<JSONObject> {
        val expected = JSONObject(context.toString()); val accepted = synchronized(lock) { generation }
        return checkNotNull(session).hostServicesCommand("managedFileScope").thenApply { value ->
            val scope = value as? JSONObject ?: error("Managed file scope required")
            for ((group, fields) in listOf("application" to listOf("hostAppId", "tenantId", "publisherId", "appId", "environment", "version", "packRoot"), "account" to listOf("principalId", "epoch"))) {
                require(fields.all { scope.getJSONObject(group).get(it).toString() == expected.getJSONObject(group).get(it).toString() }) { "Stale managed file owner" }
            }
            require(scope.getString("scopeId").matches(Regex("[a-f0-9]{64}")))
            val root = File(scope.getString("root")).canonicalFile; val auxiliary = File(scope.getString("auxiliaryRoot")).canonicalFile
            require(root.name == "user" && auxiliary.name == "native" && root.parentFile == auxiliary.parentFile && root.isDirectory && auxiliary.isDirectory) { "Invalid core-managed file scope" }
            synchronized(lock) { check(accepted == generation) { "Managed file owner retired" }; scopes[owner(expected)] = JSONObject(scope.toString()) }
            scope
        }
    }
    private fun scope(context: JSONObject) = synchronized(lock) { checkNotNull(scopes[owner(context)]) { "Prepare the current core-managed file scope first" } }
    fun root(context: JSONObject) = File(scope(context).getString("root")).canonicalFile
    fun auxiliaryRoot(context: JSONObject) = File(scope(context).getString("auxiliaryRoot")).canonicalFile
    fun clear() = synchronized(lock) { generation++; scopes.clear() }
}
