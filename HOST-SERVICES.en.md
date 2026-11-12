<a id="android-宿主能力"></a>
# Android hosting capabilities

[简体中文](HOST-SERVICES.md) · **English**


These adapters need to match the native runtime of the current host-services protocol; the old preview runtime
You cannot gain new capabilities by simply replacing the Kotlin source code. The approval of source code compilation does not mean the acceptance of equipment, channels or merchants.

First, the host verifies the publisher and installation package, configures `application`, `account`, then registers the adapter, and finally
Load pack. `registerApiHandler` Access WeChat API; continue to use it for business expansion
`registerHostMethod`. Uninstalled APIs are explicitly not supported.

`HostServicesClient.identityState` is clearly `principalId: null` in the trusted account and epoch
When
is an exact decimal u64 (snapshot assist layer accepts `"0"`, public owner configuration still requires a positive number) when preparing unlogged snapshots, the revoked host bearer is no longer read. initial preparation,
The same entrance is used for number change cleaning preparation and front-end refresh; the logged-in principal must still query the real backend.
Network, permission or credential failures will continue to be passed and will not fall back to success. The snapshot does not contain old user information or phone number authorization.

`readHostApiResource(context, offset, length)` transparently transmits the original trusted context of the resource setter,
Returns `{bytes,totalBytes}`; single block maximum 1 MiB. Check the runtime first
`hostServices.apiResourceChunks == "1.0"`, retain decimal epoch and deadline in context
and session/call ID, do not replace with transport ID. Read-only commands are still subject to runtime account, expiration and invocation
Survival check; rejected after number change, settlement, cancellation or timeout, the old runtime explicitly does not support it. it will not create
owner, enter JS or trigger surface rendering, nor grant read permission to any path.

```kotlin
val toolkit = AndroidNativeDeviceServices(activity,
    networkOrigins = setOf("https://api.example.com", "wss://api.example.com"),
    permissions = permissionBroker)

session.configureHostServices(trustedConfiguration).thenCompose {
    toolkit.install(session)
}.thenCompose { session.load(pack, options) }
```

The host must hold the toolkit until the session is closed. When the Activity is rebuilt, it is called on the main thread
`toolkit.rebindActivity(activityOrNull)`; A null value stops old resources and new activities do not register repeatedly.
handler. The host accesses the real Activity Result permission process through `AndroidPermissionBroker`.
Clicking "Allow" cannot be directly regarded as successful system authorization.

Use independent mini-program authorization prompts for positioning, camera, microphone and Bluetooth. Authorization records are classified by trusted host, tenant,
Issuer, application, environment, account and epoch isolation; system authorization already exists and mini-program authorization is required.
`wx.getSetting` only returns scopes that have been queried. The host must still declare CAMERA,
RECORD_AUDIO, positioning, Android 12+ BLUETOOTH_SCAN / BLUETOOTH_CONNECT, and older systems
the required Bluetooth/location permissions; The SDK does not apply for all dangerous permissions by default for the application.
The

toolkit has files, devices, Bluetooth, media, network and recording adapters in one place, or can be installed independently on demand.
`session.closeAsync()` Wait for the main thread's native resources to complete cleaning before shutting down the core; retained if cleaning fails
Original owner and file lease, call closeAsync again and try again. When finished, close the toolkit.

The network uses an independent OkHttp 4.12 client, which supports request, managed file multipart upload,
download, progress, response-header, and chunk events, plus WebSocket. The host explicitly lists HTTPS/WSS origin,
The client does not share host cookies and does not follow redirects; only explicit
`allowLoopbackForDevelopment = true` allows loopback HTTP/WS. request/response 1 MiB,
managed downloads/uploads 8 MiB, socket message and pending queue 1 MiB; event entry is subject to quantity and byte upper limit
limit, the final HTTP result waits for the previous event to be delivered. Number change and closure are actually canceled and waiting for I/O in transit.

RecorderManager uses the real MediaRecorder, independently checking `scope.record` and RECORD_AUDIO.
Currently only AAC ADTS, 100–60000ms, up to 4 MiB, default 8000Hz, two-channel, 48000bps,
60000ms. Supports the officially listed nine levels of 8000–48000Hz, and the encoding bit rate is based on the official range of the sampling rate for each level.
Verify separately; auto/mic audioSource and default audioMode are available, other input sources, modes and
frameSize Explicitly rejected. The temporary native file is imported into the same managed directory after completion. Hide, background, cancel, change number and close
Stop the microphone; if the release or deletion fails, the resource will be reserved for cleanup and retry.
Check the real output sampling rate and channel by MediaExtractor before importing; issued when the device encoder is silently downgraded
Error and remove staging, do not deliver incorrectly formatted files as successful results.

The system adapter installs the address book, calendar and QR code scanning API according to the host Manifest declaration. Address book required
WRITE_CONTACTS, calendar requires READ_CALENDAR / WRITE_CALENDAR; applet authorization and actual OS
Permissions are verified separately, and the system provider is submitted after confirming the contact or selecting a writable calendar in the native UI.
Transaction. calendar operations do not generate official mini-program links or signatures; Reminder advance amount is entered in seconds, Android only supports accurate
is expressed as a non-negative advance in full minutes.

Camera scanning uses Camera2 actual YUV frames and ZXing Core 3.5.3, supporting ordinary QR codes, barcodes,
Data Matrix, PDF417; currently does not provide photo album entry or WeChat code decoding. Other camera owners should be released first
device, and then open the QR code scanning UI. Cancel, hide, and close waits for CameraDevice.onClosed and the decoding thread to end.
[Maven dependencies and licenses](THIRD-PARTY-NOTICES.md) includes OkHttp, Okio, ZXing and
OkHttp public suffix License text for data; Gradle persists these texts to AAR assets.

NFC requires host explicit `enableNfc = true`, NFC permission and actual NfcAdapter, currently only
Install NDEF reader/writer. It uses real reader mode, tag connection, read and write, timeout and shutdown,
Does not declare ISO-DEP, MIFARE or HCE support. NDEF bytes and events are bounded, wait for number change I/O
Threads and discovery callbacks drained. The external real label has not yet been accepted.

SOTER support/enrollment queries check the actual fingerprint hardware and the host’s
`AndroidSoterAttestationProvider`. Returns empty supportMode/false when there is no provider,
Not registering startSoterAuthentication. The provider must run the real SOTER certification and sign the specified
challenge; SDK verification result of raw challenge and non-empty base64 signature, ordinary fingerprint cannot succeed
Replacement of trusted signatures. Currently only the fingerprint mode integration entry is provided.

Device adapter includes real clipboard, network status, battery, window brightness and always on, vibration, native system UI,
WGS84 positioning and sensor. The sensor currently supports normal sampling; GCJ-02 requires independent coordinate services.
The front desk uses LocationManager for continuous positioning, and supports start/stopLocationUpdate,
on/offLocationChange and on/offLocationChangeError. Single and continuous positioning share permission boundaries,
The mini-program is hidden or the activity enters the background to pause sampling, and then resumes according to the original owner when returning to the foreground; stop, change number
and close to remove the original listener. events retain only the latest location/error, serial delivery; background positioning is not registered.
BLE adapter has its own scanner and GATT, serial submission operations per device; shutting down does not shut down the host globally
Bluetooth. Canceling an in-flight GATT operation closes the connection to isolate late callbacks.

iBeacon resolves the real Apple manufacturer 0x004c / 0215 via standalone BluetoothLeScanner
Broadcast, providing start/stopBeaconDiscovery, getBeacons, on/offBeaconUpdate and
on/offBeaconServiceChange. Requires independent location and Bluetooth applet authorization, precise location OS permissions,
location/Bluetooth services are enabled, and the scan/connect permission corresponding to the Android version; the host cannot be
iBeacon scan declares neverForLocation, otherwise the system may filter these location broadcasts.
Up to 20 standard UUIDs, 64 results, 32 subscriptions; backgrounding/hiding pauses its own scanner,
Front desk recovery, account changes and close stop the work and discard callbacks from older generations. Android proximity is fixed at 0 according to the official contract.
accuracy is the path loss estimate based on the real RSSI and broadcast measured power, and is -1 when it cannot be estimated.
There is no need to open the BLE adapter API first, and the host radio does not need to be modified or closed.

Wi-Fi requires an explicit `enableWifi = true` from the host. Public OS implementation, system validation, application local-only
See network lease, maunal system settings, scan throttling and actual permission scope.
[Wi-Fi Access](examples/host-services/WIFI-APIS.en.md).

The file adapter exposes the managed `wxfile://user/` virtual path and the underlying asynchronous file API. sync
The FileSystemManager method is executed directly by the core, using the same managed directory as the real flock operation lock. Each block can be read and written up to 1 MiB, a single file is 8 MiB, and a single account application is 32 MiB; writing is pending.
Synchronous and atomic replacement. Guest rejects and hides the `.weapp-*` internal lock file. Native camera photos are written to the same namespace. The host path is not passed into the applet.
SDK always sets app-private `managedFilesRoot` when creating native session, independent of key-value storage
Configuration. Files and authorization records only use core `managedFileScope` returned by the runtime and verified against the application/account identity
`root` and `auxiliaryRoot`; retain the shared lease until resource cleanup is complete. Account deletion calls core uniformly
`deleteAccountData`, removes files and authorization records after cleaning up ACK and surface detach.

The media driver creates resources from the accepted node of `hostComponents` through `hostComponentFrame`
Transmit real Camera2/video decoded RGBA frames and hand them over to original Skia PaintPlan to complete transformation, cropping and
Hierarchical synthesis. The current preview limit is 320×240, 5 fps, and the queue only retains the latest frames to be submitted. Android video
Currently reads managed local files, plays audio using MediaPlayer and decodes by play position from the same file
Video frames; does not support live broadcast, DRM, recording, full screen and barrage. CameraContext takes a photo from the captured current
frames; high-resolution standalone stills not available. The context only accepts componentTarget issued by the runtime,
Cannot be replaced by the guest's node ID or ownerScopeId. Background, node uninstallation and number change stop equipment;
The camera is closed and waiting for CameraDevice.onClosed. Failure to clean will prevent new accounts from being displayed.

Identity and payment are separate host accesses:

```kotlin
val backend = HostServicesClient(URI("https://accounts.example.com"),
    hostToken = { call -> secureHostSession.tokenFor(call.context!!) })
val identity = AndroidHostIdentity(activity, backend)
val prepared = identity.prepareConfiguration(trustedConfiguration)
// 等待 prepared，configureHostServices 后安装 identity，之后才 load。
```

token closure always remains native; the applet only receives short-term, single-time, application-bound code. Application backend
Use your own confidential client to redeem the code. The issued code is not yet equal to the application being logged in;
`wx.checkSession` Query the valid sessions that have been redeemed by the server. Redemption completed, rights revoked, host credentials refreshed and
After returning to the foreground, the host calls `identity.refreshState(session, trustedContext)`; synchronization
`weappContainer.identity.getState` and onChange come from this real server state.
Refresh reads the native state before making the request, verifies the incoming application/account and freezes the actual
Internal sessionId; only ready owner can initiate the refresh, switching or empty owner directly rejects it.
The old request still carries the identity when it is completed and will not be re-bound to the new owner. carried by callback
The original ApiCallContext is also transparently transmitted as it is. the runtime uses the complete application/package identity, account epoch and internal session
Reject outdated snapshots; this verification does not require manual native refresh corresponding to a pending API call.
Before initiating asynchronous identity refresh and when submitting a snapshot with context, the SDK requires a runtime statement
`hostServices.snapshotOwnerContext == "1.0"`; If this mark is missing, it will be reported clearly.
`ERR_UNSUPPORTED_CAPABILITY`, to prevent the old runtime from silently ignoring the new owner check field.

The server reports `invalid_app_session`, `ERR_INVALID_APP_SESSION`, or publicly
When the same reason is reported in the `backendCode` field, the SDK attempts to refresh the identity snapshot before rejecting the original call.
Classification and refresh use the same normalization rule, and the original `errCode`, `errMsg` and public extensions passed to the applet
No rewriting. Retains the original calling error and last successful snapshot when the refresh fails itself; it does not fake a new unlogged state.

Information and mobile phone number prompts to first query the authorization policy from the trusted backend, and the native UI displays the server purpose.
The authorization record and subsequent code are bound to purpose and permissionVersion at the same time. desc passed in by the applet
The purpose is not determined; the synchronization server is refused and the snapshot is refreshed, and the cancellation semantics are cancelled. After the server-side policy changes,
The old authorization can no longer be used.

To change the number, replace the host security credentials first and wait within `accountCleanupHandler`
`identity.prepareAccountState(session, operationId, newTrustedContext)`, then complete the cleanup
future. New snapshots must be prepared before clearing ACK; any snapshots of the old account cannot be reused.

Continuous number changes immediately block the old identity, but the cleanup hook actually terminates the serial execution. new epoch hook and
ACK must wait for the previously started future to complete; canceling or ignoring the old results does not mean that the native resources have been released.
Stale hooks that have not yet started will be skipped. Close also waits for the entire cleanup queue; failure can be explicitly retried in the current operation.
The host should not complete the hook future until the exact device, file, and background work have all finished.
Available `node tools/test-account-cleanup.mjs --kotlin-lib <compiler-lib-directory>`
Compile run cancellation, late finalization, failed retry and shutdown barrier tests.

`AndroidAppPayments(activity, backend, wechatAppId)` registers `wx.requestPayment` and `weappContainer.payment.request` at the same time, only accepts `orderId` from the server, rejects
JSAPI signature impersonates APP Pay. the host explicitly integrates the official WeChat/Alipay Android SDK, and configure Manifest,
Application ID, callback activity and obfuscation rules. WeChat callback from official WXApi.handleIntent
IWXAPIEventHandler passed to `onWechatPayResponse`. After the official callback, back-end verification is still required to confirm paid.
Report success to the mini-program. Canceling the wait will not close the order or refund; the old payment callback will not be assigned to the new account.

Actual device permissions, Bluetooth peripherals, camera images, media frames, account switching and real merchant transactions must still be based on the current
Native products are individually accepted and cannot be replaced by mock responses, directory listings, or source code existence.

<a id="开发闭环验证"></a>
## Develop closed-loop verification

Session invalidation refresh has independent limited native regression, which can reuse already running arm64 emulator and explicitly selected
preview.6.0 native slice, does not rely on camera, contacts, payment or authorization prompts:

```sh
node tools/identity-refresh-probe.mjs \
  --serial emulator-5554 \
  --native-dir /absolute/native/aarch64-linux-android \
  --reference-root /absolute/host-services-reference \
  --out /absolute/new-identity-probe-output
```

It calls the HTTP/SQLite reference service through the public SDK, JNI and real QuickJS, checking for three invalid error forms,
callback/complete/Promise One-time settlement, original error takes precedence over refresh and disconnection, and the actual old account snapshot response is in the new
Rejected by native when released after account is presented. The native producer and SDK source code summary used in the output record,
actual system/protocol behavior and cleanup of task resources. The probe retains the borrowed simulator, only uninstalls its own package and reverse, closes
Own services. The handwritten pack, the current source code wrapper and the explicitly selected historical runtime form a development regression.
Not available for installation as a new SDK archive, compiler delivery, or current release acceptance.

`test/HostServicesProbeActivity.kt` and `tools/host-services-probe.mjs` use the full public SDK,
Real JNI and JS engines running authored pack. The probe automatically starts the independent loopback reference business service,
Only inject temporary control token into native Activity; guest uses public business address to redeem login code,
Obtain businessSession and create a fixed test product order. Tested with actual Android input click Skia
Mobile phone number button, and handle the native consent UI for data, mobile phone number, camera and reference payment respectively.

After private core checkout has generated arm64 Android dev slice, select an already running emulator:

```sh
node sdk/android/tools/host-services-probe.mjs \
  --serial emulator-5580 \
  --native-dir /absolute/development/aarch64-linux-android \
  --out /absolute/new-probe-output \
  --accept-app-consent
```

Independent SDK checkout also needs to specify the reference service directory through `--reference-root`. Probe refuses to cover existing
Output, test application with the same name and adb reverse port, and uninstall your own application and delete your own UI at the end
Temporary files, revoke its own reverse mapping, and close its own business services; the simulator is managed by the caller.

Current [Development Record](test/evidence/host-services-development.json) binds unreleased dev library,
SDK/fixture/JNI Source code and real GPU screenshot hashes, and record a consistent core source code list before and after the build.
Already redeemed through login and snapshot refresh, independent authorization, server-paid reference order, synchronous/asynchronous operations in the same directory
File, A/B number change isolation, Camera2 JPEG and Skia synthesis, account data deletion and resource cleaning. Here's
The camera comes from the simulator virtual scene, and the payment comes from the explicit reference channel, which does not represent physical devices, Bluetooth peripherals,
Official wallet, merchant transactions, compiler compatibility or official release acceptance.

[Network and recording development record](test/evidence/host-services-network-recording-development.json)
Record 14 passed extension probes: real HTTP response headers/chunks, multipart file, download bytes,
WebSocket echo/close, actual AAC ADTS frame, account changes close old requests/sockets, and an active injection
After the cleanup fails, confirm that the core is not closed in advance and the second close is successful. loopback network and emulator
Microphones do not equate to production TLS, physical recording, or release acceptance.

The system extension probe is additionally submitted through the real contact and calendar provider, and the 600-second reminder is independently checked
Written as 10 minutes, SOTER is not available when there is no signer. The probe only deletes its own random markers
Contacts and temporary calendar account; scan code identification, physical NFC and real SOTER signature are still
Independent equipment acceptance scope.

[Front-end positioning and authorization policy development record](test/evidence/host-services-location-policy-development.json)
Retain 18 independent probes. Explicit `--probe-foreground-location` uses the emulator console to inject GPS,
The applet receives the real LocationManager coordinates. After stopping and continuing to inject, it still no longer receives events; the original data is different from
The mobile phone number UI actually displays the back-end registration purpose, authorizes and completes the redemption. Tools, native packaging, fixtures and completeness
Refer to the backend source code to check the hash file by file before and after execution; this is still simulator evidence for developing snapshots.

[Beacon and recording development record](test/evidence/host-services-beacon-recorder-development.json)
Separately record the explicit rejection of the default dual channel in this simulator after it was downgraded from native to mono, and then explicitly mono
Actual AAC's 8000Hz/channel metadata, and incompatible rate rejection. The default two-channel physical device recording is still
Pending acceptance. Beacon
The new source code is completely compiled, and the JVM executes the authored broadcast byte UUID, unsigned major/minor, and distance estimation.
and tests for malformed lengths and prefix boundaries; This does not constitute an actual Beacon transmitter, permission change, or RF acceptance.

Native snapshot owner validation belongs to the negotiated runtime. The SDK passes the complete captured context unchanged and preserves the runtime error, including `ERR_CANCELLED` for a retired owner; it updates its rebuild cache only after successful validation. A credential refresh cannot replace this owner check.

Backend errors keep their container classification (`code`, `message`) separate from public API
fields. The adapter merges non-null extension fields in this order, with later sources winning:
response envelope, envelope `details`, nested `error.details`, then nested `error` fields. It
excludes the structural `error`/`details` and classification `code`/`message` keys from the public
projection. Identity rejection preserves projected `errCode` and `errMsg`; only absent values
receive the adapter defaults. Canonical `invalid_request`, `permission_denied`, `timeout`,
`cancelled`, `unsupported` and their uppercase `ERR_*` forms keep the corresponding container
classification. Consent and invalid/stale identity session codes map to `permission_denied`.
The core `ERR_UNSUPPORTED_CAPABILITY` code and `unsupported_capability` alias map to `unsupported`.
Unknown provider codes map to `invalid_request`. The projection carries a typed `HostErrorCode`
directly to `HostReply.reject` so serialization cannot collapse these categories.
Run `npm run test:host-backend-error -- --kotlin-lib <compiler-lib>
--json-jar <org-json.jar>` for the pure projection and final identity rejection regression.
