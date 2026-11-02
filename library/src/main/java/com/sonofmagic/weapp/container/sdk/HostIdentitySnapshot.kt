package com.sonofmagic.weapp.container.sdk

import org.json.JSONObject

/** Native account projection only; never substitute this for a failed HTTP request. */
internal object HostIdentitySnapshot {
    fun supportsOwnerContext(capabilities: JSONObject): Boolean =
        capabilities.optJSONObject("hostServices")?.opt("snapshotOwnerContext") == "1.0"

    /** Capture before starting asynchronous work; never bind a late result to a new owner. */
    fun refreshContext(trusted: JSONObject, nativeState: JSONObject): JSONObject {
        require(nativeState.opt("status") == "ready") { "A ready native snapshot owner is required" }
        val current = nativeState.getJSONObject("hostServicesState")
        for (group in listOf("application", "account")) {
            val expected = trusted.getJSONObject(group)
            val observed = current.getJSONObject(group)
            require(observed.length() > 0 && expected.length() == observed.length() && observed.keys().asSequence().all { name ->
                expected.has(name) && expected.get(name) == observed.get(name)
            }) { "Stale native snapshot owner" }
        }
        val incarnation = nativeState.get("sessionId")
        require(incarnation is String && incarnation.isNotEmpty()) { "A live native snapshot owner is required" }
        return JSONObject(current.toString()).put("sessionId", incarnation)
    }

    fun signedOut(context: JSONObject): JSONObject? {
        val account = context.getJSONObject("account")
        require(account.has("principalId")) { "Trusted account principal required" }
        val epoch = account.get("epoch")
        require(epoch is String && epoch.toULongOrNull()?.toString() == epoch) { "Trusted account exact epoch required" }
        val principal = account.get("principalId")
        if (principal is String && principal.isNotEmpty()) return null
        require(principal === JSONObject.NULL) { "Invalid trusted account principal" }
        return JSONObject().put("hostLoggedIn", false).put("appLoggedIn", false)
            .put("profileAuthorized", false).put("phoneAuthorized", false).put("authorizationVersion", "0")
    }
}
