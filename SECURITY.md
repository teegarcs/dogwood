# Reporting a security problem

**Use GitHub's private vulnerability reporting**, on the Security tab of this repository
("Report a vulnerability"). That opens a channel visible only to the maintainer. Please do not open
a public issue for anything in the scope below, and please do not open a pull request that fixes it
either — a fix in the open is a disclosure with extra steps.

If private reporting is unavailable to you for any reason, say so in an issue titled "security
contact request" with no detail in it, and you will be given somewhere to send the detail.

## What this project is, so you can calibrate

Dogwood downloads signed code and executes it on a device. That is the whole architecture, and it
is the reason a security report here is worth making. But two facts should shape your expectations:

- **Nothing is in production.** No application ships this, there are no production signing keys,
  and there is no hosted payload server. A defect found today costs a fix, not an incident.
- **It is maintained by one person.** You will get an acknowledgement within about a week and an
  honest answer about whether and when it will be fixed. There is no bounty, and there is no
  embargo process beyond "tell me first and I will credit you".

`0.1.0` makes no stability promise and none of this is API-frozen.

## In scope

Anything that breaks a boundary [`docs/security.md`](docs/security.md) claims to hold. Most
valuable, roughly in order:

- **A way to get unsigned or wrongly-signed code executed.** Bypassing Ed25519 manifest
  verification, the detached web sidecar check, or the rotation-key logic.
- **A way for a payload to reach something the host did not hand it.** Escaping the network
  allow-list, reading the file system, reaching a host object through the protocol, or bypassing
  the image origin policy.
- **A way to make a client render a control that lies about what it will do.** Defeating the
  affordance guard, the withholding rule, or the value clamps so that a skewed payload draws an
  enabled control the payload meant to disable.
- **A way to disable or downgrade a client's protections remotely.** Forcing the kill switch or the
  crash-loop quarantine into the wrong state, or getting the pre-flight dictionary check skipped.
- **A way to take a client down from a payload** that the runaway bounds are supposed to stop.

## Out of scope, and why

- **The Ed25519 keys committed in this repository.** They are throwaway development keys, committed
  on purpose so the samples build for anyone who clones, and labelled as such where they sit. A real
  key is passed in with `-PdogwoodSigningKey`. Finding them is not a finding.
- **The residual risks already written down.** [`docs/security.md`](docs/security.md) §4 lists seven
  things that deliberately do not exist, including in-process isolation from a hostile payload, an
  integrity check on the web guest script, and origin isolation on the web. Those are known
  positions with reasoning attached. A report that sharpens one into a concrete exploit is very much
  in scope; a report that restates the position is not.
- **"Zipline is not a sandbox."** Correct, and said in the first paragraph of the threat model. The
  guest is trusted code verified by signature, not untrusted code contained by isolation.
- **Anything that requires the attacker to already hold the signing key**, or to control the build
  server. Both are in the trusted computing base and the threat model says so.
- **Dependency advisories with no path to exploitation here.** A version bump is an ordinary issue
  or pull request, not a vulnerability report.

## If you are reviewing rather than reporting

[`docs/security.md`](docs/security.md) is the threat model: the assets, who is trusted with them,
every boundary that exists with the record and the test behind it, the seven that do not exist, and
what a real deployment still owes. It is written to be checked rather than believed, so each row
cites the decision record and the conformance claim you can go and read.
