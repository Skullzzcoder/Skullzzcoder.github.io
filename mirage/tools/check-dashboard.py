"""The dashboard, actually rendered.

The page is a Java text block, so the thing the browser receives is not the thing in the
source: javac resolves the escapes first. This unescapes it exactly the way javac does,
pulls the script out and runs its drawing against real state shapes under a small DOM.

A parse check would pass on a page that draws nothing. The cases that matter are the ugly
ones -- state the client has not sent yet, a rig with no game on, every list empty -- since
a dashboard that silently renders an empty panel looks identical to a client that is not
running."""
import io, json, os, re, shutil, subprocess, sys, tempfile, textwrap

SRC = "src/main/java/dev/skullzz/mirage/client/WebDashboard.java"
HERE = os.path.dirname(os.path.abspath(__file__))

fails = []
def check(name, cond):
    if not cond: fails.append(name)

if shutil.which("node") is None:
    print("SKIPPED: no node on this machine to run the page with")
    sys.exit(0)

source = io.open(SRC, encoding="utf-8").read()

def java_unescape(text):
    """Exactly what javac does to a text block, left to right."""
    out, i = [], 0
    table = {"n": "\n", "t": "\t", "r": "\r", '"': '"', "'": "'", "\\": "\\",
             "b": "\b", "f": "\f", "s": " "}
    while i < len(text):
        c = text[i]
        if c == "\\" and i + 1 < len(text):
            nxt = text[i + 1]
            if nxt in table:
                out.append(table[nxt]); i += 2; continue
            if nxt == "u":
                out.append(chr(int(text[i + 2:i + 6], 16))); i += 6; continue
        out.append(c); i += 1
    return "".join(out)

block = re.search(r'String PAGE = """\n(.*?)\n            """;', source, re.S)
check("the page is still one text block", block is not None)
if block is None:
    print("FAILED: " + "; ".join(fails)); sys.exit(1)

html = java_unescape(textwrap.dedent(block.group(1)))
script = "\n".join(re.findall(r"<script>\n(.*?)\n</script>", html, re.S))
check("there is a script in it", len(script) > 500)

# Every id the script asks for by name must exist in the markup it ships with, or it
# reaches for null the first time it draws.
asked = set(re.findall(r"el\('([\w-]+)'\)", script))
in_markup = set(re.findall(r'id="([\w-]+)"', html))
made = set(re.findall(r"\.id = '([\w-]+)'", script))
missing = asked - in_markup - made
check("every element it asks for exists (missing: %s)" % sorted(missing), not missing)

# ------------------------------------------------------------------ run the drawing
STATES = {
    "empty": {},
    "fresh": {"on": True, "rigsOn": True, "rig": "fifty", "mode": "50/50",
              "forward": "gold", "back": "diamond", "name": "Gold Block",
              "answer": "yes", "rigs": ["fifty"], "machines": [], "fires": [],
              "notices": [], "accent": "#8b5cf6", "theme": "Nova Purple"},
    "off": {"on": False, "rigsOn": False, "rig": "fifty", "mode": "50/50",
            "answer": "no machines watched", "rigs": ["fifty", "paper"]},
    "full": {"on": True, "rigsOn": True, "rig": "cards", "mode": "Blackjack",
             "forward": "deal", "back": "back", "name": "Paper", "price": "$12",
             "answer": "yes", "accent": "#f43f5e", "theme": "Crimson",
             "rigs": ["cards", "paper", "spin"],
             "machines": [{"pos": "1 2 3", "state": "ready", "fires": "Paper",
                           "holds": "9 slips"}],
             "paper": {"on": True, "sides": ["Player", "Host"]},
             "winner": "Host",
             "blackjack": {"on": True, "hands": [{"side": "Player", "cards": "[10, 7]",
                                                  "total": 17}]},
             "mix": {"on": True, "items": [{"name": "Diamond", "held": 4, "chance": 44,
                                            "pays": 2}]},
             "roulette": {"on": True, "armed": True},
             "schematics": {"folder": "C:/x", "files": ["a.litematic"],
                            "builds": [{"name": "casino", "blocks": 900, "size": "9x3x9",
                                        "at": "1 2 3", "here": True}]},
             "pictures": {"folder": "C:/y", "files": ["logo.png"]},
             "fires": ["1 2 3  fires  Paper"], "notices": ["hello"]},
}

work = tempfile.mkdtemp(prefix="mirage-dash-")
try:
    shutil.copy(os.path.join(HERE, "dashboard", "shim.js"), work)
    io.open(os.path.join(work, "page.mjs"), "w", encoding="utf-8").write(
        "import './shim.js';\n"
        # The search wiring and the startup calls are the two lines that want the real
        # page to be live; everything else is exercised as shipped.
        + script.replace("draw();\nrefresh();\nsetInterval(refresh, 1000);", "")
        + "\nglobalThis.__draw = draw;\nglobalThis.__setState = s => { state = s; };"
          "\nglobalThis.__setQuery = q => { query = q; };"
          "\nglobalThis.__pageNode = document.getElementById('page');"
          "\nglobalThis.__navNode = document.getElementById('nav');\n")

    runner = """
import './shim.js';
import './page.mjs';
const cases = JSON.parse(process.argv[2]);
const out = {};
for (const [name, state] of Object.entries(cases)) {
  globalThis.__setState(state);
  globalThis.__setQuery('');
  const pages = ['overview','rigs','machines','builds','schematics','mapart','log'];
  out[name] = {};
  for (const page of pages) {
    globalThis.__setState(state);
    try {
      // Reach the page by clicking its nav button, the way a person would.
      globalThis.__draw();
      const button = globalThis.__navNode.find(n => n.tag === 'button' &&
          n.text.trim().startsWith(({overview:'Overview',rigs:'Rigs',machines:'Machines',
          builds:'Builds',schematics:'Schematics',mapart:'Map art',log:'Activity'})[page]));
      if (button.length) button[0].onclick();
      const drawn = globalThis.__pageNode.text.trim();
      const heading = (globalThis.__pageNode.find(n => n.tag === 'h2')[0] || {}).text || '';
      out[name][page] = { ok: true, text: drawn.length,
                          beyondHeading: drawn.replace(heading.trim(), '').trim().length };
    } catch (failure) {
      out[name][page] = { ok: false, why: String(failure) };
    }
  }
  // And the search, which can hide the open page out from under itself.
  try {
    globalThis.__setQuery('sche'); globalThis.__draw();
    out[name].search = { ok: true, text: globalThis.__pageNode.text.trim().length };
    globalThis.__setQuery('zzzz'); globalThis.__draw();
    out[name].nomatch = { ok: true };
  } catch (failure) {
    out[name].search = { ok: false, why: String(failure) };
  }
}
console.log(JSON.stringify(out));
"""
    io.open(os.path.join(work, "run.mjs"), "w", encoding="utf-8").write(runner)
    result = subprocess.run(["node", "run.mjs", json.dumps(STATES)],
                            capture_output=True, text=True, cwd=work)
    if result.returncode != 0:
        print("FAILED: the page threw while drawing\n" + result.stderr[:2000])
        sys.exit(1)

    report = json.loads(result.stdout)
    for name, pages in report.items():
        for page, outcome in pages.items():
            check("%s / %s draws without throwing (%s)"
                  % (name, page, outcome.get("why", "")), outcome["ok"])
            # An empty panel and a dead client look the same, so every page must say
            # something even when it has nothing to show -- and a heading is not saying
            # something. It is the same word whether the list is empty or the client died.
            if outcome.get("ok") and "beyondHeading" in outcome:
                check("%s / %s says more than its own title" % (name, page),
                      outcome["beyondHeading"] > 10)
finally:
    shutil.rmtree(work, ignore_errors=True)

# ------------------------------------------------------------------- what it offers
for endpoint in ("/power", "/rigs", "/fire", "/refill", "/rig", "/winner", "/arm"):
    check("the page can reach %s" % endpoint, endpoint in script)
    check("the client serves %s" % endpoint, 'createContext("%s"' % endpoint in source)
check("nothing is fetched from off the machine",
      "http://" not in script and "https://" not in script)
check("a dead client leaves the last state up rather than blanking it",
      "catch (failure)" in script and "last = text" in script)

print("FAILED:\n  " + "\n  ".join(fails) if fails else
      "the page renders %d states x 7 sections plus search, all without throwing and all "
      "saying something" % len(STATES))
sys.exit(1 if fails else 0)
