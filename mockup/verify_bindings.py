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
id_tag = {}       # layout -> {id: widget tag}
include_map = {}  # layout -> {id: included layout name}

for f in sorted(glob.glob(os.path.join(RES, "layout/*.xml"))):
    base = os.path.basename(f)[:-4]
    try:
        tree = ET.parse(f)
    except ET.ParseError as e:
        failures.append(f"layout parse error {base}: {e}")
        continue

    ids = set()
    tags = {}
    incs = {}
    for el in tree.iter():
        raw = el.get("{http://schemas.android.com/apk/res/android}id")
        if raw and raw.startswith("@+id/"):
            name = raw[len("@+id/"):]
            ids.add(name)
            tags[name] = el.tag
            if el.tag == "include":
                ref = el.get("layout", "")
                if ref.startswith("@layout/"):
                    incs[name] = ref[len("@layout/"):]
    layout_ids[base] = ids
    id_tag[base] = tags
    include_map[base] = incs

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

# ---------------------------------------------------------------- binding type check
#
# The id-existence check above cannot catch a binding TYPE mismatch: `btnFreeze` exists in
# activity_instance_detail.xml, so `binding.btnFreeze.text` passes it, and then CI fails with
# "Unresolved reference: text" because btnFreeze is a LinearLayout row, not a TextView.
#
# This pass is whitelist-driven on purpose. A member is only judged when it belongs to a
# known widget-specific API family; anything unrecognised is left alone. That keeps the
# check at zero false positives while still catching the class that actually broke the build.

TEXT_TAGS = {
    "TextView", "Button", "EditText", "CheckBox", "Switch", "RadioButton",
    "CheckedTextView", "AutoCompleteTextView", "ToggleButton", "MultiAutoCompleteTextView",
    "androidx.appcompat.widget.AppCompatTextView",
    "androidx.appcompat.widget.AppCompatButton",
    "androidx.appcompat.widget.AppCompatEditText",
    "androidx.appcompat.widget.AppCompatCheckBox",
    "androidx.appcompat.widget.AppCompatRadioButton",
    "com.google.android.material.textview.MaterialTextView",
    "com.google.android.material.button.MaterialButton",
    "com.google.android.material.textfield.TextInputEditText",
    "com.google.android.material.switchmaterial.SwitchMaterial",
    "com.google.android.material.checkbox.MaterialCheckBox",
    "com.google.android.material.radiobutton.MaterialRadioButton",
    "com.google.android.material.chip.Chip",
}

TEXT_MEMBERS = {
    "text", "setText", "hint", "setHint", "append", "setError", "setTextColor",
    "setTextSize", "setTypeface", "setGravity", "setMaxLines", "setSingleLine",
    "setEllipsize", "setTextAppearance", "setInputType", "setSelection", "setFilters",
    "setMovementMethod", "setImeOptions", "setKeyListener", "setCursorVisible",
    "setHintTextColor", "setLinkTextColor", "setHighlightColor", "setCompoundDrawables",
    "setCompoundDrawablePadding", "setLetterSpacing", "setLineSpacing", "setAllCaps",
    "setTextIsSelectable", "setShowSoftInputOnFocus", "setSelectAllOnFocus",
    "setRawInputType", "setTransformationMethod", "setShadowLayer", "setEms",
    "setMinEms", "setMaxEms", "setMinLines", "setHorizontallyScrolling",
    "setFreezesText", "setIncludeFontPadding", "setBreakStrategy",
    "setHyphenationFrequency", "setJustificationMode", "setFallbackLineSpacing",
    "setAutoSizeTextTypeUniformWithConfiguration", "setAutoSizeTextTypeWithDefaults",
    "setTextLocales", "setTextScaleX", "setTextSkewX", "setFontFeatureSettings",
    "setFontVariationSettings", "setElegantTextHeight", "setTextCursorDrawable",
    "setLineHeight", "setTextKeepState",
}

IMAGE_TAGS = {
    "ImageView", "ImageButton",
    "androidx.appcompat.widget.AppCompatImageView",
    "androidx.appcompat.widget.AppCompatImageButton",
    "com.google.android.material.imageview.ShapeableImageView",
    "com.google.android.material.floatingactionbutton.FloatingActionButton",
}

IMAGE_MEMBERS = {
    "setImageResource", "setImageDrawable", "setImageBitmap", "setImageURI",
    "setImageIcon", "setScaleType", "setColorFilter", "setAdjustViewBounds",
}

CHECK_TAGS = {
    "CheckBox", "Switch", "RadioButton", "ToggleButton",
    "androidx.appcompat.widget.AppCompatCheckBox",
    "androidx.appcompat.widget.AppCompatRadioButton",
    "com.google.android.material.switchmaterial.SwitchMaterial",
    "com.google.android.material.checkbox.MaterialCheckBox",
    "com.google.android.material.radiobutton.MaterialRadioButton",
}

CHECK_MEMBERS = {"isChecked", "setChecked", "toggle", "setOnCheckedChangeListener"}

PROGRESS_TAGS = {
    "ProgressBar",
    "com.google.android.material.progressindicator.LinearProgressIndicator",
    "com.google.android.material.progressindicator.CircularProgressIndicator",
}

PROGRESS_MEMBERS = {
    "setProgress", "setMax", "setIndeterminate", "setProgressDrawable",
    "setSecondaryProgress", "setProgressCompat",
}

RECYCLER_TAGS = {"androidx.recyclerview.widget.RecyclerView"}

RECYCLER_MEMBERS = {
    "setLayoutManager", "setAdapter", "setHasFixedSize", "setItemAnimator",
    "addItemDecoration", "setItemViewCacheSize", "swapAdapter", "setRecycledViewPool",
    "getRecycledViewPool", "smoothScrollToPosition", "scrollToPositionWithOffset",
    "suppressLayout", "setPreserveFocusAfterLayout",
}

TYPE_FAMILIES = [
    ("text", TEXT_MEMBERS, TEXT_TAGS),
    ("image", IMAGE_MEMBERS, IMAGE_TAGS),
    ("checkable", CHECK_MEMBERS, CHECK_TAGS),
    ("progress", PROGRESS_MEMBERS, PROGRESS_TAGS),
    ("recycler", RECYCLER_MEMBERS, RECYCLER_TAGS),
]

# Every tag this check actually understands. A widget outside this set is a project custom
# view or a third-party widget whose API is unknown here, so it is never judged: ScoreRing
# legitimately declares its own setProgress(Float), and flagging that would be noise.
#
# Containers must be listed explicitly. They are standard widgets with a known API that
# simply has no text/image/checkable/progress/recycler surface, so a text-only member called
# on one is a real error. Leaving them out made the check skip the exact bug it exists for:
# btnFreeze is a LinearLayout row, and `btnFreeze.text` is what broke CI.
CONTAINER_TAGS = {
    "LinearLayout", "FrameLayout", "RelativeLayout", "GridLayout", "TableLayout",
    "TableRow", "ScrollView", "HorizontalScrollView", "NestedScrollView", "View",
    "ViewGroup", "Space", "include", "merge",
    "androidx.constraintlayout.widget.ConstraintLayout",
    "androidx.constraintlayout.widget.Guideline",
    "androidx.coordinatorlayout.widget.CoordinatorLayout",
    "androidx.core.widget.NestedScrollView",
    "androidx.appcompat.widget.LinearLayoutCompat",
    "androidx.appcompat.widget.Toolbar",
    "com.google.android.material.appbar.AppBarLayout",
    "com.google.android.material.appbar.MaterialToolbar",
    "com.google.android.material.card.MaterialCardView",
    "com.google.android.material.tabs.TabLayout",
}

KNOWN_TAGS = set(CONTAINER_TAGS)
for _fam, _members, _tags in TYPE_FAMILIES:
    KNOWN_TAGS |= _tags


def type_mismatch(member, tag):
    """Return the family name when `member` is exclusive to a family `tag` does not belong to.

    Returns None when the member is not family-specific, or when the tag is not a widget this
    check knows, which keeps custom and third-party views out of the result.
    """
    if tag not in KNOWN_TAGS:
        return None
    for fam, members, tags in TYPE_FAMILIES:
        if member in members and tag not in tags:
            return fam
    return None


def resolve_member(layout, member):
    """Return (kind, payload) for a binding member.

    kind is "widget" with the widget tag, "binding" with the included layout name, or None
    when the id is not declared in that layout at all.
    """
    tag = id_tag.get(layout, {}).get(member)
    if tag is None:
        return None, None
    if tag == "include":
        return "binding", include_map.get(layout, {}).get(member)
    return "widget", tag


for f in glob.glob(os.path.join(JAVA, "**/*.kt"), recursive=True):
    src = open(f, encoding="utf-8").read()
    rel = os.path.relpath(f, ROOT)

    var_to_layout = {}
    for prefix, rest in set(BINDING_RE.findall(src)):
        layout = prefix.lower() + "_" + re.sub(r"(?<!^)(?=[A-Z])", "_", rest).lower()
        if layout not in layout_ids:
            continue
        cls = prefix + rest + "Binding"
        for var in re.findall(r"\b(?:val|var)\s+(\w+)\s*:\s*" + cls + r"\b", src):
            var_to_layout[var] = layout
        for var in re.findall(r"\b(\w+)\s*=\s*" + cls + r"\.inflate", src):
            var_to_layout[var] = layout
        for var in re.findall(r"\b(?:val|var)\s+(\w+)\s*=\s*" + cls + r"\.inflate", src):
            var_to_layout[var] = layout
        for var in re.findall(r"\b(?:val|var)\s+(\w+)\s*:\s*" + cls + r"\s*\)", src):
            var_to_layout[var] = layout

    for var, layout in var_to_layout.items():
        # Walk the whole access chain, not just the first segment. `binding.btnFreeze.text`
        # yields the chain [btnFreeze, text]; the first segment resolves to a widget and the
        # second is the member actually being called on it. Matching only the first segment
        # is why an earlier version of this check stayed silent on the real CI failure.
        chain_re = re.compile(r"\b" + re.escape(var) + r"((?:\.[a-z][A-Za-z0-9]*)+)")
        for chain in set(chain_re.findall(src)):
            segs = chain.strip().strip(".").split(".")
            cur_layout = layout
            for i, seg in enumerate(segs):
                kind, payload = resolve_member(cur_layout, seg)
                if kind is None:
                    # Not an id in this layout. Inside an included layout that is a real
                    # error; at the top level it is just a method or a non-id member.
                    if cur_layout != layout:
                        failures.append(
                            f"{rel}: {var}.{'.'.join(segs[:i + 1])} not in {cur_layout}.xml"
                        )
                    break
                if kind == "binding":
                    if payload not in layout_ids:
                        failures.append(
                            f"{rel}: {var}.{seg} includes @layout/{payload}, which does not exist"
                        )
                        break
                    cur_layout = payload
                    continue
                # seg is a widget; the next segment is what is being called on it.
                if i + 1 < len(segs):
                    nxt = segs[i + 1]
                    fam = type_mismatch(nxt, payload)
                    if fam:
                        failures.append(
                            f"{rel}: {var}.{'.'.join(segs[:i + 2])} is {fam}-only, but "
                            f"{cur_layout}.xml declares {seg} as {payload}"
                        )
                break

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
