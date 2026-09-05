"""Building for more than one Minecraft version, and saying honestly which one a jar is.

The trap this guards is a quiet one: widening the range in fabric.mod.json makes Fabric
*load* a jar on a version it was never compiled against, so instead of "this mod needs
1.21.11" the player gets a crash inside our code with our name on it. The range must
follow what the jar was actually built against, and two targets must not write the same
file into build/libs where the second replaces the first."""
import io, os, re, sys, glob

fails = []
def check(name, cond):
    if not cond: fails.append(name)

gradle = io.open("build.gradle", encoding="utf-8").read()
mod = io.open("src/main/resources/fabric.mod.json", encoding="utf-8").read()
ps1 = io.open("fabric-versions.ps1", encoding="utf-8").read()

# ------------------------------------------------------- the metadata is not hardcoded
check("the Minecraft range is not written into the file",
      not re.search(r'"minecraft"\s*:\s*"[~>=<0-9]', mod))
check("it comes from the build", '"minecraft": "${minecraft_depend}"' in mod)

# Every placeholder in the file must be given a value, or the build fails at the very end
# of a long compile with a message about a missing property.
placeholders = set(re.findall(r"\$\{(\w+)\}", mod))
# Read out of the expand call itself rather than from a list of value names, which went
# stale the moment two more placeholders were added.
expand = re.search(r"expand ((?:.|\n)*?)\n    \}", gradle)
expanded = set(re.findall(r'"(\w+)"\s*:', expand.group(1))) if expand else set()
check("every placeholder is filled in (missing: %s)" % sorted(placeholders - expanded),
      placeholders <= expanded)

# ---------------------------------------------------------------- one file per version
targets = sorted(glob.glob("versions/*.properties"))
check("there is at least one version file", len(targets) >= 1)

REQUIRED = ["minecraft_version", "yarn_mappings", "loader_version", "fabric_version",
            "minecraft_depend"]
for path in targets:
    text = io.open(path, encoding="utf-8").read()
    values = dict(line.split("=", 1) for line in text.splitlines()
                  if "=" in line and not line.strip().startswith("#"))
    name = os.path.basename(path).replace(".properties", "")

    for key in REQUIRED:
        # Present but blank is the case that matters: the lookup script writes the file
        # even when it could not find every value, so the key is always there. A check
        # that only asks whether the line exists passes on a target that cannot build.
        check("%s sets %s" % (name, key), key in values)
        if key in values:
            check("%s leaves %s blank -- fill it in from https://fabricmc.net/develop/"
                  % (name, key), values[key].strip() != "")
    if "minecraft_version" in values:
        check("%s is the version it is named after" % name,
              values["minecraft_version"].strip() == name)
    # The honest default. A range wider than the version it was compiled against is a
    # promise the jar cannot keep -- and it is worse than no range at all, because Fabric
    # then loads the jar and the player gets a crash inside our code with our name on it
    # instead of "this mod needs 1.21.11".
    #
    # Substring matching is not enough here: ">=1.21.11 <27" contains "1.21.11" and is
    # precisely the claim being guarded against. Only a pin counts.
    if "minecraft_depend" in values and "minecraft_version" in values:
        depend = values["minecraft_depend"].strip()
        built = values["minecraft_version"].strip()
        pinned = depend.lstrip("=~")
        check("%s pins rather than ranges (%s)" % (name, depend),
              pinned == built and not any(c in depend for c in "<>|*^ ,"))

# ------------------------------------------------------------ two jars, two file names
check("a target picks a toolchain file", 'file("versions/${target}.properties")' in gradle)
check("an unknown target lists the known ones", "Known targets:" in gradle)
check("the jar name carries its Minecraft version", 'mc${prop(' in gradle
      or "mc${prop('minecraft_version')}" in gradle)
check("no target still builds as before", "target == null" in gradle)

# The script has to write the per-version file, or adding a version is hand-editing again.
check("the lookup script writes a version file", "versions" in ps1 and "$MinecraftVersion.properties" in ps1)
check("and says how to build it", "-Ptarget=" in ps1)
check("it writes without a BOM", "-Encoding ASCII" in ps1)

# ------------------------------------------------------------------- saying it plainly
for text, what in ((gradle, "build.gradle"), ):
    check("%s says one jar per version" % what,
          "one jar per" in text.lower() or "One jar per" in text)

print("FAILED:\n  " + "\n  ".join(fails) if fails else
      "%d target(s): %s; each declares only the version it was built against, and each "
      "builds to its own jar" % (len(targets),
          ", ".join(os.path.basename(t).replace(".properties", "") for t in targets)))
sys.exit(1 if fails else 0)
