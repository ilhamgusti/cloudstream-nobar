# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users
The owner and the people they watch with: friends and a partner who watch films and series together, each on their own phone or on an Android TV box in the living room. They open the app to find something, then start or join a "nobar" (watch together) room so playback stays in sync while they talk.

## Product Purpose
CloudStream Nobar is a fork of CloudStream (plugin-based media browser/player) with built-in watch-together over the syncroom server. Success: finding a title and getting everyone into the same synced room takes seconds, on phone and TV alike.

## Positioning
The only CloudStream build where any extension's stream can be watched in sync with others: a room carries the provider name plus the episode data, so every member plays the same item from their own device, with host, co-host and host handover.

## Operating Context
- Phone (touch) and Android TV (D-pad remote, 10-foot UI). Both ship from one APK with separate `*_tv` layouts.
- Content comes from user-installed extensions (repositories/plugins); every member of a room needs the same extension.
- Invites are links (`cloudstreamnobar://CODE?server=…`) shared through chat apps.
- Updates arrive through the in-app updater from the fork's GitHub releases.

## Capabilities and Constraints
- Android Views (XML layouts + Kotlin), ~148 layouts, Material 3 dark theme base. Not Compose.
- Upstream is recloudstream/cloudstream; the fork must stay mergeable with upstream, so redesigns prefer theme/style/layout-resource changes over rewriting Kotlin logic.
- Runtime-loaded plugins: R8/minification is off.
- Nobar features: create/join room, host, co-host, member requests (pause/play/host), host handover on leave.

## Brand Commitments
- Part of the **Chica family** (the owner's own watch-together app, repo `tontonan`): CloudStream Nobar should feel related to Chica — its "Mixtape Bareng" palette (blush paper / plum ink, one blush-pink accent that is never text), pill buttons, pixel-art brand mark, and Indonesian-first copy.
- App name: "CloudStream Nobar". Copy is Indonesian (values-b+in) with English fallback.

## Evidence on Hand
- Chica's theme: `~/workspace/projects/tontonan/app/src/main/java/dev/ilham/tontonan/ui/Theme.kt` and `Components.kt`.
- Chica's pixel icon sources: `~/workspace/projects/tontonan-web/design/icon/`.
- No user research, testimonials or metrics exist; none may be invented.

## Product Principles
1. Watching together comes first: the nobar entry point is never more than one step from playback.
2. Same feeling on phone and TV; the TV is driven entirely by remote focus.
3. Stay mergeable with upstream CloudStream: restyle through resources before touching logic.
4. Content (posters, titles) carries the screen; chrome stays quiet.

## Accessibility & Inclusion
TV remote focus must always be visible; touch targets 48dp; text follows the system font scale.
