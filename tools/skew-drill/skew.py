#!/usr/bin/env python3
"""Project Dogwood -- patch the surface to version N+1, one addition per containment claim.

Three additions, chosen so each exercises a different rule and so a version N client meets all
three on one screen:

  A2  a new component        -> an unknown widget tag becomes a placeholder, and the sibling
                                indices after it stay correct
  A3  a property on Badge    -> a widget with no affordance ignores it and still renders
  A4  a property on          -> a widget that *owns* an affordance is withheld entirely, because
      PrimaryButton             the client cannot read what the payload says about it

The surface is restored by the caller's trap; nothing here is meant to survive the run.
"""
import re
import sys

surface_path, codegen_path = sys.argv[1], sys.argv[2]

surface = open(surface_path).read()

# A2: a component the version N client has never heard of. Appended, so every existing local tag
# keeps its number -- the drill is about an unknown tag, not about a renumbering.
if "fun Callout(" not in surface:
    surface += '''
/**
 * Added by the skew drill (`tools/skew-drill`). Not committed.
 *
 * A component a version N client has never heard of, so it meets it as an unknown widget tag.
 */
@Composable
fun Callout(
  title: TextValue,
  modifier: Modifier = Modifier,
) {}
'''

def append_parameter(text: str, component: str, declaration: str) -> str:
    """Adds a parameter at the **end** of a component's list.

    At the end, not the front, and the lock is what taught this: property tags are assigned by
    position and are append-only, so inserting at the front renumbers every existing property and
    the generator refuses -- "PrimaryButton.enabled moved from property tag 2 to 3. Append to the
    surface instead of reordering or removing." That is the dictionary lock doing precisely its
    job, on a drill that exists to test the containment rules around it.
    """
    start = text.index(f"fun {component}(")
    end = text.index(") {}", start)
    return text[:end] + f"  {declaration}\n" + text[end:]


# A3: a property on a widget that owns no affordance.
surface = append_parameter(surface, "Badge", "subtitle: TextValue? = null,")

# A4: a property on a widget that *does* own one.
surface = append_parameter(surface, "PrimaryButton", "tone: String? = null,")

open(surface_path, "w").write(surface)

# Bump the dictionary version, which is what makes the payload declare itself newer.
codegen = open(codegen_path).read()
match = re.search(r'"--version", "(\d+)"', codegen)
if not match:
    sys.exit("could not find the dictionary version in the codegen build file")
current = int(match.group(1))
codegen = codegen.replace(f'"--version", "{current}"', f'"--version", "{current + 1}"')
open(codegen_path, "w").write(codegen)

# The guest has to actually render the new things, or the client never meets them. A
# self-contained block on the Diagnostics screen, with markers the checker can find.
guest_path = "samples/slice-guest/src/jsMain/kotlin/dev/dogwood/slice/AboutScreen.kt"
guest = open(guest_path).read()
if "SKEW-BEFORE" not in guest:
    anchor = "    Divider(modifier = Modifier.fillMaxWidth())\n"
    at = guest.index(anchor) + len(anchor)
    guest = guest[:at] + '''
    // Added by the skew drill (`tools/skew-drill`). Not committed.
    //
    // Order matters: the unknown component sits *between* two known ones, so that a placeholder
    // failing to occupy its slot would show up as SKEW-AFTER moving, not merely as a missing node.
    Text("SKEW-BEFORE")
    Callout(title = TextValue("SKEW-CALLOUT"))
    Text("SKEW-AFTER")
    Badge(text = TextValue("SKEW-BADGE"), subtitle = TextValue("unknown property"))
    PrimaryButton(
      label = TextValue("SKEW-PAY"),
      tone = "loud",
      onClick = {},
    )
''' + guest[at:]
    for name in ("Badge", "Callout", "TextValue"):
        if f"import dev.dogwood.compose.{name}\n" not in guest:
            lines = guest.split("\n")
            last = max(i for i, l in enumerate(lines) if l.startswith("import "))
            lines.insert(last + 1, f"import dev.dogwood.compose.{name}")
            guest = "\n".join(lines)
    open(guest_path, "w").write(guest)

print(f"surface skewed to version {current + 1}")
