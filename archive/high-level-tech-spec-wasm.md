# Technical Specification (v2.0): Zero-Bridge Server-Driven Compose via Dynamic WebAssembly (Wasm) Linking

**Document Version:** 2.0 (Expanded Architecture, Language Stacks, & Implementation Deep Dives)  
**Target Platforms:** Android (API 26+) & iOS (iOS 15+)  
**Core Thesis:** Eliminate application-level UI component bridges by compiling Kotlin Compose code into compact WebAssembly bytecode modules that dynamically link at runtime to pre-compiled native Compose Multiplatform and Skiko engines on the user's device.

---

## 1\. Executive Summary & Strategic Motivation

### The Problem with Traditional SDUI & Component Bridges

Traditional Server-Driven UI (SDUI) architectures rely on coarse-grained JSON/Protobuf schemas mapping to platform-native components (e.g. `Column`, `Text`, `Button`). When attempting to scale this into dynamic Server-Driven Experiences (SDE), teams hit the **"Widget Bridge Bottleneck"**:

1. **Massive Maintenance Burden:** Every new Modifier (`graphicsLayer`, `blur`, `pointerInput`), custom layout (`SubcomposeLayout`, `Layout`), custom drawing (`Canvas`, `Path`), or animation curve requires updating the bridge schema, serialization protocol, and dual client renderers (SwiftUI and Compose).  
2. **Feature Lag & Divergence:** Dynamic screens are constrained to a tiny, static subset of what Jetpack Compose / SwiftUI offer, preventing teams from shipping rich, custom interactions.  
3. **High Latency for Local Interactions:** Interactive micro-state (form validation, conditional branching, gesture tracking) either requires network roundtrips or pre-baked client action handlers.

### The Objective: Zero-Bridge Dynamic Compose

Build an execution engine where mobile developers author arbitrary Kotlin Jetpack Compose code that compiles into compact WebAssembly bytecode modules (**15 KB – 50 KB**). These modules are delivered Over-The-Air (OTA) and execute inside an embedded Wasm interpreter on Android and iOS.

Crucially, **the dynamic module does not bundle the Compose runtime or rendering engine.** Instead, it uses **Wasm Dynamic Linking (`dylink.0`)** to bind directly against the full Compose Multiplatform (CMP) and Skiko (Skia/Metal/Vulkan) engine already compiled into the native app binary.

---

## 2\. System Layering, Language Stacks, & Interop Blueprint

The system is composed of 7 discrete layers. Below is the exact breakdown of the programming language, framework, and interop mechanism for each:

┌─────────────────────────────────────────────────────────────────────────────────────────┐

│ LAYER 1: DEVELOPER AUTHORING TIER                                                       │

│ • Language: Pure Kotlin (Kotlin 2.0+)                                                   │

│ • Syntax: Idiomatic Jetpack Compose (@Composable, remember, mutableStateOf, Modifier)  │

└──────────────────────────────────────────┬──────────────────────────────────────────────┘

                                           │ Kotlin K2 Compiler AST / FIR

                                           ▼

┌─────────────────────────────────────────────────────────────────────────────────────────┐

│ LAYER 2: BUILD PIPELINE & GRADLE TOOLING (Server / CI)                                  │

│ • Language: Kotlin (Custom Gradle Plugin \+ Kotlin K2 IR Compiler Plugin)                │

│ • Post-Processing: C++ / Rust CLI (Binaryen wasm-opt \+ Ed25519 Signer)                  │

│ • Output: checkout\_flow.wasm (15 KB – 40 KB, imports external Compose symbols)          │

└──────────────────────────────────────────┬──────────────────────────────────────────────┘

                                           │ OTA Delivery (HTTPS / CDN)

                                           ▼

┌─────────────────────────────────────────────────────────────────────────────────────────┐

│ LAYER 3: THE WIRE BYTECODE CONTAINER                                                    │

│ • Format: Standard WebAssembly (.wasm) with dylink.0 dynamic linking custom section     │

└──────────────────────────────────────────┬──────────────────────────────────────────────┘

                                           │ Byte Array Ingestion

                                           ▼

┌─────────────────────────────────────────────────────────────────────────────────────────┐

│ LAYER 4: EMBEDDED WASM VIRTUAL MACHINE                                                  │

│ • Language / Engine:                                                                    │

│   \- Android: C99 (Wasm3 via JNI) or Pure JVM (Dylibso Chicory in Kotlin/Java)           │

│   \- iOS: C99 (Wasm3 via Swift C-Bridge) or Pure Swift (WasmKit)                         │

│ • Responsibility: Bytecode validation, stack machine loop, memory slicing (\_\_memory\_base)│

└──────────────────────────────────────────┬──────────────────────────────────────────────┘

                                           │ Symbol Lookup & FFI Binding

                                           ▼

┌─────────────────────────────────────────────────────────────────────────────────────────┐

│ LAYER 5: THE HOST SYMBOL REGISTRY & MARSHALLING TIER                                    │

│ • Language: Kotlin Multiplatform (KMP) \+ Auto-generated C wrappers (via KSP)            │

│ • Responsibility: Maps Wasm imports (GOT.func, GOT.mem) to on-device Compose functions; │

│   unpacks strings, bitmask modifiers, and table callback indices (\<0.1ms dispatch).     │

└──────────────────────────────────────────┬──────────────────────────────────────────────┘

                                           │ Direct Function Invocations

                                           ▼

┌─────────────────────────────────────────────────────────────────────────────────────────┐

│ LAYER 6: ON-DEVICE COMPOSE MULTIPLATFORM & SKIKO ENGINE                                 │

│ • Language: Kotlin (Compose Runtime SlotTable & Compose UI LayoutNode Engine)           │

│ • Rendering Backend: C++ / Kotlin Native (Skiko \-\> Metal on iOS, Vulkan/Canvas on Andr)│

└──────────────────────────────────────────┬──────────────────────────────────────────────┘

                                           │ GPU Rendered Buffers & Raw Touches

                                           ▼

┌─────────────────────────────────────────────────────────────────────────────────────────┐

│ LAYER 7: HARDWARE VIEWPORT & NATIVE APP HOST                                            │

│ • iOS: Swift / UIKit (CAMetalLayer / ComposeUIViewController)                           │

│ • Android: Kotlin / Android SDK (SurfaceView / AndroidView)                             │

└─────────────────────────────────────────────────────────────────────────────────────────┘

---

## 3\. Deep Dive: Component \#1 — The Host Symbol Registry

The Host Symbol Registry is the on-device "switchboard" that connects Wasm bytecode calls (`i32`/`i64` primitives) to real Kotlin/C++ Compose Multiplatform functions in the app binary.

### A. Argument Marshalling & Data Passing Mechanics

Because WebAssembly only natively understands numeric primitives (`i32`, `i64`, `f32`, `f64`), complex high-level types are marshaled as follows:

1. **Strings (Zero-Copy Memory Reads):**  
     
   * *Wasm Guest:* Passes an `i32` byte offset in its linear memory \+ `i32` byte length.  
   * *Native Host:* Directly reads the UTF-8 bytes from Wasm memory without allocating intermediate string objects:  
       
     // C++ Host Symbol Wrapper  
       
     void host\_Text(int32\_t str\_offset, int32\_t str\_len, int32\_t composer\_ptr) {  
       
         const char\* raw\_str \= (const char\*)(g\_wasm\_memory\_base \+ str\_offset);  
       
         std::string\_view text(raw\_str, str\_len);  
       
           
       
         // Calls native Compose Multiplatform Text implementation  
       
         ComposeRuntimeBridge::emitText(text, (Composer\*)composer\_ptr);  
       
     }

     
2. **Lambdas & Event Callbacks (Function Table Dispatch):**  
     
   * *Wasm Guest:* Stores the lambda in its local table and passes an `i32` table index (e.g. `table_slot = 42`).  
   * *Native Host:* When the user taps the button on screen, the host invokes the Wasm function table slot:  
       
     void host\_onButtonClick(int32\_t table\_slot) {  
       
         m3\_CallIndirect(g\_wasm\_runtime, table\_slot);  
       
     }

     
3. **Modifiers (Compact Nan-Boxing / Bitmasks):**  
     
   * Common layout modifier chains are packed into a 64-bit integer (`i64`):  
     * Bits `0..15`: Modifier Type Flags (`FILL_MAX_WIDTH | PADDING | CLIP`).  
     * Bits `16..31`: Padding Values (Packed horizontal & vertical dp).  
     * Bits `32..47`: Shape / Radius Flags.  
     * Bits `48..63`: Alpha / Opacity.  
   * This eliminates object allocations for standard modifier chains.

### B. Automated Code Generation via KSP (Kotlin Symbol Processing)

To prevent writing hundreds of manual C wrappers, an automated **KSP Processor** runs during the native app build. It scans the Compose Multiplatform API surface and auto-generates the registration table:

// Generated by KSP:

fun registerComposeSymbols(runtime: WasmRuntime) {

    runtime.linkFunction("compose\_runtime", "startRestartGroup", ::host\_startRestartGroup)

    runtime.linkFunction("compose\_runtime", "endRestartGroup",   ::host\_endRestartGroup)

    runtime.linkFunction("compose\_ui",      "Layout",            ::host\_Layout)

    runtime.linkFunction("compose\_material","Button",            ::host\_Button)

    runtime.linkFunction("compose\_material","Text",              ::host\_Text)

}

---

## 4\. Deep Dive: Component \#2 — The Build Pipeline & Gradle Plugin

The Build Pipeline automates the transformation of standard Kotlin Compose code into a dynamically-linked `.wasm` binary.

### A. The 4 Pipeline Stages

1. **Stage 1: Frontend & FIR Parsing (Kotlin 2.0+):**  
   * Validates syntax and resolves dependencies against a lightweight **Compose Header/Stub Library**.  
2. **Stage 2: Compose K2 IR Plugin Transformation:**  
   * The Compose Compiler Plugin lowers `@Composable` functions to insert `$composer` tracking, slot keys, and restart groups:  
       
     // Transformed IR signature:  
       
     fun CheckoutScreen($composer: Composer, $changed: Int) {  
       
         $composer.startRestartGroup(10842)  
       
         // ... slot management ...  
       
     }

     
3. **Stage 3: Modular Wasm Compilation (Stub Linking):**  
   * Instead of bundling the Compose Runtime, Layout, and Skia libraries, the compiler treats all Compose functions as **external unresolved imports**:  
       
     (import "compose\_runtime" "startRestartGroup" (func $startRestartGroup (param i32)))  
       
     (import "compose\_material" "Button" (func $Button (param i32 i32)))  
       
   * Emits the `dylink.0` custom section specifying memory requirements and required imports.  
4. **Stage 4: Post-Processing & Optimization (`wasm-opt` \+ Signing):**  
   * Invokes Binaryen's `wasm-opt -O3 --strip-debug` to eliminate dead code and shrink the payload to **\~15 KB – 40 KB**.  
   * Cryptographically signs the binary with an Ed25519 private key.

---

## 5\. Critical Technical Challenges & Gaps (Risk Analysis)

1. **WasmGC vs. Linear-Memory in Embedded Mobile Runtimes:**  
   * *Problem:* Standard Kotlin/Wasm relies on WasmGC, which lightweight mobile interpreters (Wasm3) do not natively support.  
   * *Mitigation:* In the near term, dynamic screens compile to linear-memory Wasm with a lightweight internal allocator (`wee_alloc`). On Android, Chicory translates bytecode directly to Dalvik/ART; on iOS, JavaScriptCore's Wasm engine can be used.  
2. **Text Input & Software Keyboard (IME):**  
   * *Problem:* Canvas rendering bypasses native `UITextField` / `EditText`.  
   * *Mitigation:* Compose Multiplatform's `PlatformTextInputService` triggers a native host callback `host_show_keyboard(config)`, piping entered characters back into Wasm state.  
3. **Screen Reader Accessibility (VoiceOver & TalkBack):**  
   * *Problem:* A raw GPU canvas is invisible to screen readers.  
   * *Mitigation:* The in-Wasm Compose engine exports its `SemanticsNode` tree across shared memory, which the native host mirrors to `UIAccessibilityElement` (iOS) and `AccessibilityNodeInfo` (Android).  
4. **Apple App Store Policy (Guideline 2.5.2):**  
   * *Compliance:* Execution is strictly interpreted (no JIT) inside an isolated sandbox, updating feature flows within the existing app domain.

---

## 6\. Implementation Roadmap & Milestones

* **Phase 1 (Weeks 1–4):** K2 Compiler Plugin Stub Emitter \+ Wasm3 Test Harness.  
* **Phase 2 (Weeks 5–8):** Compose SlotTable / Runtime linking \+ KSP Symbol Registry generator.  
* **Phase 3 (Weeks 9–12):** Skiko GPU Viewport (Metal/Vulkan) \+ Touch & Keyboard integration.  
* **Phase 4 (Weeks 13–16):** OTA cryptographic loader \+ Semantics accessibility bridge \+ 120 FPS performance tuning.
