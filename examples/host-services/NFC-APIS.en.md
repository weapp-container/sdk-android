# Android NFC

[简体中文](NFC-APIS.md) · **English**


The `enableNfc: false` feature remains disabled by default and maintains compatibility with existing NDEF interfaces.

NFC functionality is explicitly enabled and requires both a valid NfcAdapter and the Manifest NFC permission before registration.

The 8 `get*` methods of NfcAdapter, along with discovery, stop, subscription, and tech-related operations, and the 7 leaf functions of Ndef, as well as the `connect`, `close`, `isConnected`, `setTimeout`, `transceive`, and `getMaxTransceiveLength` methods for IsoDep, MifareClassic, MifareUltralight, NfcA, NfcB, NfcF, and NfcV, all delegate to Android's platform TagTechnology implementation.

- `IsoDep.getHistoricalBytes` and `NfcA.getAtqa/getSak` return raw, native data.
- For each discovered tech, the actual tag's `techList` is used as the source.
- If a hardware device or tag does not support a particular technology, `get(tag)` will explicitly fail — the presence of an API does not imply that any device supports Mifare Classic.
- Missing `historicalBytes` is not artificially generated or faked.

Only this Activity's reader mode is enabled. NFC radio is not toggled, and global technology disconnection is not performed.

Each child object is bound to a real tag with a unique epoch upon first binding. For new tags, session termination, background state, or owner retirement, previously bound child objects become invalid and must be reacquired. A single reader executes only one native I/O operation at a time. The input of `transceive` is limited by the actual `maxTransceiveLength`, and output is capped at 1 MiB for NDEF, with a maximum of 64 records per NDEF message.

NfcB and NfcV lack platform timeout setters; a bounded overall timeout is used, and the reader's TagTechnology is terminated. For other technologies, the actual Android timeout is applied. Native `close` requests do not falsely imply that the worker has ended.

Resource cleanup waits for all native worker termination states and the discovery queue barrier. On failure, resources are retained for retry.

The event queue is limited to a maximum of 16 entries and 8 MiB; backpressure triggers immediate termination of the current reader.

Actual Kotlin compilation and `test/AndroidNfcTerminalTest.kt` validation of native terminal boundaries are performed.
No claims are made regarding actual tag read/write operations, chip-specific technology support, or UI permission validation.
