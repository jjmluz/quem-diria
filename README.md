# Quem Diria?

A party game for the family TV (Google TV), played with phones. Everyone joins from their phone's browser
by scanning a QR code on the TV, takes a selfie, and then votes in secret on questions about each other:
"who's most likely to...?". You score for agreeing with the rest of the room.

Nothing to install on the phones: the TV app runs a small web server on the home network and the phones
open its page.

## Status

**Milestones 1 to 4 are built**; milestone 5 (sharing results with the launcher and Photos TV) is for later.

1. **The room**: QR code, joining, selfies, the TV's lobby.
2. **Voting rounds**: a question, secret votes on the phones, the reveal on the TV, scoring.
3. **Spotlight and drawing rounds**: one player in focus; everyone else draws on their selfie.
4. **Content and polish**: a family deck and an adults deck, end-of-game awards, sounds, the TV reading
   questions aloud.
5. **Later**: results to a family leaderboard in the launcher; the selfie collage to Photos TV.

## A game (about 15 minutes)

- **Votes**: "Who would…?" Everyone votes for a player (themselves included) on their phone. You score 1 point
  for each other player who voted the same way as you.
- **Spotlight**: up to four times a game, one player answers a question about themselves (4 choices) and the
  others guess. A right guess is worth 2; the spotlight player gets 1 for each right guess.
- **Drawing**: then everyone else draws on the spotlight player's selfie ("{name} as a pirate"), and all vote
  for the best drawing: 2 points per vote.
- **Final round**: three last votes, worth double.
- **The end**: the winner, an "award" for each player (the question the room most often picked them for), and
  the best drawings.

## Playing

1. Open Quem Diria? on the TV.
2. Each player scans the QR code with their phone's camera, or opens the address under it and types the
   four-letter code. Everyone needs to be on the TV's Wi-Fi.
3. Type a name and tap the circle to take a selfie (optional: without one, the TV shows your initial).
4. With 3 to 8 players in, pick **Start the game** with the remote. Left and right also reach **Questions:
   family / adults** and **Narrator on / off**; the TV remembers both.

During a game, OK skips the wait ("Don't wait") and Back pauses, offering to leave. Language follows the TV,
phones included, so the whole room plays in one: Portuguese from Portugal, otherwise English.

## Questions

`app/src/main/assets/decks/family.txt` and `adults.txt`, English and Portuguese side by side on each line
(format at the top of each file and in `Deck.parse`). The adults deck mixes cheeky (dating, drinking, money,
embarrassing stories) and spicy (frank about sex and relationships, with swearing, nothing graphic). The TV
remembers which questions it has played and prefers new ones until it has been through them all.

## Build and install

Needs Android Studio's JDK (21) and the Android SDK; `local.properties` holds the SDK path.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
ANDROID_SERIAL=<tv-ip>:5555 ./gradlew testDebugUnitTest installRelease
```

The release build is signed with the debug key, so it installs directly.
