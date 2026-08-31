# What crosses the Layer 4 boundary

Reference screen at 23 rows: 160 widget nodes, 572 changes.

Everything below is captured from Zipline's `Call.encodedCall`, which is the
literal string handed to `CallChannel.call(callJson: String): String`. It is
JavaScript Object Notation (JSON), UTF-8, uncompressed.

## One tap-sized crossing, verbatim

93 bytes on the wire:

```json
{"service":"zipline/host-1","function":"W0h7dMQE","args":[{"q":1,"g":[["c",{"i":1,"w":2}]]}]}
```

The outer object is Zipline's call envelope: `service` names the bound service,
`function` is the first six bytes of the SHA-256 of the function signature,
base64-encoded, and `args` is the argument list. Only the contents of `args` are
Dogwood's: 33 of these 93 bytes. The envelope is a fixed
per-call overhead, which matters for small batches and disappears into the noise
for large ones.

## One screen-open crossing

19855 bytes on the wire. First 600:

```json
{"service":"zipline/host-1","function":"W0h7dMQE","args":[{"q":1,"g":[["c",{"i":1,"w":2}],["m",{"i":1,"e":[{"t":2,"v":1},{"t":1,"v":16}]}],["c",{"i":2,"w":4}],["m",{"i":2,"e":[{"t":2,"v":1},{"t":4,"v":180},{"t":1,"v":8}]}],["c",{"i":3,"w":1}],["p",{"i":3,"p":1,"v":"Dogwood Reference Product"}],["m",{"i":3,"e":[{"t":1,"v":4},{"t":2,"v":1}]}],["c",{"i":4,"w":1}],["p",{"i":4,"p":1,"v":"A product-detail-like screen used only for measurement."}],["m",{"i":4,"e":[{"t":1,"v":4}]}],["c",{"i":5,"w":3}],["m",{"i":5,"e":[{"t":2,"v":1},{"t":1,"v":4},{"t":5,"v":1}]}],["c",{"i":6,"w":1}],["p",{"i":6,"p":1," ...
```

## Where the screen-open bytes go

Each change encoded on its own, array-polymorphic, as Zipline encodes it.
Separator bytes are excluded, so the total is slightly under the wire size.

| Change kind | Count | Bytes | Share | Mean bytes each |
| --- | ---: | ---: | ---: | ---: |
| ModifierSet | 160 | 7136 | 37.1% | 44.6 |
| ChildAdd | 160 | 5360 | 27.9% | 33.5 |
| PropertySet | 92 | 3385 | 17.6% | 36.8 |
| Create | 160 | 3329 | 17.3% | 20.8 |
| **total** | **572** | **19210** | | |

## Repetition in the modifier chains

160 modifier chains cross, of which **13 are distinct**.
The chains alone are 7136 bytes, 37.1% of the batch.

| Occurrences | Chain |
| ---: | --- |
| 70 | `{"i":0,"e":[{"t":1,"v":2}]}` |
| 25 | `{"i":0,"e":[{"t":3,"v":1},{"t":1,"v":4}]}` |
| 24 | `{"i":0,"e":[{"t":2,"v":1},{"t":1,"v":8},{"t":5,"v":1}]}` |
| 23 | `{"i":0,"e":[{"t":4,"v":48},{"t":1,"v":4}]}` |
| 8 | `{"i":0,"e":[{"t":1,"v":2},{"t":4,"v":32}]}` |
| 2 | `{"i":0,"e":[{"t":1,"v":4}]}` |
| 2 | `{"i":0,"e":[{"t":2,"v":1},{"t":1,"v":4},{"t":5,"v":1}]}` |
| 1 | `{"i":0,"e":[{"t":2,"v":1},{"t":1,"v":16}]}` |
| 1 | `{"i":0,"e":[{"t":2,"v":1},{"t":4,"v":180},{"t":1,"v":8}]}` |
| 1 | `{"i":0,"e":[{"t":1,"v":4},{"t":2,"v":1}]}` |
