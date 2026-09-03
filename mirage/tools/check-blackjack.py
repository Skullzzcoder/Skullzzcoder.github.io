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
check("and the slip is named as a card", "RigProfile.cardName(slot + 1)" in shoe)
check("the shoe never overruns the machine", "Math.min(profile.cards, STOCK_SLOTS)" in shoe)
check("cards are made of the slip item", "SelfFakes.lookupItem(profile.slipItem)" in shoe)

# ------------------------------------------------- rigged by outcome, not by number
# Naming a card meant knowing every total at the table and working out which number gave
# the result you wanted, while somebody waited. The outcome is named and the card worked
# back from it instead.
choose = body(rig, "public int chooseCard(String side, Random random) {")
check("nothing named is an honest deal", 'this.winner.isEmpty()) return 1 + random.nextInt' in choose)
check("the side that must win is never busted",
      "total > TARGET ? Integer.MIN_VALUE + 1 + total : total" in choose)
check("the side that must lose is busted where a card can",
      "total > TARGET ? Integer.MAX_VALUE - 1 : -total" in choose)
check("equally good cards are broken at random", "best.get(random.nextInt(best.size()))" in choose)

# An ace is worth eleven or one depending on what arrives after it, so a hand cannot be a
# running total -- it is re-read whole every time.
value = body(rig, "public static int handValue(List<Integer> hand) {")
check("an ace starts at eleven", "total += 11;" in value)
check("and drops to one only as far as it must",
      "while (total > TARGET && aces > 0)" in value and "total -= 10;" in value)
check("hands are kept as cards, not as a total", "Map<String, List<Integer>> hands" in rig)
check("the ace reads as one on its slip", 'card == ACE ? "A"' in rig)

# Whose card it is decides everything, so it is worked out before the card is.
deal = body(disp, "private static FakeSpec card(RigProfile profile, BlockPos pos) {")
check("the machine's own side comes first", "profile.sideOf(pos)" in deal)
check("then the side named by hand", "profile.dealTo" in deal)
check("then joining the table", "profile.sideAt(pos, watched)" in deal)
check("a box at no table says so", "not at this table" in deal or "is not at this table" in disp)
check("the card joins that hand", "profile.handFor(side).add(number)" in deal)

# ------------------------------------------------------------------- the rest
check("the keys know the game", 'case BLACKJACK: return "next winner";' in rig)
check("and the dispatch does", "case BLACKJACK:" in mc and "stepWinner(client, delta);" in mc)
check("a rig carrying only this is not empty", "!this.blackjack" in body(rig, "public boolean isEmpty() {"))
check("it is seeded", 'needsSeeding("blackjack")' in disp and "cards.blackjack = true;" in disp)
check("it is saved", 'json.add("blackjack", cards);' in disp)
check("and read back", 'json.has("blackjack")' in disp and "profile.blackjack = true;" in disp)
check("the shoe is kept sane on the way in", "profile.tidyCards();" in disp)
check("there are commands", 'literal("blackjack")' in mc and 'literal("hand")' in mc)
# Both games with named sides share the winner keys, the parts and the side machinery.
check("the two side games are named together", "public boolean hasSides()" in rig)
check("and the winner keys serve both", "profile.hasSides()" in mc)
check("arming starts a new hand instead", "profile.blackjack) {\n            profile.newHand();" in disp)

# ------------------------------------------------------------- the tower is gone
for gone in ("towerBox", "bustNext", "floorAt", "towerA", "pruneFloors", "towerToFlip"):
    for name, text in (("RigProfile", rig), ("ClientDispensers", disp), ("MirageClient", mc)):
        check("%s is gone from %s" % (gone, name), gone not in text)

print("FAILED: " + "; ".join(fails) if fails else
      "a shoe of %dx each of 1-%d fills all %d slots; named deals always come out, chance "
      "reaches every number, and the ring walks both ways" % (EACH, CARDS, SLOTS))
sys.exit(1 if fails else 0)
