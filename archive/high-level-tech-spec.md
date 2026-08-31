# Technical Specification: Project Dogwood

## Zero-Bridge Server-Driven Compose Engine via Embedded Zipline & Dynamic Compose Linking

**Document Version:** 1.0  
**Project Codename:** Dogwood  
**Target Platforms:** Android (API 26+) & iOS (iOS 15+)  
**Authors:** Mobile Platform Architecture Team

---

## 1\. Executive Summary & Problem Statement

### 1.1 The Problem with Traditional SDUI and Redwood

Traditional Server-Driven UI (SDUI) relies on JSON/Protobuf schemas mapping to coarse platform widgets (`Column`, `Row`, `Text`, `Button`). When attempting to scale this into dynamic Server-Driven Experiences (SDE), teams hit the **"Widget Bridge Bottleneck"**:

* **Schema Lock-In:** Every new Modifier (`blur`, `graphicsLayer`, `pointerInput`), custom layout (`SubcomposeLayout`, staggered grids), or custom vector drawing (`Canvas`, `Path`) requires updating the schema, rewriting dual-platform renderers (SwiftUI and Compose), and submitting an App Store binary release.  
* **The Redwood Constraint:** While [Cash App Redwood](https://code.cash.app/native-ui-and-multiplatform-compose-with-redwood) enables Kotlin-authored presenters inside [Zipline](https://github.com/cashapp/zipline), it enforces a strict `@Widget` schema and serializes widget mutation diffs across the bridge. Product teams cannot use arbitrary Jetpack Compose APIs without client binary updates.

### 1.2 The Dogwood Solution

**Project Dogwood** is a Zero-Bridge, Server-Driven Compose platform. It reuses **Cash App Zipline** for its production-hardened code push, Ed25519 cryptographic verification, and embedded [QuickJS](https://bellard.org/quickjs/) sandbox, but **completely replaces Redwood**.

Instead of serializing high-level widget diffs, Dogwood links dynamic Kotlin/JS code **directly to the on-device Compose Multiplatform (CMP) SlotTable and Skiko rendering pipeline**.

---

## 2\. Architectural Layers & Language Stacks

Dogwood is structured across 7 distinct architectural layers, ensuring clean separation of concerns:

┌─────────────────────────────────────────────────────────────────────────────────────────┐

│ LAYER 1: DEVELOPER AUTHORING TIER                                                       │

│ • Language: Pure Kotlin (Kotlin 2.0+)                                                   │

│ • Framework: Jetpack Compose / Compose Multiplatform (@Composable, remember, Modifier) │

└──────────────────────────────────────────┬──────────────────────────────────────────────┘

                                           │ Kotlin K2 Compiler IR Transformation

                                           ▼

┌─────────────────────────────────────────────────────────────────────────────────────────┐

│ LAYER 2: BUILD PIPELINE & GRADLE TOOLING (Server / CI)                                  │

│ • Language: Kotlin (Custom Gradle Plugin \+ Kotlin K2 IR Compiler Plugin)                │

│ • Frameworks: Zipline Gradle Plugin \+ Binaryen Optimization                             │

│ • Output: feature\_flow.zipline (\~40 KB, signed with Ed25519)                            │

└──────────────────────────────────────────┬──────────────────────────────────────────────┘

                                           │ HTTPS / CDN Delivery

                                           ▼

┌─────────────────────────────────────────────────────────────────────────────────────────┐

│ LAYER 3: OTA DELIVERY & CACHING PIPELINE                                                │

│ • Framework: ZiplineLoader (Cash App Zipline)                                           │

│ • Responsibility: SHA-256 integrity, Ed25519 verification, SQLite/Disk caching,        │

│   and automatic failover to embedded baseline assets.                                   │

└──────────────────────────────────────────┬──────────────────────────────────────────────┘

                                           │ Ingestion into Sandbox

                                           ▼

┌─────────────────────────────────────────────────────────────────────────────────────────┐

│ LAYER 4: EMBEDDED JAVASCRIPT / BYTECODE SANDBOX                                         │

│ • Engine: QuickJS (C99, embedded via Zipline)                                           │

│ • Responsibility: Executes Kotlin/JS state logic, coroutine dispatching, and            │

│   deterministic ARC reference-counted memory management.                                │

└──────────────────────────────────────────┬──────────────────────────────────────────────┘

                                           │ Direct Low-Level Engine Calls (Zero-Copy)

                                           ▼

┌─────────────────────────────────────────────────────────────────────────────────────────┐

│ LAYER 5: DOGWOOD HOST RUNTIME BRIDGE                                                    │

│ • Language: Kotlin Multiplatform (KMP) \+ C FFI Bindings                                 │

│ • Responsibility: Maps dynamic calls to on-device SlotTable, layout nodes, and          │

│   dispatches host device capabilities (Biometrics, Camera, Keystore, GPS).              │

└──────────────────────────────────────────┬──────────────────────────────────────────────┘

                                           │ Local Layout & Render Tree

                                           ▼

┌─────────────────────────────────────────────────────────────────────────────────────────┐

│ LAYER 6: ON-DEVICE COMPOSE MULTIPLATFORM & SKIKO ENGINE                                 │

│ • Language: Kotlin (Compose Runtime SlotTable & Compose UI LayoutNode Engine)           │

│ • Rendering Backend: Skiko / Skia (Metal on iOS, Vulkan/Canvas on Android)              │

└──────────────────────────────────────────┬──────────────────────────────────────────────┘

                                           │ Hardware Surface & Raw Events

                                           ▼

┌─────────────────────────────────────────────────────────────────────────────────────────┐

│ LAYER 7: PLATFORM HOST VIEWPORT                                                         │

│ • iOS: Swift / UIKit (CAMetalLayer via ComposeUIViewController)                         │

│ • Android: Kotlin / Android SDK (SurfaceView / AndroidView)                             │

└─────────────────────────────────────────────────────────────────────────────────────────┘

---

## 3\. End-to-End Handoff & Execution Pipeline

1. **Build & Package Time (CI / Server):**  
     
   * Developer writes standard Kotlin Compose (`@Composable fun DynamicPromoCard()`).  
   * Gradle Plugin runs Kotlin K2 IR Compiler Lowering.  
   * Injects `$composer`, slot table keys, and restart scopes.  
   * Compiles against Dogwood Compose Runtime stubs into Kotlin/JS.  
   * Bundles and signs output into `promo_card.zipline` (42 KB).

   

2. **OTA Ingestion & Verification (Device Launch):**  
     
   * `ZiplineLoader` downloads `promo_card.zipline`.  
   * Verifies Ed25519 cryptographic signature against embedded public key.  
   * Checks local SQLite disk cache; serves instantly if unchanged.  
   * Spawns sandboxed QuickJS context and evaluates the script.

   

3. **Host Binding & First-Frame Execution:**  
     
   * Dynamic script calls `DynamicPromoCard($composer)`.  
   * Calls resolve directly to the on-device Compose SlotTable cursor.  
   * On-device Compose UI measures layout constraints & modifier chains.  
   * Skiko issues hardware draw calls directly to Metal (iOS) / Vulkan (Android).  
   * Frame 1 rendered on screen in \<2.0 ms.

   

4. **Interactive Recomposition Loop (120 FPS):**  
     
   * User taps button \-\> Native Skiko hit-tests node at (x,y).  
   * Event dispatched into QuickJS callback slot \#104.  
   * Sandbox updates local state (`isExpanded = true`).  
   * Compose SlotTable scope invalidated; re-runs scope in \<0.8 ms.  
   * Re-renders locally with zero network roundtrips.

---

## 4\. The Dogwood Engine Bridge vs. Redwood

### 4.1 Why Redwood Imposes Restrictions

In Redwood, the communication protocol is a **Widget Mutation Diff Stream**:

// Redwood's approach: Must pre-declare every widget in a schema

@Widget(1)

data class Button(val text: String, val onClick: () \-\> Unit)

If you want to add a `blur`, `rotationZ`, or custom `drawPath`, Redwood cannot execute it unless the property was declared in the schema, implemented in native Swift, implemented in native Kotlin, and shipped through the App Store.

### 4.2 How Dogwood Eliminates the Bridge

In Dogwood, we do not bridge widgets. We bridge the **Compose Applier and SlotTable Driver**:

// Shared Dogwood Host Interface (Executed via ZiplineService)

interface DogwoodHostService : ZiplineService {

    fun startRestartGroup(key: Int)

    fun endRestartGroup()

    fun emitLayoutNode(nodeId: Int, measurePolicyKey: Int, packedModifiers: Long)

    fun emitDirectText(nodeId: Int, textOffset: Int, textLen: Int)

    fun emitDirectDraw(nodeId: Int, drawCommandBufferOffset: Int, bufferLen: Int)

    fun registerCallback(nodeId: Int, eventType: Int, callbackSlot: Int)

}

Because the native host has the full **Compose Multiplatform \+ Skiko** engine pre-compiled, it understands all layout policies, modifier chains, canvas draw calls, and animation transitions natively.

---

## 5\. Native Device Capabilities & Host-Delegated Networking

### 5.1 Host-Delegated Networking

Dynamic code running inside the QuickJS sandbox does not manage raw TCP/TLS sockets. All network traffic is delegated to the host's native network engine:

* OkHttp (Android) and URLSession (iOS) handle OAuth2 bearer tokens, SSL/certificate pinning, connection pooling, and HTTP/3.  
* Dynamic screens simply call `httpClient.get("...")`, which suspends across the bridge and receives the raw response.

### 5.2 Calling Native Hardware & Security APIs

Any native capability (FaceID, Biometrics, Camera, GPS, Apple Pay, Keystore) is exposed as a type-safe `ZiplineService`:

interface NativeDeviceBridge : ZiplineService {

    suspend fun authenticateBiometrics(prompt: String): Boolean

    suspend fun getCurrentLocation(): GpsCoordinates

    suspend fun triggerHaptic(type: HapticType)

    suspend fun launchApplePay(amountCents: Long): PaymentResult

}

// Usage in Dynamic Screen

@Composable

fun SecureCheckoutScreen(deviceBridge: NativeDeviceBridge, checkoutService: CheckoutService) {

    var isPaying by remember { mutableStateOf(false) }

    Button(onClick \= {

        coroutineScope.launch {

            isPaying \= true

            val success \= deviceBridge.authenticateBiometrics("Authorize Payment")

            if (success) {

                deviceBridge.triggerHaptic(HapticType.SUCCESS)

                checkoutService.submitOrder()

            }

            isPaying \= false

        }

    }) {

        Text("Pay with FaceID")

    }

}

---

## 6\. Memory Model & Zero-Copy FFI Performance

1. **`ArrayBuffer` / `Uint8Array` Direct Pointer Access:**  
   * QuickJS exposes `JS_GetArrayBuffer(ctx, &size, val)`.  
   * Returns a direct raw `uint8_t*` pointer to the bytes in memory.  
   * Skiko / Skia reads draw commands directly from this buffer with **zero memory copies**.  
2. **String Pointer Views:**  
   * QuickJS exposes `JS_ToCStringLen(ctx, &len, val)`.  
   * Returns a direct `const char*` pointer. Skia creates `SkTextBlob` directly from this pointer.  
3. **Nan-Boxed Modifier Bitmasks:**  
   * Modifiers are packed into 64-bit integer bitmasks (`Long`) passed directly in CPU registers without object allocation.

---

## 7\. Critical Risk Analysis & Edge-Case Engineering

1. **Soft Keyboards & Text Input (IME):**  
   * Dogwood connects Compose Multiplatform’s `PlatformTextInputService`. When an in-sandbox `BasicTextField` gains focus, it issues `host_show_keyboard(config)`. Characters entered via native keyboards (dictation, IME, autofill) are piped directly into Compose’s `TextFieldState`.  
2. **Screen Reader Accessibility (VoiceOver & TalkBack):**  
   * The on-device Compose engine automatically generates its internal **Semantics Tree** (`Modifier.semantics`). Dogwood synchronizes this tree with native `UIAccessibilityElement`s on iOS and `AccessibilityNodeInfo` on Android on every layout pass.  
3. **Apple App Store Compliance (Guideline 2.5.2):**  
   * QuickJS is a pure C interpreter with zero JIT machine code running in an isolated sandbox, updating feature workflows within the existing app domain.

---

## 8\. Implementation Roadmap & Milestones

* **Phase 1 (Weeks 1–4):** K2 IR Compiler Plugin & Zipline Harness (Stubs \+ QuickJS execution loop).  
* **Phase 2 (Weeks 5–8):** Compose Runtime & SlotTable Integration (DogwoodHostService \+ zero-copy buffers).  
* **Phase 3 (Weeks 9–12):** Skiko GPU Viewport, Input, & Native Device Bridge (Metal/Vulkan \+ Biometrics).  
* **Phase 4 (Weeks 13–16):** Production Hardening, a11y, & Performance Tuning (ZiplineLoader OTA caching \+ VoiceOver/TalkBack).

---

## 9\. Comprehensive Reference Materials & Source Links

1. [**Cash App Zipline (GitHub)**](https://github.com/cashapp/zipline)**:** The Kotlin Multiplatform runtime library for embedding QuickJS and managing OTA code delivery.  
2. [**QuickJS Javascript Engine**](https://bellard.org/quickjs/)**:** Fabrice Bellard's ultra-lightweight, embeddable C JavaScript interpreter.  
3. [**Compose Multiplatform (JetBrains)**](https://github.com/jetbrains/compose-multiplatform)**:** Declarative UI framework for Kotlin compiling natively to Android, iOS, Desktop, and Web.  
4. [**Skiko (Skia for Kotlin)**](https://github.com/JetBrains/skiko)**:** Kotlin Multiplatform bindings to Google's Skia 2D Graphics Engine with Metal/Vulkan backends.  
5. [**Skia Graphics Library**](https://skia.org/)**:** Google's high-performance 2D graphics engine powering Chrome, Android, and Flutter.  
6. [**Native UI and Multiplatform Compose with Redwood (Cash App Engineering)**](https://code.cash.app/native-ui-and-multiplatform-compose-with-redwood)**:** Overview of Redwood's schema-based Treehouse architecture.  
7. [**Shorebird System Architecture**](https://docs.shorebird.dev/code-push/system-architecture/)**:** Flutter code push architecture using an embedded Dart interpreter.  
8. [**WebAssembly Dynamic Linking Specification**](https://github.com/WebAssembly/tool-conventions/blob/main/DynamicLinking.md)**:** Bytecode Alliance tool convention for dynamic module linking (`dylink.0`).  
9. [**Binaryen WebAssembly Optimizer (`wasm-opt`)**](https://github.com/WebAssembly/binaryen)**:** Toolchain optimizer for dead code elimination and size reduction.  
10. [**Jetpack Compose Compiler Plugin Architecture**](https://developer.android.com/jetpack/androidx/releases/compose-compiler)**:** Official documentation on Compose K2 IR lowering transformations.
