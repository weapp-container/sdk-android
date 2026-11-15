package com.sonofmagic.weapp.container.sdk

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.Executors

/** An explicitly installed host channel; implementations never accept guest-created launch signatures. */
interface AndroidNativeAppPayChannel {
    val channel: String
    val isAvailable: Boolean
    fun pay(launch: JSONObject, call: HostCall, complete: (Result<Unit>) -> Unit)
}

/** Host-owned mapping for the standard mini-program JSAPI payment contract. */
interface AndroidNativeWxPayChannel {
    val isAvailable: Boolean
    fun pay(options: JSONObject, call: HostCall, complete: (Result<Unit>) -> Unit)
}

/**
 * Official WeChat SDK bridge for hosts whose backend issues a native PayReq
 * compatible with the validated JSAPI fields. The partner ID stays in the
 * host; it is never accepted from the guest payload.
 */
class AndroidWechatWxPayChannel(
    private val activity: Activity,
    private val appId: String,
    private val partnerId: String,
) : AndroidNativeWxPayChannel {
    override val isAvailable: Boolean
        get() = appId.isNotBlank() && partnerId.isNotBlank() &&
            runCatching {
                Class.forName("com.tencent.mm.opensdk.openapi.WXAPIFactory")
                Class.forName("com.tencent.mm.opensdk.modelpay.PayReq")
            }.isSuccess

    override fun pay(options: JSONObject, call: HostCall, complete: (Result<Unit>) -> Unit) {
        try {
            require(options.optString("signType") == "MD5") {
                "Official Android WeChat PayReq requires signType MD5"
            }
            val packageValue = options.getString("package")
            require(packageValue.startsWith("prepay_id=") && packageValue.length > "prepay_id=".length) {
                "wx.requestPayment package must contain a prepay_id"
            }
            val factory = Class.forName("com.tencent.mm.opensdk.openapi.WXAPIFactory")
            val api = factory.getMethod(
                "createWXAPI",
                Context::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType,
            ).invoke(null, activity, appId, true)
            val apiType = Class.forName("com.tencent.mm.opensdk.openapi.IWXAPI")
            check(apiType.getMethod("registerApp", String::class.java).invoke(api, appId) == true) {
                "WeChat application registration failed"
            }
            val requestType = Class.forName("com.tencent.mm.opensdk.modelpay.PayReq")
            val request = requestType.getConstructor().newInstance()
            requestType.getField("appId").set(request, appId)
            requestType.getField("partnerId").set(request, partnerId)
            requestType.getField("prepayId").set(request, packageValue.removePrefix("prepay_id="))
            requestType.getField("packageValue").set(request, "Sign=WXPay")
            requestType.getField("nonceStr").set(request, options.getString("nonceStr"))
            requestType.getField("timeStamp").set(request, options.getString("timeStamp"))
            requestType.getField("sign").set(request, options.getString("paySign"))
            check(apiType.getMethod("sendReq", Class.forName("com.tencent.mm.opensdk.modelbase.BaseReq"))
                .invoke(api, request) == true) { "WeChat rejected JSAPI payment launch" }
            // AndroidAppPayments owns the process-wide response lease and
            // settles this call from onWechatPayResponse.
        } catch (error: Throwable) {
            complete(Result.failure(error))
        }
    }
}

private fun validatedWxPaymentOptions(payload: Any?): JSONObject {
    val input = payload as? JSONObject ?: throw IllegalArgumentException("wx.requestPayment options are required")
    val fields = listOf("timeStamp", "nonceStr", "package", "signType", "paySign")
    val result = JSONObject()
    for (field in fields) {
        val value = input.opt(field) as? String
        require(!value.isNullOrBlank()) { "wx.requestPayment requires non-empty $field" }
        result.put(field, value)
    }
    require(result.getString("signType") in setOf("MD5", "HMAC-SHA256", "RSA")) {
        "wx.requestPayment signType must be MD5, HMAC-SHA256, or RSA"
    }
    return result
}

/** Uses the host's official SDK dependencies. Merchant signing stays on the backend. */
class AndroidAppPayments(
    private val activity: Activity,
    private val backend: HostServicesClient,
    private val wechatAppId: String? = null,
    channels: List<AndroidNativeAppPayChannel> = emptyList(),
) : AutoCloseable {
    private val channels = channels.associateBy { it.channel }.also { require(it.size == channels.size) { "Duplicate native payment channel" } }
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    companion object {
        // Official wallet callbacks are process-wide; never assign an old callback to a new account/order.
        private var pending: Pending? = null
        private class Pending(val channel: String, val call: HostCall, val result: (Int, String) -> Unit)
    }
    val availableChannels: Set<String> get() = buildSet {
        addAll(channels.values.filter { it.isAvailable }.map { it.channel })
        if (wechatAppId != null && runCatching { Class.forName("com.tencent.mm.opensdk.openapi.WXAPIFactory") }.isSuccess) add("wechat")
        if (runCatching { Class.forName("com.alipay.sdk.app.PayTask") }.isSuccess) add("alipay")
    }
    /** Installs the backend-order payment extension, separate from wx.requestPayment. */
    fun install(session: WeappContainerSession) = session.registerApiHandler("weappContainer.payment.request") { call, reply ->
        val payload = call.payload as? JSONObject ?: JSONObject()
        val orderId = payload.optString("orderId")
        if (!orderId.matches(Regex("[A-Za-z0-9-]{1,128}")) || listOf("appId", "prepayId", "paySign", "package", "orderString", "signType").any { payload.has(it) }) {
            reply.reject(HostErrorCode.INVALID_REQUEST, "Pass a backend orderId; JSAPI signatures cannot be used for native APP Pay"); return@registerApiHandler
        }
        try { backend.launch(call, orderId) { result -> result.fold({ order ->
            try {
                require(order.getString("orderId") == orderId && order.getString("product") == "app") { "Backend order is not APP Pay" }
                if (order.getString("status") == "paid") { complete(reply, orderId); return@fold }
                check(pending == null) { "Another official payment UI is still active" }
                val channel = order.getString("channel"); val launch = order.getJSONObject("launch")
                check(channel in availableChannels) { "Official $channel SDK is not configured in this host" }
                val entry = Pending(channel, call) { code, message ->
                    if (!call.isCancelled) {
                        when (code) {
                            0 -> confirm(call, reply, orderId, 0)
                            -2, 6001 -> reply.reject(HostErrorCode.CANCELLED, "Payment UI cancelled", false, JSONObject().put("errCode", code).put("errMsg", "requestPayment:fail cancel"))
                            8000, 6004 -> confirm(call, reply, orderId, 0)
                            else -> reply.reject(HostErrorCode.INVALID_REQUEST, message.ifEmpty { "Official payment SDK failed" }, false, JSONObject().put("errCode", code).put("errMsg", "requestPayment:fail $message"))
                        }
                    }
                }
                pending = entry
                // Account cancellation stops waiting; the already submitted wallet transaction continues.
                call.onCancel = { }
                val custom = channels[channel]
                if (custom != null) custom.pay(launch, call) { result -> main.post {
                    if (pending === entry) { pending = null; result.fold({ entry.result(0, "") }, { error -> entry.result(if (call.isCancelled) -2 else -1, error.message ?: "Native payment channel failed") }) }
                } }
                else if (channel == "wechat") launchWechat(launch) else launchAlipay(launch, entry)
            } catch (error: Throwable) {
                if (pending?.call === call) pending = null
                reply.reject(HostErrorCode.UNSUPPORTED, error.message ?: "Official APP Pay unavailable")
            }
        }, { error -> reply.reject(HostErrorCode.INVALID_REQUEST, error.message ?: "Backend order unavailable") }) } }
        catch (error: Throwable) { reply.reject(HostErrorCode.INVALID_REQUEST, error.message ?: "Backend order unavailable") }
    }

    /**
     * Install standard `wx.requestPayment` explicitly. The runtime validates
     * its JSAPI fields; the host channel maps them to the official SDK.
     */
    fun installWxRequestPayment(
        session: WeappContainerSession,
        channel: AndroidNativeWxPayChannel,
    ) = session.registerApiHandler("wx.requestPayment") { call, reply ->
        if (!channel.isAvailable) {
            reply.reject(HostErrorCode.UNSUPPORTED, "Standard wx.requestPayment channel is unavailable")
            return@registerApiHandler
        }
        val options = try {
            validatedWxPaymentOptions(call.payload)
        } catch (error: Throwable) {
            reply.reject(HostErrorCode.INVALID_REQUEST, error.message ?: "Invalid wx.requestPayment options")
            return@registerApiHandler
        }
        if (pending != null) {
            reply.reject(HostErrorCode.INVALID_REQUEST, "Another official payment UI is still active")
            return@registerApiHandler
        }
        val entry = Pending("wx-jsapi", call) { code, message ->
            if (!call.isCancelled) {
                when (code) {
                    0 -> reply.resolve(JSONObject().put("errMsg", "requestPayment:ok"))
                    -2, 6001 -> reply.reject(HostErrorCode.CANCELLED, "Payment UI cancelled", false, JSONObject().put("errCode", code).put("errMsg", "requestPayment:fail cancel"))
                    else -> reply.reject(HostErrorCode.INVALID_REQUEST, message.ifEmpty { "Standard payment channel failed" }, false, JSONObject().put("errCode", code).put("errMsg", "requestPayment:fail $message"))
                }
            }
        }
        pending = entry
        try {
            channel.pay(options, call) { result -> main.post {
                if (pending === entry) {
                    pending = null
                    result.fold({ entry.result(0, "") }, { error -> entry.result(if (call.isCancelled) -2 else -1, error.message ?: "Standard payment channel failed") })
                }
            } }
        } catch (error: Throwable) {
            if (pending === entry) pending = null
            entry.result(-1, error.message ?: "Standard payment channel failed")
        }
    }
    private fun launchWechat(value: JSONObject) {
        require(value.getString("kind") == "wechat-app" && value.getString("appId") == wechatAppId && value.getString("packageValue") == "Sign=WXPay") { "Wrong WeChat APP payment contract" }
        val factory = Class.forName("com.tencent.mm.opensdk.openapi.WXAPIFactory")
        val api = factory.getMethod("createWXAPI", Context::class.java, String::class.java, Boolean::class.javaPrimitiveType).invoke(null, activity, wechatAppId, true)
        val apiType = Class.forName("com.tencent.mm.opensdk.openapi.IWXAPI")
        check(apiType.getMethod("registerApp", String::class.java).invoke(api, wechatAppId) == true) { "WeChat application registration failed" }
        val requestType = Class.forName("com.tencent.mm.opensdk.modelpay.PayReq"); val request = requestType.getConstructor().newInstance()
        for (field in listOf("appId", "partnerId", "prepayId", "packageValue", "nonceStr", "timeStamp", "sign")) requestType.getField(field).set(request, value.getString(field))
        check(apiType.getMethod("sendReq", Class.forName("com.tencent.mm.opensdk.modelbase.BaseReq")).invoke(api, request) == true) { "WeChat rejected APP payment launch" }
    }
    private fun launchAlipay(value: JSONObject, entry: Pending) {
        require(value.getString("kind") == "alipay-app") { "Wrong Alipay APP payment contract" }
        val orderString = value.getString("orderString"); require(orderString.length in 1..65536)
        executor.execute {
            val result = runCatching {
                val type = Class.forName("com.alipay.sdk.app.PayTask"); val task = type.getConstructor(Activity::class.java).newInstance(activity)
                @Suppress("UNCHECKED_CAST")
                type.getMethod("payV2", String::class.java, Boolean::class.javaPrimitiveType).invoke(task, orderString, true) as Map<String, String>
            }
            main.post {
                if (pending !== entry) return@post
                pending = null
                result.fold({ values -> entry.result(if (values["resultStatus"] == "9000") 0 else values["resultStatus"]?.toIntOrNull() ?: -1, values["memo"] ?: "") },
                    { entry.result(-1, "Alipay SDK execution failed") })
            }
        }
    }
    /** Call ONLY from the host's official WXApi.handleIntent / WXPayEntryActivity PayResp delegate. */
    fun onWechatPayResponse(errCode: Int, errStr: String?) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val entry = pending?.takeIf { it.channel == "wechat" || it.channel == "wx-jsapi" } ?: return
        pending = null; entry.result(errCode, errStr ?: "")
    }
    private fun complete(reply: HostReply, orderId: String) { reply.resolve(JSONObject().put("errMsg", "requestPayment:ok").put("orderId", orderId).put("serverConfirmed", true)) }
    private fun confirm(call: HostCall, reply: HostReply, orderId: String, attempt: Int) {
        if (call.isCancelled) return
        backend.launch(call, orderId) { result -> result.fold({ order ->
            when (order.optString("status")) {
                "paid" -> complete(reply, orderId)
                "closed", "refunded" -> reply.reject(HostErrorCode.INVALID_REQUEST, "Backend reports ${order.optString("status")}", false, JSONObject().put("orderId", orderId))
                else -> if (attempt < 20) main.postDelayed({ confirm(call, reply, orderId, attempt + 1) }, 500)
                    else reply.reject(HostErrorCode.TIMEOUT, "Awaiting verified server payment confirmation", true, JSONObject().put("orderId", orderId).put("errMsg", "requestPayment:fail server confirmation pending"))
            }
        }, { reply.reject(HostErrorCode.INVALID_REQUEST, "Cannot confirm payment with backend", true, JSONObject().put("orderId", orderId)) }) }
    }
    /** Closing stops this caller's waiting; it never refunds or cancels a financial transaction. */
    override fun close() { executor.shutdown() }
}
