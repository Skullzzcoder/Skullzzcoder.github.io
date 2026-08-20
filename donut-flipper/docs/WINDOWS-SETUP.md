# Windows setup, start to finish

Everything here runs **on your own PC**, in PowerShell. Nothing runs in a
browser or on a server.

Open PowerShell: press `Win`, type `powershell`, hit Enter.

---

## 1. Check whether you have a JDK

```powershell
javac -version
```

You want `javac 21` or higher.

**If you get "not recognized"** — that is expected even if you play Minecraft.
Minecraft ships its own private Java runtime; it is not on your PATH and it is a
JRE, not a JDK. A JRE can *run* Java programs but cannot *build* them. You need
a JDK.

Install one:

```powershell
winget install EclipseAdoptium.Temurin.21.JDK
```

Then **close PowerShell and open a new window** — PATH changes only apply to new
windows. Check again:

```powershell
javac -version
```

> If `winget` itself is not recognised, you are on an older Windows build.
> Download the installer directly from <https://adoptium.net/temurin/releases/?version=21>
> and pick the Windows x64 `.msi`. During install, enable "Set JAVA_HOME" and
> "Add to PATH".

---

## 2. Get the code

```powershell
winget install Git.Git
```

Again, open a **new** PowerShell window afterwards. Then:

```powershell
cd $HOME
git clone https://github.com/Skullzzcoder/Skullzzcoder.github.io.git
cd Skullzzcoder.github.io
git checkout claude/donut-smp-auction-flipper-qnmsb6
cd donut-flipper
```

That last `git checkout` matters — the project is on a branch, not on `main`.

> Prefer not to install Git? Go to the repo on GitHub, switch to the
> `claude/donut-smp-auction-flipper-qnmsb6` branch, click **Code → Download ZIP**,
> extract it, and `cd` into the `donut-flipper` folder inside.

---

## 3. Build it

```powershell
.\gradlew.bat :daemon:fatJar
```

On Windows it is `.\gradlew.bat`, not `./gradlew`.

First run takes a few minutes — it downloads Gradle and the dependencies. You
should end on `BUILD SUCCESSFUL`.

Quick sanity check that the engine works, no API key needed:

```powershell
.\gradlew.bat :daemon:run --args="demo"
```

This runs the whole pipeline against a simulated market with known true prices.
If you see a table of valuations close to their true values, everything compiled
and works.

---

## 4. Add your API key

```powershell
java -jar daemon\build\libs\daemon-0.1.0-all.jar set-key
```

It asks you to paste your key, saves it, and immediately makes one API call to
confirm the server accepts it. If the key is wrong you find out now rather than
three days into a collection run.

That is all. You do not need to find or edit any file.

> **Why not pass the key as an argument?** Anything you type on the command line
> is written to your PowerShell history in plain text. The prompt avoids that.

> **Where does it go?** `%USERPROFILE%\.donutflipper\config.json` — in your user
> folder, deliberately outside the project, so it can never end up in a git
> commit. Run `java -jar $J where` to print the exact path any time.

**If you would rather edit it by hand**, the folder starts with a dot, which
Explorer hides by default. Skip Explorer and open it directly:

```powershell
notepad $HOME\.donutflipper\config.json
```

The file only exists after you have run the program at least once. If Notepad
offers to create a new file, run `java -jar $J where` first.

## 5. Probe, diagnose, collect

```powershell
$J = "daemon\build\libs\daemon-0.1.0-all.jar"

java -jar $J probe
java -jar $J doctor
```

`probe` saves the raw API responses to `%USERPROFILE%\.donutflipper\probe\` and
writes a `schema-report.md` there. `doctor` tells you whether anything is broken.

Then start collecting, and **leave this window open**:

```powershell
java -jar $J collect
```

Closing the window stops the collector. Minimise it instead. If you want it out
of the way entirely, start it detached:

```powershell
Start-Process java -ArgumentList "-jar","$PWD\daemon\build\libs\daemon-0.1.0-all.jar","collect" -WindowStyle Hidden
```

To stop a detached collector:

```powershell
Get-Process java | Where-Object { $_.CommandLine -like "*daemon*" } | Stop-Process
```

---

## 6. Wait, then check

Give it a day or two. Then:

```powershell
java -jar $J doctor      # is there enough history yet?
java -jar $J scan        # what would it buy right now?
java -jar $J backtest    # would this actually have made money?
```

Do not trade on it until `backtest` stops saying `NOT yet trustworthy`.

---

## 7. Build the mod (after the collector has data)

```powershell
cd mod
.\gradlew.bat build
```

Copy `mod\build\libs\donutflipper-0.1.0.jar` into your mods folder:

```powershell
explorer $env:APPDATA\.minecraft\mods
```

You also need Fabric Loader and Fabric API installed for your Minecraft version.

In game: `\` opens the flip board, `]` toggles the corner overlay.

The mod reads from the collector on `127.0.0.1`, so the collector must be
running for the board to show anything.

---

## Common problems

| Symptom | Cause |
|---|---|
| `javac` not recognised | No JDK, or PATH not refreshed — open a new PowerShell window |
| `gradlew.bat` not recognised | You are in the wrong folder, or typed `./gradlew` instead of `.\gradlew.bat` |
| `BUILD FAILED` mentioning a download | Network hiccup — just run it again |
| `doctor` says the key is rejected | Re-run `/api` in game and update `config.json` |
| `doctor` says the mapper parsed 0 records | The API field names differ from what the code guesses. Send me `schema-report.md` |
| Flip board is empty in game | The collector is not running, or has no history yet — run `doctor` |
