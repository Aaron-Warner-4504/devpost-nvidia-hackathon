# Sentinel design system

Sentinel protects ordinary people, including parents who are new to smartphones, from scams. The app has to feel
**calm, trustworthy and quiet**, and when something is wrong it has to be **unmistakable without being frightening**.
The reference is the restraint of Apple's own apps, built with Android-native parts (Compose, predictive back,
edge-to-edge, Android haptics) rather than copying iOS chrome.

Everything below lives in `app/src/main/java/ai/enthral/sentinel/ui/theme` (tokens) and `ui/components` (parts).
Screens only compose those parts. If a screen needs a one-off value, the token is missing: add the token.

## Principles
1. **One idea per screen.** Each screen answers one question ("Am I protected?", "Why was this flagged?").
2. **Type, not boxes.** Hierarchy comes from size, weight and whitespace. Cards appear only when they group
   something the user acts on (an alert, a code, a settings group).
3. **Content first, chrome last.** Large titles that scroll away, a hairline instead of a toolbar shadow, no FAB.
4. **Calm urgency.** A critical alert is a clear sentence, a heavier weight and one accent colour, never a red
   screen. Colour always comes with an icon shape and a word.
5. **Nothing decorative without purpose.** One soft gradient (Home hero), one breathing glyph, no other effects.
6. **Every state is designed:** loading (shimmer skeleton), empty (illustration + sentence), error (sentence +
   one retry), offline (quiet note, cached data stays visible).

## Glass and atmosphere (pass 2)
The first pass was correct but too plain. Pass 2 adds depth and atmosphere without giving up calm:

* **Three layers, always in this order.** (1) A living *atmosphere*: a deep base with three slow, soft aurora blobs
  in the brand hues (teal, harbour blue, sage in light; teal, indigo, violet in dark). (2) *Content* on translucent
  glass cards with a 1-px light edge. (3) *Navigation glass*: the top bar and a floating bottom bar that blur the
  content scrolling beneath them. Nothing else is glass.
* **Blur only where something moves beneath.** Cards sit on the smooth atmosphere, where blur would be invisible, so
  they are translucent fills, not blurred (cheap, and safe in long lists). Real backdrop blur (Haze, API 31+) is
  reserved for the bars, at most two blurred layers per screen, radius 24 dp.
* **Tint before blur.** Text never sits on raw blur: every glass surface has a tint layer heavy enough that every
  text colour passes 4.5:1 against the *worst-case* backdrop (the brightest/darkest aurora overlap, or a solid
  accent button scrolling under a bar). `PaletteContrastTest` computes these composites.
* **Opaque fallback.** Glass turns into the pass-1 opaque surfaces (same layout, no translucency, static
  atmosphere) when the user turns on Settings > Appearance > Reduce transparency, or the system has animations off,
  high-contrast text, battery saver or a low-RAM device. Below API 31 the bars use a heavier tint instead of blur.
* **Calm glow, not alarm.** A critical alert's hero card gets a crimson luminous edge and a soft glow of the same
  hue. The screen stays dark/airy; the sentence still carries the meaning.
* **Adaptive navigation.** Compact width (< 600 dp, phones in portrait): floating glass bottom bar in thumb reach.
  Medium (600 to 839 dp): glass navigation rail. Expanded (>= 840 dp): labelled sidebar with a secondary group, and
  Alerts becomes list + detail side by side. A sidebar on a portrait phone would take a third of the width away from
  the content and put navigation out of thumb reach, so phones never get one.

## Colour
Neutrals are near-white and near-black with a faint cool cast. Surfaces are layered, not outlined:

| Role | Light | Dark | True black (OLED) |
|---|---|---|---|
| `groupedBackground` (screen) | `#F2F3F5` | `#0B0C0E` | `#000000` |
| `background` (onboarding, sheets behind) | `#FBFBFC` | `#0B0C0E` | `#000000` |
| `elevated` (rows, cards, sheets) | `#FFFFFF` | `#1A1C1F` | `#131416` |
| `elevatedSecondary` (fields, chips, skeleton) | `#F6F7F9` | `#24272B` | `#1E2023` |
| `label` | `#101216` | `#F2F3F5` | same |
| `secondaryLabel` | `#565C66` | `#A3A9B2` | same |
| `tertiaryLabel` | `#676D77` | `#8C929B` | same |
| `separator` (hairline, decorative) | `#E3E5E9` | `#2B2E33` | `#222427` |
| **`accent`** | **`#0C5A73`** | **`#71C4DA`** | same |
| `accentSoft` | `#E4EFF3` | `#12303A` | `#0F2A33` |

**Accent: "harbour ink", a deep blue-teal.** Blue reads as trustworthy and institutional (banks, government), teal
keeps it from feeling corporate or cold, and it sits far from every tier hue, so the accent can never be mistaken
for a warning. It is the only colour used for interactive elements (primary button, links, switches, selection).

**Tiers** are desaturated and paired with a shape and a label:

| Tier | Shape | Light content / container | Dark content / container |
|---|---|---|---|
| Info ("Looks fine") | circle | `#2B6A4C` / `#E5F1EA` sage | `#8DD0AE` / `#14281E` |
| Caution | diamond | `#7A5100` / `#F9EFD9` amber | `#EDC36C` / `#2C2311` |
| High risk | triangle | `#9A3B12` / `#FAE8DF` clay | `#F4A684` / `#33190E` |
| Critical | octagon | `#A8172F` / `#FBE3E6` crimson | `#FF9DAA` / `#3A1219` |

Rules: text pairs ≥ 4.5:1 and UI/glyph pairs ≥ 3:1, in light, dark and true black (enforced by `PaletteContrastTest`
for every text/background pair). No Material dynamic colour: the tier hues carry meaning and the accent is
deliberate. Tier colour fills at most a glyph, a small badge or the current timeline event, never a whole screen.

## Typography
Inter (variable, OFL, `res/font/inter_variable.ttf`, subset to Latin + ₹ + punctuation, weights 400 to 700, 86 KB)
for Latin. Devanagari falls back to the system Noto Sans Devanagari, which matches Inter's x-height and stroke
well, so no Devanagari font ships. Hindi gets ~12% more line height (`DevanagariType`, picked by the theme from the locale).

| Token | Size / line (sp) | Weight | Use |
|---|---|---|---|
| `largeTitle` | 34 / 41 | SemiBold, -0.4 tracking | screen titles, verdicts |
| `title1` | 28 / 34 | SemiBold | hero status line |
| `title2` | 22 / 28 | SemiBold | invite code label, sheet titles |
| `title3` | 20 / 25 | SemiBold | collapsed bar title, card headlines |
| `headline` | 17 / 22 | SemiBold | row titles, section headers |
| `body` | 17 / 24 | Regular | reading text |
| `callout` | 16 / 22 | Regular | secondary text in cards |
| `subhead` | 15 / 20 | Regular | row subtitles |
| `footnote` | 13 / 18 | Regular | section footers, timestamps |
| `caption` | 12 / 16 | Medium | tab labels, badges |

Times, counts and codes use tabular figures (`tnum`); the invite code also uses wide tracking. Everything scales to
2.0x; layouts wrap instead of truncating (the tab bar caps its labels at 1.3x and keeps full names for TalkBack).

## Spacing, shape, elevation
* 8-dp grid: 4, 8, 12, 16, 20, 24, 32, 48. Screen gutter 20 dp; grouped lists are inset 16 dp.
* Radii: 10 (chips, small), 14 (fields, buttons), 20 (cards, grouped lists), 28 (sheets, hero). Rounded corners
  everywhere; pills only for badges.
* Elevation is light, not shadow: a surface is lifted by being a lighter layer. Cards that float over content
  (Home hero, latest alert) get one soft diffuse shadow (`softShadow`: 24 dp blur, 6% ink); no Material tonal elevation.
* Touch targets ≥ 48 dp; rows ≥ 52 dp (60 with a subtitle).

## Motion
Short and spring-based; everything is skipped when the system "remove animations" setting is on.
* Screen push/pop: shared-axis X (slide 8% + fade, `spring(stiffness = 500)`); tabs: fade-through (fade + 2% scale).
  Predictive back is enabled (`enableOnBackInvokedCallback`).
* Large titles scroll with content; a compact title and hairline fade in once the large title has scrolled away.
* Home hero glyph breathes slowly (4 s, 3% scale) when protected and morphs (scale + crossfade) between states.
* A critical tier glyph pulses once when it appears; list items fade/settle in with `animateItem`.
* Skeletons shimmer (1.2 s sweep). Buttons scale to 97% on press.
* Haptics: light tick on selection (tabs, segmented, toggles), confirm on feedback sent, reject on a critical alert
  opening.

## Components (`ui/components`)
`LargeTitleList` / `LargeTitleColumn` + `CollapsingTopBar` (large title, compact bar on scroll, optional back),
`TabBar`, `GroupedSection` / `GroupedItem` + `GroupedRow` (chevron when it navigates) / `ToggleRow` / `ValueRow`
(inset grouped lists with hairlines), `PrimaryButton`,
`SecondaryButton`, `PlainButton`, `DestructiveButton`, `IconAction`, `SegmentedControl`, `TierGlyph`, `TierBadge`,
`TierCountRow`, `AlertCard`, `AlertRow`, `StatusHero`, `WhyTimeline`, `SignalChips`, `NoteCard` (quiet footnote),
`StatusLine`, `EmptyState`/`ErrorState`, `LoadingSkeleton` + `Modifier.shimmer`, `SentinelSheet`/`ConfirmSheet`,
`SentinelTextField`, `SentinelSnackbarHost`, `PageIndicator`, `CodeDisplay` + `CountdownRing`, `CodeEntry`,
illustrations (shield, masked messages, family, language), `SentinelIcons` (~40 1.6-dp line icons), `Haptics`.

## Do / don't
* Do write the verdict as a sentence ("Likely scam: ..."). Don't rely on a colour or a badge alone.
* Do use one primary button per screen. Don't put two filled buttons side by side.
* Do use grouped lists for settings-like content. Don't put borders around cards.
* Do keep destructive actions calm (red text, confirmation sheet). Don't use a red filled button.
* Don't add a new colour, radius or text style in a screen: add a token here first.

## Accessibility
Headings carry heading semantics; tier glyphs are announced as "Risk level: X"; toggles announce name and state;
timeline events are merged into one announcement each; the invite code is spelled out. Contrast is unit-tested.
Font scale 1.3 and 2.0 must not clip (spot-checked on Home, Alert detail, Family, Settings). Targets ≥ 48 dp.
Motion and shimmer respect "remove animations".

## References & what we borrowed
Read for this pass (October 2026). Each idea names the screen that uses it.

1. Apple, [Adopting Liquid Glass](https://developer.apple.com/documentation/technologyoverviews/adopting-liquid-glass):
   glass is "a distinct functional layer for controls and navigation", used sparingly, never stacked. We keep glass
   for the bars and lift content on translucent cards (all screens).
2. Same page: tab bars "recede" while scrolling, and the tab bar can adapt into a sidebar. We float the bottom bar
   as an inset pill (all tabs) and turn it into a rail / sidebar on wider windows.
3. Same page: "Optimize for legibility when content scrolls beneath controls" (scroll edge effect). Our bars blur and
   tint whatever scrolls under them (top bar, bottom bar).
4. Same page: half sheets turn "more opaque" when expanded to keep focus. Our sheets use the thickest glass tint
   (consent, privacy, delete, unlink).
5. Same page: test with reduce transparency / motion settings. Our Reduce transparency toggle and system fallbacks.
6. Apple Newsroom, [new software design](https://www.apple.com/newsroom/2025/06/apple-introduces-a-delightful-and-elegant-new-software-design/):
   glass "reacts to movement with specular highlights". We use a static 1-px specular edge (top-left highlight)
   on every glass card instead of a live effect (cards, bars).
7. Nielsen Norman Group, [Glassmorphism](https://www.nngroup.com/articles/glassmorphism/): check contrast against
   every background area, keep the background simple, add a low-opacity stroke, let users reduce transparency. This
   is why the aurora is soft blobs (no imagery), why every card has an edge stroke, and why contrast is computed on
   worst-case composites.
8. AOSP, [Window blurs](https://source.android.com/docs/core/display/window-blurs): blur is API 31+, the system
   disables it in battery saver and on weak GPUs, and apps should ship a more opaque fallback. Our fallback rules.
9. Android, [window size classes](https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes)
   (compact < 600, medium < 840, expanded) and [adaptive navigation](https://developer.android.com/develop/ui/compose/layouts/adaptive/build-adaptive-navigation)
   (bar on compact, rail otherwise, drawer optional on expanded). Our breakpoints; we draw our own glass rail and
   sidebar instead of `NavigationSuiteScaffold` to keep the glass look.
10. Google Design, [M3 Expressive research](https://design.google/library/expressive-material-design-google-research):
    larger, contained key actions were found up to 4x faster and helped users 45+; also "doesn't suit every product,
    such as banking" and a strong minority preferred calmer designs. We enlarge the one key action per screen
    (hero action, Check, Share) but keep the palette calm.
11. [Haze](https://github.com/chrisbanes/haze) (Apache-2.0) for backdrop blur: `hazeSource` on content,
    `hazeEffect` on the bars, `blurEnabled` for the fallback. 1.6.10 is the newest version compatible with our
    Compose 1.8 / Kotlin 2.2 toolchain.
12. Truecaller's colour-coded full-screen caller ID ([TechRadar](https://www.techradar.com/news/truecaller-update-revamps-the-app-with-smart-features))
    shows that a single strong hue is recognised at a glance. We use the tier hue as a glow on the critical hero
    only, never as a full-screen fill.

Deliberately not copied:
* **Live refraction and specular motion** (Liquid Glass): expensive on mid-range Android and distracting for older
  users; a static edge gives the same "glass" read.
* **Full-screen red caller-ID style warnings** (Truecaller): alarming; Sentinel's principle is calm urgency.
* **NeoPOP-style 3-D extruded buttons and playful motion** (CRED, as described in
  [press coverage](https://analyticsindiamag.com/cred-open-sources-its-ui-design-system-neopop/)): strong brand
  personality, wrong mood for a safety app used by parents.
* **Icon-only toolbars without labels**: the M3 Expressive research found removing labels hurt usability; every
  navigation item keeps its label.
* **Busy photographic or video backgrounds behind glass**: NN/g's main legibility failure; our backdrop is abstract
  and slow.
