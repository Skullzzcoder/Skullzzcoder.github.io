"""The keys screen.

Two things have to hold. Every key the mod owns has to appear in it -- a key registered
some other way is a key with no way to change it, and no way to find out it exists. And a
change has to actually take: a rebind that is not pushed into the game keeps answering to
the old key, which is the same silence as a key that does nothing."""
import io, re, sys
mc = io.open("src/main/java/dev/skullzz/mirage/client/MirageClient.java", encoding="utf-8").read()
ui = io.open("src/main/java/dev/skullzz/mirage/client/MirageKeysScreen.java", encoding="utf-8").read()
items = io.open("src/main/java/dev/skullzz/mirage/client/FakeItemsScreen.java", encoding="utf-8").read()

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
check("there are the keys we think there are", len(bound) == 15)

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

# The screen must read the table rather than a list of its own.
check("the screen reads the table", "MirageClient.binds()" in ui)
check("and never hardcodes the keys", "GLFW_KEY_F" not in ui)

# ------------------------------------------------------------------ a change takes
# setKeyCode alone changes the binding's key; the game keeps answering to the old one
# until the lookup is rebuilt, and forgets the whole thing on restart unless it is written.
for name, sig in (("rebinding", "private void assign(InputUtil.Key key) {"),
                  ("putting them back", "private void resetAll() {"),
                  ("clearing a clash", "private void clearClashes() {")):
    text = body(ui, sig)
    check("%s pushes the change into the game" % name,
          "KeyBinding.updateKeysByCode();" in text)
    check("%s saves it" % name, "this.client.options.write();" in text)
    check("%s goes through the options" % name, "options.setKeyCode(" in text)

# Escape is the only way to unbind, so while a row is listening it must not close instead.
pressed = body(ui, "public boolean keyPressed(int keyCode, int scanCode, int modifiers) {")
check("escape unbinds while listening",
      "GLFW.GLFW_KEY_ESCAPE" in pressed and "InputUtil.UNKNOWN_KEY" in pressed)
check("and only while listening", "if (this.listening < 0) return super.keyPressed" in pressed)

clicked = body(ui, "public boolean mouseClicked(double mouseX, double mouseY, int button) {")
check("a mouse button can be bound", "InputUtil.Type.MOUSE.createFromCode(button)" in clicked)
check("but not the click that started it", "if (this.listening >= 0) {" in clicked)

# ------------------------------------------------------------------- clashes
# The reason this screen exists: F is vanilla's swap-to-offhand and Minecraft runs both.
same = body(ui, "private static boolean sameKey(KeyBinding one, KeyBinding two) {")
check("a clash is judged on the keys, not the bindings",
      "getBoundKeyOf(one).equals(KeyBindingHelper.getBoundKeyOf(two))" in same)
check("nothing relies on KeyBinding.equals", ".equals(bind.binding())" not in ui)

clash = body(ui, "private String clashFor(Bind bind) {")
check("an unbound key clashes with nothing", "isUnbound()" in clash)
check("clashes are looked for across everything", "this.client.options.allKeys" in clash)

clear = body(ui, "private void clearClashes() {")
check("clearing a clash never clears one of ours", "isOurs(other)" in clear)
check("and says how many it took", "cleared" in clear)

# A clash has to be said, not only coloured: a red button is read once and then never again.
assign = body(ui, "private void assign(InputUtil.Key key) {")
check("picking a clashing key says so at the time",
      "clashFor(bind)" in assign and "Minecraft will run both" in assign)

# ------------------------------------------------------------------- reachable
check("every row lands in a column",
      all(i // ((n + 1) // 2) < 2 for n in range(1, 40) for i in range(n)))
check("the footer is pinned to the bottom", "int footer = this.height - 28;" in ui)
check("there is a way in from the menu", "new MirageKeysScreen(this)" in items)
check("and a command", 'literal("keys")' in mc and "openKeys = true;" in mc)
check("the screen is opened from the tick, not the command", "openKeys = false;" in mc)

print("FAILED: " + "; ".join(fails) if fails else
      "all %d keys reach the screen, labelled and rebindable; clashes are found by key and "
      "can be cleared" % len(bound))
sys.exit(1 if fails else 0)
