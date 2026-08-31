# Host rendering strategy — snapshot mirror vs imperative

**Device:** Google sdk_gphone64_arm64, Android 15

60 iterations after 10 warm-ups; medians.

Apply-to-frame is quantised to the refresh interval, so ~33 ms means two vertical
syncs and therefore that the work fitted inside one frame. A figure above that is
the strategy missing frames.

| Nodes | Strategy | Batch | Apply | Bindings recomposed | Apply to end of frame |
| ---: | --- | ---: | ---: | ---: | ---: |
| 160 | snapshot mirror | 1 | 120 µs | 1 | 33358 µs |
| 160 | snapshot mirror | 10 | 298 µs | 10 | 33329 µs |
| 160 | snapshot mirror | 100 | 282 µs | 69 | 33198 µs |
| 160 | snapshot mirror | 1000 | 1451 µs | 69 | 32618 µs |
| 160 | imperative | 1 | 48 µs | 93 | 33301 µs |
| 160 | imperative | 10 | 45 µs | 93 | 33305 µs |
| 160 | imperative | 100 | 61 µs | 93 | 33294 µs |
| 160 | imperative | 1000 | 344 µs | 93 | 32583 µs |
| 1222 | snapshot mirror | 1 | 56 µs | 1 | 33374 µs |
| 1222 | snapshot mirror | 10 | 84 µs | 10 | 33325 µs |
| 1222 | snapshot mirror | 100 | 203 µs | 100 | 33185 µs |
| 1222 | snapshot mirror | 1000 | 1404 µs | 600 | 49080 µs |
| 1222 | imperative | 1 | 40 µs | 801 | 33312 µs |
| 1222 | imperative | 10 | 43 µs | 801 | 33286 µs |
| 1222 | imperative | 100 | 52 µs | 801 | 33337 µs |
| 1222 | imperative | 1000 | 262 µs | 801 | 49328 µs |
