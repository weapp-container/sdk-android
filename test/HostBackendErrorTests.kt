package com.sonofmagic.weapp.container.sdk

import org.json.JSONObject

fun main() {
    fun reject(error: HostIdentityError): JSONObject {
        var completed: JSONObject? = null
        val reply = HostReply { completed = it }
        reply.reject(error.code, error.message, false, error.details)
        return checkNotNull(completed).getJSONObject("error")
    }
    val plain = parseHostBackendError(
        JSONObject()
            .put("error", "invalid_app_session")
            .put("message", "expired")
            .put("errCode", 4017)
            .put("errMsg", "login:fail expired")
            .put("traceId", "trace-1"),
    )
    check(plain.code == "invalid_app_session" && plain.message == "expired")
    check(plain.details.getInt("errCode") == 4017 && plain.details.getString("errMsg") == "login:fail expired")
    check(plain.details.getString("traceId") == "trace-1")

    val nested = parseHostBackendError(
        JSONObject()
            .put("code", "envelope-code")
            .put("message", "envelope message")
            .put("errCode", 10)
            .put("errMsg", "envelope:fail")
            .put("requestId", "request-1")
            .put("collision", "envelope")
            .put("details", JSONObject().put("collision", "envelope-details").put("envelopeDetails", true))
            .put("error", JSONObject()
                .put("code", "consent_required")
                .put("message", "consent required")
                .put("errCode", 4017)
                .put("errMsg", "login:fail custom backend message")
                .put("collision", "nested")
                .put("details", JSONObject().put("collision", "nested-details").put("provider", "identity-provider").put("errCode", 12))),
    )
    check(nested.code == "consent_required" && nested.message == "consent required")
    val reply = projectHostIdentityError("wx.login", nested.code, nested.message, nested.details)
    check(reply.code == HostErrorCode.PERMISSION_DENIED && reply.message == "consent required")
    check(reply.details.getInt("errCode") == 4017)
    check(reply.details.getString("errMsg") == "login:fail custom backend message")
    check(reply.details.getString("requestId") == "request-1")
    check(reply.details.getString("provider") == "identity-provider")
    check(reply.details.getBoolean("envelopeDetails"))
    check(reply.details.getString("collision") == "nested")
    check(listOf("code", "message", "error", "details").none { reply.details.has(it) })
    val completion = reject(reply)
    check(completion.getString("code") == "permission_denied" && completion.getString("message") == "consent required")
    check(completion.getJSONObject("details").getInt("errCode") == 4017)
    check(completion.getJSONObject("details").getString("errMsg") == "login:fail custom backend message")
    reply.details.put("provider", "changed by receiver")
    check(nested.details.getString("provider") == "identity-provider")

    val detailsOnly = parseHostBackendError(JSONObject().put("error", JSONObject()
        .put("details", JSONObject().put("errCode", 4017).put("errMsg", "checkSession:fail provider"))))
    check(detailsOnly.code == "ERR_HOST_SERVICES" && detailsOnly.message == "checkSession:fail provider")
    val detailsReply = projectHostIdentityError("wx.checkSession", detailsOnly.code, detailsOnly.message, detailsOnly.details)
    check(detailsReply.code == HostErrorCode.INVALID_REQUEST && detailsReply.details.getInt("errCode") == 4017)
    check(detailsReply.details.getString("errMsg") == "checkSession:fail provider")

    for (badCode in listOf(JSONObject(), JSONObject.NULL, false, 4017, "")) {
        val unknown = parseHostBackendError(JSONObject().put("error", badCode))
        check(unknown.code == "ERR_HOST_SERVICES" && unknown.message == "Host services request failed")
    }
    val unknownReply = projectHostIdentityError("wx.login", "unknown_provider_code", null, null)
    check(unknownReply.code == HostErrorCode.INVALID_REQUEST && unknownReply.details.getInt("errCode") == -1)
    check(unknownReply.details.getString("errMsg") == "wx.login:fail Host identity request failed")
    check(reject(unknownReply).getJSONObject("details").getInt("errCode") == -1)
    val permissionReply = projectHostIdentityError("wx.login", "invalid_app_session", "expired", JSONObject()
        .put("errCode", JSONObject.NULL).put("errMsg", JSONObject.NULL))
    check(permissionReply.code == HostErrorCode.PERMISSION_DENIED && permissionReply.details.getInt("errCode") == 2001)
    check(permissionReply.details.getString("errMsg") == "wx.login:fail expired")

    for (wire in listOf("invalid_request", "permission_denied", "timeout", "cancelled", "unsupported")) {
        for (code in listOf(wire, "ERR_" + wire.uppercase(java.util.Locale.ROOT))) {
            val provider = parseHostBackendError(JSONObject().put("error", JSONObject()
                .put("code", code).put("message", "classified backend failure")
                .put("errCode", 4017).put("errMsg", "login:fail provider original")
                .put("providerId", "identity-provider")))
            val classified = projectHostIdentityError("wx.login", provider.code, provider.message, provider.details)
            val wireError = reject(classified)
            check(wireError.getString("code") == wire) { "$code lost its container classification" }
            check(wireError.getJSONObject("details").getInt("errCode") == 4017)
            check(wireError.getJSONObject("details").getString("errMsg") == "login:fail provider original")
            check(wireError.getJSONObject("details").getString("providerId") == "identity-provider")
        }
    }
    for (code in listOf("unsupported_capability", "ERR_UNSUPPORTED_CAPABILITY")) {
        val provider = parseHostBackendError(JSONObject("""
            {"error":{"code":"$code","message":"Runtime capability unavailable",
                "errCode":4017,"errMsg":"login:fail provider original","capability":"identity"}}
        """))
        val wireError = reject(projectHostIdentityError("wx.login", provider.code, provider.message, provider.details))
        check(wireError.getString("code") == "unsupported") { "$code lost the core unsupported classification" }
        check(wireError.getJSONObject("details").getInt("errCode") == 4017)
        check(wireError.getJSONObject("details").getString("errMsg") == "login:fail provider original")
        check(wireError.getJSONObject("details").getString("capability") == "identity")
    }
    for (alias in listOf("consent_required", "invalid_host_session", "invalid_app_session", "host_identity_mismatch", "stale_principal")) {
        for (code in listOf(alias, "ERR_" + alias.uppercase(java.util.Locale.ROOT))) {
            val wireError = reject(projectHostIdentityError("wx.login", code, "authorization rejected", null))
            check(wireError.getString("code") == "permission_denied")
            check(wireError.getJSONObject("details").getInt("errCode") == 2001)
        }
    }

    for ((code, backendCode) in listOf(
        "invalid_app_session" to null,
        "ERR_INVALID_APP_SESSION" to null,
        "permission_denied" to "invalid_app_session",
        "ERR_PERMISSION_DENIED" to "ERR_INVALID_APP_SESSION",
    )) {
        val fields = JSONObject().put("errCode", 4017).put("errMsg", "checkSession:fail original provider message")
            .put("providerId", "reference-provider")
        if (backendCode != null) fields.put("backendCode", backendCode)
        val original = ContainerException(code, "Application session expired", details = fields)
        check(shouldRefreshHostIdentityAfterFailure(original)) { "$code/$backendCode did not refresh revoked application state" }
        check(original.code == code && original.details.toString() == fields.toString())
        val rejected = reject(projectHostIdentityError("wx.checkSession", original.code, original.message, original.details))
        check(rejected.getString("code") == "permission_denied")
        check(rejected.getString("message") == original.message)
        check(rejected.getJSONObject("details").getInt("errCode") == 4017)
        check(rejected.getJSONObject("details").getString("errMsg") == "checkSession:fail original provider message")
        check(rejected.getJSONObject("details").getString("providerId") == "reference-provider")
        if (backendCode != null) check(rejected.getJSONObject("details").getString("backendCode") == backendCode)
    }
    for (code in listOf("permission_denied", "invalid_host_session", "consent_required", "ERR_TIMEOUT", "ERR_CANCELLED", "ERR_HOST_SERVICES"))
        check(!shouldRefreshHostIdentityAfterFailure(ContainerException(code, "Other failure")))
    check(!shouldRefreshHostIdentityAfterFailure(IllegalStateException("invalid_app_session")))
    check(!shouldRefreshHostIdentityAfterFailure(ContainerException("permission_denied", "Other failure", details = JSONObject().put("backendCode", 4017))))

    val fallback = parseHostBackendError(JSONObject().put("errMsg", "backend unavailable"))
    check(fallback.code == "ERR_HOST_SERVICES" && fallback.message == "backend unavailable")
    println("Android identity rejection preserves nested errCode/errMsg, extension precedence and independent container classification")
}
