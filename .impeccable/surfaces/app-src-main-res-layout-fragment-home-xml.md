---
version: 1
slug: "app-src-main-res-layout-fragment-home-xml"
primary_target: "app/src/main/res/layout/fragment_home.xml"
related_targets: ["app/src/main/res/layout/fragment_home_head.xml"]
---

# Home (phone + TV) and app-wide restyle

Scope: full redesign of CloudStream Nobar, all screens, phone and TV, in the Chica family world. Mode: Operate.
Approved comp: .impeccable/mocks/decision/bysource.webp (card "Per sumber", seed 6e1c967a).

## Direction contract
THESIS: The home is organised by source. Installed extensions become the first navigation (a row of pills), the selected source's featured title leads as a hero with Putar and Nobar side by side, then poster rails. Refuses CloudStream's full-screen centred hero with a hidden provider dropdown.
OWN-WORLD: Chica's Mixtape Bareng: plum ground #1C1721, plum surfaces #2A2233/#3D3147, hairline #3B3245, ink #F2E9F1, muted #9D8FA5, one blush pink #F2BED1 (on-pink #4A3B47) for the primary action, the selected pill and progress only, never text. Amber #F59E0B only for ratings. Pills for every button and filter, 8-12dp poster corners, google_sans.
STORY: Pick a source, see what it features, press Putar to watch alone or Nobar to watch together; continue watching sits in the rails.
FIRST VIEWPORT: Header (pixel brand tile, "Beranda", search, avatar) then source pills (selected pink, '+' to add extensions) then hero backdrop ~45% height with title, "Series · 2026 · ★ 9.0", Putar (pink pill) + Nobar (outlined pill); then "Trending" poster rail; bottom navigation (Beranda, Cari, Pustaka, Unduhan, Pengaturan). TV: same order under the navigation rail, focus = pink outline.
FORM: "Per sumber", position 3 of 7 on the ordered list, seed key 6e1c967a.
FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
