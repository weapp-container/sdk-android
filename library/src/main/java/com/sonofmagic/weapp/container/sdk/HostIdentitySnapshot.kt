package com.sonofmagic.weapp.container.sdk

import org.json.JSONObject

/** Native account projection only; never substitute this for a failed HTTP request. */
internal object HostIdentitySnapshot {
    fun signedOut(context: JSONObject): JSONObject? {
        val account = context.getJSONObject("account")
        require(account.has("principalId")) { "Trusted account principal required" }
        val epoch = account.get("epoch")
        require(epoch is String && epoch.toULongOrNull()?.let { it > 0u && it.toString() == epoch } == true) { "Trusted account exact epoch required" }
        val principal = account.get("principalId")
        if (principal is String && principal.isNotEmpty()) return null
        require(principal === JSONObject.NULL) { "Invalid trusted account principal" }
        return JSONObject().put("hostLoggedIn", false).put("appLoggedIn", false)
            .put("profileAuthorized", false).put("phoneAuthorized", false).put("authorizationVersion", "0")
    }
}
