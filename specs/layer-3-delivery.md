# Layer 3: Over-The-Air (OTA) Delivery & Security

## 1. Responsibilities & Scope
Layer 3 is the bridge between the remote server and the local device's WebAssembly (Wasm) engine. It is strictly responsible for the secure delivery, verification, and storage of the dynamic Server-Driven Experience (SDE) payloads.

**What it does:**
- Fetches the compiled `.wasm` bytecode and its associated Manifest over HTTP/HTTPS.
- Cryptographically verifies the payload using Ed25519 public-key signatures to ensure no malicious code was injected in transit.
- Caches the validated `.wasm` bytecode locally on the device's disk so the dynamic UI can render instantly offline on subsequent app launches.

**What it does NOT do:**
- It does **not** execute the `.wasm` payload.
- It does **not** know what the payload contains (it treats the bytecode strictly as an opaque array of bytes).

## 2. Technical Stack & Dependencies
- **Language:** Kotlin Multiplatform (KMP)
- **Core Delivery Framework:** Fork of [Cash App's ZiplineLoader](https://github.com/cashapp/zipline/tree/trunk/zipline-loader)
- **Networking:** [OkHttp](https://square.github.io/okhttp/) (Android) / NSURLSession (iOS)
- **Local Caching:** [SQLDelight](https://cashapp.github.io/sqldelight/) and [Okio](https://square.github.io/okio/)
- **Security:** Ed25519 Cryptography.

## 3. Internal Architecture & Data Flow

Rather than building a complex networking and caching layer from scratch, we will reuse Cash App's battle-tested `zipline-loader` module. However, because standard Zipline is hardcoded to feed the downloaded bytes into a QuickJS Javascript engine, we must fork the library and intercept the bytes just before execution.

```mermaid
flowchart TD
    subgraph Layer 3: Delivery & Security (ZiplineLoader Fork)
        Host[Native Host Application] --> |Requests Module| Loader[Dogwood ZiplineLoader]
        
        Loader --> CacheCheck{Is Payload in SQLDelight?}
        
        CacheCheck -- Yes --> FileRead[Read Bytes via Okio]
        
        CacheCheck -- No --> HTTP[OkHttp Network Fetch]
        HTTP --> Manifest[Download Zipline Manifest]
        Manifest --> Verifier[Ed25519 ManifestVerifier]
        
        Verifier --> |Signature Failed| Crash[Throw SecurityException]
        Verifier --> |Signature Valid| SaveCache[Save to SQLDelight Database]
        SaveCache --> FileRead
        
        FileRead --> Out[Raw .wasm ByteArray]
    end
    
    Out -.-> |Passed to Layer 4| Engine[Embedded Wasm Engine]
```

### Detailed Diagram Node Breakdown
* **Native Host Application:** The Android or iOS app. It asks the loader for a specific module (e.g., "CheckoutScreen").
* **Dogwood ZiplineLoader:** Our custom fork of the `ZiplineLoader` class. It orchestrates the entire flow.
* **CacheCheck (Is Payload in SQLDelight?):** ZiplineLoader queries the local SQLDelight database to see if the requested `.wasm` module hash already exists and is up to date.
* **Read Bytes via Okio:** If the file is cached (or after it is downloaded), the Okio file system library reads the `.wasm` file from disk into memory.
* **OkHttp Network Fetch:** If the cache misses, the loader executes a network request to the Content Delivery Network (CDN).
* **Download Zipline Manifest:** The loader first pulls a JSON manifest containing the SHA-256 hashes of the `.wasm` files and the Ed25519 cryptographic signature of those hashes.
* **Ed25519 ManifestVerifier:** A cryptographic module that uses a Public Key (embedded in the native app at compile time) to verify the signature of the Manifest. This guarantees the code was produced by our build servers.
* **Throw SecurityException:** If the signature does not match, the entire process halts to prevent remote code execution attacks.
* **Save to SQLDelight Database:** Validated payloads are written to the local disk cache so the app can boot offline next time.
* **Raw .wasm ByteArray:** The final output of this layer. It is simply a Kotlin `ByteArray` containing the WebAssembly binary.
* **Embedded Wasm Engine:** Layer 4. The native app takes the `ByteArray` and feeds it into Chicory/Wasm3.

## 4. Interfaces & Foreign Function Interface (FFI) Boundary
This layer does not cross a Foreign Function Interface (FFI) boundary. It is executed purely in the native Kotlin/Swift runtime of the host application.

- **Inputs:** A string URL or Module Name provided by the host app (e.g., `loadModule("profile_screen")`).
- **Outputs:** A validated Kotlin `ByteArray`.
- **Memory Ownership:** The `ByteArray` lives on the standard JVM or Kotlin/Native heap. It is the responsibility of Layer 4 (the Wasm engine) to copy these bytes into the secure, isolated WebAssembly linear memory sandbox. Once copied, the `ByteArray` is garbage collected by the host.

## 5. Implementation Roadmap
1. **Milestone 1: Fork ZiplineLoader:** Create a stripped-down fork of `cashapp/zipline` that removes all references to QuickJS, `Zipline`, and Javascript execution. We only want the `zipline-loader` module.
2. **Milestone 2: Key Distribution:** Generate Ed25519 key pairs. Configure the Server Build Pipeline (Layer 2) to sign the Manifest with the Private Key, and embed the Public Key directly into the Android/iOS application binaries.
3. **Milestone 3: Engine Hand-off Protocol:** Build the Kotlin API that allows the Native Host to seamlessly accept the `ByteArray` from the loader and pipe it directly into the embedded Wasm engine instance.
