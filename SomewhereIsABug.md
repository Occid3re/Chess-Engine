15:42:49.687 [main] INFO julius.game.chessengine.ai.SearchConcurrencyPlanner -- Search concurrency plan: availableProcessors=24, searchThreads=1 (user), lazySmpThreads=1 (user), rootParallelLimit=48 (user), ttMb=2048 (user)
15:42:49.699 [main] INFO julius.game.chessengine.ai.SearchConcurrencyPlanner -- Search concurrency plan: availableProcessors=24, searchThreads=1 (user), lazySmpThreads=1 (user), rootParallelLimit=48 (user), ttMb=2048 (user)
15:42:49.707 [main] INFO julius.game.chessengine.tuning.EngineTuningBootstrap -- Loaded 1 tuning configurations from classpath:tuning/seed-tunings.yaml
15:42:49.710 [main] INFO julius.game.chessengine.tuning.EngineTuningBootstrap -- Applied engine tuning "tuned_v1" from classpath:tuning/seed-tunings.yaml (1 configurations available).
15:42:49.990 [main] INFO julius.game.chessengine.ai.OpeningBookLoader -- Loaded opening book from cache (360 entries)
15:42:49.993 [main] INFO julius.game.chessengine.ai.OpeningBook -- Opening book initialised with 131 unique positions (cache hit)
15:42:49.994 [main] INFO julius.game.chessengine.ai.SearchConcurrencyPlanner -- Search concurrency plan: availableProcessors=24, searchThreads=1 (user), lazySmpThreads=1 (user), rootParallelLimit=48 (user), ttMb=2048 (user)
15:42:50.001 [main] INFO julius.game.chessengine.syzygy.bridge.SyzygyBridge -- Loaded JSyzygy from override path C:\Development\Chess-Engine\target\classes\natives\win-x86_64\Release\JSyzygy.dll
15:42:50.058 [main] INFO julius.game.chessengine.syzygy.bridge.SyzygyBridge -- loading syzygy tablebases from C:\Syzygy\3-4-5-dtz;C:\Syzygy\3-4-5-dtz-nr;C:\Syzygy\3-4-5-wdl;C:\Syzygy\6-dtz;C:\Syzygy\6-dtz-nr;C:\Syzygy\6-wdl
15:42:50.100 [main] INFO julius.game.chessengine.syzygy.Tables -- Syzygy tablebases ready (directories=C:\Syzygy\3-4-5-dtz;C:\Syzygy\3-4-5-dtz-nr;C:\Syzygy\3-4-5-wdl;C:\Syzygy\6-dtz;C:\Syzygy\6-dtz-nr;C:\Syzygy\6-wdl, supportedPieces=6, configuredMaxPieces=6)
15:42:50.102 [main] INFO julius.game.chessengine.syzygy.SyzygyTablebaseService -- Syzygy tablebase cache initialised with capacity 65536 entries
15:42:50.107 [main] INFO julius.game.chessengine.ai.AI -- ### SearchThreads = 1, LazySmpThreads = 1

=== Engine decision statistics ===
FEN: 8/8/8/2p2Pk1/8/P2R2K1/3r4/8 b - - 2 48
Side to move: Black
Expected best moves: [Rxd3]
Baseline evaluation: -2.74 pawns
Search configuration: runtimeCores=24, searchThreads=1, lazySmpThreads=1, rootParallelLimit=48, ttSize=2048 MB, multiThreaded=no
Depth target: 8, deepest completed iteration: 8
Chosen move: Re2 -> -14.36 pawns (Δ vs baseline: -11.62) [rank #13]
Engine top move: Kxf5 -> -999.99 pawns (Δ vs chosen: +997.81)
Nodes visited: 228242, null-move prunes: 3483, search duration: 3319 ms
Top candidates by evaluation:
1. Kxf5 -> -999.99 pawns (Δ vs chosen: -985.63)
2. Rd1 -> -3.77 pawns (Δ vs chosen: +10.59)
3. Rg2 -> -3.69 pawns (Δ vs chosen: +10.67)
4. Rc2 -> -3.65 pawns (Δ vs chosen: +10.71)
5. Kh5 -> -3.41 pawns (Δ vs chosen: +10.95)
   Expected move evaluations:
   Rxd3 -> +9.56 pawns (Δ vs chosen: +23.92)
   Evaluation delta vs engine best: +997.81 pawns
   Principal variation: Rc2 (-0.00 pawns) -> f6 (+16.71 pawns) -> Rc1 (-16.71 pawns)
   ==================================

[BMSTAT] {"environment":{"availableProcessors":24,"searchThreads":1,"lazySmpThreads":1,"rootParallelLimit":48,"ttMb":2048,"multiThreaded":false},"fen":"8/8/8/2p2Pk1/8/P2R2K1/3r4/8 b - - 2 48","side":"b","expected":["Rxd3"],"chosen":"Re2","chosenScore":-14.36,"best":"Kxf5","bestScore":-999.99,"baseline":-2.74,"cpLoss":-997.81,"cpGain":-11.62,"rank":13,"nodes":228242,"nullMoves":3483,"durationMs":3319,"topCandidates":[{"move":"Kxf5","score":-999.99},{"move":"Rd1","score":-3.77},{"move":"Rg2","score":-3.69},{"move":"Rc2","score":-3.65},{"move":"Kh5","score":-3.41}],"expectedCandidates":[{"move":"Rxd3","score":9.56}],"depthTarget":8,"depthReached":8,"pv":"Rc2 (-0.00 pawns) -> f6 (+16.71 pawns) -> Rc1 (-16.71 pawns)"}

================ Best Move Search Diagnostic ================
FEN: 8/8/8/2p2Pk1/8/P2R2K1/3r4/8 b - - 2 48
Expected best moves: Rxd3
Depth coverage: target=8, deepestCompleted=8, iterations=8
Search result: Re2 [d2e2] (score=14.36)
Principal variation: Rc2 [d2c2] → f6 [f5f6] → Rc1 [c2c1]
Nodes visited: 228242
Null moves tried: 3483
Elapsed wall-clock: 3319 ms

-- Iterative deepening overview --
Depth  1 (attempt 1, black to move, window=[-Infinity, Infinity])
#01 Rxd3 [d2d3] * | nodes=1      time=14085µs | window [-Infinity, Infinity] → [-Infinity, Infinity] | result=-999.99 (via alphaBeta)
#02 Kxf5 [g5f5]  | nodes=1      time=3749µs | window [-Infinity, -999.99] → [-Infinity, -999.99] | result=999.99 (via alphaBeta)
#03 Kh6 [g5h6]   | nodes=1      time= 348µs | window [-Infinity, -999.99] → [-Infinity, -999.99] | result=16.20 (via alphaBeta)
#04 Kf6 [g5f6]   | nodes=1      time= 963µs | window [-Infinity, -999.99] → [-Infinity, -999.99] | result=14.98 (via alphaBeta)
#05 Kh5 [g5h5]   | nodes=1      time= 319µs | window [-Infinity, -999.99] → [-Infinity, -999.99] | result=16.20 (via alphaBeta)
#06 Rh2 [d2h2]   | nodes=1      time= 689µs | window [-Infinity, -999.99] → [-Infinity, -999.99] | result=15.24 (via alphaBeta)
#07 Rg2 [d2g2]   | nodes=1      time= 978µs | window [-Infinity, -999.99] → [-Infinity, -999.99] | result=15.39 (via alphaBeta)
#08 Rf2 [d2f2]   | nodes=1      time= 581µs | window [-Infinity, -999.99] → [-Infinity, -999.99] | result=15.47 (via alphaBeta)
#09 Re2 [d2e2]   | nodes=1      time= 111µs | window [-Infinity, -999.99] → [-Infinity, -999.99] | result=2.18 (via alphaBeta)
#10 Rc2 [d2c2]   | nodes=1      time= 117µs | window [-Infinity, -999.99] → [-Infinity, -999.99] | result=3.65 (via alphaBeta)
#11 Rb2 [d2b2]   | nodes=1      time= 111µs | window [-Infinity, -999.99] → [-Infinity, -999.99] | result=2.22 (via alphaBeta)
#12 Ra2 [d2a2]   | nodes=1      time= 122µs | window [-Infinity, -999.99] → [-Infinity, -999.99] | result=2.78 (via alphaBeta)
#13 Rd1 [d2d1]   | nodes=1      time= 540µs | window [-Infinity, -999.99] → [-Infinity, -999.99] | result=15.52 (via alphaBeta)
#14 c4 [c5c4]    | nodes=1      time= 545µs | window [-Infinity, -999.99] → [-Infinity, -999.99] | result=15.12 (via alphaBeta)
Best so far: Rxd3 [d2d3] score=-999.99
Final window: [-Infinity, -999.99] after 32 ms
Depth  2 (attempt 1, black to move, window=[-Infinity, Infinity])
#01 Rxd3 [d2d3] * | nodes=1      time=1220µs | window [-Infinity, Infinity] → [-Infinity, Infinity] | result=999.99 (via alphaBeta)
#02 Kxf5 [g5f5]  | nodes=1      time=2081µs | window [-Infinity, 999.99] → [-Infinity, 999.99] | result=999.99 (via alphaBeta)
#03 Kh6 [g5h6]   | nodes=17     time=5907µs | window [-Infinity, 999.99] → [-Infinity, 999.99] | result=16.20 (via alphaBeta)
#04 Kf6 [g5f6]   | nodes=17     time=7334µs | window [-Infinity, 16.20] → [-Infinity, 16.20] | result=14.98 (via alphaBeta)
#05 Kh5 [g5h5]   | nodes=2      time= 144µs | window [-Infinity, 14.98] → [-Infinity, 14.98] | result=16.20 (via alphaBeta)
#06 Rh2 [d2h2]   | nodes=2      time= 213µs | window [-Infinity, 14.98] → [-Infinity, 14.98] | result=15.24 (via alphaBeta)
#07 Rg2 [d2g2]   | nodes=2      time= 241µs | window [-Infinity, 14.98] → [-Infinity, 14.98] | result=15.39 (via alphaBeta)
#08 Rf2 [d2f2]   | nodes=2      time= 193µs | window [-Infinity, 14.98] → [-Infinity, 14.98] | result=15.47 (via alphaBeta)
#09 Re2 [d2e2]   | nodes=17     time=3259µs | window [-Infinity, 14.98] → [-Infinity, 14.98] | result=2.86 (via alphaBeta)
#10 Rc2 [d2c2]   | nodes=9      time= 963µs | window [-Infinity, 2.86] → [-Infinity, 2.86] | result=3.92 (via alphaBeta)
#11 Rb2 [d2b2]   | nodes=16     time=3031µs | window [-Infinity, 2.86] → [-Infinity, 2.86] | result=2.71 (via alphaBeta)
#12 Ra2 [d2a2]   | nodes=10     time=5873µs | window [-Infinity, 2.71] → [-Infinity, 2.71] | result=2.74 (via alphaBeta)
#13 Rd1 [d2d1]   | nodes=2      time= 142µs | window [-Infinity, 2.71] → [-Infinity, 2.71] | result=15.52 (via alphaBeta)
#14 c4 [c5c4]    | nodes=2      time=  93µs | window [-Infinity, 2.71] → [-Infinity, 2.71] | result=15.12 (via alphaBeta)
Best so far: Rb2 [d2b2] score=2.71
Final window: [-Infinity, 2.71] after 30 ms
Depth  3 (attempt 1, black to move, window=[-397.00, 403.00])
#01 Rxd3 [d2d3] * | nodes=1      time=  72µs | window [-397.00, 403.00] → [-397.00, 403.00] | result=999.99 (via alphaBeta)
#02 Kxf5 [g5f5]  | nodes=1      time= 262µs | window [-397.00, 403.00] → [-397.00, 403.00] | result=999.99 (via alphaBeta)
#03 Kh6 [g5h6]   | nodes=95     time=5800µs | window [-397.00, 403.00] → [-397.00, 403.00] | result=15.52 (via alphaBeta)
#04 Kf6 [g5f6]   | nodes=73     time=3016µs | window [-397.00, 15.52] → [-397.00, 15.52] | result=15.28 (via alphaBeta)
#05 Kh5 [g5h5]   | nodes=5      time= 186µs | window [-397.00, 15.28] → [-397.00, 15.28] | result=15.52 (via alphaBeta)
#06 Rh2 [d2h2]   | nodes=49     time=3111µs | window [-397.00, 15.28] → [-397.00, 15.28] | result=13.83 (via alphaBeta)
#07 Rg2 [d2g2]   | nodes=9      time= 267µs | window [-397.00, 13.83] → [-397.00, 13.83] | result=14.00 (via alphaBeta)
#08 Rf2 [d2f2]   | nodes=9      time= 246µs | window [-397.00, 13.83] → [-397.00, 13.83] | result=14.07 (via alphaBeta)
#09 Re2 [d2e2]   | nodes=74     time=4495µs | window [-397.00, 13.83] → [-397.00, 13.83] | result=2.18 (via alphaBeta)
#10 Rc2 [d2c2]   | nodes=120    time=4756µs | window [-397.00, 2.18] → [-397.00, 2.18] | result=3.41 (via alphaBeta)
#11 Rb2 [d2b2]   | nodes=82     time=3253µs | window [-397.00, 2.18] → [-397.00, 2.18] | result=2.15 (via alphaBeta)
#12 Ra2 [d2a2]   | nodes=59     time=1887µs | window [-397.00, 2.15] → [-397.00, 2.15] | result=2.69 (via alphaBeta)
#13 Rd1 [d2d1]   | nodes=6      time= 159µs | window [-397.00, 2.15] → [-397.00, 2.15] | result=14.98 (via alphaBeta)
#14 c4 [c5c4]    | nodes=6      time= 184µs | window [-397.00, 2.15] → [-397.00, 2.15] | result=13.87 (via alphaBeta)
Best so far: Rb2 [d2b2] score=2.15
Final window: [-397.00, 2.15] after 27 ms
Depth  4 (attempt 1, black to move, window=[-391.00, 395.00])
#01 Rxd3 [d2d3] * | nodes=1      time=  48µs | window [-391.00, 395.00] → [-391.00, 395.00] | result=999.99 (via alphaBeta)
#02 Kxf5 [g5f5]  | nodes=1      time= 223µs | window [-391.00, 395.00] → [-391.00, 395.00] | result=999.99 (via alphaBeta)
#03 Rh2 [d2h2]   | nodes=330    time=10985µs | window [-391.00, 395.00] → [-391.00, 395.00] | result=15.69 (via alphaBeta)
#04 Re2 [d2e2]   | nodes=564    time=20333µs | window [-391.00, 15.69] → [-391.00, 15.69] | result=2.63 (via alphaBeta)
#05 Kh6 [g5h6]   | nodes=12     time= 331µs | window [-391.00, 2.63] → [-391.00, 2.63] | result=15.24 (via alphaBeta)
#06 Kf6 [g5f6]   | nodes=40     time= 660µs | window [-391.00, 2.63] → [-391.00, 2.63] | result=14.89 (via alphaBeta)
#07 Kh5 [g5h5]   | nodes=7      time= 111µs | window [-391.00, 2.63] → [-391.00, 2.63] | result=15.24 (via alphaBeta)
#08 Rg2 [d2g2]   | nodes=16     time= 289µs | window [-391.00, 2.63] → [-391.00, 2.63] | result=15.27 (via alphaBeta)
#09 Rf2 [d2f2]   | nodes=16     time= 393µs | window [-391.00, 2.63] → [-391.00, 2.63] | result=15.34 (via alphaBeta)
#10 Rc2 [d2c2]   | nodes=159    time=5560µs | window [-391.00, 2.63] → [-391.00, 2.63] | result=2.69 (via alphaBeta)
#11 Rb2 [d2b2]   | nodes=452    time=10829µs | window [-391.00, 2.63] → [-391.00, 2.63] | result=2.67 (via alphaBeta)
#12 Ra2 [d2a2]   | nodes=98     time=3415µs | window [-391.00, 2.63] → [-391.00, 2.63] | result=2.85 (via alphaBeta)
#13 Rd1 [d2d1]   | nodes=10     time= 310µs | window [-391.00, 2.63] → [-391.00, 2.63] | result=14.81 (via alphaBeta)
#14 c4 [c5c4]    | nodes=7      time= 200µs | window [-391.00, 2.63] → [-391.00, 2.63] | result=14.42 (via alphaBeta)
Best so far: Re2 [d2e2] score=2.63
Final window: [-391.00, 2.63] after 53 ms
Depth  5 (attempt 1, black to move, window=[-395.00, 401.00])
#01 Rxd3 [d2d3] * | nodes=1      time=  22µs | window [-395.00, 401.00] → [-395.00, 401.00] | result=999.99 (via alphaBeta)
#02 Kxf5 [g5f5]  | nodes=1      time=  99µs | window [-395.00, 401.00] → [-395.00, 401.00] | result=999.99 (via alphaBeta)
#03 Rh2 [d2h2]   | nodes=161    time=6439µs | window [-395.00, 401.00] → [-395.00, 401.00] | result=999.99 (via alphaBeta)
#04 Kh6 [g5h6]   | nodes=155    time=2868µs | window [-395.00, 401.00] → [-395.00, 401.00] | result=999.99 (via alphaBeta)
#05 Kf6 [g5f6]   | nodes=603    time=8526µs | window [-395.00, 401.00] → [-395.00, 401.00] | result=999.99 (via alphaBeta)
#06 Kh5 [g5h5]   | nodes=33     time= 347µs | window [-395.00, 401.00] → [-395.00, 401.00] | result=999.99 (via alphaBeta)
#07 Rg2 [d2g2]   | nodes=94     time=2275µs | window [-395.00, 401.00] → [-395.00, 401.00] | result=999.99 (via alphaBeta)
#08 Rf2 [d2f2]   | nodes=94     time=2252µs | window [-395.00, 401.00] → [-395.00, 401.00] | result=999.99 (via alphaBeta)
#09 Re2 [d2e2]   | nodes=2585   time=72784µs | window [-395.00, 401.00] → [-395.00, 401.00] | result=3.18 (via alphaBeta)
#10 Rc2 [d2c2]   | nodes=171    time=2048µs | window [-395.00, 3.18] → [-395.00, 3.18] | result=3.30 (via alphaBeta)
#11 Rb2 [d2b2]   | nodes=1624   time=24011µs | window [-395.00, 3.18] → [-395.00, 3.18] | result=2.71 (via alphaBeta)
#12 Ra2 [d2a2]   | nodes=216    time=1444µs | window [-395.00, 2.71] → [-395.00, 2.71] | result=2.91 (via alphaBeta)
#13 Rd1 [d2d1]   | nodes=22     time= 147µs | window [-395.00, 2.71] → [-395.00, 2.71] | result=14.42 (via alphaBeta)
#14 c4 [c5c4]    | nodes=6      time=  61µs | window [-395.00, 2.71] → [-395.00, 2.71] | result=999.99 (via alphaBeta)
Best so far: Rb2 [d2b2] score=2.71
Final window: [-395.00, 2.71] after 123 ms
Depth  6 (attempt 1, black to move, window=[-401.00, 407.00])
#01 Rxd3 [d2d3] * | nodes=1      time=  18µs | window [-401.00, 407.00] → [-401.00, 407.00] | result=999.99 (via alphaBeta)
#02 Kxf5 [g5f5]  | nodes=1      time= 100µs | window [-401.00, 407.00] → [-401.00, 407.00] | result=999.99 (via alphaBeta)
#03 Rc2 [d2c2]   | nodes=8415   time=137450µs | window [-401.00, 407.00] → [-401.00, 407.00] | result=4.26 (via alphaBeta)
#04 c4 [c5c4]    | nodes=9      time= 114µs | window [-401.00, 4.26] → [-401.00, 4.26] | result=999.99 (via alphaBeta)
#05 Kf6 [g5f6]   | nodes=15     time= 162µs | window [-401.00, 4.26] → [-401.00, 4.26] | result=999.99 (via alphaBeta)
#06 Kh6 [g5h6]   | nodes=10     time= 155µs | window [-401.00, 4.26] → [-401.00, 4.26] | result=999.99 (via alphaBeta)
#07 Rh2 [d2h2]   | nodes=16     time= 228µs | window [-401.00, 4.26] → [-401.00, 4.26] | result=999.99 (via alphaBeta)
#08 Kh5 [g5h5]   | nodes=6      time=  42µs | window [-401.00, 4.26] → [-401.00, 4.26] | result=999.99 (via alphaBeta)
#09 Rg2 [d2g2]   | nodes=16     time= 187µs | window [-401.00, 4.26] → [-401.00, 4.26] | result=999.99 (via alphaBeta)
#10 Rf2 [d2f2]   | nodes=16     time= 142µs | window [-401.00, 4.26] → [-401.00, 4.26] | result=999.99 (via alphaBeta)
#11 Re2 [d2e2]   | nodes=10360  time=112588µs | window [-401.00, 4.26] → [-401.00, 4.26] | result=3.00 (via alphaBeta)
#12 Rb2 [d2b2]   | nodes=1762   time=18021µs | window [-401.00, 3.00] → [-401.00, 3.00] | result=3.03 (via alphaBeta)
#13 Ra2 [d2a2]   | nodes=485    time=5437µs | window [-401.00, 3.00] → [-401.00, 3.00] | result=3.72 (via alphaBeta)
#14 Rd1 [d2d1]   | nodes=40     time= 663µs | window [-401.00, 3.00] → [-401.00, 3.00] | result=14.81 (via alphaBeta)
Best so far: Re2 [d2e2] score=3.00
Final window: [-401.00, 3.00] after 275 ms
Depth  7 (attempt 1, black to move, window=[-406.00, 412.00])
#01 Rc2 [d2c2]   | nodes=15163  time=208526µs | window [-406.00, 412.00] → [-406.00, 412.00] | result=4.56 (via alphaBeta)
#02 Rxd3 [d2d3] * | nodes=1      time=  31µs | window [-406.00, 4.56] → [-406.00, 4.56] | result=999.99 (via alphaBeta)
#03 Kxf5 [g5f5]  | nodes=1      time= 330µs | window [-406.00, 4.56] → [-406.00, 4.56] | result=999.99 (via alphaBeta)
#04 c4 [c5c4]    | nodes=11     time= 180µs | window [-406.00, 4.56] → [-406.00, 4.56] | result=999.99 (via alphaBeta)
#05 Kf6 [g5f6]   | nodes=17     time= 240µs | window [-406.00, 4.56] → [-406.00, 4.56] | result=999.99 (via alphaBeta)
#06 Kh5 [g5h5]   | nodes=6      time=  31µs | window [-406.00, 4.56] → [-406.00, 4.56] | result=999.99 (via alphaBeta)
#07 Kh6 [g5h6]   | nodes=13     time= 116µs | window [-406.00, 4.56] → [-406.00, 4.56] | result=999.99 (via alphaBeta)
#08 Rh2 [d2h2]   | nodes=19     time= 258µs | window [-406.00, 4.56] → [-406.00, 4.56] | result=999.99 (via alphaBeta)
#09 Rg2 [d2g2]   | nodes=20     time= 272µs | window [-406.00, 4.56] → [-406.00, 4.56] | result=999.99 (via alphaBeta)
#10 Rf2 [d2f2]   | nodes=20     time= 279µs | window [-406.00, 4.56] → [-406.00, 4.56] | result=999.99 (via alphaBeta)
#11 Re2 [d2e2]   | nodes=19912  time=219171µs | window [-406.00, 4.56] → [-406.00, 4.56] | result=4.25 (via alphaBeta)
#12 Rb2 [d2b2]   | nodes=16604  time=154610µs | window [-406.00, 4.25] → [-406.00, 4.25] | result=3.82 (via alphaBeta)
#13 Ra2 [d2a2]   | nodes=3120   time=21630µs | window [-406.00, 3.82] → [-406.00, 3.82] | result=3.87 (via alphaBeta)
#14 Rd1 [d2d1]   | nodes=66     time= 296µs | window [-406.00, 3.82] → [-406.00, 3.82] | result=13.85 (via alphaBeta)
Best so far: Rb2 [d2b2] score=3.82
Final window: [-406.00, 3.82] after 606 ms
Depth  8 (attempt 1, black to move, window=[-410.00, 418.00])
#01 Rc2 [d2c2]   | nodes=75193  time=983136µs | window [-410.00, 418.00] → [-410.00, 418.00] | result=16.71 (via alphaBeta)
#02 Rxd3 [d2d3] * | nodes=1      time=  28µs | window [-410.00, 16.71] → [-410.00, 16.71] | result=999.99 (via alphaBeta)
#03 Kxf5 [g5f5]  | nodes=1      time= 308µs | window [-410.00, 16.71] → [-410.00, 16.71] | result=999.99 (via alphaBeta)
#04 c4 [c5c4]    | nodes=10     time=1138µs | window [-410.00, 16.71] → [-410.00, 16.71] | result=999.99 (via alphaBeta)
#05 Kf6 [g5f6]   | nodes=17     time=2172µs | window [-410.00, 16.71] → [-410.00, 16.71] | result=999.99 (via alphaBeta)
#06 Kh5 [g5h5]   | nodes=7      time=  74µs | window [-410.00, 16.71] → [-410.00, 16.71] | result=999.99 (via alphaBeta)
#07 Re2 [d2e2]   | nodes=51364  time=759269µs | window [-410.00, 16.71] → [-410.00, 16.71] | result=14.36 (via alphaBeta)
#08 Rh2 [d2h2]   | nodes=19     time=2831µs | window [-410.00, 14.36] → [-410.00, 14.36] | result=999.99 (via alphaBeta)
#09 Rg2 [d2g2]   | nodes=20     time=3487µs | window [-410.00, 14.36] → [-410.00, 14.36] | result=999.99 (via alphaBeta)
#10 Kh6 [g5h6]   | nodes=13     time= 154µs | window [-410.00, 14.36] → [-410.00, 14.36] | result=999.99 (via alphaBeta)
#11 Rf2 [d2f2]   | nodes=20     time=3427µs | window [-410.00, 14.36] → [-410.00, 14.36] | result=999.99 (via alphaBeta)
#12 Rb2 [d2b2]   | nodes=3078   time=79176µs | window [-410.00, 14.36] → [-410.00, 14.36] | result=14.36 (via alphaBeta)
#13 Ra2 [d2a2]   | nodes=14057  time=315911µs | window [-410.00, 14.36] → [-410.00, 14.36] | result=14.36 (via alphaBeta)
#14 Rd1 [d2d1]   | nodes=135    time=3689µs | window [-410.00, 14.36] → [-410.00, 14.36] | result=14.41 (via alphaBeta)
Best so far: Re2 [d2e2] score=14.36
Final window: [-410.00, 14.36] after 2155 ms

-- Transposition table lookups for expected moves --
Rxd3 → TT depth=7 score=999.99 type=EXACT bestMove=Kh2 [g3h2]
========================================================


org.opentest4j.AssertionFailedError: Expected one of [Rxd3] but got Re2 for FEN: 8/8/8/2p2Pk1/8/P2R2K1/3r4/8 b - - 2 48 (cpLoss=-997.8100000000001, tolerance=1.0)
=== Engine decision statistics ===
FEN: 8/8/8/2p2Pk1/8/P2R2K1/3r4/8 b - - 2 48
Side to move: Black
Expected best moves: [Rxd3]
Baseline evaluation: -2.74 pawns
Search configuration: runtimeCores=24, searchThreads=1, lazySmpThreads=1, rootParallelLimit=48, ttSize=2048 MB, multiThreaded=no
Depth target: 8, deepest completed iteration: 8
Chosen move: Re2 -> -14.36 pawns (Δ vs baseline: -11.62) [rank #13]
Engine top move: Kxf5 -> -999.99 pawns (Δ vs chosen: +997.81)
Nodes visited: 228242, null-move prunes: 3483, search duration: 3319 ms
Top candidates by evaluation:
1. Kxf5 -> -999.99 pawns (Δ vs chosen: -985.63)
2. Rd1 -> -3.77 pawns (Δ vs chosen: +10.59)
3. Rg2 -> -3.69 pawns (Δ vs chosen: +10.67)
4. Rc2 -> -3.65 pawns (Δ vs chosen: +10.71)
5. Kh5 -> -3.41 pawns (Δ vs chosen: +10.95)
   Expected move evaluations:
   Rxd3 -> +9.56 pawns (Δ vs chosen: +23.92)
   Evaluation delta vs engine best: +997.81 pawns
   Principal variation: Rc2 (-0.00 pawns) -> f6 (+16.71 pawns) -> Rc1 (-16.71 pawns)
   ==================================


================ Best Move Search Diagnostic ================
FEN: 8/8/8/2p2Pk1/8/P2R2K1/3r4/8 b - - 2 48
Expected best moves: Rxd3
Depth coverage: target=8, deepestCompleted=8, iterations=8
Search result: Re2 [d2e2] (score=14.36)
Principal variation: Rc2 [d2c2] → f6 [f5f6] → Rc1 [c2c1]
Nodes visited: 228242
Null moves tried: 3483
Elapsed wall-clock: 3319 ms

-- Iterative deepening overview --
Depth  1 (attempt 1, black to move, window=[-Infinity, Infinity])
#01 Rxd3 [d2d3] * | nodes=1      time=14085µs | window [-Infinity, Infinity] → [-Infinity, Infinity] | result=-999.99 (via alphaBeta)
#02 Kxf5 [g5f5]  | nodes=1      time=3749µs | window [-Infinity, -999.99] → [-Infinity, -999.99] | result=999.99 (via alphaBeta)
#03 Kh6 [g5h6]   | nodes=1      time= 348µs | window [-Infinity, -999.99] → [-Infinity, -999.99] | result=16.20 (via alphaBeta)
#04 Kf6 [g5f6]   | nodes=1      time= 963µs | window [-Infinity, -999.99] → [-Infinity, -999.99] | result=14.98 (via alphaBeta)
#05 Kh5 [g5h5]   | nodes=1      time= 319µs | window [-Infinity, -999.99] → [-Infinity, -999.99] | result=16.20 (via alphaBeta)
#06 Rh2 [d2h2]   | nodes=1      time= 689µs | window [-Infinity, -999.99] → [-Infinity, -999.99] | result=15.24 (via alphaBeta)
#07 Rg2 [d2g2]   | nodes=1      time= 978µs | window [-Infinity, -999.99] → [-Infinity, -999.99] | result=15.39 (via alphaBeta)
#08 Rf2 [d2f2]   | nodes=1      time= 581µs | window [-Infinity, -999.99] → [-Infinity, -999.99] | result=15.47 (via alphaBeta)
#09 Re2 [d2e2]   | nodes=1      time= 111µs | window [-Infinity, -999.99] → [-Infinity, -999.99] | result=2.18 (via alphaBeta)
#10 Rc2 [d2c2]   | nodes=1      time= 117µs | window [-Infinity, -999.99] → [-Infinity, -999.99] | result=3.65 (via alphaBeta)
#11 Rb2 [d2b2]   | nodes=1      time= 111µs | window [-Infinity, -999.99] → [-Infinity, -999.99] | result=2.22 (via alphaBeta)
#12 Ra2 [d2a2]   | nodes=1      time= 122µs | window [-Infinity, -999.99] → [-Infinity, -999.99] | result=2.78 (via alphaBeta)
#13 Rd1 [d2d1]   | nodes=1      time= 540µs | window [-Infinity, -999.99] → [-Infinity, -999.99] | result=15.52 (via alphaBeta)
#14 c4 [c5c4]    | nodes=1      time= 545µs | window [-Infinity, -999.99] → [-Infinity, -999.99] | result=15.12 (via alphaBeta)
Best so far: Rxd3 [d2d3] score=-999.99
Final window: [-Infinity, -999.99] after 32 ms
Depth  2 (attempt 1, black to move, window=[-Infinity, Infinity])
#01 Rxd3 [d2d3] * | nodes=1      time=1220µs | window [-Infinity, Infinity] → [-Infinity, Infinity] | result=999.99 (via alphaBeta)
#02 Kxf5 [g5f5]  | nodes=1      time=2081µs | window [-Infinity, 999.99] → [-Infinity, 999.99] | result=999.99 (via alphaBeta)
#03 Kh6 [g5h6]   | nodes=17     time=5907µs | window [-Infinity, 999.99] → [-Infinity, 999.99] | result=16.20 (via alphaBeta)
#04 Kf6 [g5f6]   | nodes=17     time=7334µs | window [-Infinity, 16.20] → [-Infinity, 16.20] | result=14.98 (via alphaBeta)
#05 Kh5 [g5h5]   | nodes=2      time= 144µs | window [-Infinity, 14.98] → [-Infinity, 14.98] | result=16.20 (via alphaBeta)
#06 Rh2 [d2h2]   | nodes=2      time= 213µs | window [-Infinity, 14.98] → [-Infinity, 14.98] | result=15.24 (via alphaBeta)
#07 Rg2 [d2g2]   | nodes=2      time= 241µs | window [-Infinity, 14.98] → [-Infinity, 14.98] | result=15.39 (via alphaBeta)
#08 Rf2 [d2f2]   | nodes=2      time= 193µs | window [-Infinity, 14.98] → [-Infinity, 14.98] | result=15.47 (via alphaBeta)
#09 Re2 [d2e2]   | nodes=17     time=3259µs | window [-Infinity, 14.98] → [-Infinity, 14.98] | result=2.86 (via alphaBeta)
#10 Rc2 [d2c2]   | nodes=9      time= 963µs | window [-Infinity, 2.86] → [-Infinity, 2.86] | result=3.92 (via alphaBeta)
#11 Rb2 [d2b2]   | nodes=16     time=3031µs | window [-Infinity, 2.86] → [-Infinity, 2.86] | result=2.71 (via alphaBeta)
#12 Ra2 [d2a2]   | nodes=10     time=5873µs | window [-Infinity, 2.71] → [-Infinity, 2.71] | result=2.74 (via alphaBeta)
#13 Rd1 [d2d1]   | nodes=2      time= 142µs | window [-Infinity, 2.71] → [-Infinity, 2.71] | result=15.52 (via alphaBeta)
#14 c4 [c5c4]    | nodes=2      time=  93µs | window [-Infinity, 2.71] → [-Infinity, 2.71] | result=15.12 (via alphaBeta)
Best so far: Rb2 [d2b2] score=2.71
Final window: [-Infinity, 2.71] after 30 ms
Depth  3 (attempt 1, black to move, window=[-397.00, 403.00])
#01 Rxd3 [d2d3] * | nodes=1      time=  72µs | window [-397.00, 403.00] → [-397.00, 403.00] | result=999.99 (via alphaBeta)
#02 Kxf5 [g5f5]  | nodes=1      time= 262µs | window [-397.00, 403.00] → [-397.00, 403.00] | result=999.99 (via alphaBeta)
#03 Kh6 [g5h6]   | nodes=95     time=5800µs | window [-397.00, 403.00] → [-397.00, 403.00] | result=15.52 (via alphaBeta)
#04 Kf6 [g5f6]   | nodes=73     time=3016µs | window [-397.00, 15.52] → [-397.00, 15.52] | result=15.28 (via alphaBeta)
#05 Kh5 [g5h5]   | nodes=5      time= 186µs | window [-397.00, 15.28] → [-397.00, 15.28] | result=15.52 (via alphaBeta)
#06 Rh2 [d2h2]   | nodes=49     time=3111µs | window [-397.00, 15.28] → [-397.00, 15.28] | result=13.83 (via alphaBeta)
#07 Rg2 [d2g2]   | nodes=9      time= 267µs | window [-397.00, 13.83] → [-397.00, 13.83] | result=14.00 (via alphaBeta)
#08 Rf2 [d2f2]   | nodes=9      time= 246µs | window [-397.00, 13.83] → [-397.00, 13.83] | result=14.07 (via alphaBeta)
#09 Re2 [d2e2]   | nodes=74     time=4495µs | window [-397.00, 13.83] → [-397.00, 13.83] | result=2.18 (via alphaBeta)
#10 Rc2 [d2c2]   | nodes=120    time=4756µs | window [-397.00, 2.18] → [-397.00, 2.18] | result=3.41 (via alphaBeta)
#11 Rb2 [d2b2]   | nodes=82     time=3253µs | window [-397.00, 2.18] → [-397.00, 2.18] | result=2.15 (via alphaBeta)
#12 Ra2 [d2a2]   | nodes=59     time=1887µs | window [-397.00, 2.15] → [-397.00, 2.15] | result=2.69 (via alphaBeta)
#13 Rd1 [d2d1]   | nodes=6      time= 159µs | window [-397.00, 2.15] → [-397.00, 2.15] | result=14.98 (via alphaBeta)
#14 c4 [c5c4]    | nodes=6      time= 184µs | window [-397.00, 2.15] → [-397.00, 2.15] | result=13.87 (via alphaBeta)
Best so far: Rb2 [d2b2] score=2.15
Final window: [-397.00, 2.15] after 27 ms
Depth  4 (attempt 1, black to move, window=[-391.00, 395.00])
#01 Rxd3 [d2d3] * | nodes=1      time=  48µs | window [-391.00, 395.00] → [-391.00, 395.00] | result=999.99 (via alphaBeta)
#02 Kxf5 [g5f5]  | nodes=1      time= 223µs | window [-391.00, 395.00] → [-391.00, 395.00] | result=999.99 (via alphaBeta)
#03 Rh2 [d2h2]   | nodes=330    time=10985µs | window [-391.00, 395.00] → [-391.00, 395.00] | result=15.69 (via alphaBeta)
#04 Re2 [d2e2]   | nodes=564    time=20333µs | window [-391.00, 15.69] → [-391.00, 15.69] | result=2.63 (via alphaBeta)
#05 Kh6 [g5h6]   | nodes=12     time= 331µs | window [-391.00, 2.63] → [-391.00, 2.63] | result=15.24 (via alphaBeta)
#06 Kf6 [g5f6]   | nodes=40     time= 660µs | window [-391.00, 2.63] → [-391.00, 2.63] | result=14.89 (via alphaBeta)
#07 Kh5 [g5h5]   | nodes=7      time= 111µs | window [-391.00, 2.63] → [-391.00, 2.63] | result=15.24 (via alphaBeta)
#08 Rg2 [d2g2]   | nodes=16     time= 289µs | window [-391.00, 2.63] → [-391.00, 2.63] | result=15.27 (via alphaBeta)
#09 Rf2 [d2f2]   | nodes=16     time= 393µs | window [-391.00, 2.63] → [-391.00, 2.63] | result=15.34 (via alphaBeta)
#10 Rc2 [d2c2]   | nodes=159    time=5560µs | window [-391.00, 2.63] → [-391.00, 2.63] | result=2.69 (via alphaBeta)
#11 Rb2 [d2b2]   | nodes=452    time=10829µs | window [-391.00, 2.63] → [-391.00, 2.63] | result=2.67 (via alphaBeta)
#12 Ra2 [d2a2]   | nodes=98     time=3415µs | window [-391.00, 2.63] → [-391.00, 2.63] | result=2.85 (via alphaBeta)
#13 Rd1 [d2d1]   | nodes=10     time= 310µs | window [-391.00, 2.63] → [-391.00, 2.63] | result=14.81 (via alphaBeta)
#14 c4 [c5c4]    | nodes=7      time= 200µs | window [-391.00, 2.63] → [-391.00, 2.63] | result=14.42 (via alphaBeta)
Best so far: Re2 [d2e2] score=2.63
Final window: [-391.00, 2.63] after 53 ms
Depth  5 (attempt 1, black to move, window=[-395.00, 401.00])
#01 Rxd3 [d2d3] * | nodes=1      time=  22µs | window [-395.00, 401.00] → [-395.00, 401.00] | result=999.99 (via alphaBeta)
#02 Kxf5 [g5f5]  | nodes=1      time=  99µs | window [-395.00, 401.00] → [-395.00, 401.00] | result=999.99 (via alphaBeta)
#03 Rh2 [d2h2]   | nodes=161    time=6439µs | window [-395.00, 401.00] → [-395.00, 401.00] | result=999.99 (via alphaBeta)
#04 Kh6 [g5h6]   | nodes=155    time=2868µs | window [-395.00, 401.00] → [-395.00, 401.00] | result=999.99 (via alphaBeta)
#05 Kf6 [g5f6]   | nodes=603    time=8526µs | window [-395.00, 401.00] → [-395.00, 401.00] | result=999.99 (via alphaBeta)
#06 Kh5 [g5h5]   | nodes=33     time= 347µs | window [-395.00, 401.00] → [-395.00, 401.00] | result=999.99 (via alphaBeta)
#07 Rg2 [d2g2]   | nodes=94     time=2275µs | window [-395.00, 401.00] → [-395.00, 401.00] | result=999.99 (via alphaBeta)
#08 Rf2 [d2f2]   | nodes=94     time=2252µs | window [-395.00, 401.00] → [-395.00, 401.00] | result=999.99 (via alphaBeta)
#09 Re2 [d2e2]   | nodes=2585   time=72784µs | window [-395.00, 401.00] → [-395.00, 401.00] | result=3.18 (via alphaBeta)
#10 Rc2 [d2c2]   | nodes=171    time=2048µs | window [-395.00, 3.18] → [-395.00, 3.18] | result=3.30 (via alphaBeta)
#11 Rb2 [d2b2]   | nodes=1624   time=24011µs | window [-395.00, 3.18] → [-395.00, 3.18] | result=2.71 (via alphaBeta)
#12 Ra2 [d2a2]   | nodes=216    time=1444µs | window [-395.00, 2.71] → [-395.00, 2.71] | result=2.91 (via alphaBeta)
#13 Rd1 [d2d1]   | nodes=22     time= 147µs | window [-395.00, 2.71] → [-395.00, 2.71] | result=14.42 (via alphaBeta)
#14 c4 [c5c4]    | nodes=6      time=  61µs | window [-395.00, 2.71] → [-395.00, 2.71] | result=999.99 (via alphaBeta)
Best so far: Rb2 [d2b2] score=2.71
Final window: [-395.00, 2.71] after 123 ms
Depth  6 (attempt 1, black to move, window=[-401.00, 407.00])
#01 Rxd3 [d2d3] * | nodes=1      time=  18µs | window [-401.00, 407.00] → [-401.00, 407.00] | result=999.99 (via alphaBeta)
#02 Kxf5 [g5f5]  | nodes=1      time= 100µs | window [-401.00, 407.00] → [-401.00, 407.00] | result=999.99 (via alphaBeta)
#03 Rc2 [d2c2]   | nodes=8415   time=137450µs | window [-401.00, 407.00] → [-401.00, 407.00] | result=4.26 (via alphaBeta)
#04 c4 [c5c4]    | nodes=9      time= 114µs | window [-401.00, 4.26] → [-401.00, 4.26] | result=999.99 (via alphaBeta)
#05 Kf6 [g5f6]   | nodes=15     time= 162µs | window [-401.00, 4.26] → [-401.00, 4.26] | result=999.99 (via alphaBeta)
#06 Kh6 [g5h6]   | nodes=10     time= 155µs | window [-401.00, 4.26] → [-401.00, 4.26] | result=999.99 (via alphaBeta)
#07 Rh2 [d2h2]   | nodes=16     time= 228µs | window [-401.00, 4.26] → [-401.00, 4.26] | result=999.99 (via alphaBeta)
#08 Kh5 [g5h5]   | nodes=6      time=  42µs | window [-401.00, 4.26] → [-401.00, 4.26] | result=999.99 (via alphaBeta)
#09 Rg2 [d2g2]   | nodes=16     time= 187µs | window [-401.00, 4.26] → [-401.00, 4.26] | result=999.99 (via alphaBeta)
#10 Rf2 [d2f2]   | nodes=16     time= 142µs | window [-401.00, 4.26] → [-401.00, 4.26] | result=999.99 (via alphaBeta)
#11 Re2 [d2e2]   | nodes=10360  time=112588µs | window [-401.00, 4.26] → [-401.00, 4.26] | result=3.00 (via alphaBeta)
#12 Rb2 [d2b2]   | nodes=1762   time=18021µs | window [-401.00, 3.00] → [-401.00, 3.00] | result=3.03 (via alphaBeta)
#13 Ra2 [d2a2]   | nodes=485    time=5437µs | window [-401.00, 3.00] → [-401.00, 3.00] | result=3.72 (via alphaBeta)
#14 Rd1 [d2d1]   | nodes=40     time= 663µs | window [-401.00, 3.00] → [-401.00, 3.00] | result=14.81 (via alphaBeta)
Best so far: Re2 [d2e2] score=3.00
Final window: [-401.00, 3.00] after 275 ms
Depth  7 (attempt 1, black to move, window=[-406.00, 412.00])
#01 Rc2 [d2c2]   | nodes=15163  time=208526µs | window [-406.00, 412.00] → [-406.00, 412.00] | result=4.56 (via alphaBeta)
#02 Rxd3 [d2d3] * | nodes=1      time=  31µs | window [-406.00, 4.56] → [-406.00, 4.56] | result=999.99 (via alphaBeta)
#03 Kxf5 [g5f5]  | nodes=1      time= 330µs | window [-406.00, 4.56] → [-406.00, 4.56] | result=999.99 (via alphaBeta)
#04 c4 [c5c4]    | nodes=11     time= 180µs | window [-406.00, 4.56] → [-406.00, 4.56] | result=999.99 (via alphaBeta)
#05 Kf6 [g5f6]   | nodes=17     time= 240µs | window [-406.00, 4.56] → [-406.00, 4.56] | result=999.99 (via alphaBeta)
#06 Kh5 [g5h5]   | nodes=6      time=  31µs | window [-406.00, 4.56] → [-406.00, 4.56] | result=999.99 (via alphaBeta)
#07 Kh6 [g5h6]   | nodes=13     time= 116µs | window [-406.00, 4.56] → [-406.00, 4.56] | result=999.99 (via alphaBeta)
#08 Rh2 [d2h2]   | nodes=19     time= 258µs | window [-406.00, 4.56] → [-406.00, 4.56] | result=999.99 (via alphaBeta)
#09 Rg2 [d2g2]   | nodes=20     time= 272µs | window [-406.00, 4.56] → [-406.00, 4.56] | result=999.99 (via alphaBeta)
#10 Rf2 [d2f2]   | nodes=20     time= 279µs | window [-406.00, 4.56] → [-406.00, 4.56] | result=999.99 (via alphaBeta)
#11 Re2 [d2e2]   | nodes=19912  time=219171µs | window [-406.00, 4.56] → [-406.00, 4.56] | result=4.25 (via alphaBeta)
#12 Rb2 [d2b2]   | nodes=16604  time=154610µs | window [-406.00, 4.25] → [-406.00, 4.25] | result=3.82 (via alphaBeta)
#13 Ra2 [d2a2]   | nodes=3120   time=21630µs | window [-406.00, 3.82] → [-406.00, 3.82] | result=3.87 (via alphaBeta)
#14 Rd1 [d2d1]   | nodes=66     time= 296µs | window [-406.00, 3.82] → [-406.00, 3.82] | result=13.85 (via alphaBeta)
Best so far: Rb2 [d2b2] score=3.82
Final window: [-406.00, 3.82] after 606 ms
Depth  8 (attempt 1, black to move, window=[-410.00, 418.00])
#01 Rc2 [d2c2]   | nodes=75193  time=983136µs | window [-410.00, 418.00] → [-410.00, 418.00] | result=16.71 (via alphaBeta)
#02 Rxd3 [d2d3] * | nodes=1      time=  28µs | window [-410.00, 16.71] → [-410.00, 16.71] | result=999.99 (via alphaBeta)
#03 Kxf5 [g5f5]  | nodes=1      time= 308µs | window [-410.00, 16.71] → [-410.00, 16.71] | result=999.99 (via alphaBeta)
#04 c4 [c5c4]    | nodes=10     time=1138µs | window [-410.00, 16.71] → [-410.00, 16.71] | result=999.99 (via alphaBeta)
#05 Kf6 [g5f6]   | nodes=17     time=2172µs | window [-410.00, 16.71] → [-410.00, 16.71] | result=999.99 (via alphaBeta)
#06 Kh5 [g5h5]   | nodes=7      time=  74µs | window [-410.00, 16.71] → [-410.00, 16.71] | result=999.99 (via alphaBeta)
#07 Re2 [d2e2]   | nodes=51364  time=759269µs | window [-410.00, 16.71] → [-410.00, 16.71] | result=14.36 (via alphaBeta)
#08 Rh2 [d2h2]   | nodes=19     time=2831µs | window [-410.00, 14.36] → [-410.00, 14.36] | result=999.99 (via alphaBeta)
#09 Rg2 [d2g2]   | nodes=20     time=3487µs | window [-410.00, 14.36] → [-410.00, 14.36] | result=999.99 (via alphaBeta)
#10 Kh6 [g5h6]   | nodes=13     time= 154µs | window [-410.00, 14.36] → [-410.00, 14.36] | result=999.99 (via alphaBeta)
#11 Rf2 [d2f2]   | nodes=20     time=3427µs | window [-410.00, 14.36] → [-410.00, 14.36] | result=999.99 (via alphaBeta)
#12 Rb2 [d2b2]   | nodes=3078   time=79176µs | window [-410.00, 14.36] → [-410.00, 14.36] | result=14.36 (via alphaBeta)
#13 Ra2 [d2a2]   | nodes=14057  time=315911µs | window [-410.00, 14.36] → [-410.00, 14.36] | result=14.36 (via alphaBeta)
#14 Rd1 [d2d1]   | nodes=135    time=3689µs | window [-410.00, 14.36] → [-410.00, 14.36] | result=14.41 (via alphaBeta)
Best so far: Re2 [d2e2] score=14.36
Final window: [-410.00, 14.36] after 2155 ms

-- Transposition table lookups for expected moves --
Rxd3 → TT depth=7 score=999.99 type=EXACT bestMove=Kh2 [g3h2]
========================================================
==>
Expected :true
Actual   :false
<Click to see difference>


In depth 1 it is correct with Best so far: Rxd3 [d2d3] score=-999.99
And it is the right move picked using syzygy...
But from depth 2 onwards it goes wrong 

Depth  2 (attempt 1, black to move, window=[-Infinity, Infinity])
#01 Rxd3 [d2d3] * | nodes=1      time=1220µs | window [-Infinity, Infinity] → [-Infinity, Infinity] | result=999.99 (via alphaBeta)

Rxd3 is the only move that wins.... but somewhere is a sign problem or similar...
