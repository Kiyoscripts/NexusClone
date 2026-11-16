#!/usr/bin/env python3
"""
Static verification for the NexusClone redesign.

There is no local Gradle (no NDK, no sdkmanager, 2 cores), so the build happens on
GitHub Actions. That means the only cheap local gate is static: every binding id a
Kotlin file references must exist in the layout that generates that binding, and every
resource reference in every layout must resolve.

This catches the failure mode that actually bites here: a layout is rewritten, an id is
renamed or dropped, and the Kotlin still compiles against the old generated binding until
CI fails 20 minutes later.
"""

import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app/src/main/res")
JAVA = os.path.join(ROOT, "app/src/main/java")

failures = []


def snake_to_camel(name):
    parts = name.split("_")
    return parts[0] + "".join(p.capitalize() for p in parts[1:])


# ---------------------------------------------------------------- resource inventory

res_names = {"dimen": set(), "style": set(), "color": set(), "string": set(), "drawable": set()}

for f in glob.glob(os.path.join(RES, "values*/*.xml")):
    try:
        root = ET.parse(f).getroot()
    except ET.ParseError as e:
        failures.append(f"values parse error {f}: {e}")
        continue
    for child in root:
        tag = child.tag
        name = child.get("name")
        if tag in res_names and name:
            res_names[tag].add(name)

# Duplicate detection, scoped per configuration directory.
#
# values/ and values-night/ are different configurations and are SUPPOSED to declare the
# same names; that is how a night override works. Two files inside the SAME configuration
# declaring the same name is a build failure, and aapt2 compile does not catch it because
# it compiles each file independently. Only the resource merger does, which means the
# failure surfaces 20 minutes into CI. This is the cheap local equivalent.
seen_names = {}
for f in glob.glob(os.path.join(RES, "values*/*.xml")):
    qual = os.path.basename(os.path.dirname(f))
    try:
        root = ET.parse(f).getroot()
    except ET.ParseError:
        continue
    for child in root:
        name = child.get("name")
        if not name:
            continue
        key = (qual, child.tag, name)
        seen_names.setdefault(key, []).append(os.path.basename(f))

for (qual, tag, name), files in sorted(seen_names.items()):
    if len(files) > 1:
        failures.append(
            f"duplicate {tag}/{name} in {qual}: {', '.join(sorted(set(files)))}"
        )

# Dotted style names inherit implicitly: "Nc.Button.Primary" inherits from "Nc.Button",
# which inherits from "Nc". Every prefix must exist as a style or the link step fails with
# "resource style/Nc not found". aapt2 compile does not catch this; only link does.
#
# A style that declares an explicit parent= does not need the implicit chain, so those are
# exempt: Theme.NexusClone has parent="Theme.MaterialComponents..." and is fine.
style_parents = {}
for f in glob.glob(os.path.join(RES, "values*/*.xml")):
    try:
        root = ET.parse(f).getroot()
    except ET.ParseError:
        continue
    for child in root:
        if child.tag == "style" and child.get("name"):
            style_parents[child.get("name")] = child.get("parent")

for style_name in sorted(res_names["style"]):
    if "." not in style_name:
        continue
    if style_parents.get(style_name):
        continue
    parts = style_name.split(".")
    for i in range(1, len(parts)):
        prefix = ".".join(parts[:i])
        if prefix not in res_names["style"]:
            failures.append(
                f"style/{style_name} needs an implicit parent style/{prefix}, which is not declared"
            )

for f in glob.glob(os.path.join(RES, "drawable*/*")):
    res_names["drawable"].add(os.path.basename(f).split(".")[0])

# ---------------------------------------------------------------- layout inventory

layout_ids = {}

for f in sorted(glob.glob(os.path.join(RES, "layout/*.xml"))):
    base = os.path.basename(f)[:-4]
    try:
        tree = ET.parse(f)
    except ET.ParseError as e:
        failures.append(f"layout parse error {base}: {e}")
        continue

    ids = set()
    for el in tree.iter():
        raw = el.get("{http://schemas.android.com/apk/res/android}id")
        if raw and raw.startswith("@+id/"):
            ids.add(raw[len("@+id/"):])
    layout_ids[base] = ids

    # every reference in the file must resolve
    text = open(f, encoding="utf-8").read()
    for kind, name in re.findall(r"@(dimen|style|color|drawable|string)/([A-Za-z0-9_.]+)", text):
        if name not in res_names[kind]:
            failures.append(f"{base}: unresolved @{kind}/{name}")

# ---------------------------------------------------------------- binding references

# ActivityXBinding / ItemXBinding / FragmentXBinding / ViewXBinding -> layout file
BINDING_RE = re.compile(r"\b(Activity|Item|Fragment|View)([A-Z][A-Za-z0-9]*)Binding\b")

for f in glob.glob(os.path.join(JAVA, "**/*.kt"), recursive=True):
    src = open(f, encoding="utf-8").read()
    rel = os.path.relpath(f, ROOT)

    # Map each binding class to the variable names that hold it, so `binding.x` is checked
    # against the layout of the binding that variable actually is. A file can hold several
    # bindings (an Activity plus its row binding), and checking every member against every
    # layout produces noise instead of signal.
    var_to_layout = {}
    var_all_layouts = {}

    for prefix, rest in set(BINDING_RE.findall(src)):
        layout = prefix.lower() + "_" + re.sub(r"(?<!^)(?=[A-Z])", "_", rest).lower()
        if layout not in layout_ids:
            failures.append(f"{rel}: binding {prefix}{rest}Binding has no layout {layout}.xml")
            continue

        cls = prefix + rest + "Binding"

        # `val binding: ActivityMainBinding by inflate()`
        for var in re.findall(r"\bval\s+(\w+)\s*:\s*" + cls + r"\b", src):
            var_to_layout[var] = layout
            var_all_layouts.setdefault(var, set()).add(layout)
        # `binding = ActivityMainBinding.inflate(...)`
        for var in re.findall(r"\b(\w+)\s*=\s*" + cls + r"\.inflate", src):
            var_to_layout[var] = layout
            var_all_layouts.setdefault(var, set()).add(layout)
        # `private val b = ItemCloneBinding.inflate(...)`
        for var in re.findall(r"\bval\s+(\w+)\s*=\s*" + cls + r"\.inflate", src):
            var_to_layout[var] = layout
            var_all_layouts.setdefault(var, set()).add(layout)
        # `VH(private val b: ItemCloneBinding)`
        for var in re.findall(r"\bval\s+(\w+)\s*:\s*" + cls + r"\s*\)", src):
            var_to_layout[var] = layout
            var_all_layouts.setdefault(var, set()).add(layout)

    for var, layout in var_to_layout.items():
        # Skip names declared against more than one layout in this file; the per-inner-class
        # pass below handles those without false positives.
        if len(var_all_layouts.get(var, set())) > 1:
            continue
        for member in set(re.findall(r"\b" + re.escape(var) + r"\.([a-z][A-Za-z0-9]*)\b", src)):
            if member in ("root", "inflate"):
                continue
            if member not in layout_ids[layout]:
                failures.append(f"{rel}: {var}.{member} not in {layout}.xml")

    # A file can declare the same variable name in two inner classes (two adapters that
    # both call their binding `b`). The loop above then checks every member against both
    # layouts and reports false positives. Re-check those ambiguous names per inner class
    # body, where the binding is unambiguous.
    ambiguous = var_all_layouts

    for var, layouts in ambiguous.items():
        if len(layouts) < 2:
            continue

        # Split the source on inner-class declarations and check each body separately.
        bodies = re.split(r"\binner class\s+\w+", src)
        for body in bodies[1:]:
            declared = re.search(
                r"\bval\s+" + re.escape(var) + r"\s*:\s*(\w+Binding)", body
            )
            if not declared:
                continue
            cls = declared.group(1)
            m = re.match(r"(Activity|Item|Fragment|View)([A-Z][A-Za-z0-9]*)Binding", cls)
            if not m:
                continue
            layout = m.group(1).lower() + "_" + re.sub(
                r"(?<!^)(?=[A-Z])", "_", m.group(2)
            ).lower()
            if layout not in layout_ids:
                continue
            for member in set(
                re.findall(r"\b" + re.escape(var) + r"\.([a-z][A-Za-z0-9]*)\b", body)
            ):
                if member in ("root", "inflate"):
                    continue
                if member not in layout_ids[layout]:
                    failures.append(f"{rel}: {var}.{member} not in {layout}.xml")

# ---------------------------------------------------------------- report

seen = set()
unique = []
for f in failures:
    if f not in seen:
        seen.add(f)
        unique.append(f)

if unique:
    print(f"FAIL: {len(unique)} problem(s)")
    for f in unique:
        print("  " + f)
    sys.exit(1)

print(f"OK: {len(layout_ids)} layouts, {sum(len(v) for v in res_names.values())} resources, "
      f"all binding references resolve")
