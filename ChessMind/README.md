# ChessMind — Android Studio project (prototype 0.1.0)

ChessMind is an offline-first Android chess trainer prototype. The selected product direction is **Progressive Coach + dynamic playing style**.

## Included in this first source drop
- Native Android UI built with Kotlin + Jetpack Compose.
- Chessboard, legal destination highlighting, move list, undo and new-game controls.
- Chess rules and legal move generation delegated to `chesslib`.
- A local adaptive opponent layer that evaluates legal moves, varies its candidate selection by the learner's estimated level, and adjusts strategic preferences from observed captures/central pawn play.
- A small persistent player profile stored in Android SharedPreferences.

## Important prototype limitations
- This is an early playable foundation, not a release APK.
- The current coach is a compact evaluation/search layer, **not Stockfish**. It does not yet provide engine-grade analysis, a calibrated Elo estimate, robust blunder detection, or a complete pedagogical lesson system.
- The profile currently uses lightweight behavioral signals; a later iteration should add tactical motifs, king safety, hanging pieces, missed wins, time pressure, opening preferences, and endgame conversion.
- Build and device testing are still required. The build environment used to prepare this source archive did not have Android SDK/Gradle network access, so no APK is claimed as built or tested.

## Build
1. Open this folder in Android Studio (Giraffe or newer; AGP 8.7.3).
2. Allow Gradle sync and dependency download.
3. Build > Build Bundle(s) / APK(s) > Build APK(s).
4. Install the resulting debug APK on an Android device (Android 8.0+).

## Project structure
- `app/src/main/java/com/chessmind/app/MainActivity.kt` — UI, local profile, adaptive opponent.
- `app/build.gradle.kts` — Compose and chesslib dependencies.

## Next engineering milestones
1. Verify chesslib API compatibility and build on a real Android SDK.
2. Integrate Stockfish (NDK binaries for supported ABIs) as the tactical engine.
3. Add a coach controller that adjusts Stockfish skill/depth and candidate selection without making obviously irrational moves.
4. Persist full game history and profile data; add PGN import/export.
5. Add post-game review, tactical lessons, rating calibration and accessibility settings.

## License notes
ChessMind source code in this project is provided as a prototype. `chesslib` is a third-party dependency; observe its upstream license. If Stockfish is bundled later, comply with the GPLv3 license and source distribution requirements.