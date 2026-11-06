package com.sonofmagic.weapp.container.sdk

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.pm.PackageManager
import android.hardware.fingerprint.FingerprintManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.ContactsContract
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A real SOTER integration must authenticate and attest the exact trusted call.
 * The provider owns its SDK/keys/server trust and returns a cancellable operation.
 * Ordinary Android biometric success is never converted into a SOTER signature. */
interface AndroidSoterAttestationProvider {
    val supportedAuthModes: Set<String>
    fun authenticate(activity: Activity, call: HostCall, authMode: String, completion: (Result<AndroidSoterAttestation>) -> Unit): AutoCloseable
}
data class AndroidSoterAttestation(val resultJSON: String, val resultJSONSignature: String)

/** Foreground system operations with native confirmation and actual provider commits. */
class AndroidSystemApis(
    activity: Activity,
    private val files: AndroidManagedFiles,
    private val permissions: AndroidPermissionBroker? = null,
    enableNfc: Boolean = false,
    private val soter: AndroidSoterAttestationProvider? = null,
) : AutoCloseable {
    private var binding: Activity? = activity
    private val activity: Activity get() = checkNotNull(binding) { "System Activity detached" }
    private val application = activity.application
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val consent = AndroidAppPermissionConsent({ this.activity }, { files.auxiliaryRoot(it) })
    private val nfc = if (enableNfc) AndroidNdefApis(activity, files) else null
    private val dialogs = mutableMapOf<String, AlertDialog>()
    private val pendingReplies = mutableMapOf<String, HostReply>()
    private val attestations = mutableMapOf<String, AutoCloseable>()
    private class Work { val drained = CountDownLatch(1) }
    private val work = mutableSetOf<Work>()
    private var scanner: AndroidCodeScanner? = null
    private var session: WeappContainerSession? = null
    private var closed = false
    private var resetting = false
    private var visible = true
    @Volatile private var generation = 0L
    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityPaused(a: Activity) { if (a === binding) runCatching { nfc?.stop() }.onFailure { android.util.Log.e("WeappContainer", "NFC cleanup needs retry", it) } }
        override fun onActivityDestroyed(a: Activity) { if (a === binding) runCatching { reset() }.onFailure { android.util.Log.e("WeappContainer", "System cleanup needs retry", it) } }
        override fun onActivityCreated(a: Activity, state: Bundle?) {}
        override fun onActivityStarted(a: Activity) {}
        override fun onActivityResumed(a: Activity) {}
        override fun onActivityStopped(a: Activity) { if (a === binding) runCatching { cancelUi() }.onFailure { android.util.Log.e("WeappContainer", "System UI cleanup needs retry", it) } }
        override fun onActivitySaveInstanceState(a: Activity, state: Bundle) {}
    }
    private fun declares(permission: String) = activity.packageManager.getPackageInfo(activity.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.contains(permission) == true
    companion object { val potentialApis = setOf("wx.addPhoneContact", "wx.addPhoneCalendar", "wx.scanCode", "wx.checkIsSupportSoterAuthentication", "wx.checkIsSoterEnrolledInDevice", "wx.startSoterAuthentication") }
    val supportedApis: Set<String> get() = buildSet {
        add("wx.checkIsSupportSoterAuthentication"); add("wx.checkIsSoterEnrolledInDevice")
        if (declares(Manifest.permission.WRITE_CONTACTS)) add("wx.addPhoneContact")
        if (declares(Manifest.permission.READ_CALENDAR) && declares(Manifest.permission.WRITE_CALENDAR)) add("wx.addPhoneCalendar")
        if (declares(Manifest.permission.CAMERA) && activity.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) add("wx.scanCode")
        if (soter != null && supportedSoterModes().isNotEmpty()) add("wx.startSoterAuthentication")
    }
    fun install(target: WeappContainerSession): CompletableFuture<Void> {
        check(session == null); session = target; application.registerActivityLifecycleCallbacks(lifecycle)
        return CompletableFuture.allOf(target.registerHostResourceCleanup("system-${UUID.randomUUID()}") { reset() },
            target.registerHostVisibilityHandler("system-${UUID.randomUUID()}") { value -> visible = value; if (!value) cancelUi() },
            nfc?.install(target) ?: CompletableFuture.completedFuture(null),
            *supportedApis.map { api -> target.registerApiHandler(api) { call, reply ->
                if (closed || resetting || call.isCancelled || !visible || binding == null) reject(reply, HostErrorCode.CANCELLED, "Native system owner is unavailable")
                else { val accepted = generation; files.prepareScope(requireNotNull(call.context)).whenComplete { _, error ->
                    if (live(call, accepted)) { if (error != null) failure(api, reply, error) else runCatching { invoke(api, call, reply) }.onFailure { failure(api, reply, it) } }
                    else if (!call.isCancelled) reject(reply, HostErrorCode.CANCELLED, "System preparation owner retired")
                } }
            } }.toTypedArray())
    }
    private fun ok(api: String, reply: HostReply, value: JSONObject = JSONObject()) { pendingReplies.entries.removeAll { it.value === reply }; reply.resolve(value.put("errMsg", "${api.removePrefix("wx.")}:ok")) }
    private fun reject(reply: HostReply, code: HostErrorCode, message: String, retryable: Boolean = false, details: JSONObject = JSONObject()) { pendingReplies.entries.removeAll { it.value === reply }; reply.reject(code, message, retryable, details) }
    private fun failure(api: String, reply: HostReply, error: Throwable) { reject(reply, if (error is SecurityException) HostErrorCode.PERMISSION_DENIED else HostErrorCode.INVALID_REQUEST, error.message ?: "Native system operation failed", false, JSONObject().put("errMsg", "${api.removePrefix("wx.")}:fail ${error.message}").put("errCode", -1).put("nativeException", error.javaClass.name)) }
    private fun live(call: HostCall, accepted: Long): Boolean = !closed && !resetting && generation == accepted && visible && binding != null && !call.isCancelled
    private fun text(value: JSONObject, key: String, max: Int = 1024): String { val result = value.optString(key, ""); require(result.length <= max && !result.contains('\u0000')) { "Invalid $key" }; return result }
    private fun authorize(api: String, scope: String, requested: Array<String>, call: HostCall, reply: HostReply, accepted: Long, action: () -> Unit) {
        consent.request(scope, call) { granted ->
            fun finish(allowed: Boolean) { main.post { if (live(call, accepted)) { if (!granted || !allowed || requested.any { activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) reject(reply, HostErrorCode.PERMISSION_DENIED, "$scope permission denied") else runCatching(action).onFailure { failure(api, reply, it) } } } }
            if (!granted || requested.all { activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) finish(granted)
            else permissions?.request(requested, call, ::finish) ?: finish(false)
        }
    }
    private fun confirm(call: HostCall, title: String, description: String, accepted: Long, reply: HostReply, action: () -> Unit) {
        check(dialogs.size < 8)
        val app = requireNotNull(call.context).getJSONObject("application").getString("appId")
        val dialog = AlertDialog.Builder(activity).setTitle(title).setMessage("$app\n$description")
            .setPositiveButton("确认添加") { _, _ -> dialogs.remove(call.id); if (live(call, accepted)) runCatching(action).onFailure { failure(call.method, reply, it) } }
            .setNegativeButton("取消") { _, _ -> dialogs.remove(call.id); reject(reply, HostErrorCode.CANCELLED, "User cancelled native confirmation") }
            .setOnCancelListener { dialogs.remove(call.id); reject(reply, HostErrorCode.CANCELLED, "User cancelled native confirmation") }.create()
        dialogs[call.id] = dialog; call.addCancellationHandler { dialogs.remove(call.id)?.dismiss() }; dialog.show()
    }
    private fun <T> background(call: HostCall, reply: HostReply, accepted: Long, operation: () -> T, completion: (T) -> Unit) {
        val item = Work(); synchronized(work) { check(!resetting && !closed && generation == accepted); work.add(item) }
        try { worker.execute {
            val result = runCatching { check(generation == accepted && !call.isCancelled); operation() }
            synchronized(work) { work.remove(item) }; item.drained.countDown()
            main.post { if (live(call, accepted)) result.fold({ runCatching { completion(it) }.onFailure { failure(call.method, reply, it) } }, { failure(call.method, reply, it) }) }
        } } catch (error: Throwable) { synchronized(work) { work.remove(item) }; item.drained.countDown(); throw error }
    }
    private fun invoke(api: String, call: HostCall, reply: HostReply) {
        check(!closed && !resetting && visible && binding != null)
        val value = call.payload as? JSONObject ?: JSONObject(); val accepted = generation
        if (api in setOf("wx.addPhoneContact", "wx.addPhoneCalendar", "wx.scanCode", "wx.startSoterAuthentication")) { check(pendingReplies.size < 32); pendingReplies[call.id] = reply; call.addCancellationHandler { pendingReplies.remove(call.id) } }
        when (api) {
            "wx.addPhoneContact" -> {
                val first = text(value, "firstName", 256); require(first.isNotBlank()) { "firstName is required" }
                authorize(api, "scope.addPhoneContact", arrayOf(Manifest.permission.WRITE_CONTACTS), call, reply, accepted) {
                    confirm(call, "添加联系人", "${text(value, "lastName", 256)}$first\n${text(value, "mobilePhoneNumber", 128)}", accepted, reply) {
                        val photo = text(value, "photoFilePath").takeIf { it.isNotEmpty() }?.let { files.readForNative(it, requireNotNull(call.context)).also { bytes -> require(bytes.size <= 1048576) } }
                        val resolver = activity.contentResolver
                        background(call, reply, accepted, { resolver.applyBatch(ContactsContract.AUTHORITY, contactOperations(value, photo)); Unit }) { ok(api, reply) }
                    }
                }
            }
            "wx.addPhoneCalendar" -> {
                val title = text(value, "title"); require(title.isNotBlank()); val start = value.getDouble("startTime"); val end = value.optDouble("endTime", start)
                require(start.isFinite() && end.isFinite() && start >= -62135596800.0 && end >= start && end <= 253402300799.0)
                require(!value.has("path") || value.optString("path").isEmpty()) { "Calendar mini-program links need an official link adapter" }; require(!value.has("signature") || value.optString("signature").isEmpty()) { "Calendar signatures need an official link adapter" }
                val alarm = value.optBoolean("alarm", true); val offset = value.optDouble("alarmOffset", 0.0)
                require(!alarm || offset.isFinite() && offset in 0.0..604800.0 && offset % 60.0 == 0.0) { "Android calendar alarms require whole-minute nonnegative lead seconds" }
                authorize(api, "scope.addPhoneCalendar", arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), call, reply, accepted) {
                    val resolver = activity.contentResolver
                    background(call, reply, accepted, {
                        val calendars = mutableListOf<Pair<Long, String>>()
                        resolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME), "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.VISIBLE} = 1", arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), null)?.use { cursor -> while (cursor.moveToNext() && calendars.size < 64) calendars.add(cursor.getLong(0) to cursor.getString(1)) }; calendars
                    }) { calendars ->
                        require(calendars.isNotEmpty()) { "No writable native calendar" }
                        val choose = AlertDialog.Builder(activity).setTitle("添加日历：$title").setItems(calendars.map { it.second }.toTypedArray()) { _, selected ->
                            dialogs.remove(call.id)
                            if (live(call, accepted)) background(call, reply, accepted, {
                                val operations = arrayListOf(ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI).withValues(ContentValues().apply {
                                    put(CalendarContract.Events.CALENDAR_ID, calendars[selected].first); put(CalendarContract.Events.TITLE, title)
                                    put(CalendarContract.Events.DTSTART, (start * 1000).toLong()); put(CalendarContract.Events.DTEND, (end * 1000).toLong()); put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                                    put(CalendarContract.Events.DESCRIPTION, text(value, "description", 8192)); put(CalendarContract.Events.EVENT_LOCATION, text(value, "location")); put(CalendarContract.Events.ALL_DAY, if (value.optBoolean("allDay")) 1 else 0)
                                    put(CalendarContract.Events.HAS_ALARM, if (alarm) 1 else 0)
                                }).build())
                                if (alarm) operations.add(ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI).withValueBackReference(CalendarContract.Reminders.EVENT_ID, 0).withValue(CalendarContract.Reminders.MINUTES, (offset / 60).toInt()).withValue(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT).build())
                                resolver.applyBatch(CalendarContract.AUTHORITY, operations); Unit
                            }) { ok(api, reply) }
                        }.setNegativeButton("取消") { _, _ -> dialogs.remove(call.id); reject(reply, HostErrorCode.CANCELLED, "Calendar cancelled") }.setOnCancelListener { dialogs.remove(call.id); reject(reply, HostErrorCode.CANCELLED, "Calendar cancelled") }.create()
                        dialogs[call.id] = choose; call.addCancellationHandler { dialogs.remove(call.id)?.dismiss() }; choose.show()
                    }
                }
            }
            "wx.scanCode" -> {
                check(scanner == null) { "A scan is already active" }
                val requested = value.optJSONArray("scanType") ?: JSONArray().put("qrCode").put("barCode").put("datamatrix").put("pdf417")
                val types = (0 until requested.length()).map { requested.getString(it) }.toSet(); require(types.isNotEmpty() && types.all { it in setOf("qrCode", "barCode", "datamatrix", "pdf417") })
                authorize(api, "scope.camera", arrayOf(Manifest.permission.CAMERA), call, reply, accepted) {
                    check(scanner == null)
                    val scan = AndroidCodeScanner(activity, types) { result ->
                        val current = scanner
                        try { current?.close(); if (scanner === current) scanner = null } catch (error: Throwable) { failure(api, reply, error); return@AndroidCodeScanner }
                        if (live(call, accepted)) result.fold({ ok(api, reply, it) }, { failure(api, reply, it) })
                    }; scanner = scan
                    call.addCancellationHandler { if (scanner === scan) { scan.close(); scanner = null } }; scan.show()
                }
            }
            "wx.checkIsSupportSoterAuthentication" -> ok(api, reply, JSONObject().put("supportMode", JSONArray(supportedSoterModes().toList())))
            "wx.checkIsSoterEnrolledInDevice" -> {
                val mode = value.getString("checkAuthMode"); require(mode in setOf("fingerPrint", "facial", "speech"))
                ok(api, reply, JSONObject().put("isEnrolled", mode == "fingerPrint" && mode in supportedSoterModes() && fingerprint()?.hasEnrolledFingerprints() == true))
            }
            "wx.startSoterAuthentication" -> {
                val modes = value.getJSONArray("requestAuthModes"); require(modes.length() in 1..3)
                val selected = (0 until modes.length()).map { modes.getString(it) }.firstOrNull { it in supportedSoterModes() && it == "fingerPrint" && fingerprint()?.hasEnrolledFingerprints() == true } ?: throw IllegalStateException("No enrolled SOTER provider mode")
                val challenge = value.getString("challenge"); require(challenge.isNotBlank() && challenge.toByteArray().size <= 4096 && attestations.size < 8)
                var completed = false
                val operation = requireNotNull(soter).authenticate(activity, call, selected) { result -> main.post {
                    if (completed) return@post; completed = true
                    try { retireAttestation(call.id) } catch (error: Throwable) { failure(api, reply, error); return@post }
                    if (live(call, accepted)) result.fold({ attestation -> runCatching {
                        require(attestation.resultJSON.toByteArray().size in 2..65536 && attestation.resultJSONSignature.length in 1..16384); require(JSONObject(attestation.resultJSON).getString("raw") == challenge) { "SOTER attestation challenge mismatch" }; require(java.util.Base64.getDecoder().decode(attestation.resultJSONSignature).isNotEmpty()) { "SOTER signature must be nonempty base64" }
                        ok(api, reply, JSONObject().put("authMode", selected).put("resultJSON", attestation.resultJSON).put("resultJSONSignature", attestation.resultJSONSignature))
                    }.onFailure { failure(api, reply, it) } }, { failure(api, reply, it) })
                } }
                if (!completed) attestations[call.id] = operation
                call.addCancellationHandler { retireAttestation(call.id) }
            }
        }
    }
    private fun contactOperations(value: JSONObject, photo: ByteArray?): ArrayList<ContentProviderOperation> {
        val ops = arrayListOf(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI).withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null).withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build())
        fun data(mime: String, entries: Map<String, Any?>) { val builder = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0).withValue(ContactsContract.Data.MIMETYPE, mime); for ((key, item) in entries) builder.withValue(key, item); ops.add(builder.build()) }
        data(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, mapOf(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME to text(value, "firstName", 256), ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME to text(value, "middleName", 256), ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME to text(value, "lastName", 256)))
        for ((key, type) in mapOf("mobilePhoneNumber" to 2, "homePhoneNumber" to 1, "workPhoneNumber" to 3, "homeFaxNumber" to 5, "workFaxNumber" to 4, "hostNumber" to 7)) { val number = text(value, key, 128); if (number.isNotEmpty()) data(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, mapOf(ContactsContract.CommonDataKinds.Phone.NUMBER to number, ContactsContract.CommonDataKinds.Phone.TYPE to type)) }
        if (text(value, "email").isNotEmpty()) data(ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE, mapOf(ContactsContract.CommonDataKinds.Email.ADDRESS to text(value, "email")))
        if (text(value, "organization").isNotEmpty() || text(value, "title").isNotEmpty()) data(ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE, mapOf(ContactsContract.CommonDataKinds.Organization.COMPANY to text(value, "organization"), ContactsContract.CommonDataKinds.Organization.TITLE to text(value, "title")))
        if (text(value, "remark", 8192).isNotEmpty()) data(ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE, mapOf(ContactsContract.CommonDataKinds.Note.NOTE to text(value, "remark", 8192)))
        if (text(value, "url").isNotEmpty()) data(ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE, mapOf(ContactsContract.CommonDataKinds.Website.URL to text(value, "url")))
        if (text(value, "nickName", 256).isNotEmpty()) data(ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE, mapOf(ContactsContract.CommonDataKinds.Nickname.NAME to text(value, "nickName", 256)))
        for ((prefix, type) in listOf("address" to 3, "homeAddress" to 1, "workAddress" to 2)) { val fields = listOf("Country", "State", "City", "Street", "PostalCode").map { text(value, prefix + it) }; if (fields.any { it.isNotEmpty() }) data(ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE, mapOf(ContactsContract.CommonDataKinds.StructuredPostal.TYPE to type, ContactsContract.CommonDataKinds.StructuredPostal.COUNTRY to fields[0], ContactsContract.CommonDataKinds.StructuredPostal.REGION to fields[1], ContactsContract.CommonDataKinds.StructuredPostal.CITY to fields[2], ContactsContract.CommonDataKinds.StructuredPostal.STREET to fields[3], ContactsContract.CommonDataKinds.StructuredPostal.POSTCODE to fields[4])) }
        if (photo != null) data(ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE, mapOf(ContactsContract.CommonDataKinds.Photo.PHOTO to photo))
        return ops
    }
    @Suppress("DEPRECATION") private fun fingerprint(): FingerprintManager? = binding?.getSystemService(FingerprintManager::class.java)
    @Suppress("DEPRECATION") private fun supportedSoterModes(): Set<String> = if (soter?.supportedAuthModes?.contains("fingerPrint") == true && declares(Manifest.permission.USE_FINGERPRINT) && fingerprint()?.isHardwareDetected == true) setOf("fingerPrint") else emptySet()
    private fun retireAttestation(id: String) { val operation = attestations[id] ?: return; operation.close(); if (attestations[id] === operation) attestations.remove(id) }
    private fun cancelUi() { generation++; for (reply in pendingReplies.values.toList()) reject(reply, HostErrorCode.CANCELLED, "System operation left the foreground"); pendingReplies.clear(); consent.reset(); for (dialog in dialogs.values.toList()) dialog.dismiss(); dialogs.clear(); scanner?.close(); scanner = null; for (id in attestations.keys.toList()) retireAttestation(id); nfc?.stop() }
    private fun reset() { resetting = true; cancelUi(); val pending = synchronized(work) { work.toList() }; check(pending.all { it.drained.await(5, TimeUnit.SECONDS) }) { "Native provider operation has not drained" }; nfc?.reset(); resetting = false }
    fun rebindActivity(activity: Activity?) { if (binding !== activity) { reset(); nfc?.rebindActivity(activity); binding = activity } }
    override fun close() { if (closed) return; reset(); nfc?.close(); worker.shutdown(); application.unregisterActivityLifecycleCallbacks(lifecycle); closed = true; binding = null }
}
