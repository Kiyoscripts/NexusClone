# NexusClone UI mockup

Static HTML/CSS/JS mockup of the redesigned NexusClone interface. No build step, no
dependencies. Open `index.html` directly or serve it.

## Serve

```bash
./serve.sh          # 127.0.0.1:8453 + tailnet https://kaizerfx.tail8b5e67.ts.net:8453
```

## Verify

```bash
python3 verify.py   # renders all 6 screens in both themes, asserts no console errors,
                    # no page errors, no horizontal overflow; writes shots/*.png
```

## Decision recorded

Three bottom tabs: Clones, Apps, Settings. Diagnostics is not a tab. It is reached from
the Settings row (with a live count badge) and from the clone detail, and its summary
state is surfaced as a status strip on the Clones screen. Proxy is per-clone, reached
from the clone detail, with a default-proxy row in Settings.

## Screens

| # | Screen | Replaces |
|---|--------|----------|
| 01 | Clones grid | `activity_main.xml` ViewPager2 + WormDotsIndicator + `item_app.xml` |
| 02 | Clone detail | `activity_instance_detail.xml` (fifteen identical buttons) |
| 03 | Apps picker | `activity_list.xml` + `item_package.xml` |
| 04 | Proxy | `activity_proxy.xml` (five bare EditTexts) |
| 05 | Diagnostics | `activity_diagnostics.xml` + `item_check.xml` |
| 06 | Settings | `activity_setting.xml` |

## Design rules this mockup encodes

- The clone is the primary object, not a hidden detail of an app icon.
- Dark-first control room: near-black canvas, one muted lavender accent, hairline
  borders instead of shadows, oversized tabular numerals, monospace for package names
  and identifiers.
- Light theme is a designed counterpart, not an inversion.
- Every screen has an explicit empty, loading and error state; the mockup shows the
  error state on the Proxy bypass field and the warn/fail states on Diagnostics.
- Touch targets are 44px minimum; the tab bar is 62px plus a 22px gesture inset.

## Tokens

Defined once at the top of `app.css` and mirrored into `values/colors.xml` +
`values-night/colors.xml` in Phase 1.

| Token | Dark | Light |
|-------|------|-------|
| canvas | `#0B0B10` | `#F7F7FB` |
| surface | `#14141C` | `#FFFFFF` |
| surface_raised | `#1C1C26` | `#FFFFFF` |
| hairline | `#262633` | `#E6E6EF` |
| accent | `#C9C9F0` | `#5B5BD6` |
| accent_strong | `#8B8BF5` | `#4F46E5` |
| success | `#4ADE80` | `#16A34A` |
| warn | `#FBBF24` | `#B45309` |
| danger | `#F87171` | `#DC2626` |
| text_primary | `#ECECF4` | `#14141C` |
| text_secondary | `#8E8EA3` | `#5C5C70` |
| text_tertiary | `#5C5C70` | `#8E8EA3` |

Radius: 8 chip, 12 input, 16 card, 28 FAB. Spacing: 4/8/12/16/20/24/32/48.

## Files

- `index.html` - all six screens, inline SVG sprite, no external assets
- `app.css` - tokens, components, responsive rules
- `app.js` - screen routing, theme toggle, segmented/chip/switch interaction
- `verify.py` - Playwright render + assertion pass
- `shots/` - 12 device captures (6 screens x 2 themes) + 1 phone-viewport capture
