package com.sonofmagic.weapp.container.sdk

import org.json.JSONObject

/** Container classification and public API fields are separate namespaces. */
internal data class HostBackendError(
    val code: String,
    val message: String,
    val details: JSONObject,
)

internal fun parseHostBackendError(response: JSONObject): HostBackendError {
    val nested = response.optJSONObject("error")
    val code = nested?.text("code")
        ?: response.text("code")
        ?: response.text("error")
        ?: "ERR_HOST_SERVICES"
    val details = JSONObject()
    // Higher-priority, non-null fields win: envelope < envelope.details <
    // error.details < error. Container code/message never enter the API fields.
    for (source in listOf(response, response.optJSONObject("details"), nested?.optJSONObject("details"), nested)) {
        if (source == null) continue
        for (key in source.keys()) if (key !in setOf("code", "message", "error", "details")) {
            val value = source.opt(key)
            if (value != null && value !== JSONObject.NULL) details.put(key, value)
        }
    }
    val message = nested?.text("message")
        ?: response.text("message")
        ?: details.text("errMsg")
        ?: "Host services request failed"
    return HostBackendError(code, message, JSONObject(details.toString()))
}

/** The exact projection passed to HostReply.reject, independent of Android UI. */
internal fun projectHostIdentityError(api: String, code: String?, message: String?, fields: JSONObject?): HostBackendError {
    val permission = code in setOf("consent_required", "invalid_host_session", "invalid_app_session", "host_identity_mismatch", "stale_principal")
    val resolvedMessage = message?.takeIf { it.isNotBlank() } ?: "Host identity request failed"
    val details = JSONObject(fields?.toString() ?: "{}")
    if (details.text("errMsg") == null) details.put("errMsg", "$api:fail $resolvedMessage")
    if (!details.has("errCode") || details.isNull("errCode")) details.put("errCode", if (permission) 2001 else -1)
    return HostBackendError(if (permission) "permission_denied" else "invalid_request", resolvedMessage, details)
}

private fun JSONObject.text(key: String): String? = (opt(key) as? String)?.takeIf { it.isNotBlank() }
