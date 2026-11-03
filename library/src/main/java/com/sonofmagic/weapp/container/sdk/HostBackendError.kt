package com.sonofmagic.weapp.container.sdk

import org.json.JSONObject
import java.util.Locale

/** Container classification and public API fields are separate namespaces. */
internal data class HostBackendError(
    val code: String,
    val message: String,
    val details: JSONObject,
)

/** A classified identity rejection is ready to serialize without another mapping. */
internal data class HostIdentityError(
    val code: HostErrorCode,
    val message: String,
    val details: JSONObject,
)

// Normalize only for policy decisions. The original backend code and public
// fields remain available to the rejection projection without being rewritten.
private fun hostIdentityFailureKind(code: String?): String? =
    code?.removePrefix("ERR_")?.lowercase(Locale.ROOT)

internal fun shouldRefreshHostIdentityAfterFailure(error: Throwable): Boolean {
    val failure = error as? ContainerException ?: return false
    return sequenceOf(failure.code, failure.details.text("backendCode"))
        .any { hostIdentityFailureKind(it) == "invalid_app_session" }
}

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
internal fun projectHostIdentityError(api: String, code: String?, message: String?, fields: JSONObject?): HostIdentityError {
    val classification = when (hostIdentityFailureKind(code)) {
        "permission_denied", "consent_required", "invalid_host_session", "invalid_app_session", "host_identity_mismatch", "stale_principal" -> HostErrorCode.PERMISSION_DENIED
        "timeout" -> HostErrorCode.TIMEOUT
        "cancelled" -> HostErrorCode.CANCELLED
        "unsupported", "unsupported_capability" -> HostErrorCode.UNSUPPORTED
        else -> HostErrorCode.INVALID_REQUEST
    }
    val resolvedMessage = message?.takeIf { it.isNotBlank() } ?: "Host identity request failed"
    val details = JSONObject(fields?.toString() ?: "{}")
    if (details.text("errMsg") == null) details.put("errMsg", "$api:fail $resolvedMessage")
    if (!details.has("errCode") || details.isNull("errCode")) details.put("errCode", if (classification == HostErrorCode.PERMISSION_DENIED) 2001 else -1)
    return HostIdentityError(classification, resolvedMessage, details)
}

private fun JSONObject.text(key: String): String? = (opt(key) as? String)?.takeIf { it.isNotBlank() }
