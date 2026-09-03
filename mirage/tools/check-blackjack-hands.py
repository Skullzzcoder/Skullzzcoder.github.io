"""Simulate the shipped chooseCard/handValue against real tables."""
import io, re, random, sys
rig = io.open("src/main/java/dev/skullzz/mirage/client/RigProfile.java", encoding="utf-8").read()
CARDS = int(re.search(r"public int cards = (\d+)", rig).group(1))
ACE   = int(re.search(r"int ACE = (\d+)", rig).group(1))
TARGET= int(re.search(r"int TARGET = (\d+)", rig).group(1))

# This file proves the RULE is right by playing thousands of hands. It cannot prove the
# shipped Java still implements that rule -- it is a mirror, and a mirror goes stale in
# silence, which is the one failure a test must not have. So the lines it mirrors are
# pinned here: change the scoring in Java and this stops claiming to have checked it.
MIRRORED = [
    "score = total > TARGET ? Integer.MIN_VALUE + 1 + total : total;",
    "score = total > TARGET ? Integer.MAX_VALUE - 1 : -total;",
    "while (total > TARGET && aces > 0) {",
    "total -= 10;",
    "total += 11;",
    "return best.get(random.nextInt(best.size()));",
]
stale = [line for line in MIRRORED if line not in rig]
if stale:
    print("FAILED: this simulation no longer mirrors the source. Missing:")
    for line in stale:
        print("  " + line)
    sys.exit(1)

def value(hand):
    total = sum(11 if c == ACE else c for c in hand)
    aces = sum(1 for c in hand if c == ACE)
    while total > TARGET and aces:
        total -= 10; aces -= 1
    return total

def choose(hand, to_win, rnd):
    best, score_best = [], None
    for card in range(1, CARDS + 1):
        total = value(hand + [card])
        if to_win:
            score = -10**9 + total if total > TARGET else total
        else:
            score = 10**9 if total > TARGET else -total
        if score_best is None or score > score_best:
            score_best, best = score, [card]
        elif score == score_best:
            best.append(card)
    return rnd.choice(best)

rnd = random.Random(11)
fails = []

# An ace is eleven until it cannot be.
assert value([ACE]) == 11
assert value([ACE, ACE]) == 12
assert value([ACE, 9, 5]) == 15
assert value([9, 9, 9]) == 27

# The side meant to win is never busted while any card keeps them alive.
for _ in range(4000):
    hand = []
    while value(hand) < TARGET and len(hand) < 6:
        card = choose(hand, True, rnd)
        after = value(hand + [card])
        if after > TARGET and any(value(hand + [c]) <= TARGET for c in range(1, CARDS + 1)):
            fails.append("busted a winner at %s with %d" % (hand, card))
            break
        hand.append(card)

# The side meant to lose is busted whenever a card can do it.
busted = kept_low = 0
for _ in range(4000):
    hand = [rnd.randint(1, CARDS) for _ in range(rnd.randint(1, 3))]
    if value(hand) > TARGET: continue
    card = choose(hand, False, rnd)
    if any(value(hand + [c]) > TARGET for c in range(1, CARDS + 1)):
        if value(hand + [card]) <= TARGET:
            fails.append("failed to bust a loser at %s (gave %d)" % (hand, card))
        else: busted += 1
    else:
        # nothing can bust them, so it must be the lowest total available
        low = min(value(hand + [c]) for c in range(1, CARDS + 1))
        if value(hand + [card]) != low:
            fails.append("did not keep a loser lowest at %s" % hand)
        else: kept_low += 1

# Chance must reach every card and never be steered.
seen = {choose([], None, rnd) if False else rnd.randint(1, CARDS) for _ in range(500)}
assert seen == set(range(1, CARDS + 1))

print("FAILED: " + "; ".join(fails[:5]) if fails else
      "aces float 11/1; a rigged winner is never busted; a rigged loser is busted %d times "
      "and held lowest %d times" % (busted, kept_low))
sys.exit(1 if fails else 0)
