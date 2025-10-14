You are editing a Java chess engine. A single parameterized JUnit test invocation fails due to a Syzygy integration bug.

⚠️ DO NOT RUN THE WHOLE TEST SUITE. If you must verify, run only this single JUnit Platform UniqueId:
[engine:junit-jupiter]/[class:julius.game.chessengine.ai.BestMoveSearchTest]/[test-template:testBestMove(java.lang.String, java.util.List, java.lang.Integer)]/[test-template-invocation:#1]

If you execute anything locally, use JUnit Platform with --select-uniqueid (never 'mvn test' or 'gradle test'):
java --enable-preview --enable-native-access=ALL-UNNAMED \
-Dchessengine.syzygy.nativeLibrary=C:\Development\Chess-Engine\target\classes\natives\win-x86_64\Release\JSyzygy.dll \
-Dchessengine.syzygy.paths=C:\Syzygy \
-cp "<same classpath IntelliJ shows>" \
org.junit.platform.console.ConsoleLauncher \
--select-uniqueid "[engine:junit-jupiter]/[class:julius.game.chessengine.ai.BestMoveSearchTest]/[test-template:testBestMove(java.lang.String, java.util.List, java.lang.Integer)]/[test-template-invocation:#1]"

❖ REPRO (for that one invocation)
FEN: 8/7K/8/8/2kP4/8/8/7B w - - 2 61
Correct best move: d5 (the only winning move). Engine currently returns Kh8.

Observed diagnostic (trimmed):
- Every candidate at depths 1..7 is scored ≈ +1000 with nodes=1 (indicates TB result from the root is reused for children).
- Search picks Kh8, which TB says should draw.

❖ TASK
Fix the Syzygy probing so we evaluate the CHILD position (after making a move) with the correct side-to-move and state:
- Probe WDL/DTZ only after applying the move on a copied or made/unmade position.
- Ensure position → TB adapter passes: side-to-move, piece squares, castling rights, en passant square, and halfmove clock (for DTZ).
- Reset halfmove clock on pawn moves or captures so zeroing moves (like d5) are recognized.
- Map WDL to scores consistently: WIN→large positive finite; DRAW→0; LOSS→large negative. Use DTZ only for tie-breaking / ordering.

Do NOT hardcode 'd5'. The fix must be general and live in the TB probe/eval path (e.g., TablebaseProbe/SyzygyService and the search leaf TB hook). Likely bug: probing root state or wrong STM for all children.

❖ ACCEPTANCE
1) Running ONLY the UniqueId above yields best move exactly d5.
2) Candidate moves that draw (e.g., Kh8) evaluate ~0, while d5 is winning.
3) No change to other behavior; existing tests remain green (you don’t need to run them).