# Native SDK Maven dependencies

The Android SDK declares these ordinary Maven dependencies. Gradle resolves the
transitive graph; these libraries are separate from the private Rust runtime
artifact and its own distribution-specific notices.

| Component | Version | License | Source |
| --- | --- | --- | --- |
| OkHttp | 4.12.0 | Apache-2.0 | https://github.com/square/okhttp/tree/parent-4.12.0 |
| Okio JVM | 3.6.0 | Apache-2.0 | https://github.com/square/okio/tree/parent-3.6.0 |
| ZXing Core | 3.5.3 | Apache-2.0 | https://github.com/zxing/zxing/tree/zxing-3.5.3 |

OkHttp and Okio are Copyright Square, Inc. ZXing is Copyright ZXing authors.
The Apache license is included at `licenses/Apache-2.0.txt`. The dependencies'
upstream notices and attribution continue to apply to the actual resolved inputs.

OkHttp also includes a public suffix list resource. Its actual 4.12.0 JAR notice
is preserved verbatim at `licenses/okhttp-publicsuffix-NOTICE.txt`; the Mozilla
Public License 2.0 is included at `licenses/MPL-2.0.txt`. The public suffix list
project and source are available at https://publicsuffix.org/ and
https://github.com/publicsuffix/list.

The Gradle SDK build copies these texts into the AAR assets under
`weapp-container/sdk-dependencies/`. An embedding distribution must retain the
notices required by its complete resolved dependencies and private runtime.

The development probe separately hash-pins its compiler JAR inputs in
`tools/network-dependencies.mjs`. This is source/build input identification;
it does not assert any official wallet, scanner hardware, NFC or release acceptance.
