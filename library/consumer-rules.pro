# JNI symbols bind to this library-owned class, independent of the embedding Activity.
-keep class com.sonofmagic.weapp.container.sdk.NativeBridge { *; }
# Optional official APP Pay dependencies are invoked only when configured by the host.
-if class com.tencent.mm.opensdk.openapi.WXAPIFactory
-keep class com.tencent.mm.opensdk.openapi.WXAPIFactory { *; }
-if class com.tencent.mm.opensdk.openapi.IWXAPI
-keep interface com.tencent.mm.opensdk.openapi.IWXAPI { *; }
-if class com.tencent.mm.opensdk.modelpay.PayReq
-keep class com.tencent.mm.opensdk.modelpay.PayReq { *; }
-if class com.tencent.mm.opensdk.modelbase.BaseReq
-keep class com.tencent.mm.opensdk.modelbase.BaseReq { *; }
-if class com.alipay.sdk.app.PayTask
-keep class com.alipay.sdk.app.PayTask { *; }
