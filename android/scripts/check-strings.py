#!/usr/bin/env python3
"""Fails if res/values/strings.xml and res/values-hi/strings.xml differ in keys, kinds or format placeholders.

    python3 android/scripts/check-strings.py
(The same rules run as StringsParityTest in `./gradlew testDebugUnitTest`.)
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

RES = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "res"


def load(folder):
    root = ET.parse(RES / folder / "strings.xml").getroot()
    return {el.get("name"): (el.tag, "".join(el.itertext())) for el in root}


def placeholders(text):
    return sorted(re.sub(r"^%\d+\$", "%", m) for m in re.findall(r"%(?:\d+\$)?[sd]", text))


def main():
    en, hi = load("values"), load("values-hi")
    problems = []
    problems += [f"missing in hi: {k}" for k in sorted(en.keys() - hi.keys())]
    problems += [f"extra in hi: {k}" for k in sorted(hi.keys() - en.keys())]
    for k in sorted(en.keys() & hi.keys()):
        if en[k][0] != hi[k][0]:
            problems.append(f"{k}: kind differs ({en[k][0]} vs {hi[k][0]})")
        elif en[k][0] == "string" and placeholders(en[k][1]) != placeholders(hi[k][1]):
            problems.append(f"{k}: placeholders differ")
    for p in problems:
        print(p)
    print(f"{len(en)} keys in en, {len(hi)} in hi, {len(problems)} problem(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
