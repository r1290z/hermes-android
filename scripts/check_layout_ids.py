#!/usr/bin/env python3
"""Catch the findViewById bugs that no compiler and no unit test can see.

A layout id that does not exist, or an id whose view is a different class than
the Kotlin variable expects, compiles fine and crashes at runtime the moment the
screen opens. Both happened here:

  * `<include android:id="@+id/searchResults">` REPLACED the root id of
    search_results.xml (`searchScroll`) — so @+id/searchScroll vanished and
    @+id/searchResults resolved to a ScrollView the code cast to LinearLayout.
  * Result: ClassCastException on every open of the chat.

Checks, in order of how much they hurt:
  1. every R.id.X used in Kotlin exists in some layout (hard error);
  2. an id's view class matches the Kotlin type it is assigned to (hard error);
  3. ids declared and never used — informational only.

Exit 0 = clean, 1 = found problems (the caller must not build).
"""
from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAVA = ROOT / "app/src/main/java/ru/evgen/hermes"
LAYOUTS = ROOT / "app/src/main/res/layout"

ANDROID_ID = "{http://schemas.android.com/apk/res/android}id"


def tag_of(el) -> str:
    return el.tag.split("}")[-1]


def root_element(path: Path):
    """First real element (skips the XML declaration and comments)."""
    parser = ET.XMLParser(target=ET.TreeBuilder(insert_comments=True))
    tree = ET.parse(path, parser=parser)
    return tree.getroot()


def collect_layouts():
    """id -> {"tags": {widget classes}, "files": {layout files}}.

    An <include android:id="…"> overrides the id of the included layout's ROOT
    view (the root keeps its own class), so that root id must be dropped when the
    child is included with a different id — otherwise the check green-lights an
    id that no longer exists at runtime.
    """
    ids: dict[str, dict] = {}

    def add(id_name: str, tag: str, file_name: str):
        entry = ids.setdefault(id_name, {"tags": set(), "files": set()})
        entry["tags"].add(tag)
        entry["files"].add(file_name)

    children: dict[str, list[dict]] = {}

    for path in sorted(LAYOUTS.glob("*.xml")):
        root = root_element(path)
        children[path.stem] = []

        def walk(el, is_root=False):
            ident = el.get(ANDROID_ID)
            if ident and ident.startswith("@+id/"):
                add(ident[5:], tag_of(el), path.name)
            if tag_of(el) == "include":
                child = el.get("layout") or el.get("{%s}layout" % el.tag.split("}")[0][1:])
                inc_id = el.get(ANDROID_ID)
                children[path.stem].append(
                    {"child": (child or "").split("/")[-1],
                     "id": inc_id[5:] if inc_id and inc_id.startswith("@+id/") else None}
                )
                return
            for kid in el:
                if isinstance(kid.tag, str) and not kid.tag.startswith("{"):
                    walk(kid)

        walk(root, is_root=True)

    # Apply include id overrides: child root ids that are replaced disappear.
    for parent, includes in children.items():
        for inc in includes:
            child = inc["child"]
            if not child or child not in children:
                continue
            child_root = root_element(LAYOUTS / f"{child}.xml")
            child_root_id = (child_root.get(ANDROID_ID) or "")[5:]
            if inc["id"] and child_root_id and inc["id"] != child_root_id:
                entry = ids.get(child_root_id)
                if entry:
                    entry["files"].discard(f"{child}.xml")
                    if not entry["files"]:
                        ids.pop(child_root_id, None)
                add(inc["id"], tag_of(child_root), f"{parent}.xml")
    return ids


DECL = re.compile(r"(?:lateinit\s+var|var|val)\s+(\w+)\s*:\s*([A-Za-z_][\w.]*)")
FIND_SINGLE = re.compile(r"findViewById\s*<\s*([A-Za-z_][\w.]*)\s*>\s*\(\s*R\.id\.(\w+)\s*\)")
ASSIGN = re.compile(r"(\w+)\s*=\s*findViewById\s*(?:<\s*([A-Za-z_][\w.]*)\s*>)?\s*\(\s*R\.id\.(\w+)\s*\)")

# Widget class -> Kotlin type: equal names, plus the ones that legitimately differ.
ALIASES = {
    "View": None,          # assignable to/from anything below
    "ViewGroup": None,
    "RecyclerView": "RecyclerView",
}
NUMERIC_WIDGETS = {"TextView", "EditText", "Button", "ImageView", "ImageButton"}


def basename(t: str) -> str:
    return t.split(".")[-1]


def compatible(kotlin_type: str, widget: str) -> bool:
    kt, wd = basename(kotlin_type), basename(widget)
    if kt in ("View", "Any", "ViewGroup"):
        return True
    if wd in ("View", "ViewGroup"):
        return True
    if kt == wd:
        return True
    # TextView/EditText/Button all answer to TextView-ish Kotlin types only when equal.
    if kt in NUMERIC_WIDGETS and wd in NUMERIC_WIDGETS:
        return kt == wd
    return False


def main() -> int:
    ids = collect_layouts()
    problems: list[str] = []
    used: set[str] = set()

    for kt_file in sorted(JAVA.rglob("*.kt")):
        text = kt_file.read_text(encoding="utf-8")
        declared = {m.group(1): m.group(2) for m in DECL.finditer(text)}
        rel = kt_file.relative_to(ROOT)

        for m in FIND_SINGLE.finditer(text):
            typ, ident = m.group(1), m.group(2)
            used.add(ident)
            if ident not in ids:
                problems.append(f"{rel}: findViewById<{typ}>(R.id.{ident}) — нет такого id ни в одном layout")
                continue
            tags = ids[ident]["tags"]
            if not any(compatible(typ, t) for t in tags):
                problems.append(
                    f"{rel}: findViewById<{typ}>(R.id.{ident}) — в layout это "
                    f"{'/'.join(sorted(tags))} ({', '.join(sorted(ids[ident]['files']))}); "
                    f"приведение упадёт при открытии экрана"
                )

        for m in ASSIGN.finditer(text):
            var, typ, ident = m.group(1), m.group(2), m.group(3)
            typ = typ or declared.get(var) or "View"
            used.add(ident)
            if ident not in ids:
                problems.append(f"{rel}: {var} = findViewById(R.id.{ident}) — нет такого id ни в одном layout")
                continue
            tags = ids[ident]["tags"]
            if not any(compatible(typ, t) for t in tags):
                problems.append(
                    f"{rel}: {var}: {typ} = findViewById(R.id.{ident}) — в layout это "
                    f"{'/'.join(sorted(tags))} ({', '.join(sorted(ids[ident]['files']))}); "
                    f"приведение упадёт при открытии экрана"
                )

    for ident, entry in sorted(ids.items()):
        if ident not in used:
            print(f"  не используется в коде: @+id/{ident} ({', '.join(sorted(entry['files']))})")

    if problems:
        print("\nОТКАЗ: проверка layout-id нашла ошибки, которые уронили бы экран:", file=sys.stderr)
        for p in problems:
            print("  - " + p, file=sys.stderr)
        return 1

    print(f"layout-id: ок ({len(ids)} id, {len(used)} использований)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
