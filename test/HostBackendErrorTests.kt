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
        JSONObject().put(
            "error",
            JSONObject().put("code", "consent_required").put("message", "consent required").put("errCode", 2001),
        ).put("requestId", "request-1"),
    )
    check(nested.code == "consent_required" && nested.message == "consent required")
    check(nested.details.getJSONObject("error").getInt("errCode") == 2001)
    check(nested.details.getString("requestId") == "request-1")

    val fallback = parseHostBackendError(JSONObject().put("errMsg", "backend unavailable"))
    check(fallback.code == "ERR_HOST_SERVICES" && fallback.message == "backend unavailable")
    println("Android backend errors retain raw errCode/errMsg and extension fields")
}
