package com.sonofmagic.weapp.container.sdk

import org.json.JSONObject

/** Backend error projection that retains the response's public extension fields. */
internal data class HostBackendError(
    val code: String,
    val message: String,
    val details: JSONObject,
)

internal fun parseHostBackendError(response: JSONObject): HostBackendError {
    val nested = response.optJSONObject("error")
    val code = nested?.optString("code")?.takeIf { it.isNotBlank() }
        ?: response.optString("code").takeIf { it.isNotBlank() }
        ?: response.optString("error").takeIf { it.isNotBlank() }
        ?: "ERR_HOST_SERVICES"
    val message = nested?.optString("message")?.takeIf { it.isNotBlank() }
        ?: response.optString("message").takeIf { it.isNotBlank() }
        ?: response.optString("errMsg").takeIf { it.isNotBlank() }
        ?: "Host services request failed"
    return HostBackendError(code, message, JSONObject(response.toString()))
}
