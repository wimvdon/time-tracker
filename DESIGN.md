# Design

Time Tracker for Notion (Android) is one of the sibling Android apps (Watchlist, Time Tracker
for Notion, Snoozer, RSS Reader and Noter for Notion) that share one design language.

The shared rules (every section except "This app") have their master copy in `DESIGN.md` of
the android-app template (`wimvdon/android-app`). Change them there first, then copy them
unchanged to every app. Only the "This app" section belongs to this repository.

## Icons

- One meaningful shape with one diagonal gradient through neighbouring hues (top left to bottom
  right), filling most of the 66dp safe zone. The shape itself says what the app does: no
  symbol dropped into a box, no shape inside a shape, no thin outlines, glass or drop shadows.
- The adaptive-icon background is a soft plate tinted with the app colour, never white: the
  middle gradient stop mixed with white, 90% at the top left to 80% at the bottom right
  (`res/drawable/ic_launcher_background.xml`).
- A detail inside the shape (a check, a play button) is solid in the plate's colour, not a
  hole and not white, so the icon also reads without its plate.
- A themed (monochrome) layer: the same shape, with the detail cut out or half transparent.
- Every other icon of the app follows the same shape. Only icons the system shows as a tile
  carry the plate: the launcher icon, home-screen shortcuts, TV banners and the icons of an
  installed web app on a phone (maskable and Apple touch icons). Everywhere else the shape
  stands on its own, without a plate: in-app logos, the TV home row logo, the status bar
  (notification) icon, Quick Settings tiles, web favicons, desktop web app icons and extension
  icons.

| App | Shape | Gradient | Plate |
| --- | --- | --- | --- |
| Watchlist | Wide TV screen with a fat check on its right side | `#3D7BFF` → `#7A5CFF` → `#D15CE6` | `#F2EFFF` → `#E4DEFF` |
| Content | A C built from the feed symbol's dot and arcs | `#4FC3F7` → `#0091EA` → `#0D47A1` | `#E6F4FD` → `#CFE6F8` |
| Player | Three uneven bars on their side | `#FFA23A` → `#F0243A` → `#A0105E` | `#FCE9E8` → `#F9D3D1` |
| Clauder | Progress ring of six segments around a check | `#FAA75E` → `#DA6440` → `#A52E52` | `#FBF1EE` → `#F7E4DD` |
| Quick Settings | Settings cogwheel with a lightning bolt | `#4FC3F7` → `#3D7BFF` → `#5B4FD9` | `#ECF2FF` → `#D8E5FF` |
| TV Favorites | Heart with a play button | `#FF7AA8` → `#E83A7A` → `#A82BC4` | `#FDEBF2` → `#F6D5E6` |
| Screensaver | Crescent moon | `#B388FF` → `#7C4DFF` → `#4527A0` | `#F2EDFF` → `#E5DBFF` |
| Time Tracker for Notion | Stopwatch with its elapsed time as a slice that fades in | `#FF8A3D` → `#F04438` → `#C81E5A` | `#FEECEB` → `#FCDAD7` |
| Snoozer | Bell with one fat Z on it | `#22C3B4` → `#3E7BDA` → `#4E5BB0` | `#ECF2FB` → `#D8E5F8` |
| Noter for Notion | Sticky note on a 3D box with a folded corner (not redesigned yet: white background) | `#FFC94D` → `#F5A623` → `#E8710A` | |

## Colour

- Each app's fallback palette is a full Material 3 tonal scheme generated from its icon's
  middle gradient stop, so every colour role is set (none fall back to Material's purple).
- Settings › Theme offers System / Light / Dark and, on Android 12+, a Dynamic color switch.
  Dynamic color is off by default so the app's own colours show.
- The status and navigation bar icons follow the app's theme, not the system's
  (`SystemBarsFollowTheme`).
- `android:windowBackground` (and the splash background) matches the theme's background.

## Material 3

- Top app bars are Material's `TopAppBar` family with the default title style
  (Title Large, regular weight). Weight is not used to make app bar titles stand out.
- Emphasis through weight is kept for hero content (a title on a detail page, a big number).
- Button labels are in sentence case and short: the action in one to three words ("Watched",
  "Open settings", "Retry"). The screen around the button gives the context, so a label never
  repeats it ("Watched", not "Mark as watched"); explanations go in the text above the button.
  Longer wording is fine in menus and for screen readers (`contentDescription`).
- Icons come from Material's Rounded set; direction-dependent icons use `AutoMirrored`.
- Cards and grouped surfaces use the `surfaceContainer*` roles.
- Components keep Material's shape scale (`MaterialTheme.shapes`). App-level containers use
  `AppShapes`: `Group` (24dp) for banners and grouped content, `Item` (20dp) for tiles and
  highlighted rows.

## Navigation and structure

Follows Material 3's split between destinations and actions:

- Top-level destinations (two to five) sit in Material's `NavigationBar`, docked at the bottom
  edge. On wide windows an app may switch to a `NavigationRail` with the same destinations in
  the same order. A selected destination shows its filled (Rounded) icon, the others their
  Outlined icon, as Material's navigation guidelines describe. Floating bars (M3 Expressive's floating toolbar) are for actions on the
  current screen, never for switching sections.
- Tabs are only for peer views of the same content. Chips filter or sort a collection; they are
  not navigation and not general buttons.
- Every other page is a child page: a top app bar with a back arrow, no navigation bar.
- One FAB at most, for the screen's main action (add, start). Destructive actions never sit in
  a FAB or next to routine actions without separation, and ask for confirmation or offer Undo.
- Lists are lists: `ListItem` rows (or rows with the same paddings), separated by spacing or
  dividers. A card is for a unit that stands on its own (a poster, a banner, a running timer),
  not for every row or section.
- Rows that scroll sideways (posters, trailers, chips) keep one height while they scroll: text
  under a tile has a fixed number of lines (two for a title, one for a caption, ending in "…"),
  and a missing line still takes its space. Otherwise the row grows and shrinks as tiles with
  longer titles scroll into view, and everything below it jumps.
- Content keeps a readable width on wide windows: lists, forms, settings and text stay at most
  640dp wide and centred (`Modifier.readableWidth()`). Grids of posters or cards and pictures
  may use the whole width.

## Reordering

Every list the user can put in their own order works the same way (`ui/theme/Reorder.kt`):

- A drag handle (the six-dot grip, `DragHandle`) at the end of each row. Dragging it moves the
  row at once; the dragged row lifts with a shadow on `surfaceContainerHigh`, with a haptic tick
  when the drag starts. The new order is saved when the row is dropped.
- No up and down arrows. Screen readers get Move up and Move down as actions on the handle (or
  on the row in a list with its own drag code).
- A row that can't move (for example a destination locked last) shows what it is instead of a
  handle, such as a lock, and other rows can't pass it.
- Short lists use `ReorderableColumn`. A long or lazy list, or one with sections, may build
  its own drag on the same look and handle.
- Cards in a grid (a staggered grid of notes) are moved with a long press instead, as on a
  home screen: a grid has no row end for a handle.

## Settings screen

Built from `ui/theme/SettingsKit.kt`, so every app's settings look and behave the same. It
follows the Android and Material 3 settings pattern: one scrolling list, grouped by headers.

- `SettingsScaffold`: a large top app bar ("Settings") that collapses on scroll, with a back
  button, over one list, at most 640dp wide.
- `SettingsSection`: a section header (Title Small in the primary colour) above its rows. No
  cards and no collapsing: every setting is visible and one tap away.
- Rows inside a section:
  - `SwitchSetting`: a `ListItem` with a switch; the whole row is the switch and the change
    applies at once (no Save button).
  - `SettingItem`: a `ListItem` that opens something (a page, a dialog, another app), with a
    chevron or "opens elsewhere" icon when it leaves the screen.
  - `ExpandableSetting`: a row that shows longer instructions below it (setup steps most people
    read once).
  - `SettingsBlock`: anything else (a text field, segmented buttons for a single choice among
    two to four options, an explanation, a button), lined up with the rows' text.
- A choice among more than four options, or with long labels, opens a dialog with radio buttons
  from a `SettingItem` that shows the current value.
- Sections in this order, leaving out what an app does not have:
  1. Connection (account, API key, sync)
  2. The app's own features
  3. Shortcuts and integrations
  4. Appearance (`AppearanceSettings`: Theme, then Dynamic color)
  5. Language and formats
  6. `SettingsFooter`: app name and version, plus links such as data sources

## Motion

- Material 3 easing and duration tokens in `Motion`.
- Screens change with the shared-axis X transition (`Motion.sharedAxisX`, 30dp).
- State changes fade through (`Motion.fadeSpec`).
- Decorative animation is skipped when the user turned animations off
  (`rememberReducedMotion()`).

## Loading

- Content that loads over the network shows placeholders while it loads for the first time
  (`ui/theme/Skeleton.kt`), shaped like what will appear: poster tiles, list rows, form fields,
  lines of text. A soft highlight sweeps across them; with animations off they stay still.
- Screen readers read the placeholders out as one "Loading…" element.
- A refresh keeps the content on screen and shows pull-to-refresh or a small progress
  indicator instead; a busy button shows a spinner in place of its icon.
- An app with nothing to load (Snoozer) leaves `Skeleton.kt` out.

## Empty and setup states

Every screen that can be empty uses `EmptyState` (`ui/theme/EmptyState.kt`), so an empty
screen looks the same in every app:

- Centred in the space below the app bar, at most 360dp wide, and scrollable so pull-to-refresh
  keeps working.
- An icon in a 72dp `secondaryContainer` circle that says what is missing (a bookmark for an
  empty watchlist, a key for a missing API key), a title in Title Large that says what the state
  means ("No feeds yet"), and, when it helps, one or two sentences in Body Medium on how to fill
  the screen or what will appear here.
- At most two buttons, for the next useful step: a filled `Button` for the main one, an
  `OutlinedButton` or `TextButton` for an alternative. No button when there is nothing useful
  to do (a list that fills by itself).

The same component covers four situations:

| Situation | Example | Button |
| --- | --- | --- |
| Nothing here yet | "No watched movies" | The way to add something, if there is one |
| Nothing matches | A search or filter found nothing | "Clear filters" when filters are on |
| Couldn't load | The network failed and there is nothing to show | "Retry" |
| Setup needed | Not signed in, no API key, a permission missing | Straight to the place that fixes it ("Open settings") |

- A setup-needed state replaces the screen's content: a screen that can't work shows what to
  do, not a disabled screen with a small banner above it. Apps that need the same setup use the
  same words (Time Tracker for Notion and Noter for Notion both say "Connect to Notion").
- A banner (a `Group`-shaped card at the top) is only for something worth knowing while the
  screen still works. An error from an action on a screen that has content is a snackbar.

## Wording

Shared settings and actions use the same words in every app and language (strings are kept in
English, Dutch, German, French, Spanish and Italian).

| Meaning | en | nl | de | fr | es | it |
| --- | --- | --- | --- | --- | --- | --- |
| Theme: follow the device | System | Systeem | System | Système | Sistema | Sistema |
| Theme: light / dark | Light / Dark | Licht / Donker | Hell / Dunkel | Clair / Sombre | Claro / Oscuro | Chiaro / Scuro |
| Settings section | Appearance | Weergave | Darstellung | Apparence | Apariencia | Aspetto |
| Wallpaper colours | Dynamic color | Dynamische kleuren | Dynamische Farben | Couleurs dynamiques | Color dinámico | Colori dinamici |
| Back | Back | Terug | Zurück | Retour | Atrás | Indietro |
| Cancel / Save | Cancel / Save | Annuleren / Opslaan | Abbrechen / Speichern | Annuler / Enregistrer | Cancelar / Guardar | Annulla / Salva |
| Clear | Clear | Wissen | Löschen | Effacer | Borrar | Cancella |
| Retry | Retry | Opnieuw proberen | Wiederholen | Réessayer | Reintentar | Riprova |
| Dynamic color, supporting text | Use colors from your wallpaper | Kleuren van je achtergrond gebruiken | Farben deines Hintergrundbilds verwenden | Utiliser les couleurs du fond d’écran | Usar los colores del fondo de pantalla | Usa i colori dello sfondo |
| Go to settings | Open settings | Naar instellingen | Zu den Einstellungen | Ouvrir les paramètres | Abrir ajustes | Apri impostazioni |
| Setup needed (Notion apps) | Connect to Notion | Verbind met Notion | Mit Notion verbinden | Connexion à Notion | Conéctate a Notion | Collegati a Notion |
| Close a dialog that only shows something | Close | Sluiten | Schließen | Fermer | Cerrar | Chiudi |
| Finish an editing mode | Done | Klaar | Fertig | OK | Listo | Fine |
| Leave an account | Sign out | Uitloggen | Abmelden | Se déconnecter | Cerrar sesión | Esci |
| Screen reader: reorder handle | Reorder | Volgorde wijzigen | Neu anordnen | Réorganiser | Reordenar | Riordina |
| Screen reader: move a row | Move up / Move down | Omhoog / Omlaag | Nach oben / Nach unten | Monter / Descendre | Subir / Bajar | Sposta su / Sposta giù |

- Prefer the shortest clear wording ("System", not "Follow device (Auto)").
- American spelling in English ("color").
- Address the user informally in Dutch, German, Spanish and Italian (je, du, tú, tu) and with
  "vous" in French.
- Labels and buttons in sentence case.
- Help text only says what the screen doesn't: a consequence ("Your SIMKL lists stay as they
  are"), where data goes, or a term people may not know. It never restates a label ("Hide
  specials" needs no "Leave out special episodes"), describes a standard control (drag handles,
  switches, tapping a row) or explains why the app was built this way. One sentence where
  possible; longer setup steps go in an `ExpandableSetting` or the user guide.
- Buttons name the action as briefly as the language allows; drop "mark as", "markeren als",
  "als … markieren" and the like when the screen already makes it clear ("Gezien", not
  "Markeren als gezien").

## Shared design kit

These files live in each app (the template does not include them). They are identical in every
app apart from their package (and `R` import) lines. Copy a change to the other repositories:

- `ui/theme/Motion.kt`: motion tokens, the shared-axis transition and `rememberReducedMotion()`.
- `ui/theme/Shapes.kt`: `AppShapes`.
- `ui/theme/SystemBars.kt`: `SystemBarsFollowTheme()`.
- `ui/theme/Skeleton.kt`: loading placeholders (`Modifier.skeleton()`, `SkeletonLine`,
  `SkeletonBlock`).
- `ui/theme/EmptyState.kt`: `EmptyState` for empty, no-result, failed and setup-needed screens
  (see "Empty and setup states").
- `ui/theme/SettingsKit.kt`: the settings screen components above. It also needs
  `drawable/ic_arrow_back`, `drawable/ic_expand_more` and the strings `section_expanded` and
  `section_collapsed`.
- `ui/theme/Layout.kt`: `ReadableWidth` (640dp) and `Modifier.readableWidth()`.
- `ui/theme/Reorder.kt`: `DragHandle` and `ReorderableColumn` (see "Reordering"). It also
  needs `drawable/ic_drag_handle` and the strings `action_reorder`, `action_move_up` and
  `action_move_down`. An app without lists to reorder (Snoozer) leaves it out.

## This app

- Seed `#F04438` (red), green tertiary (`#16A34A`) for Start when a workspace has no colour.
- Signature element: the timer. The elapsed time sits on a nine-lobed cookie shape (the
  Material 3 Expressive shape Snoozer's toggle uses), on both the idle and the running screen:
  idle it stands still at 00:00:00; running it turns slowly and springs in when the timer
  starts, over the same slowly breathing glow as Snoozer's snoozing screen. No progress ring:
  a running timer has no target. Start and Stop are Material 3 Expressive medium buttons
  (56dp, Title Medium label, 24dp icon), round, tightening to 12dp corners while pressed. They
  sit on the cookie's bottom edge, overlapping it by half their height, as a FAB anchored to a
  surface does: labelled, full size, and never over the time. Because they sit on the cookie
  they are never see-through: busy, they keep their colour with a spinner; unavailable, they
  take a solid grey.
- Idle and running share one layout, top to bottom: preset chips; the workspace as the
  screen's title (Headline Small, its colour dot; idle, a button opening the workspace menu,
  each workspace with its dot); the cookie with Start or Stop; one card (`AppShapes.Group`)
  holding every timer field; the lists.
- Presets are made and changed in one place: the pen after the preset chips opens Manage
  presets (a list row per preset: its name over its workspace with colour dot, a pen to edit
  and a drag handle to move it within its workspace), with New preset as a dialog action. New and Edit open a full-screen editor: the name
  on top on its own, then a card (`AppShapes.Group`) with what the preset fills in (workspace,
  description, extra fields; required ones marked but not enforced); editing adds Remove
  preset at the bottom, with a confirmation. A new preset starts from the timer form.
  Before the first preset, a "New preset" chip stands in for the chips.
- The card's fields are standard outlined fields with the field's name as its own floating
  label: description, start time (and end time while running), extra fields. Idle, the start
  time is empty ("Now") unless the user picks one for a timer they forgot to start.
- List sections (Active timers, Recent time entries) start with a Title Medium heading and
  are set apart by space, not dividers. Their entries are list rows (at least 56dp high, icon
  buttons with full 48dp touch targets), not a card each; a recent entry shows its workspace
  colour as a dot at the start.
- Preset chips keep Material's 48dp touch target.
- Start is green and Stop is red whatever the theme or wallpaper colours (a workspace colour
  replaces the green), so their meaning never depends on Dynamic color.
- The icon is a stopwatch (round body, crown on a short stem, side button) in orange → red →
  raspberry, with the elapsed time as a slice of the dial in the plate's colour that fades in
  from 12 o'clock (transparent) to the hand (solid). On Android it sits on a soft red plate; on
  Windows (app, taskbar, installer) it stands on its own. The tray shows the same stopwatch, grey
  while idle and in the running workspace's colour. The Android launcher shortcuts show it in the
  workspace colour on a plate tinted with that colour.
- The launcher icon comes from `windows/scripts/generate-icons.cjs` (`npm run icons`), which
  also draws the Windows icons and the Android status bar icon. Edit the artwork there, not the
  generated XML.
- The Windows app is not covered by this file. Its timer follows the same order in Fluent
  terms (Subtitle workspace title as a drop-down button, the time in Title Large on a card with
  Start or Stop as the card's action, a card with every field; presets made and edited from
  Manage presets), so the two apps are recognisably the same.
