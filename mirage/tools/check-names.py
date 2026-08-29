"""Walks a custom name through every step it takes between being fired and sitting in the
inventory, using the real source as the spec, so a step that drops it shows up here."""
import io, re, sys
src = lambda f: io.open("src/main/java/dev/skullzz/mirage/client/%s.java" % f, encoding="utf-8").read()
fake, self_, disp, lore = src("FakeSpec"), src("SelfFakes"), src("ClientDispensers"), src("FakeLore")
fails = []
def check(name, cond):
    if not cond: fails.append(name)

# 1. withCount must carry the name through, since every count change goes through it
w = re.search(r"public FakeSpec withCount\(int newCount\) \{(.*?)\n    \}", fake, re.S).group(1)
check("withCount keeps the name", "this.name" in w)

# 2. stacksWith must compare it, or a named slip merges into an unnamed stack and loses it
sw = re.search(r"public boolean stacksWith\(FakeSpec other\) \{(.*?)\n    \}", fake, re.S).group(1)
check("stacksWith compares the name", "name.equals(other.name)" in sw)

# 3. stack() must apply the name, and applyTo must not replace the stack afterwards
st = re.search(r"public ItemStack stack\(\) \{(.*?)\n    \}", fake, re.S).group(1)
check("stack() applies the name", "applyName" in st)
at = re.search(r"public static ItemStack applyTo\(ItemStack stack, String enchantSpec, "
              r"Double priceOverride\) \{(.*?)\n    \}", lore, re.S).group(1)
check("applyTo mutates rather than rebuilding", "new ItemStack" not in at and "return stack;" in at)

# 4. collect: both the merge branch and the empty-slot branch must keep it
co = re.search(r"public static boolean collect\(FakeSpec spec, ClientPlayerEntity player\) \{"
               r"(.*?)\n    \}", self_, re.S).group(1)
check("collect merges with withCount", "existing.withCount" in co)
check("collect stores the whole spec", "set(slot, spec)" in co)

# 5. the save/load round trip
ws = re.search(r"public static JsonObject writeSpec\(FakeSpec spec\) \{(.*?)\n    \}", self_, re.S).group(1)
check("writeSpec saves the name", '"name", spec.name' in ws)
rs = re.search(r"private static FakeSpec readSpec\(JsonObject json\) \{(.*?)\n    \}", disp, re.S).group(1)
check("readSpec restores the name", 'get("name")' in rs)

# 6. what comes out of a dispenser is collected as the very spec that was fired
check("the fired spec is what gets collected", "SelfFakes.collect(item.spec, player)" in disp)

print("FAILED: " + ", ".join(fails) if fails else
      "custom names survive fire -> pickup -> merge -> save -> load")
sys.exit(1 if fails else 0)
