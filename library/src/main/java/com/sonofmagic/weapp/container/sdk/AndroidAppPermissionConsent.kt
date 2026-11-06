package com.sonofmagic.weapp.container.sdk

import android.app.Activity
import android.app.AlertDialog
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Application consent and OS authorization are distinct gates, scoped only from native metadata. */
internal class AndroidAppPermissionConsent(private val activity: () -> Activity, private val auxiliaryRoot: (JSONObject) -> File) {
    private val pending = mutableMapOf<String, () -> Unit>()
    private fun key(call: HostCall, scope: String): String {
        val context = requireNotNull(call.context)
        val app = context.getJSONObject("application"); val account = context.getJSONObject("account")
        val fields = JSONArray()
        for (name in listOf("hostAppId", "tenantId", "publisherId", "appId", "environment")) fields.put(app.getString(name))
        fields.put(account.opt("principalId")).put(account.get("epoch")).put(scope)
        if (account.isNull("principalId")) fields.put(context.getString("sessionId"))
        return MessageDigest.getInstance("SHA-256").digest(fields.toString().toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun record(call: HostCall, scope: String): File {
        val root = auxiliaryRoot(requireNotNull(call.context)); val file = File(root, "consent-${key(call, scope)}.json").canonicalFile
        require(file.parentFile == root); return file
    }
    private fun read(file: File): Boolean? = if (file.isFile) when (file.readText()) { "true" -> true; "false" -> false; else -> error("Invalid native consent record") } else null
    private fun write(file: File, value: Boolean) {
        val temporary = File(file.parentFile, ".consent-${java.util.UUID.randomUUID()}")
        try {
            FileOutputStream(temporary).use { it.write(value.toString().toByteArray()); it.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
    fun setting(call: HostCall, scope: String, osGranted: Boolean): Boolean? {
        return read(record(call, scope))?.let { it && osGranted }
    }
    fun request(scope: String, call: HostCall, completion: (Boolean) -> Unit) {
        if (call.isCancelled) return
        val record = record(call, scope)
        if (read(record) == true) { completion(true); return }
        val host = activity(); check(!host.isFinishing && !host.isDestroyed) { "Visible host Activity required for app consent" }
        val app = requireNotNull(call.context).getJSONObject("application")
        var settled = false
        var dialog: AlertDialog? = null
        fun finish(granted: Boolean, remember: Boolean) {
            if (settled) return; settled = true; pending.remove(call.id)
            dialog?.dismiss()
            val persisted = !remember || runCatching { write(record, granted) }.isSuccess
            if (!call.isCancelled) completion(granted && persisted)
        }
        dialog = AlertDialog.Builder(host).setTitle("小程序权限申请")
            .setMessage("${app.getString("appId")}（${app.getString("publisherId")}）申请使用 $scope。此授权仅适用于当前账号和此小程序。")
            .setPositiveButton("允许") { _, _ -> finish(true, true) }.setNegativeButton("拒绝") { _, _ -> finish(false, true) }
            .setOnCancelListener { finish(false, false) }.create()
        pending[call.id] = { finish(false, false) }
        call.addCancellationHandler { finish(false, false) }; dialog.show()
    }
    fun reset() { for (finish in pending.values.toList()) finish(); pending.clear() }
}
