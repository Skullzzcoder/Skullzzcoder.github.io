"""The keys screen.

Every key the mod owns has to appear in it -- a key registered some other way is a key with
no way to see it and no way to find out it exists.

The half that changes a key from inside the screen is parked: setting a binding and reading
a key press both moved in this version of Minecraft, and the names that replaced them are
not worth a third guess. So the other rule this enforces is that nothing reaches for an API
the build has already said is gone, and that the task which will name the replacements
actually covers them."""
import io, re, sys
mc = io.open("src/main/java/dev/skullzz/mirage/client/MirageClient.java", encoding="utf-8").read()
ui = io.open("src/main/java/dev/skullzz/mirage/client/MirageKeysScreen.java", encoding="utf-8").read()
items = io.open("src/main/java/dev/skullzz/mirage/client/FakeItemsScreen.java", encoding="utf-8").read()
gradle = io.open("build.gradle", encoding="utf-8").read()

fails = []
def check(name, cond):
    if not cond: fails.append(name)

def body(src, sig):
    m = re.search(re.escape(sig) + r"(.*?)\n    \}", src, re.S)
    return m.group(1) if m else ""

# ---------------------------------------------------------------- every key is in
register = body(mc, "private static void registerKeys() {")
bound = re.findall(r"^\s*(\w+) = bind\(\"([\w_]+)\", GLFW\.(\w+), category,", register, re.M)
fields = re.findall(r"^    private static KeyBinding (\w+);", mc, re.M)

check("every key field is registered through the table",
      sorted(f for f, _, _ in bound) == sorted(fields))
# Named rather than counted. A number tells you something changed; a list tells you what,
# and makes a key that quietly disappears as loud as one that quietly appears.
EXPECTED = {"next_result", "prev_result", "arm_next", "fire_now", "refill", "clear_fakes",
            "cycle_winner", "win_first", "win_second", "power", "cut_block", "call_first",
            "call_second", "cycle_rig", "open_menu", "open_client", "open_rigs",
            "open_tracker"}
found = {name for _, name, _ in bound}
check("the keys are the ones we think (extra: %s, missing: %s)"
      % (sorted(found - EXPECTED), sorted(EXPECTED - found)), found == EXPECTED)

# A default that vanilla also uses runs both actions and says nothing about it, which is
# the whole reason the keys screen exists. These two are the defaults added for the menus.
defaults = {name: key for _, name, key in bound}
check("the client menu opens on a key vanilla leaves alone",
      defaults.get("open_client") == "GLFW_KEY_RIGHT_SHIFT")
check("so does the rig menu", defaults.get("open_rigs") == "GLFW_KEY_G")
check("and the tracker", defaults.get("open_tracker") == "GLFW_KEY_J")

# The keys are named on joining a world, because a key nobody was told about looks exactly
# like a key that does not work -- which is how this was reported. Scoped to the greeting
# itself: both "greeted" and "quiet()" appear elsewhere in this file, so asking whether
# the word is present anywhere passed on a greeting that had lost the guard.
greeting = re.search(r"if \(!greeted && client\.player != null\) \{(.*?)\n            \}",
                     mc, re.S)
check("the keys are named on joining a world", greeting is not None)
if greeting:
    body_text = greeting.group(1)
    check("and only once", "greeted = true;" in body_text)
    check("and never in quiet mode", "!SelfFakes.quiet()" in body_text)
    check("and read from the binding rather than written out",
          "keyName(openClient)" in body_text and "keyName(openRigs)" in body_text
          and "keyName(openTracker)" in body_text)
    check("and say which mod is talking", "Ryne Client: " in body_text)
check("the greeting comes back on the next world", "greeted = false;" in mc)

# The one way this quietly rots: a new key registered straight with Fabric never reaches
# the table, so it works in game and does not exist as far as this screen is concerned.
helper = body(mc, "private static KeyBinding bind(String id, int code, "
                  "KeyBinding.Category category,\n                                   String label) {")
check("only the table talks to Fabric",
      mc.count("KeyBindingHelper.registerKeyBinding") == 1
      and "KeyBindingHelper.registerKeyBinding" in helper)
check("the table records the default it just used", "new Bind(binding, label, code)" in helper)

# Each key needs words of its own; an id like "cut_block" is not an explanation.
labels = re.findall(r'category,\n\s*"([^"]+)"\);', register)
check("every key is labelled", len(labels) == len(bound))
check("no label is just its id", not any("_" in label for label in labels))
check("labels are distinct", len(set(labels)) == len(labels))

check("the screen reads the table", "MirageClient.binds()" in ui)
check("and never hardcodes the keys", "GLFW_KEY_F" not in ui)

# ------------------------------------------------- nothing the build says is gone
# These four are what the compiler named. Reaching for any of them again is the same
# round trip a second time, so they are barred until inspectApi says what replaced them.
GONE = {
    "options.setKeyCode(": "GameOptions.setKeyCode",
    ".getTranslationKey()": "KeyBinding.getTranslationKey",
    "public boolean keyPressed(int": "the old keyPressed(int, int, int)",
    "public boolean mouseClicked(double": "the old mouseClicked(double, double, int)",
}
for fragment, name in GONE.items():
    check("%s is gone from this version and must not come back" % name, fragment not in ui)

# Only what the build confirmed compiles may be used to read a binding.
check("the key shown comes from a call the build accepted",
      "getBoundKeyLocalizedText()" in ui and "isUnbound()" in ui)

# And the task that will answer the rest has to actually cover it.
for name in ("net.minecraft.client.option.KeyBinding",
             "net.minecraft.client.option.GameOptions",
             "net.minecraft.client.gui.screen.Screen"):
    check("inspectApi covers %s" % name.rsplit(".", 1)[1], name in gradle)
check("inspectApi dumps KeyBinding whole, since the new names cannot be searched for",
      "dump(binding, [], '  <- the whole thing, on purpose')" in gradle)
check("inspectApi follows both moved signatures",
      "follow(screen, 'keyPressed')" in gradle and "follow(screen, 'mouseClicked')" in gradle)

# A focused version, because the full dump is a haystack to paste back and the answer is
# four class listings. It has to cover the same ground and follow the same two signatures.
check("there is a focused task", "tasks.register('inspectKeys')" in gradle)
keys_task = gradle[gradle.index("tasks.register('inspectKeys')"):gradle.index("tasks.register('inspectApi')")]
for name in ("net.minecraft.client.option.KeyBinding",
             "net.minecraft.client.option.GameOptions",
             "net.minecraft.client.gui.screen.Screen"):
    check("inspectKeys covers %s" % name.rsplit(".", 1)[1], name in keys_task)
check("inspectKeys names the input classes from the signatures rather than guessing",
      "['keyPressed', 'mouseClicked'].each" in keys_task and "method.parameterTypes.each" in keys_task)
check("inspectKeys dumps KeyBinding whole",
      "list('net.minecraft.client.option.KeyBinding', [])" in keys_task)
check("inspectKeys writes a file, not just console output",
      "file.text = out.toString()" in keys_task)

# Both tasks must make the logs directory first, or Minecraft's logger buries the answer
# in stack traces that look like a failure.
for task in ("inspectKeys", "inspectApi"):
    start = gradle.index("tasks.register('%s')" % task)
    end = gradle.index("tasks.register('inspectApi')", start + 10) if task == "inspectKeys" else len(gradle)
    check("%s makes room for the logger" % task,
          "new File(projectDir, 'logs').mkdirs()" in gradle[start:end])

# ------------------------------------------------------------------- clashes
# The reason this screen exists: F is vanilla's swap-to-offhand and Minecraft runs both.
same = body(ui, "private static boolean sameKey(KeyBinding one, KeyBinding two) {")
check("a clash is judged on the keys, not the bindings",
      "getBoundKeyOf(one).equals(KeyBindingHelper.getBoundKeyOf(two))" in same)
check("nothing relies on KeyBinding.equals", ".equals(bind.binding())" not in ui)

clash = body(ui, "private KeyBinding clashFor(Bind bind) {")
check("an unbound key clashes with nothing", "isUnbound()" in clash)
check("clashes are looked for across everything", "this.client.options.allKeys" in clash)

# A clash has to be said, not only coloured: red alone is read once and then never again.
check("the clashes are counted out loud", "clashCount()" in ui and "It runs both" in ui)
check("and the screen says where to change a key", "Options -> Controls" in ui)

# ------------------------------------------------------------------- reachable
check("every row lands in a column",
      all(i // ((n + 1) // 2) < 2 for n in range(1, 40) for i in range(n)))
check("the footer is pinned to the bottom", "int footer = this.height - 28;" in ui)
check("there is a way in from the menu", "new MirageKeysScreen(this)" in items)
check("and a command", 'literal("keys")' in mc and "openKeys = true;" in mc)
check("the screen is opened from the tick, not the command", "openKeys = false;" in mc)

print("FAILED: " + "; ".join(fails) if fails else
      "all %d keys reach the screen, labelled, clashes found by key; nothing reaches for an "
      "API this version dropped" % len(bound))
sys.exit(1 if fails else 0)
