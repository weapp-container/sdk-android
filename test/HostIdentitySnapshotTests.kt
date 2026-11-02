package com.sonofmagic.weapp.container.sdk

import org.json.JSONObject

fun main() {
    check(HostIdentitySnapshot.supportsOwnerContext(JSONObject().put("hostServices", JSONObject().put("snapshotOwnerContext", "1.0"))))
    for (flag in listOf(JSONObject.NULL, "", "0.9", "2.0", 1, 1.0))
        check(!HostIdentitySnapshot.supportsOwnerContext(JSONObject().put("hostServices", JSONObject().put("snapshotOwnerContext", flag))))
    check(!HostIdentitySnapshot.supportsOwnerContext(JSONObject()))
    fun context(principal: Any, epoch: Any = "1") = JSONObject().put("account", JSONObject().put("principalId", principal).put("epoch", epoch))
    fun rejects(value: JSONObject) { check(runCatching { HostIdentitySnapshot.signedOut(value) }.isFailure) }
    val state = checkNotNull(HostIdentitySnapshot.signedOut(context(JSONObject.NULL, "18446744073709551615")))
    check(state.length() == 5 && !state.getBoolean("hostLoggedIn") && !state.getBoolean("appLoggedIn") && !state.getBoolean("profileAuthorized") && !state.getBoolean("phoneAuthorized") && state.getString("authorizationVersion") == "0")
    val defaultState = checkNotNull(HostIdentitySnapshot.signedOut(context(JSONObject.NULL, "0")))
    check(defaultState.toString() == state.toString())
    check(HostIdentitySnapshot.signedOut(context("user-A")) == null)
    rejects(JSONObject())
    rejects(JSONObject().put("account", JSONObject().put("epoch", "1")))
    for (principal in listOf("", 1, false, JSONObject())) rejects(context(principal))
    for (epoch in listOf("00", "01", "-1", "18446744073709551616", 1)) rejects(context(JSONObject.NULL, epoch))
    val application = JSONObject().put("hostAppId", "host").put("tenantId", "tenant").put("publisherId", "publisher").put("appId", "app").put("environment", "develop").put("version", "1").put("packRoot", "a".repeat(64))
    val trusted = context("user-A").put("application", application).put("sessionId", "native-demo")
    val current = JSONObject(trusted.toString()).apply { remove("sessionId") }
    val nativeState = JSONObject().put("status", "ready").put("hostServicesState", current).put("sessionId", "runtime-A-1")
    val frozen = HostIdentitySnapshot.refreshContext(trusted, nativeState)
    check(frozen.getString("sessionId") == "runtime-A-1" && trusted.getString("sessionId") == "native-demo")
    for (status in listOf("identitySwitching", "empty", "closed")) {
        check(runCatching { HostIdentitySnapshot.refreshContext(trusted, JSONObject(nativeState.toString()).put("status", status)) }.isFailure)
    }
    nativeState.put("sessionId", "runtime-A-2")
    current.getJSONObject("account").put("principalId", "user-B").put("epoch", "2")
    check(frozen.getString("sessionId") == "runtime-A-1" && frozen.getJSONObject("account").getString("principalId") == "user-A")
    check(runCatching { HostIdentitySnapshot.refreshContext(trusted, nativeState) }.isFailure)
    for (field in listOf("hostAppId", "tenantId", "publisherId", "appId", "environment", "version", "packRoot")) {
        val changed = JSONObject(frozen.toString()).apply { getJSONObject("application").put(field, "changed") }
        val stateWithChangedApplication = JSONObject().put("status", "ready").put("sessionId", "runtime-A-1").put("hostServicesState", changed)
        check(runCatching { HostIdentitySnapshot.refreshContext(trusted, stateWithChangedApplication) }.isFailure)
    }
    for (invalid in listOf(JSONObject.NULL, "", 1)) {
        val stateWithoutIncarnation = JSONObject().put("status", "ready").put("hostServicesState", JSONObject(frozen.toString()).apply { remove("sessionId") }).put("sessionId", invalid)
        check(runCatching { HostIdentitySnapshot.refreshContext(trusted, stateWithoutIncarnation) }.isFailure)
    }
    println("Native identity snapshots preserve exact epochs; refresh freezes the real incarnation before I/O and rejects stale application/account ownership.")
}
