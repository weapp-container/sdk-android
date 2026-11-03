package com.sonofmagic.weapp.container.sdk

import org.json.JSONObject

fun main() {
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
    check(reply.code == "permission_denied" && reply.message == "consent required")
    check(reply.details.getInt("errCode") == 4017)
    check(reply.details.getString("errMsg") == "login:fail custom backend message")
    check(reply.details.getString("requestId") == "request-1")
    check(reply.details.getString("provider") == "identity-provider")
    check(reply.details.getBoolean("envelopeDetails"))
    check(reply.details.getString("collision") == "nested")
    check(listOf("code", "message", "error", "details").none { reply.details.has(it) })
    reply.details.put("provider", "changed by receiver")
    check(nested.details.getString("provider") == "identity-provider")

    val detailsOnly = parseHostBackendError(JSONObject().put("error", JSONObject()
        .put("details", JSONObject().put("errCode", 4017).put("errMsg", "checkSession:fail provider"))))
    check(detailsOnly.code == "ERR_HOST_SERVICES" && detailsOnly.message == "checkSession:fail provider")
    val detailsReply = projectHostIdentityError("wx.checkSession", detailsOnly.code, detailsOnly.message, detailsOnly.details)
    check(detailsReply.code == "invalid_request" && detailsReply.details.getInt("errCode") == 4017)
    check(detailsReply.details.getString("errMsg") == "checkSession:fail provider")

    for (badCode in listOf(JSONObject(), JSONObject.NULL, false, 4017, "")) {
        val unknown = parseHostBackendError(JSONObject().put("error", badCode))
        check(unknown.code == "ERR_HOST_SERVICES" && unknown.message == "Host services request failed")
    }
    val unknownReply = projectHostIdentityError("wx.login", "unknown_provider_code", null, null)
    check(unknownReply.code == "invalid_request" && unknownReply.details.getInt("errCode") == -1)
    check(unknownReply.details.getString("errMsg") == "wx.login:fail Host identity request failed")
    val permissionReply = projectHostIdentityError("wx.login", "invalid_app_session", "expired", JSONObject()
        .put("errCode", JSONObject.NULL).put("errMsg", JSONObject.NULL))
    check(permissionReply.code == "permission_denied" && permissionReply.details.getInt("errCode") == 2001)
    check(permissionReply.details.getString("errMsg") == "wx.login:fail expired")

    val fallback = parseHostBackendError(JSONObject().put("errMsg", "backend unavailable"))
    check(fallback.code == "ERR_HOST_SERVICES" && fallback.message == "backend unavailable")
    println("Android identity rejection preserves nested errCode/errMsg, extension precedence and independent container classification")
}
