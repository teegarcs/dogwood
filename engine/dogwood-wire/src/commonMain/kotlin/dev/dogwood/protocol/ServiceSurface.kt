/*
 * Project Dogwood -- the host service surface's revision, where every host can read it.
 *
 * These two constants described a Zipline service surface and therefore lived beside it in
 * `dogwood-protocol`, which links Zipline. That put them out of reach of any host that cannot --
 * and `Bindings.kt` reports them in `segmentVersions` from common code that now compiles for
 * WebAssembly as well (Layer 5 ADR-041). A revision number is data, not transport, so it belongs
 * in the transport-free module. The package is unchanged, so no import moved.
 */
package dev.dogwood.protocol

/** The service-surface revision. Reported as `segmentVersions[SERVICES_SEGMENT]`. */
const val SERVICES_SEGMENT = "dogwood.services"

const val SERVICES_VERSION = 2
