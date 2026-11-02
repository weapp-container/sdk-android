package com.sonofmagic.weapp.container.sdk

import org.json.JSONObject

fun main() {
    fun context(principal: Any, epoch: Any = "1") = JSONObject().put("account", JSONObject().put("principalId", principal).put("epoch", epoch))
    fun rejects(value: JSONObject) { check(runCatching { HostIdentitySnapshot.signedOut(value) }.isFailure) }
    val state = checkNotNull(HostIdentitySnapshot.signedOut(context(JSONObject.NULL, "18446744073709551615")))
    check(state.length() == 5 && !state.getBoolean("hostLoggedIn") && !state.getBoolean("appLoggedIn") && !state.getBoolean("profileAuthorized") && !state.getBoolean("phoneAuthorized") && state.getString("authorizationVersion") == "0")
    check(HostIdentitySnapshot.signedOut(context("user-A")) == null)
    rejects(JSONObject())
    rejects(JSONObject().put("account", JSONObject().put("epoch", "1")))
    for (principal in listOf("", 1, false, JSONObject())) rejects(context(principal))
    for (epoch in listOf("0", "01", "-1", "18446744073709551616", 1)) rejects(context(JSONObject.NULL, epoch))
    println("Native logout snapshots preserve exact account epochs and reject missing/malformed identity. Signed-in accounts still require the backend.")
}
