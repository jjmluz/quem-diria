# Quem Diria?

A party game for the family TV (Google TV), played with phones. Everyone joins from their phone's browser
by scanning a QR code on the TV, takes a selfie, and then votes in secret on questions about each other:
"who's most likely to...?". You score for agreeing with the rest of the room.

Nothing to install on the phones: the TV app runs a small web server on the home network and the phones
open its page.

## Status

**Milestone 1 of 5: the room.** Phones can join, take a selfie, change their name and leave, and the TV shows
everyone live. OK starts the game once there are 3 players, but the rounds aren't built yet.

The plan:

1. **The room** (done): QR code, joining, selfies, the TV's lobby.
2. **Voting round**: a question, secret votes on the phones, the reveal on the TV, scoring.
3. **Spotlight and drawing rounds**: one player in focus; drawing on their selfie.
4. **Content and polish**: about 200 questions in a family mode and an adults mode, end-of-game awards, sounds,
   the TV reading questions aloud.
5. **Optional**: results to a family leaderboard in the launcher; the selfie collage to Photos TV.

## Playing

1. Open Quem Diria? on the TV.
2. Each player scans the QR code with their phone's camera, or opens the address under it and types the
   four-letter code. Everyone needs to be on the TV's Wi-Fi.
3. Type a name and tap the circle to take a selfie (optional: without one, the TV shows your initial).
4. With 3 to 8 players in, press OK on the remote to start.

Back on the TV returns from the game to the room; in the room, it closes the game. Closing the game ends the
room: phones are asked for the new code next time.

## Build and install

Needs Android Studio's JDK (21) and the Android SDK; `local.properties` holds the SDK path.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
ANDROID_SERIAL=<tv-ip>:5555 ./gradlew testDebugUnitTest installRelease
```

The release build is signed with the debug key, so it installs directly.
