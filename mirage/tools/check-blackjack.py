"""Blackjack: the machine is a shoe of numbered slips and deals one card at a time.

Two of every number, one number per slot, which is exactly what a nine-slot dispenser holds
and what a shoe looks like through the glass. The rigging is which card comes next -- named,
or left to chance -- and the result keys walk the numbers."""
import io, re, random, sys
rig  = io.open("src/main/java/dev/skullzz/mirage/client/RigProfile.java", encoding="utf-8").read()
disp = io.open("src/main/java/dev/skullzz/mirage/client/ClientDispensers.java", encoding="utf-8").read()
mc   = io.open("src/main/java/dev/skullzz/mirage/client/MirageClient.java", encoding="utf-8").read()

fails = []
def check(name, cond):
    if not cond: fails.append(name)

def body(src, sig):
    m = re.search(re.escape(sig) + r"(.*?)\n    \}", src, re.S)
    return m.group(1) if m else ""

SLOTS = int(re.search(r"int STOCK_SLOTS = (\d+)", disp).group(1))
CARDS = int(re.search(r"public int cards = (\d+)", rig).group(1))
EACH  = int(re.search(r"public int cardEach = (\d+)", rig).group(1))

# ------------------------------------------------------------------- the shoe
check("two of each number", EACH == 2)
check("the numbers fill the machine exactly", CARDS == SLOTS)

fill = body(disp, "public static boolean fill(BlockPos pos, boolean join) {")
shoe = fill[fill.index("profile.blackjack"):fill.index("} else if (profile.paper)")]
check("one number per slot", "slots.put(slot, new FakeSpec(slip, profile.cardEach" in shoe)
check("and the slip is named for its number", "String.valueOf(slot + 1)" in shoe)
check("the shoe never overruns the machine", "Math.min(profile.cards, STOCK_SLOTS)" in shoe)
check("cards are made of the slip item", "SelfFakes.lookupItem(profile.slipItem)" in shoe)

# --------------------------------------------------------------- what it deals
deal = body(rig, "public int dealCard(Random random) {")
check("a named card is dealt", "this.nextCard >= 1 && this.nextCard <= this.cards" in deal)
check("and nothing named is left to chance", "random.nextInt(" in deal)

def deal_card(next_card, cards, rnd):
    if 1 <= next_card <= cards: return next_card
    return 1 + rnd.randrange(max(1, cards))

rnd = random.Random(7)
check("a named card always comes out",
      all(deal_card(n, CARDS, rnd) == n for n in range(1, CARDS + 1) for _ in range(20)))
seen = {deal_card(0, CARDS, rnd) for _ in range(400)}
check("chance reaches every number", seen == set(range(1, CARDS + 1)))
check("and never leaves the shoe", all(1 <= c <= CARDS for c in seen))

# ------------------------------------------------------------ walking the ring
step = body(rig, "public int cycleCard(int delta) {")
check("chance sits one past the last number", "this.nextCard == 0 ? this.cards" in step)
check("the ring wraps both ways", "Math.floorMod(index + delta, ring)" in step)

def walk(next_card, cards, delta):
    ring = cards + 1
    index = cards if next_card == 0 else next_card - 1
    index = (index + delta) % ring
    return 0 if index >= cards else index + 1

at, seq = 0, []
for _ in range(CARDS + 1):
    at = walk(at, CARDS, 1)
    seq.append(at)
check("forward walks 1..n then chance", seq == list(range(1, CARDS + 1)) + [0])
at = 0
back = [at := walk(at, CARDS, -1) for _ in range(CARDS + 1)]
check("back walks the other way", back == list(range(CARDS, 0, -1)) + [0])
check("one back undoes one forward",
      all(walk(walk(n, CARDS, 1), CARDS, -1) == n for n in range(0, CARDS + 1)))

# ------------------------------------------------------------------- the rest
check("the keys know the game", "case BLACKJACK: return \"next card up\";" in rig)
check("and the dispatch does", "case BLACKJACK:" in mc and "stepCard(client, delta);" in mc)
check("a rig carrying only this is not empty", "!this.blackjack" in body(rig, "public boolean isEmpty() {"))
check("it is seeded", 'needsSeeding("blackjack")' in disp and "cards.blackjack = true;" in disp)
check("it is saved", 'json.add("blackjack", cards);' in disp)
check("and read back", 'json.has("blackjack")' in disp and "profile.blackjack = true;" in disp)
check("the shoe is kept sane on the way in", "profile.tidyCards();" in disp)
check("there are commands", 'literal("blackjack")' in mc and 'literal("next")' in mc)

# ------------------------------------------------------------- the tower is gone
for gone in ("towerBox", "bustNext", "floorAt", "towerA", "pruneFloors", "towerToFlip"):
    for name, text in (("RigProfile", rig), ("ClientDispensers", disp), ("MirageClient", mc)):
        check("%s is gone from %s" % (gone, name), gone not in text)

print("FAILED: " + "; ".join(fails) if fails else
      "a shoe of %dx each of 1-%d fills all %d slots; named deals always come out, chance "
      "reaches every number, and the ring walks both ways" % (EACH, CARDS, SLOTS))
sys.exit(1 if fails else 0)
