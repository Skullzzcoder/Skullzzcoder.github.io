#!/usr/bin/env python3
"""Check that calls between this mod's own classes actually resolve.

Minecraft is not on the classpath outside a Loom build, so javac reports a call to a
method we never wrote exactly the same way as a missing Minecraft class: "cannot find
symbol". That made it easy to filter away a real mistake along with the noise. This
checks our own classes against each other, which needs no classpath at all.

Run from the mod directory:  python3 tools/check-calls.py
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent / 'src' / 'main' / 'java'


def split_args(text, open_index, angles=False):
    """Return (argument count, index of the closing bracket) for a call or declaration."""
    depth = 0
    commas = 0
    index = open_index
    in_string = in_char = False

    while index < len(text):
        char = text[index]
        if in_string:
            if char == '\\':
                index += 2
                continue
            if char == '"':
                in_string = False
        elif in_char:
            if char == '\\':
                index += 2
                continue
            if char == "'":
                in_char = False
        elif char == '"':
            in_string = True
        elif char == "'":
            in_char = True
        elif char in '([{' or (angles and char == '<'):
            depth += 1
        elif angles and char == '>':
            depth -= 1
        elif char in ')]}':
            depth -= 1
            if depth == 0:
                body = text[open_index + 1:index].strip()
                return (0 if not body else commas + 1), index
        elif char == ',' and depth == 1:
            commas += 1
        index += 1

    return None, len(text)


MEMBER = (r'^\s{2,8}(?:(?:public|protected|private)\s+)?(?:static\s+)?(?:final\s+)?'
          r'[\w<>,\[\]\.\? ]+?\s+(\w+)\s*\(')
FIELD = (r'^\s{4}(?:(?:public|protected|private)\s+)?(?:static\s+)?(?:final\s+)?'
         r'[\w<>,\[\]\.\? ]+?\s+(\w+)\s*[=;]')


def main():
    files = sorted(ROOT.rglob('*.java'))
    own = {path.stem: path for path in files}

    # Everything each class declares, and how many parameters each method takes.
    names = {}
    arities = {}
    for name, path in own.items():
        text = path.read_text()
        counts = {}
        for match in re.finditer(MEMBER, text, re.MULTILINE):
            # Angles tracked here only: a declaration's generics contain commas, while a
            # call site's arguments almost never do.
            count, _ = split_args(text, match.end() - 1, angles=True)
            if count is not None:
                counts.setdefault(match.group(1), set()).add(count)

        arities[name] = counts
        fields = set(re.findall(FIELD, text, re.MULTILINE))
        names[name] = set(counts) | fields | {name}

    problems = []
    for path in files:
        text = path.read_text()
        for match in re.finditer(r'\b([A-Z]\w+)\.(\w+)\s*\(', text):
            owner, member = match.group(1), match.group(2)
            if owner not in own or owner == path.stem:
                continue

            line = text[:match.start()].count('\n') + 1
            where = f"{path.relative_to(ROOT)}:{line}"

            if member not in names[owner]:
                problems.append(f"{where}  {owner}.{member}(...) does not exist")
                continue

            count, _ = split_args(text, match.end() - 1)
            expected = arities[owner].get(member)
            if count is not None and expected and count not in expected:
                problems.append(f"{where}  {owner}.{member}() takes {sorted(expected)} "
                                f"arguments, called with {count}")

    print(f"checked {len(files)} files, {sum(len(v) for v in names.values())} declared members")
    if problems:
        print(f"\n{len(problems)} problem(s):")
        for problem in problems:
            print("  " + problem)
        return 1

    print("\nall cross-class calls resolve")
    return 0


if __name__ == '__main__':
    sys.exit(main())
