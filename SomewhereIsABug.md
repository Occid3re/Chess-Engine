14:49:14.805 [main] INFO julius.game.chessengine.ai.SearchConcurrencyPlanner -- Search concurrency plan: availableProcessors=24, searchThreads=1 (user), lazySmpThreads=1 (user), rootParallelLimit=48 (user), ttMb=2048 (user)
14:49:14.816 [main] INFO julius.game.chessengine.ai.SearchConcurrencyPlanner -- Search concurrency plan: availableProcessors=24, searchThreads=1 (user), lazySmpThreads=1 (user), rootParallelLimit=48 (user), ttMb=2048 (user)
14:49:14.823 [main] INFO julius.game.chessengine.tuning.EngineTuningBootstrap -- Loaded 1 tuning configurations from classpath:tuning/seed-tunings.yaml
14:49:14.826 [main] INFO julius.game.chessengine.tuning.EngineTuningBootstrap -- Applied engine tuning "tuned_v1" from classpath:tuning/seed-tunings.yaml (1 configurations available).
14:49:15.088 [main] INFO julius.game.chessengine.ai.OpeningBookLoader -- Loaded opening book from cache (360 entries)
14:49:15.090 [main] INFO julius.game.chessengine.ai.OpeningBook -- Opening book initialised with 131 unique positions (cache hit)
14:49:15.091 [main] INFO julius.game.chessengine.ai.SearchConcurrencyPlanner -- Search concurrency plan: availableProcessors=24, searchThreads=1 (user), lazySmpThreads=1 (user), rootParallelLimit=48 (user), ttMb=2048 (user)
14:49:15.098 [main] INFO julius.game.chessengine.syzygy.bridge.SyzygyBridge -- Loaded JSyzygy from override path C:\Development\Chess-Engine\target\classes\natives\win-x86_64\Release\JSyzygy.dll
14:49:15.153 [main] INFO julius.game.chessengine.syzygy.bridge.SyzygyBridge -- loading syzygy tablebases from C:\Syzygy\3-4-5-dtz;C:\Syzygy\3-4-5-dtz-nr;C:\Syzygy\3-4-5-wdl;C:\Syzygy\6-dtz;C:\Syzygy\6-dtz-nr;C:\Syzygy\6-wdl
14:49:15.194 [main] INFO julius.game.chessengine.syzygy.Tables -- Syzygy tablebases ready (directories=C:\Syzygy\3-4-5-dtz;C:\Syzygy\3-4-5-dtz-nr;C:\Syzygy\3-4-5-wdl;C:\Syzygy\6-dtz;C:\Syzygy\6-dtz-nr;C:\Syzygy\6-wdl, supportedPieces=6, configuredMaxPieces=6)
14:49:15.195 [main] INFO julius.game.chessengine.syzygy.SyzygyTablebaseService -- Syzygy tablebase cache initialised with capacity 65536 entries
14:49:15.201 [main] INFO julius.game.chessengine.ai.AI -- ### SearchThreads = 1, LazySmpThreads = 1

=== Engine decision statistics ===
FEN: 8/6k1/1pp5/2p3p1/2B1p1b1/P7/3K4/5R2 w - - 0 36
Side to move: White
Expected best moves: [Rf7, Ke3, a4]
Baseline evaluation: -0.40 pawns
Search configuration: runtimeCores=24, searchThreads=1, lazySmpThreads=1, rootParallelLimit=48, ttSize=2048 MB, multiThreaded=no
Depth target: 7, deepest completed iteration: 7
Chosen move: Be2 -> +409.68 pawns (Δ vs baseline: +410.08) [rank #6]
Engine top move: Ke3 -> +0.25 pawns (Δ vs chosen: -0.90)
Nodes visited: 176334, null-move prunes: 2587, search duration: 1400 ms
Top candidates by evaluation:
1. Ke3 -> +0.25 pawns (Δ vs chosen: -409.43)
2. Rh1 -> -0.27 pawns (Δ vs chosen: -409.95)
3. a4 -> -0.40 pawns (Δ vs chosen: -410.08)
4. Rf7 -> -0.46 pawns (Δ vs chosen: -410.14)
5. Kc2 -> -0.61 pawns (Δ vs chosen: -410.29)
   Expected move evaluations:
   Rf7 -> -0.46 pawns (Δ vs chosen: -410.14)
   Ke3 -> +0.25 pawns (Δ vs chosen: -409.43)
   a4 -> -0.40 pawns (Δ vs chosen: -410.08)
   Evaluation delta vs engine best: -0.90 pawns
   Principal variation: Be2 (+0.00 pawns)
   ==================================

[BMSTAT] {"environment":{"availableProcessors":24,"searchThreads":1,"lazySmpThreads":1,"rootParallelLimit":48,"ttMb":2048,"multiThreaded":false},"fen":"8/6k1/1pp5/2p3p1/2B1p1b1/P7/3K4/5R2 w - - 0 36","side":"w","expected":["Rf7","Ke3","a4"],"chosen":"Be2","chosenScore":409.68,"best":"Ke3","bestScore":0.25,"baseline":-0.40,"cpLoss":0.90,"cpGain":410.08,"rank":6,"nodes":176334,"nullMoves":2587,"durationMs":1400,"topCandidates":[{"move":"Ke3","score":0.25},{"move":"Rh1","score":-0.27},{"move":"a4","score":-0.40},{"move":"Rf7","score":-0.46},{"move":"Kc2","score":-0.61}],"expectedCandidates":[{"move":"Rf7","score":-0.46},{"move":"Ke3","score":0.25},{"move":"a4","score":-0.40}],"depthTarget":7,"depthReached":7,"pv":"Be2 (+0.00 pawns)"}

================ Best Move Search Diagnostic ================
FEN: 8/6k1/1pp5/2p3p1/2B1p1b1/P7/3K4/5R2 w - - 0 36
Expected best moves: Rf7, Ke3, a4
Depth coverage: target=7, deepestCompleted=7, iterations=11
Search result: Be2 [c4e2] (score=409.68)
Principal variation: Be2 [c4e2]
Nodes visited: 176334
Null moves tried: 2587
Elapsed wall-clock: 1400 ms

-- Iterative deepening overview --
Depth  1 (attempt 1, white to move, window=[-Infinity, Infinity])
#01 Ke3 [d2e3] * | nodes=1      time=4927µs | window [-Infinity, Infinity] → [-Infinity, Infinity] | result=0.25 (via alphaBeta)
#02 Kc3 [d2c3]   | nodes=1      time= 171µs | window [0.25, Infinity] → [0.25, Infinity] | result=-0.72 (via alphaBeta)
#03 Kc2 [d2c2]   | nodes=1      time= 162µs | window [0.25, Infinity] → [0.25, Infinity] | result=-0.61 (via alphaBeta)
#04 Ke1 [d2e1]   | nodes=1      time= 252µs | window [0.25, Infinity] → [0.25, Infinity] | result=-0.92 (via alphaBeta)
#05 Kc1 [d2c1]   | nodes=1      time= 285µs | window [0.25, Infinity] → [0.25, Infinity] | result=-1.11 (via alphaBeta)
#06 Rf8 [f1f8]   | nodes=1      time=1543µs | window [0.25, Infinity] → [0.25, Infinity] | result=-13.10 (via alphaBeta)
#07 Rf7 [f1f7] * | nodes=1      time= 830µs | window [0.25, Infinity] → [0.25, Infinity] | result=-0.78 (via alphaBeta)
#08 Rf6 [f1f6]   | nodes=1      time= 385µs | window [0.25, Infinity] → [0.25, Infinity] | result=-13.44 (via alphaBeta)
#09 Rf5 [f1f5]   | nodes=1      time= 427µs | window [0.25, Infinity] → [0.25, Infinity] | result=-13.90 (via alphaBeta)
#10 Rf4 [f1f4]   | nodes=1      time= 323µs | window [0.25, Infinity] → [0.25, Infinity] | result=-14.87 (via alphaBeta)
#11 Rf3 [f1f3]   | nodes=1      time= 460µs | window [0.25, Infinity] → [0.25, Infinity] | result=-13.77 (via alphaBeta)
#12 Rf2 [f1f2]   | nodes=1      time= 126µs | window [0.25, Infinity] → [0.25, Infinity] | result=-0.79 (via alphaBeta)
#13 Rh1 [f1h1]   | nodes=1      time= 122µs | window [0.25, Infinity] → [0.25, Infinity] | result=-0.27 (via alphaBeta)
#14 Rg1 [f1g1]   | nodes=1      time= 179µs | window [0.25, Infinity] → [0.25, Infinity] | result=-1.36 (via alphaBeta)
#15 Re1 [f1e1]   | nodes=1      time= 151µs | window [0.25, Infinity] → [0.25, Infinity] | result=-0.91 (via alphaBeta)
#16 Rd1 [f1d1]   | nodes=1      time= 522µs | window [0.25, Infinity] → [0.25, Infinity] | result=-6.15 (via alphaBeta)
#17 Rc1 [f1c1]   | nodes=1      time= 156µs | window [0.25, Infinity] → [0.25, Infinity] | result=-1.73 (via alphaBeta)
#18 Rb1 [f1b1]   | nodes=1      time= 175µs | window [0.25, Infinity] → [0.25, Infinity] | result=-1.12 (via alphaBeta)
#19 Ra1 [f1a1]   | nodes=1      time= 233µs | window [0.25, Infinity] → [0.25, Infinity] | result=-2.26 (via alphaBeta)
#20 Bg8 [c4g8]   | nodes=1      time= 393µs | window [0.25, Infinity] → [0.25, Infinity] | result=-10.04 (via alphaBeta)
#21 Bf7 [c4f7]   | nodes=1      time= 144µs | window [0.25, Infinity] → [0.25, Infinity] | result=-1.76 (via alphaBeta)
#22 Be6 [c4e6]   | nodes=1      time= 244µs | window [0.25, Infinity] → [0.25, Infinity] | result=-10.98 (via alphaBeta)
#23 Ba6 [c4a6]   | nodes=1      time= 115µs | window [0.25, Infinity] → [0.25, Infinity] | result=-1.59 (via alphaBeta)
#24 Bd5 [c4d5]   | nodes=1      time= 304µs | window [0.25, Infinity] → [0.25, Infinity] | result=-10.90 (via alphaBeta)
#25 Bb5 [c4b5]   | nodes=1      time= 260µs | window [0.25, Infinity] → [0.25, Infinity] | result=-9.10 (via alphaBeta)
#26 Bd3 [c4d3]   | nodes=1      time= 510µs | window [0.25, Infinity] → [0.25, Infinity] | result=-5.50 (via alphaBeta)
#27 Bb3 [c4b3]   | nodes=1      time= 101µs | window [0.25, Infinity] → [0.25, Infinity] | result=-1.19 (via alphaBeta)
#28 Be2 [c4e2]   | nodes=1      time= 367µs | window [0.25, Infinity] → [0.25, Infinity] | result=-1.82 (via alphaBeta)
#29 Ba2 [c4a2]   | nodes=1      time=  97µs | window [0.25, Infinity] → [0.25, Infinity] | result=-1.45 (via alphaBeta)
#30 a4 [a3a4] *  | nodes=1      time= 106µs | window [0.25, Infinity] → [0.25, Infinity] | result=-0.40 (via alphaBeta)
Best so far: Ke3 [d2e3] score=0.25
Final window: [0.25, Infinity] after 22 ms
Depth  2 (attempt 1, white to move, window=[-Infinity, Infinity])
#01 Ke3 [d2e3] * | nodes=15     time=5906µs | window [-Infinity, Infinity] → [-Infinity, Infinity] | result=-1.93 (via alphaBeta)
#02 Kc3 [d2c3]   | nodes=14     time=1551µs | window [-1.93, Infinity] → [-1.93, Infinity] | result=-2.69 (via alphaBeta)
#03 Kc2 [d2c2]   | nodes=2      time= 335µs | window [-1.93, Infinity] → [-1.93, Infinity] | result=-2.57 (via alphaBeta)
#04 Ke1 [d2e1]   | nodes=2      time= 307µs | window [-1.93, Infinity] → [-1.93, Infinity] | result=-2.88 (via alphaBeta)
#05 Kc1 [d2c1]   | nodes=2      time= 299µs | window [-1.93, Infinity] → [-1.93, Infinity] | result=-3.07 (via alphaBeta)
#06 Rf8 [f1f8]   | nodes=2      time= 221µs | window [-1.93, Infinity] → [-1.93, Infinity] | result=-13.10 (via alphaBeta)
#07 Rf7 [f1f7] * | nodes=6      time= 589µs | window [-1.93, Infinity] → [-1.93, Infinity] | result=-0.78 (via alphaBeta)
#08 Rf6 [f1f6]   | nodes=2      time= 179µs | window [-0.78, Infinity] → [-0.78, Infinity] | result=-13.44 (via alphaBeta)
#09 Rf5 [f1f5]   | nodes=2      time= 252µs | window [-0.78, Infinity] → [-0.78, Infinity] | result=-13.90 (via alphaBeta)
#10 Rf4 [f1f4]   | nodes=2      time= 179µs | window [-0.78, Infinity] → [-0.78, Infinity] | result=-14.87 (via alphaBeta)
#11 Rf3 [f1f3]   | nodes=2      time= 228µs | window [-0.78, Infinity] → [-0.78, Infinity] | result=-13.65 (via alphaBeta)
#12 Rf2 [f1f2]   | nodes=2      time= 598µs | window [-0.78, Infinity] → [-0.78, Infinity] | result=-2.76 (via alphaBeta)
#13 Rh1 [f1h1]   | nodes=2      time= 193µs | window [-0.78, Infinity] → [-0.78, Infinity] | result=-1.71 (via alphaBeta)
#14 Rg1 [f1g1]   | nodes=20     time=9989µs | window [-0.78, Infinity] → [-0.78, Infinity] | result=1.50 (via alphaBeta)
#15 Re1 [f1e1]   | nodes=2      time= 156µs | window [1.50, Infinity] → [1.50, Infinity] | result=-1.55 (via alphaBeta)
#16 Rd1 [f1d1]   | nodes=2      time= 139µs | window [1.50, Infinity] → [1.50, Infinity] | result=-6.15 (via alphaBeta)
#17 Rc1 [f1c1]   | nodes=2      time= 126µs | window [1.50, Infinity] → [1.50, Infinity] | result=-2.15 (via alphaBeta)
#18 Rb1 [f1b1]   | nodes=20     time=1935µs | window [1.50, Infinity] → [1.50, Infinity] | result=1.64 (via alphaBeta)
#19 Ra1 [f1a1]   | nodes=2      time=  98µs | window [1.64, Infinity] → [1.64, Infinity] | result=-2.68 (via alphaBeta)
#20 Bg8 [c4g8]   | nodes=2      time=  95µs | window [1.64, Infinity] → [1.64, Infinity] | result=-10.04 (via alphaBeta)
#21 Bf7 [c4f7]   | nodes=2      time= 127µs | window [1.64, Infinity] → [1.64, Infinity] | result=-3.41 (via alphaBeta)
#22 Be6 [c4e6]   | nodes=2      time= 163µs | window [1.64, Infinity] → [1.64, Infinity] | result=-10.98 (via alphaBeta)
#23 Ba6 [c4a6]   | nodes=2      time= 205µs | window [1.64, Infinity] → [1.64, Infinity] | result=-3.56 (via alphaBeta)
#24 Bd5 [c4d5]   | nodes=2      time=  79µs | window [1.64, Infinity] → [1.64, Infinity] | result=-10.90 (via alphaBeta)
#25 Bb5 [c4b5]   | nodes=2      time= 105µs | window [1.64, Infinity] → [1.64, Infinity] | result=-9.10 (via alphaBeta)
#26 Bd3 [c4d3]   | nodes=2      time= 130µs | window [1.64, Infinity] → [1.64, Infinity] | result=-5.50 (via alphaBeta)
#27 Bb3 [c4b3]   | nodes=2      time= 128µs | window [1.64, Infinity] → [1.64, Infinity] | result=-3.16 (via alphaBeta)
#28 Be2 [c4e2]   | nodes=2      time= 104µs | window [1.64, Infinity] → [1.64, Infinity] | result=-1.82 (via alphaBeta)
#29 Ba2 [c4a2]   | nodes=2      time=  97µs | window [1.64, Infinity] → [1.64, Infinity] | result=-3.42 (via alphaBeta)
#30 a4 [a3a4] *  | nodes=2      time= 128µs | window [1.64, Infinity] → [1.64, Infinity] | result=-2.37 (via alphaBeta)
Best so far: Rb1 [f1b1] score=1.64
Final window: [1.64, Infinity] after 25 ms
Depth  3 (attempt 1, white to move, window=[-10.00, 14.00])
#01 Ke3 [d2e3] * | nodes=74     time=4889µs | window [-10.00, 14.00] → [-10.00, 14.00] | result=-0.62 (via alphaBeta)
#02 Kc3 [d2c3]   | nodes=17     time= 598µs | window [-0.62, 14.00] → [-0.62, 14.00] | result=-1.24 (via alphaBeta)
#03 Kc2 [d2c2]   | nodes=17     time= 760µs | window [-0.62, 14.00] → [-0.62, 14.00] | result=-1.13 (via alphaBeta)
#04 Ke1 [d2e1]   | nodes=17     time=1133µs | window [-0.62, 14.00] → [-0.62, 14.00] | result=-1.44 (via alphaBeta)
#05 Kc1 [d2c1]   | nodes=17     time= 969µs | window [-0.62, 14.00] → [-0.62, 14.00] | result=-1.62 (via alphaBeta)
#06 Rf8 [f1f8]   | nodes=17     time= 564µs | window [-0.62, 14.00] → [-0.62, 14.00] | result=-12.43 (via alphaBeta)
#07 Rf7 [f1f7] * | nodes=47     time=1447µs | window [-0.62, 14.00] → [-0.62, 14.00] | result=0.17 (via alphaBeta)
#08 Rf6 [f1f6]   | nodes=17     time= 418µs | window [0.17, 14.00] → [0.17, 14.00] | result=-12.77 (via alphaBeta)
#09 Rf5 [f1f5]   | nodes=17     time= 386µs | window [0.17, 14.00] → [0.17, 14.00] | result=-13.45 (via alphaBeta)
#10 Rf4 [f1f4]   | nodes=17     time= 459µs | window [0.17, 14.00] → [0.17, 14.00] | result=-14.68 (via alphaBeta)
#11 Rf3 [f1f3]   | nodes=17     time= 393µs | window [0.17, 14.00] → [0.17, 14.00] | result=-13.31 (via alphaBeta)
#12 Rf2 [f1f2]   | nodes=17     time= 571µs | window [0.17, 14.00] → [0.17, 14.00] | result=-1.20 (via alphaBeta)
#13 Rh1 [f1h1]   | nodes=18     time= 480µs | window [0.17, 14.00] → [0.17, 14.00] | result=-0.92 (via alphaBeta)
#14 Rg1 [f1g1]   | nodes=98     time=4410µs | window [0.17, 14.00] → [0.17, 14.00] | result=1.50 (via alphaBeta)
#15 Re1 [f1e1]   | nodes=17     time= 549µs | window [1.50, 14.00] → [1.50, 14.00] | result=-0.92 (via alphaBeta)
#16 Rd1 [f1d1]   | nodes=17     time= 467µs | window [1.50, 14.00] → [1.50, 14.00] | result=-6.15 (via alphaBeta)
#17 Rc1 [f1c1]   | nodes=17     time= 348µs | window [1.50, 14.00] → [1.50, 14.00] | result=-0.92 (via alphaBeta)
#18 Rb1 [f1b1]   | nodes=61     time=1716µs | window [1.50, 14.00] → [1.50, 14.00] | result=-2.20 (via alphaBeta)
#19 Ra1 [f1a1]   | nodes=17     time= 345µs | window [1.50, 14.00] → [1.50, 14.00] | result=-0.92 (via alphaBeta)
#20 Bg8 [c4g8]   | nodes=18     time= 363µs | window [1.50, 14.00] → [1.50, 14.00] | result=-9.37 (via alphaBeta)
#21 Bf7 [c4f7]   | nodes=17     time= 486µs | window [1.50, 14.00] → [1.50, 14.00] | result=-3.61 (via alphaBeta)
#22 Be6 [c4e6]   | nodes=18     time= 394µs | window [1.50, 14.00] → [1.50, 14.00] | result=-10.31 (via alphaBeta)
#23 Ba6 [c4a6]   | nodes=17     time= 295µs | window [1.50, 14.00] → [1.50, 14.00] | result=-2.12 (via alphaBeta)
#24 Bd5 [c4d5]   | nodes=18     time= 400µs | window [1.50, 14.00] → [1.50, 14.00] | result=-10.90 (via alphaBeta)
#25 Bb5 [c4b5]   | nodes=18     time= 363µs | window [1.50, 14.00] → [1.50, 14.00] | result=-8.44 (via alphaBeta)
#26 Bd3 [c4d3]   | nodes=18     time= 455µs | window [1.50, 14.00] → [1.50, 14.00] | result=-5.50 (via alphaBeta)
#27 Bb3 [c4b3]   | nodes=17     time= 426µs | window [1.50, 14.00] → [1.50, 14.00] | result=-1.85 (via alphaBeta)
#28 Be2 [c4e2]   | nodes=18     time= 550µs | window [1.50, 14.00] → [1.50, 14.00] | result=-1.82 (via alphaBeta)
#29 Ba2 [c4a2]   | nodes=17     time= 332µs | window [1.50, 14.00] → [1.50, 14.00] | result=-2.11 (via alphaBeta)
#30 a4 [a3a4] *  | nodes=17     time= 490µs | window [1.50, 14.00] → [1.50, 14.00] | result=-0.92 (via alphaBeta)
Best so far: Rg1 [f1g1] score=1.50
Final window: [1.50, 14.00] after 26 ms
Depth  4 (attempt 1, white to move, window=[-9.00, 13.00])
#01 Ke3 [d2e3] * | nodes=356    time=14907µs | window [-9.00, 13.00] → [-9.00, 13.00] | result=-1.82 (via alphaBeta)
#02 Kc3 [d2c3]   | nodes=219    time=11210µs | window [-1.82, 13.00] → [-1.82, 13.00] | result=-1.83 (via alphaBeta)
#03 Kc2 [d2c2]   | nodes=174    time=5262µs | window [-1.82, 13.00] → [-1.82, 13.00] | result=-2.02 (via alphaBeta)
#04 Ke1 [d2e1]   | nodes=48     time=1693µs | window [-1.82, 13.00] → [-1.82, 13.00] | result=-1.82 (via alphaBeta)
#05 Kc1 [d2c1]   | nodes=40     time= 807µs | window [-1.82, 13.00] → [-1.82, 13.00] | result=-2.09 (via alphaBeta)
#06 Rf8 [f1f8]   | nodes=36     time= 597µs | window [-1.82, 13.00] → [-1.82, 13.00] | result=-11.00 (via alphaBeta)
#07 Rf7 [f1f7] * | nodes=161    time=4712µs | window [-1.82, 13.00] → [-1.82, 13.00] | result=0.17 (via alphaBeta)
#08 Rf6 [f1f6]   | nodes=36     time= 512µs | window [0.17, 13.00] → [0.17, 13.00] | result=-11.35 (via alphaBeta)
#09 Rf5 [f1f5]   | nodes=40     time= 531µs | window [0.17, 13.00] → [0.17, 13.00] | result=-11.80 (via alphaBeta)
#10 Rf4 [f1f4]   | nodes=34     time= 627µs | window [0.17, 13.00] → [0.17, 13.00] | result=-12.78 (via alphaBeta)
#11 Rf3 [f1f3]   | nodes=40     time= 642µs | window [0.17, 13.00] → [0.17, 13.00] | result=-11.56 (via alphaBeta)
#12 Rf2 [f1f2]   | nodes=39     time= 762µs | window [0.17, 13.00] → [0.17, 13.00] | result=-0.63 (via alphaBeta)
#13 Rh1 [f1h1]   | nodes=67     time=1531µs | window [0.17, 13.00] → [0.17, 13.00] | result=-1.03 (via alphaBeta)
#14 Rg1 [f1g1]   | nodes=510    time=47244µs | window [0.17, 13.00] → [0.17, 13.00] | result=8.01 (via alphaBeta)
#15 Re1 [f1e1]   | nodes=329    time=5202µs | window [8.01, 13.00] → [8.01, 13.00] | result=8.44 (via alphaBeta)
#16 Rd1 [f1d1]   | nodes=521    time=15901µs | window [8.44, 13.00] → [8.44, 13.00] | result=13.05 (via alphaBeta)
Cutoff after Rd1 [f1d1] (alpha=13.05, beta=13.00)
Best so far: Rd1 [f1d1] score=13.05
Final window: [13.05, 13.00] after 112 ms
Depth  4 (attempt 2, white to move, window=[-31.00, 57.00])
#01 Be2 [c4e2]   | nodes=407    time=8005µs | window [-31.00, 57.00] → [-31.00, 57.00] | result=17.35 (via alphaBeta)
#02 Bd3 [c4d3]   | nodes=48     time= 868µs | window [17.35, 57.00] → [17.35, 57.00] | result=14.62 (via alphaBeta)
#03 Ke1 [d2e1]   | nodes=1      time=  25µs | window [17.35, 57.00] → [17.35, 57.00] | result=-1.82 (via alphaBeta)
#04 Rf7 [f1f7] * | nodes=1      time=  32µs | window [17.35, 57.00] → [17.35, 57.00] | result=0.17 (via alphaBeta)
#05 Rg1 [f1g1]   | nodes=1      time=  14µs | window [17.35, 57.00] → [17.35, 57.00] | result=8.01 (via alphaBeta)
#06 Ke3 [d2e3] * | nodes=1      time=  12µs | window [17.35, 57.00] → [17.35, 57.00] | result=-1.82 (via alphaBeta)
#07 Kc3 [d2c3]   | nodes=1      time=  12µs | window [17.35, 57.00] → [17.35, 57.00] | result=-1.83 (via alphaBeta)
#08 Kc2 [d2c2]   | nodes=1      time=  11µs | window [17.35, 57.00] → [17.35, 57.00] | result=-2.02 (via alphaBeta)
#09 Kc1 [d2c1]   | nodes=1      time=  11µs | window [17.35, 57.00] → [17.35, 57.00] | result=-2.09 (via alphaBeta)
#10 Rf8 [f1f8]   | nodes=1      time=  13µs | window [17.35, 57.00] → [17.35, 57.00] | result=-11.00 (via alphaBeta)
#11 Rf6 [f1f6]   | nodes=1      time=  11µs | window [17.35, 57.00] → [17.35, 57.00] | result=-11.35 (via alphaBeta)
#12 Rf5 [f1f5]   | nodes=1      time=  11µs | window [17.35, 57.00] → [17.35, 57.00] | result=-11.80 (via alphaBeta)
#13 Rf4 [f1f4]   | nodes=1      time=  11µs | window [17.35, 57.00] → [17.35, 57.00] | result=-12.78 (via alphaBeta)
#14 Rf3 [f1f3]   | nodes=1      time=  12µs | window [17.35, 57.00] → [17.35, 57.00] | result=-11.56 (via alphaBeta)
#15 Rf2 [f1f2]   | nodes=1      time=  12µs | window [17.35, 57.00] → [17.35, 57.00] | result=-0.63 (via alphaBeta)
#16 Rh1 [f1h1]   | nodes=1      time=  11µs | window [17.35, 57.00] → [17.35, 57.00] | result=-1.03 (via alphaBeta)
#17 Re1 [f1e1]   | nodes=1      time=  10µs | window [17.35, 57.00] → [17.35, 57.00] | result=8.44 (via alphaBeta)
#18 Rd1 [f1d1]   | nodes=57     time=1576µs | window [17.35, 57.00] → [17.35, 57.00] | result=13.84 (via alphaBeta)
#19 Rc1 [f1c1]   | nodes=612    time=9770µs | window [17.35, 57.00] → [17.35, 57.00] | result=18.39 (via alphaBeta)
#20 Rb1 [f1b1]   | nodes=42     time= 510µs | window [18.39, 57.00] → [18.39, 57.00] | result=17.16 (via alphaBeta)
#21 Ra1 [f1a1]   | nodes=44     time= 555µs | window [18.39, 57.00] → [18.39, 57.00] | result=17.68 (via alphaBeta)
#22 Bg8 [c4g8]   | nodes=48     time=1226µs | window [18.39, 57.00] → [18.39, 57.00] | result=14.14 (via alphaBeta)
#23 Bf7 [c4f7]   | nodes=51     time= 973µs | window [18.39, 57.00] → [18.39, 57.00] | result=18.15 (via alphaBeta)
#24 Be6 [c4e6]   | nodes=54     time= 450µs | window [18.39, 57.00] → [18.39, 57.00] | result=12.93 (via alphaBeta)
#25 Ba6 [c4a6]   | nodes=42     time= 784µs | window [18.39, 57.00] → [18.39, 57.00] | result=18.02 (via alphaBeta)
#26 Bd5 [c4d5]   | nodes=48     time= 398µs | window [18.39, 57.00] → [18.39, 57.00] | result=13.74 (via alphaBeta)
#27 Bb5 [c4b5]   | nodes=48     time= 553µs | window [18.39, 57.00] → [18.39, 57.00] | result=13.55 (via alphaBeta)
#28 Bb3 [c4b3]   | nodes=35     time= 540µs | window [18.39, 57.00] → [18.39, 57.00] | result=18.02 (via alphaBeta)
#29 Ba2 [c4a2]   | nodes=32     time= 440µs | window [18.39, 57.00] → [18.39, 57.00] | result=17.76 (via alphaBeta)
#30 a4 [a3a4] *  | nodes=312    time=9786µs | window [18.39, 57.00] → [18.39, 57.00] | result=31.34 (via alphaBeta)
Best so far: a4 [a3a4] score=31.34
Final window: [31.34, 57.00] after 37 ms
Depth  5 (attempt 1, white to move, window=[9.00, 53.00])
#01 Be2 [c4e2]   | nodes=997    time=14542µs | window [9.00, 53.00] → [9.00, 53.00] | result=31.45 (via alphaBeta)
#02 Ke3 [d2e3] * | nodes=759    time=14489µs | window [31.45, 53.00] → [31.45, 53.00] | result=54.17 (via alphaBeta)
Cutoff after Ke3 [d2e3] (alpha=54.17, beta=53.00)
Best so far: Ke3 [d2e3] score=54.17
Final window: [54.17, 53.00] after 29 ms
Depth  5 (attempt 2, white to move, window=[-11.00, 119.00])
#01 Be2 [c4e2]   | nodes=1      time=  15µs | window [-11.00, 119.00] → [-11.00, 119.00] | result=31.45 (via alphaBeta)
#02 Bd3 [c4d3]   | nodes=1413   time=14415µs | window [31.45, 119.00] → [31.45, 119.00] | result=72.06 (via alphaBeta)
#03 Rf2 [f1f2]   | nodes=721    time=7471µs | window [72.06, 119.00] → [72.06, 119.00] | result=119.89 (via alphaBeta)
Cutoff after Rf2 [f1f2] (alpha=119.89, beta=119.00)
Best so far: Rf2 [f1f2] score=119.89
Final window: [119.89, 119.00] after 21 ms
Depth  5 (attempt 3, white to move, window=[-Infinity, Infinity])
#01 Be2 [c4e2]   | nodes=1      time=  13µs | window [-Infinity, Infinity] → [-Infinity, Infinity] | result=31.45 (via alphaBeta)
#02 Kc3 [d2c3]   | nodes=5122   time=54480µs | window [31.45, Infinity] → [31.45, Infinity] | result=148.28 (via alphaBeta)
#03 Bd3 [c4d3]   | nodes=1      time=  13µs | window [148.28, Infinity] → [148.28, Infinity] | result=72.06 (via alphaBeta)
#04 Rf2 [f1f2]   | nodes=1658   time=12982µs | window [148.28, Infinity] → [148.28, Infinity] | result=148.23 (via alphaBeta)
#05 Rh1 [f1h1]   | nodes=494    time=2534µs | window [148.28, Infinity] → [148.28, Infinity] | result=148.17 (via alphaBeta)
#06 Ke3 [d2e3] * | nodes=297    time=2058µs | window [148.28, Infinity] → [148.28, Infinity] | result=148.17 (via alphaBeta)
#07 Rf7 [f1f7] * | nodes=622    time=3992µs | window [148.28, Infinity] → [148.28, Infinity] | result=148.21 (via alphaBeta)
#08 Rg1 [f1g1]   | nodes=584    time=7650µs | window [148.28, Infinity] → [148.28, Infinity] | result=147.52 (via alphaBeta)
#09 Ke1 [d2e1]   | nodes=168    time= 950µs | window [148.28, Infinity] → [148.28, Infinity] | result=148.13 (via alphaBeta)
#10 Bb3 [c4b3]   | nodes=364    time=2121µs | window [148.28, Infinity] → [148.28, Infinity] | result=147.95 (via alphaBeta)
#11 Kc2 [d2c2]   | nodes=518    time=2683µs | window [148.28, Infinity] → [148.28, Infinity] | result=148.15 (via alphaBeta)
#12 Kc1 [d2c1]   | nodes=206    time=1078µs | window [148.28, Infinity] → [148.28, Infinity] | result=148.04 (via alphaBeta)
#13 Rf8 [f1f8]   | nodes=168    time= 882µs | window [148.28, Infinity] → [148.28, Infinity] | result=-4.91 (via alphaBeta)
#14 Rf6 [f1f6]   | nodes=141    time= 637µs | window [148.28, Infinity] → [148.28, Infinity] | result=136.69 (via alphaBeta)
#15 Rf5 [f1f5]   | nodes=184    time= 771µs | window [148.28, Infinity] → [148.28, Infinity] | result=136.70 (via alphaBeta)
#16 Rf4 [f1f4]   | nodes=119    time= 536µs | window [148.28, Infinity] → [148.28, Infinity] | result=135.39 (via alphaBeta)
#17 Rf3 [f1f3]   | nodes=197    time= 913µs | window [148.28, Infinity] → [148.28, Infinity] | result=136.96 (via alphaBeta)
#18 Re1 [f1e1]   | nodes=208    time=1410µs | window [148.28, Infinity] → [148.28, Infinity] | result=148.17 (via alphaBeta)
#19 Rd1 [f1d1]   | nodes=279    time=2018µs | window [148.28, Infinity] → [148.28, Infinity] | result=148.28 (via alphaBeta)
#20 Rc1 [f1c1]   | nodes=286    time=1465µs | window [148.28, Infinity] → [148.28, Infinity] | result=147.52 (via alphaBeta)
#21 Rb1 [f1b1]   | nodes=362    time=1660µs | window [148.28, Infinity] → [148.28, Infinity] | result=143.25 (via alphaBeta)
#22 Ra1 [f1a1]   | nodes=218    time=1010µs | window [148.28, Infinity] → [148.28, Infinity] | result=148.17 (via alphaBeta)
#23 Bg8 [c4g8]   | nodes=157    time= 725µs | window [148.28, Infinity] → [148.28, Infinity] | result=140.56 (via alphaBeta)
#24 Bf7 [c4f7]   | nodes=231    time=1035µs | window [148.28, Infinity] → [148.28, Infinity] | result=146.54 (via alphaBeta)
#25 Be6 [c4e6]   | nodes=191    time= 912µs | window [148.28, Infinity] → [148.28, Infinity] | result=139.62 (via alphaBeta)
#26 Ba6 [c4a6]   | nodes=39     time= 254µs | window [148.28, Infinity] → [148.28, Infinity] | result=132.47 (via alphaBeta)
#27 Bd5 [c4d5]   | nodes=125    time= 683µs | window [148.28, Infinity] → [148.28, Infinity] | result=139.25 (via alphaBeta)
#28 Bb5 [c4b5]   | nodes=196    time= 821µs | window [148.28, Infinity] → [148.28, Infinity] | result=13.67 (via alphaBeta)
#29 Ba2 [c4a2]   | nodes=124    time= 603µs | window [148.28, Infinity] → [148.28, Infinity] | result=148.23 (via alphaBeta)
#30 a4 [a3a4] *  | nodes=205    time=1783µs | window [148.28, Infinity] → [148.28, Infinity] | result=147.65 (via alphaBeta)
Best so far: Kc3 [d2c3] score=148.28
Final window: [148.28, Infinity] after 108 ms
Depth  6 (attempt 1, white to move, window=[18.00, 282.00])
#01 Be2 [c4e2]   | nodes=461    time=7368µs | window [18.00, 282.00] → [18.00, 282.00] | result=18.00 (via alphaBeta)
#02 Kc3 [d2c3]   | nodes=3423   time=18829µs | window [18.00, 282.00] → [18.00, 282.00] | result=12.77 (via alphaBeta)
#03 Bd3 [c4d3]   | nodes=600    time=4497µs | window [18.00, 282.00] → [18.00, 282.00] | result=14.61 (via alphaBeta)
#04 Rf2 [f1f2]   | nodes=2662   time=34601µs | window [18.00, 282.00] → [18.00, 282.00] | result=18.00 (via alphaBeta)
#05 Rh1 [f1h1]   | nodes=1186   time=7229µs | window [18.00, 282.00] → [18.00, 282.00] | result=17.99 (via alphaBeta)
#06 Rf7 [f1f7] * | nodes=2523   time=16768µs | window [18.00, 282.00] → [18.00, 282.00] | result=18.00 (via alphaBeta)
#07 Ke3 [d2e3] * | nodes=2441   time=23645µs | window [18.00, 282.00] → [18.00, 282.00] | result=17.55 (via alphaBeta)
#08 Ba6 [c4a6]   | nodes=159    time= 703µs | window [18.00, 282.00] → [18.00, 282.00] | result=17.70 (via alphaBeta)
#09 Rg1 [f1g1]   | nodes=2015   time=22630µs | window [18.00, 282.00] → [18.00, 282.00] | result=17.95 (via alphaBeta)
#10 Rf6 [f1f6]   | nodes=325    time=1366µs | window [18.00, 282.00] → [18.00, 282.00] | result=12.81 (via alphaBeta)
#11 Bb3 [c4b3]   | nodes=769    time=3962µs | window [18.00, 282.00] → [18.00, 282.00] | result=16.81 (via alphaBeta)
#12 Rf5 [f1f5]   | nodes=419    time=1515µs | window [18.00, 282.00] → [18.00, 282.00] | result=1.20 (via alphaBeta)
#13 Ke1 [d2e1]   | nodes=338    time=1943µs | window [18.00, 282.00] → [18.00, 282.00] | result=17.41 (via alphaBeta)
#14 Rf8 [f1f8]   | nodes=317    time=1135µs | window [18.00, 282.00] → [18.00, 282.00] | result=13.15 (via alphaBeta)
#15 Kc2 [d2c2]   | nodes=1892   time=9378µs | window [18.00, 282.00] → [18.00, 282.00] | result=16.35 (via alphaBeta)
#16 Kc1 [d2c1]   | nodes=598    time=3698µs | window [18.00, 282.00] → [18.00, 282.00] | result=17.92 (via alphaBeta)
#17 Rf4 [f1f4]   | nodes=239    time= 890µs | window [18.00, 282.00] → [18.00, 282.00] | result=12.20 (via alphaBeta)
#18 Rf3 [f1f3]   | nodes=410    time=1637µs | window [18.00, 282.00] → [18.00, 282.00] | result=13.86 (via alphaBeta)
#19 Re1 [f1e1]   | nodes=2084   time=13420µs | window [18.00, 282.00] → [18.00, 282.00] | result=16.57 (via alphaBeta)
#20 Rd1 [f1d1]   | nodes=1411   time=9744µs | window [18.00, 282.00] → [18.00, 282.00] | result=18.00 (via alphaBeta)
#21 Rc1 [f1c1]   | nodes=820    time=5575µs | window [18.00, 282.00] → [18.00, 282.00] | result=17.23 (via alphaBeta)
#22 Rb1 [f1b1]   | nodes=599    time=2971µs | window [18.00, 282.00] → [18.00, 282.00] | result=16.76 (via alphaBeta)
#23 Ra1 [f1a1]   | nodes=483    time=2278µs | window [18.00, 282.00] → [18.00, 282.00] | result=17.99 (via alphaBeta)
#24 Bg8 [c4g8]   | nodes=351    time=1341µs | window [18.00, 282.00] → [18.00, 282.00] | result=14.14 (via alphaBeta)
#25 Bf7 [c4f7]   | nodes=3952   time=26906µs | window [18.00, 282.00] → [18.00, 282.00] | result=33.31 (via alphaBeta)
#26 Be6 [c4e6]   | nodes=7953   time=51518µs | window [33.31, 282.00] → [33.31, 282.00] | result=142.38 (via alphaBeta)
#27 Bd5 [c4d5]   | nodes=4348   time=36785µs | window [142.38, 282.00] → [142.38, 282.00] | result=142.10 (via alphaBeta)
#28 Bb5 [c4b5]   | nodes=407    time=1698µs | window [142.38, 282.00] → [142.38, 282.00] | result=123.77 (via alphaBeta)
#29 Ba2 [c4a2]   | nodes=319    time=1428µs | window [142.38, 282.00] → [142.38, 282.00] | result=141.00 (via alphaBeta)
#30 a4 [a3a4] *  | nodes=573    time=4786µs | window [142.38, 282.00] → [142.38, 282.00] | result=141.74 (via alphaBeta)
Best so far: Be6 [c4e6] score=142.38
Final window: [142.38, 282.00] after 320 ms
Depth  7 (attempt 1, white to move, window=[21.00, 263.00])
#01 Be2 [c4e2]   | nodes=7787   time=63046µs | window [21.00, 263.00] → [21.00, 263.00] | result=388.73 (via alphaBeta)
Cutoff after Be2 [c4e2] (alpha=388.73, beta=263.00)
Best so far: Be2 [c4e2] score=388.73
Final window: [388.73, 263.00] after 63 ms
Depth  7 (attempt 2, white to move, window=[236.00, 542.00])
#01 Be2 [c4e2]   | nodes=11681  time=97847µs | window [236.00, 542.00] → [236.00, 542.00] | result=409.68 (via alphaBeta)
#02 a4 [a3a4] *  | nodes=5009   time=54323µs | window [409.68, 542.00] → [409.68, 542.00] | result=377.55 (via alphaBeta)
#03 Kc3 [d2c3]   | nodes=11910  time=68566µs | window [409.68, 542.00] → [409.68, 542.00] | result=408.41 (via alphaBeta)
#04 Rh1 [f1h1]   | nodes=4474   time=23098µs | window [409.68, 542.00] → [409.68, 542.00] | result=397.73 (via alphaBeta)
#05 Rf2 [f1f2]   | nodes=4230   time=27307µs | window [409.68, 542.00] → [409.68, 542.00] | result=409.68 (via alphaBeta)
#06 Ke3 [d2e3] * | nodes=6496   time=41289µs | window [409.68, 542.00] → [409.68, 542.00] | result=409.68 (via alphaBeta)
#07 Rd1 [f1d1]   | nodes=5980   time=39239µs | window [409.68, 542.00] → [409.68, 542.00] | result=409.68 (via alphaBeta)
#08 Ke1 [d2e1]   | nodes=975    time=5069µs | window [409.68, 542.00] → [409.68, 542.00] | result=386.32 (via alphaBeta)
#09 Bd3 [c4d3]   | nodes=1443   time=7212µs | window [409.68, 542.00] → [409.68, 542.00] | result=373.41 (via alphaBeta)
#10 Rb1 [f1b1]   | nodes=2546   time=12474µs | window [409.68, 542.00] → [409.68, 542.00] | result=400.72 (via alphaBeta)
#11 Rf5 [f1f5]   | nodes=1149   time=4626µs | window [409.68, 542.00] → [409.68, 542.00] | result=365.40 (via alphaBeta)
#12 Rf7 [f1f7] * | nodes=9594   time=45454µs | window [409.68, 542.00] → [409.68, 542.00] | result=409.68 (via alphaBeta)
#13 Rf6 [f1f6]   | nodes=891    time=4476µs | window [409.68, 542.00] → [409.68, 542.00] | result=387.22 (via alphaBeta)
#14 Rg1 [f1g1]   | nodes=7284   time=41911µs | window [409.68, 542.00] → [409.68, 542.00] | result=401.31 (via alphaBeta)
#15 Ba6 [c4a6]   | nodes=460    time=2260µs | window [409.68, 542.00] → [409.68, 542.00] | result=394.52 (via alphaBeta)
#16 Bb3 [c4b3]   | nodes=2913   time=13264µs | window [409.68, 542.00] → [409.68, 542.00] | result=386.48 (via alphaBeta)
#17 Rf4 [f1f4]   | nodes=729    time=3567µs | window [409.68, 542.00] → [409.68, 542.00] | result=355.56 (via alphaBeta)
#18 Rc1 [f1c1]   | nodes=3013   time=20156µs | window [409.68, 542.00] → [409.68, 542.00] | result=401.31 (via alphaBeta)
#19 Ra1 [f1a1]   | nodes=1206   time=4597µs | window [409.68, 542.00] → [409.68, 542.00] | result=397.73 (via alphaBeta)
#20 Kc2 [d2c2]   | nodes=4918   time=22502µs | window [409.68, 542.00] → [409.68, 542.00] | result=400.72 (via alphaBeta)
#21 Kc1 [d2c1]   | nodes=1579   time=7617µs | window [409.68, 542.00] → [409.68, 542.00] | result=386.47 (via alphaBeta)
#22 Rf8 [f1f8]   | nodes=906    time=4339µs | window [409.68, 542.00] → [409.68, 542.00] | result=375.87 (via alphaBeta)
#23 Rf3 [f1f3]   | nodes=1089   time=4694µs | window [409.68, 542.00] → [409.68, 542.00] | result=358.26 (via alphaBeta)
#24 Re1 [f1e1]   | nodes=1098   time=7561µs | window [409.68, 542.00] → [409.68, 542.00] | result=396.28 (via alphaBeta)
#25 Bg8 [c4g8]   | nodes=988    time=3627µs | window [409.68, 542.00] → [409.68, 542.00] | result=375.87 (via alphaBeta)
#26 Bf7 [c4f7]   | nodes=1596   time=6857µs | window [409.68, 542.00] → [409.68, 542.00] | result=385.66 (via alphaBeta)
#27 Be6 [c4e6]   | nodes=2075   time=11199µs | window [409.68, 542.00] → [409.68, 542.00] | result=397.31 (via alphaBeta)
#28 Bd5 [c4d5]   | nodes=4047   time=25546µs | window [409.68, 542.00] → [409.68, 542.00] | result=387.22 (via alphaBeta)
#29 Bb5 [c4b5]   | nodes=665    time=2955µs | window [409.68, 542.00] → [409.68, 542.00] | result=353.00 (via alphaBeta)
#30 Ba2 [c4a2]   | nodes=741    time=2810µs | window [409.68, 542.00] → [409.68, 542.00] | result=386.10 (via alphaBeta)
Best so far: Be2 [c4e2] score=409.68
Final window: [409.68, 542.00] after 617 ms

-- Transposition table lookups for expected moves --
Rf7  → TT depth=6 score=409.68 type=UPPERBOUND bestMove=Kh8 [g7h8]
Ke3  → TT depth=6 score=409.68 type=UPPERBOUND bestMove=Kh6 [g7h6]
a4   → TT depth=6 score=377.55 type=UPPERBOUND bestMove=Bf3 [g4f3]
========================================================


org.opentest4j.AssertionFailedError: Expected one of [Rf7, Ke3, a4] but got Be2 for FEN: 8/6k1/1pp5/2p3p1/2B1p1b1/P7/3K4/5R2 w - - 0 36 (cpLoss=0.9, tolerance=0.5)
=== Engine decision statistics ===
FEN: 8/6k1/1pp5/2p3p1/2B1p1b1/P7/3K4/5R2 w - - 0 36
Side to move: White
Expected best moves: [Rf7, Ke3, a4]
Baseline evaluation: -0.40 pawns
Search configuration: runtimeCores=24, searchThreads=1, lazySmpThreads=1, rootParallelLimit=48, ttSize=2048 MB, multiThreaded=no
Depth target: 7, deepest completed iteration: 7
Chosen move: Be2 -> +409.68 pawns (Δ vs baseline: +410.08) [rank #6]
Engine top move: Ke3 -> +0.25 pawns (Δ vs chosen: -0.90)
Nodes visited: 176334, null-move prunes: 2587, search duration: 1400 ms
Top candidates by evaluation:
1. Ke3 -> +0.25 pawns (Δ vs chosen: -409.43)
2. Rh1 -> -0.27 pawns (Δ vs chosen: -409.95)
3. a4 -> -0.40 pawns (Δ vs chosen: -410.08)
4. Rf7 -> -0.46 pawns (Δ vs chosen: -410.14)
5. Kc2 -> -0.61 pawns (Δ vs chosen: -410.29)
   Expected move evaluations:
   Rf7 -> -0.46 pawns (Δ vs chosen: -410.14)
   Ke3 -> +0.25 pawns (Δ vs chosen: -409.43)
   a4 -> -0.40 pawns (Δ vs chosen: -410.08)
   Evaluation delta vs engine best: -0.90 pawns
   Principal variation: Be2 (+0.00 pawns)
   ==================================


================ Best Move Search Diagnostic ================
FEN: 8/6k1/1pp5/2p3p1/2B1p1b1/P7/3K4/5R2 w - - 0 36
Expected best moves: Rf7, Ke3, a4
Depth coverage: target=7, deepestCompleted=7, iterations=11
Search result: Be2 [c4e2] (score=409.68)
Principal variation: Be2 [c4e2]
Nodes visited: 176334
Null moves tried: 2587
Elapsed wall-clock: 1400 ms

-- Iterative deepening overview --
Depth  1 (attempt 1, white to move, window=[-Infinity, Infinity])
#01 Ke3 [d2e3] * | nodes=1      time=4927µs | window [-Infinity, Infinity] → [-Infinity, Infinity] | result=0.25 (via alphaBeta)
#02 Kc3 [d2c3]   | nodes=1      time= 171µs | window [0.25, Infinity] → [0.25, Infinity] | result=-0.72 (via alphaBeta)
#03 Kc2 [d2c2]   | nodes=1      time= 162µs | window [0.25, Infinity] → [0.25, Infinity] | result=-0.61 (via alphaBeta)
#04 Ke1 [d2e1]   | nodes=1      time= 252µs | window [0.25, Infinity] → [0.25, Infinity] | result=-0.92 (via alphaBeta)
#05 Kc1 [d2c1]   | nodes=1      time= 285µs | window [0.25, Infinity] → [0.25, Infinity] | result=-1.11 (via alphaBeta)
#06 Rf8 [f1f8]   | nodes=1      time=1543µs | window [0.25, Infinity] → [0.25, Infinity] | result=-13.10 (via alphaBeta)
#07 Rf7 [f1f7] * | nodes=1      time= 830µs | window [0.25, Infinity] → [0.25, Infinity] | result=-0.78 (via alphaBeta)
#08 Rf6 [f1f6]   | nodes=1      time= 385µs | window [0.25, Infinity] → [0.25, Infinity] | result=-13.44 (via alphaBeta)
#09 Rf5 [f1f5]   | nodes=1      time= 427µs | window [0.25, Infinity] → [0.25, Infinity] | result=-13.90 (via alphaBeta)
#10 Rf4 [f1f4]   | nodes=1      time= 323µs | window [0.25, Infinity] → [0.25, Infinity] | result=-14.87 (via alphaBeta)
#11 Rf3 [f1f3]   | nodes=1      time= 460µs | window [0.25, Infinity] → [0.25, Infinity] | result=-13.77 (via alphaBeta)
#12 Rf2 [f1f2]   | nodes=1      time= 126µs | window [0.25, Infinity] → [0.25, Infinity] | result=-0.79 (via alphaBeta)
#13 Rh1 [f1h1]   | nodes=1      time= 122µs | window [0.25, Infinity] → [0.25, Infinity] | result=-0.27 (via alphaBeta)
#14 Rg1 [f1g1]   | nodes=1      time= 179µs | window [0.25, Infinity] → [0.25, Infinity] | result=-1.36 (via alphaBeta)
#15 Re1 [f1e1]   | nodes=1      time= 151µs | window [0.25, Infinity] → [0.25, Infinity] | result=-0.91 (via alphaBeta)
#16 Rd1 [f1d1]   | nodes=1      time= 522µs | window [0.25, Infinity] → [0.25, Infinity] | result=-6.15 (via alphaBeta)
#17 Rc1 [f1c1]   | nodes=1      time= 156µs | window [0.25, Infinity] → [0.25, Infinity] | result=-1.73 (via alphaBeta)
#18 Rb1 [f1b1]   | nodes=1      time= 175µs | window [0.25, Infinity] → [0.25, Infinity] | result=-1.12 (via alphaBeta)
#19 Ra1 [f1a1]   | nodes=1      time= 233µs | window [0.25, Infinity] → [0.25, Infinity] | result=-2.26 (via alphaBeta)
#20 Bg8 [c4g8]   | nodes=1      time= 393µs | window [0.25, Infinity] → [0.25, Infinity] | result=-10.04 (via alphaBeta)
#21 Bf7 [c4f7]   | nodes=1      time= 144µs | window [0.25, Infinity] → [0.25, Infinity] | result=-1.76 (via alphaBeta)
#22 Be6 [c4e6]   | nodes=1      time= 244µs | window [0.25, Infinity] → [0.25, Infinity] | result=-10.98 (via alphaBeta)
#23 Ba6 [c4a6]   | nodes=1      time= 115µs | window [0.25, Infinity] → [0.25, Infinity] | result=-1.59 (via alphaBeta)
#24 Bd5 [c4d5]   | nodes=1      time= 304µs | window [0.25, Infinity] → [0.25, Infinity] | result=-10.90 (via alphaBeta)
#25 Bb5 [c4b5]   | nodes=1      time= 260µs | window [0.25, Infinity] → [0.25, Infinity] | result=-9.10 (via alphaBeta)
#26 Bd3 [c4d3]   | nodes=1      time= 510µs | window [0.25, Infinity] → [0.25, Infinity] | result=-5.50 (via alphaBeta)
#27 Bb3 [c4b3]   | nodes=1      time= 101µs | window [0.25, Infinity] → [0.25, Infinity] | result=-1.19 (via alphaBeta)
#28 Be2 [c4e2]   | nodes=1      time= 367µs | window [0.25, Infinity] → [0.25, Infinity] | result=-1.82 (via alphaBeta)
#29 Ba2 [c4a2]   | nodes=1      time=  97µs | window [0.25, Infinity] → [0.25, Infinity] | result=-1.45 (via alphaBeta)
#30 a4 [a3a4] *  | nodes=1      time= 106µs | window [0.25, Infinity] → [0.25, Infinity] | result=-0.40 (via alphaBeta)
Best so far: Ke3 [d2e3] score=0.25
Final window: [0.25, Infinity] after 22 ms
Depth  2 (attempt 1, white to move, window=[-Infinity, Infinity])
#01 Ke3 [d2e3] * | nodes=15     time=5906µs | window [-Infinity, Infinity] → [-Infinity, Infinity] | result=-1.93 (via alphaBeta)
#02 Kc3 [d2c3]   | nodes=14     time=1551µs | window [-1.93, Infinity] → [-1.93, Infinity] | result=-2.69 (via alphaBeta)
#03 Kc2 [d2c2]   | nodes=2      time= 335µs | window [-1.93, Infinity] → [-1.93, Infinity] | result=-2.57 (via alphaBeta)
#04 Ke1 [d2e1]   | nodes=2      time= 307µs | window [-1.93, Infinity] → [-1.93, Infinity] | result=-2.88 (via alphaBeta)
#05 Kc1 [d2c1]   | nodes=2      time= 299µs | window [-1.93, Infinity] → [-1.93, Infinity] | result=-3.07 (via alphaBeta)
#06 Rf8 [f1f8]   | nodes=2      time= 221µs | window [-1.93, Infinity] → [-1.93, Infinity] | result=-13.10 (via alphaBeta)
#07 Rf7 [f1f7] * | nodes=6      time= 589µs | window [-1.93, Infinity] → [-1.93, Infinity] | result=-0.78 (via alphaBeta)
#08 Rf6 [f1f6]   | nodes=2      time= 179µs | window [-0.78, Infinity] → [-0.78, Infinity] | result=-13.44 (via alphaBeta)
#09 Rf5 [f1f5]   | nodes=2      time= 252µs | window [-0.78, Infinity] → [-0.78, Infinity] | result=-13.90 (via alphaBeta)
#10 Rf4 [f1f4]   | nodes=2      time= 179µs | window [-0.78, Infinity] → [-0.78, Infinity] | result=-14.87 (via alphaBeta)
#11 Rf3 [f1f3]   | nodes=2      time= 228µs | window [-0.78, Infinity] → [-0.78, Infinity] | result=-13.65 (via alphaBeta)
#12 Rf2 [f1f2]   | nodes=2      time= 598µs | window [-0.78, Infinity] → [-0.78, Infinity] | result=-2.76 (via alphaBeta)
#13 Rh1 [f1h1]   | nodes=2      time= 193µs | window [-0.78, Infinity] → [-0.78, Infinity] | result=-1.71 (via alphaBeta)
#14 Rg1 [f1g1]   | nodes=20     time=9989µs | window [-0.78, Infinity] → [-0.78, Infinity] | result=1.50 (via alphaBeta)
#15 Re1 [f1e1]   | nodes=2      time= 156µs | window [1.50, Infinity] → [1.50, Infinity] | result=-1.55 (via alphaBeta)
#16 Rd1 [f1d1]   | nodes=2      time= 139µs | window [1.50, Infinity] → [1.50, Infinity] | result=-6.15 (via alphaBeta)
#17 Rc1 [f1c1]   | nodes=2      time= 126µs | window [1.50, Infinity] → [1.50, Infinity] | result=-2.15 (via alphaBeta)
#18 Rb1 [f1b1]   | nodes=20     time=1935µs | window [1.50, Infinity] → [1.50, Infinity] | result=1.64 (via alphaBeta)
#19 Ra1 [f1a1]   | nodes=2      time=  98µs | window [1.64, Infinity] → [1.64, Infinity] | result=-2.68 (via alphaBeta)
#20 Bg8 [c4g8]   | nodes=2      time=  95µs | window [1.64, Infinity] → [1.64, Infinity] | result=-10.04 (via alphaBeta)
#21 Bf7 [c4f7]   | nodes=2      time= 127µs | window [1.64, Infinity] → [1.64, Infinity] | result=-3.41 (via alphaBeta)
#22 Be6 [c4e6]   | nodes=2      time= 163µs | window [1.64, Infinity] → [1.64, Infinity] | result=-10.98 (via alphaBeta)
#23 Ba6 [c4a6]   | nodes=2      time= 205µs | window [1.64, Infinity] → [1.64, Infinity] | result=-3.56 (via alphaBeta)
#24 Bd5 [c4d5]   | nodes=2      time=  79µs | window [1.64, Infinity] → [1.64, Infinity] | result=-10.90 (via alphaBeta)
#25 Bb5 [c4b5]   | nodes=2      time= 105µs | window [1.64, Infinity] → [1.64, Infinity] | result=-9.10 (via alphaBeta)
#26 Bd3 [c4d3]   | nodes=2      time= 130µs | window [1.64, Infinity] → [1.64, Infinity] | result=-5.50 (via alphaBeta)
#27 Bb3 [c4b3]   | nodes=2      time= 128µs | window [1.64, Infinity] → [1.64, Infinity] | result=-3.16 (via alphaBeta)
#28 Be2 [c4e2]   | nodes=2      time= 104µs | window [1.64, Infinity] → [1.64, Infinity] | result=-1.82 (via alphaBeta)
#29 Ba2 [c4a2]   | nodes=2      time=  97µs | window [1.64, Infinity] → [1.64, Infinity] | result=-3.42 (via alphaBeta)
#30 a4 [a3a4] *  | nodes=2      time= 128µs | window [1.64, Infinity] → [1.64, Infinity] | result=-2.37 (via alphaBeta)
Best so far: Rb1 [f1b1] score=1.64
Final window: [1.64, Infinity] after 25 ms
Depth  3 (attempt 1, white to move, window=[-10.00, 14.00])
#01 Ke3 [d2e3] * | nodes=74     time=4889µs | window [-10.00, 14.00] → [-10.00, 14.00] | result=-0.62 (via alphaBeta)
#02 Kc3 [d2c3]   | nodes=17     time= 598µs | window [-0.62, 14.00] → [-0.62, 14.00] | result=-1.24 (via alphaBeta)
#03 Kc2 [d2c2]   | nodes=17     time= 760µs | window [-0.62, 14.00] → [-0.62, 14.00] | result=-1.13 (via alphaBeta)
#04 Ke1 [d2e1]   | nodes=17     time=1133µs | window [-0.62, 14.00] → [-0.62, 14.00] | result=-1.44 (via alphaBeta)
#05 Kc1 [d2c1]   | nodes=17     time= 969µs | window [-0.62, 14.00] → [-0.62, 14.00] | result=-1.62 (via alphaBeta)
#06 Rf8 [f1f8]   | nodes=17     time= 564µs | window [-0.62, 14.00] → [-0.62, 14.00] | result=-12.43 (via alphaBeta)
#07 Rf7 [f1f7] * | nodes=47     time=1447µs | window [-0.62, 14.00] → [-0.62, 14.00] | result=0.17 (via alphaBeta)
#08 Rf6 [f1f6]   | nodes=17     time= 418µs | window [0.17, 14.00] → [0.17, 14.00] | result=-12.77 (via alphaBeta)
#09 Rf5 [f1f5]   | nodes=17     time= 386µs | window [0.17, 14.00] → [0.17, 14.00] | result=-13.45 (via alphaBeta)
#10 Rf4 [f1f4]   | nodes=17     time= 459µs | window [0.17, 14.00] → [0.17, 14.00] | result=-14.68 (via alphaBeta)
#11 Rf3 [f1f3]   | nodes=17     time= 393µs | window [0.17, 14.00] → [0.17, 14.00] | result=-13.31 (via alphaBeta)
#12 Rf2 [f1f2]   | nodes=17     time= 571µs | window [0.17, 14.00] → [0.17, 14.00] | result=-1.20 (via alphaBeta)
#13 Rh1 [f1h1]   | nodes=18     time= 480µs | window [0.17, 14.00] → [0.17, 14.00] | result=-0.92 (via alphaBeta)
#14 Rg1 [f1g1]   | nodes=98     time=4410µs | window [0.17, 14.00] → [0.17, 14.00] | result=1.50 (via alphaBeta)
#15 Re1 [f1e1]   | nodes=17     time= 549µs | window [1.50, 14.00] → [1.50, 14.00] | result=-0.92 (via alphaBeta)
#16 Rd1 [f1d1]   | nodes=17     time= 467µs | window [1.50, 14.00] → [1.50, 14.00] | result=-6.15 (via alphaBeta)
#17 Rc1 [f1c1]   | nodes=17     time= 348µs | window [1.50, 14.00] → [1.50, 14.00] | result=-0.92 (via alphaBeta)
#18 Rb1 [f1b1]   | nodes=61     time=1716µs | window [1.50, 14.00] → [1.50, 14.00] | result=-2.20 (via alphaBeta)
#19 Ra1 [f1a1]   | nodes=17     time= 345µs | window [1.50, 14.00] → [1.50, 14.00] | result=-0.92 (via alphaBeta)
#20 Bg8 [c4g8]   | nodes=18     time= 363µs | window [1.50, 14.00] → [1.50, 14.00] | result=-9.37 (via alphaBeta)
#21 Bf7 [c4f7]   | nodes=17     time= 486µs | window [1.50, 14.00] → [1.50, 14.00] | result=-3.61 (via alphaBeta)
#22 Be6 [c4e6]   | nodes=18     time= 394µs | window [1.50, 14.00] → [1.50, 14.00] | result=-10.31 (via alphaBeta)
#23 Ba6 [c4a6]   | nodes=17     time= 295µs | window [1.50, 14.00] → [1.50, 14.00] | result=-2.12 (via alphaBeta)
#24 Bd5 [c4d5]   | nodes=18     time= 400µs | window [1.50, 14.00] → [1.50, 14.00] | result=-10.90 (via alphaBeta)
#25 Bb5 [c4b5]   | nodes=18     time= 363µs | window [1.50, 14.00] → [1.50, 14.00] | result=-8.44 (via alphaBeta)
#26 Bd3 [c4d3]   | nodes=18     time= 455µs | window [1.50, 14.00] → [1.50, 14.00] | result=-5.50 (via alphaBeta)
#27 Bb3 [c4b3]   | nodes=17     time= 426µs | window [1.50, 14.00] → [1.50, 14.00] | result=-1.85 (via alphaBeta)
#28 Be2 [c4e2]   | nodes=18     time= 550µs | window [1.50, 14.00] → [1.50, 14.00] | result=-1.82 (via alphaBeta)
#29 Ba2 [c4a2]   | nodes=17     time= 332µs | window [1.50, 14.00] → [1.50, 14.00] | result=-2.11 (via alphaBeta)
#30 a4 [a3a4] *  | nodes=17     time= 490µs | window [1.50, 14.00] → [1.50, 14.00] | result=-0.92 (via alphaBeta)
Best so far: Rg1 [f1g1] score=1.50
Final window: [1.50, 14.00] after 26 ms
Depth  4 (attempt 1, white to move, window=[-9.00, 13.00])
#01 Ke3 [d2e3] * | nodes=356    time=14907µs | window [-9.00, 13.00] → [-9.00, 13.00] | result=-1.82 (via alphaBeta)
#02 Kc3 [d2c3]   | nodes=219    time=11210µs | window [-1.82, 13.00] → [-1.82, 13.00] | result=-1.83 (via alphaBeta)
#03 Kc2 [d2c2]   | nodes=174    time=5262µs | window [-1.82, 13.00] → [-1.82, 13.00] | result=-2.02 (via alphaBeta)
#04 Ke1 [d2e1]   | nodes=48     time=1693µs | window [-1.82, 13.00] → [-1.82, 13.00] | result=-1.82 (via alphaBeta)
#05 Kc1 [d2c1]   | nodes=40     time= 807µs | window [-1.82, 13.00] → [-1.82, 13.00] | result=-2.09 (via alphaBeta)
#06 Rf8 [f1f8]   | nodes=36     time= 597µs | window [-1.82, 13.00] → [-1.82, 13.00] | result=-11.00 (via alphaBeta)
#07 Rf7 [f1f7] * | nodes=161    time=4712µs | window [-1.82, 13.00] → [-1.82, 13.00] | result=0.17 (via alphaBeta)
#08 Rf6 [f1f6]   | nodes=36     time= 512µs | window [0.17, 13.00] → [0.17, 13.00] | result=-11.35 (via alphaBeta)
#09 Rf5 [f1f5]   | nodes=40     time= 531µs | window [0.17, 13.00] → [0.17, 13.00] | result=-11.80 (via alphaBeta)
#10 Rf4 [f1f4]   | nodes=34     time= 627µs | window [0.17, 13.00] → [0.17, 13.00] | result=-12.78 (via alphaBeta)
#11 Rf3 [f1f3]   | nodes=40     time= 642µs | window [0.17, 13.00] → [0.17, 13.00] | result=-11.56 (via alphaBeta)
#12 Rf2 [f1f2]   | nodes=39     time= 762µs | window [0.17, 13.00] → [0.17, 13.00] | result=-0.63 (via alphaBeta)
#13 Rh1 [f1h1]   | nodes=67     time=1531µs | window [0.17, 13.00] → [0.17, 13.00] | result=-1.03 (via alphaBeta)
#14 Rg1 [f1g1]   | nodes=510    time=47244µs | window [0.17, 13.00] → [0.17, 13.00] | result=8.01 (via alphaBeta)
#15 Re1 [f1e1]   | nodes=329    time=5202µs | window [8.01, 13.00] → [8.01, 13.00] | result=8.44 (via alphaBeta)
#16 Rd1 [f1d1]   | nodes=521    time=15901µs | window [8.44, 13.00] → [8.44, 13.00] | result=13.05 (via alphaBeta)
Cutoff after Rd1 [f1d1] (alpha=13.05, beta=13.00)
Best so far: Rd1 [f1d1] score=13.05
Final window: [13.05, 13.00] after 112 ms
Depth  4 (attempt 2, white to move, window=[-31.00, 57.00])
#01 Be2 [c4e2]   | nodes=407    time=8005µs | window [-31.00, 57.00] → [-31.00, 57.00] | result=17.35 (via alphaBeta)
#02 Bd3 [c4d3]   | nodes=48     time= 868µs | window [17.35, 57.00] → [17.35, 57.00] | result=14.62 (via alphaBeta)
#03 Ke1 [d2e1]   | nodes=1      time=  25µs | window [17.35, 57.00] → [17.35, 57.00] | result=-1.82 (via alphaBeta)
#04 Rf7 [f1f7] * | nodes=1      time=  32µs | window [17.35, 57.00] → [17.35, 57.00] | result=0.17 (via alphaBeta)
#05 Rg1 [f1g1]   | nodes=1      time=  14µs | window [17.35, 57.00] → [17.35, 57.00] | result=8.01 (via alphaBeta)
#06 Ke3 [d2e3] * | nodes=1      time=  12µs | window [17.35, 57.00] → [17.35, 57.00] | result=-1.82 (via alphaBeta)
#07 Kc3 [d2c3]   | nodes=1      time=  12µs | window [17.35, 57.00] → [17.35, 57.00] | result=-1.83 (via alphaBeta)
#08 Kc2 [d2c2]   | nodes=1      time=  11µs | window [17.35, 57.00] → [17.35, 57.00] | result=-2.02 (via alphaBeta)
#09 Kc1 [d2c1]   | nodes=1      time=  11µs | window [17.35, 57.00] → [17.35, 57.00] | result=-2.09 (via alphaBeta)
#10 Rf8 [f1f8]   | nodes=1      time=  13µs | window [17.35, 57.00] → [17.35, 57.00] | result=-11.00 (via alphaBeta)
#11 Rf6 [f1f6]   | nodes=1      time=  11µs | window [17.35, 57.00] → [17.35, 57.00] | result=-11.35 (via alphaBeta)
#12 Rf5 [f1f5]   | nodes=1      time=  11µs | window [17.35, 57.00] → [17.35, 57.00] | result=-11.80 (via alphaBeta)
#13 Rf4 [f1f4]   | nodes=1      time=  11µs | window [17.35, 57.00] → [17.35, 57.00] | result=-12.78 (via alphaBeta)
#14 Rf3 [f1f3]   | nodes=1      time=  12µs | window [17.35, 57.00] → [17.35, 57.00] | result=-11.56 (via alphaBeta)
#15 Rf2 [f1f2]   | nodes=1      time=  12µs | window [17.35, 57.00] → [17.35, 57.00] | result=-0.63 (via alphaBeta)
#16 Rh1 [f1h1]   | nodes=1      time=  11µs | window [17.35, 57.00] → [17.35, 57.00] | result=-1.03 (via alphaBeta)
#17 Re1 [f1e1]   | nodes=1      time=  10µs | window [17.35, 57.00] → [17.35, 57.00] | result=8.44 (via alphaBeta)
#18 Rd1 [f1d1]   | nodes=57     time=1576µs | window [17.35, 57.00] → [17.35, 57.00] | result=13.84 (via alphaBeta)
#19 Rc1 [f1c1]   | nodes=612    time=9770µs | window [17.35, 57.00] → [17.35, 57.00] | result=18.39 (via alphaBeta)
#20 Rb1 [f1b1]   | nodes=42     time= 510µs | window [18.39, 57.00] → [18.39, 57.00] | result=17.16 (via alphaBeta)
#21 Ra1 [f1a1]   | nodes=44     time= 555µs | window [18.39, 57.00] → [18.39, 57.00] | result=17.68 (via alphaBeta)
#22 Bg8 [c4g8]   | nodes=48     time=1226µs | window [18.39, 57.00] → [18.39, 57.00] | result=14.14 (via alphaBeta)
#23 Bf7 [c4f7]   | nodes=51     time= 973µs | window [18.39, 57.00] → [18.39, 57.00] | result=18.15 (via alphaBeta)
#24 Be6 [c4e6]   | nodes=54     time= 450µs | window [18.39, 57.00] → [18.39, 57.00] | result=12.93 (via alphaBeta)
#25 Ba6 [c4a6]   | nodes=42     time= 784µs | window [18.39, 57.00] → [18.39, 57.00] | result=18.02 (via alphaBeta)
#26 Bd5 [c4d5]   | nodes=48     time= 398µs | window [18.39, 57.00] → [18.39, 57.00] | result=13.74 (via alphaBeta)
#27 Bb5 [c4b5]   | nodes=48     time= 553µs | window [18.39, 57.00] → [18.39, 57.00] | result=13.55 (via alphaBeta)
#28 Bb3 [c4b3]   | nodes=35     time= 540µs | window [18.39, 57.00] → [18.39, 57.00] | result=18.02 (via alphaBeta)
#29 Ba2 [c4a2]   | nodes=32     time= 440µs | window [18.39, 57.00] → [18.39, 57.00] | result=17.76 (via alphaBeta)
#30 a4 [a3a4] *  | nodes=312    time=9786µs | window [18.39, 57.00] → [18.39, 57.00] | result=31.34 (via alphaBeta)
Best so far: a4 [a3a4] score=31.34
Final window: [31.34, 57.00] after 37 ms
Depth  5 (attempt 1, white to move, window=[9.00, 53.00])
#01 Be2 [c4e2]   | nodes=997    time=14542µs | window [9.00, 53.00] → [9.00, 53.00] | result=31.45 (via alphaBeta)
#02 Ke3 [d2e3] * | nodes=759    time=14489µs | window [31.45, 53.00] → [31.45, 53.00] | result=54.17 (via alphaBeta)
Cutoff after Ke3 [d2e3] (alpha=54.17, beta=53.00)
Best so far: Ke3 [d2e3] score=54.17
Final window: [54.17, 53.00] after 29 ms
Depth  5 (attempt 2, white to move, window=[-11.00, 119.00])
#01 Be2 [c4e2]   | nodes=1      time=  15µs | window [-11.00, 119.00] → [-11.00, 119.00] | result=31.45 (via alphaBeta)
#02 Bd3 [c4d3]   | nodes=1413   time=14415µs | window [31.45, 119.00] → [31.45, 119.00] | result=72.06 (via alphaBeta)
#03 Rf2 [f1f2]   | nodes=721    time=7471µs | window [72.06, 119.00] → [72.06, 119.00] | result=119.89 (via alphaBeta)
Cutoff after Rf2 [f1f2] (alpha=119.89, beta=119.00)
Best so far: Rf2 [f1f2] score=119.89
Final window: [119.89, 119.00] after 21 ms
Depth  5 (attempt 3, white to move, window=[-Infinity, Infinity])
#01 Be2 [c4e2]   | nodes=1      time=  13µs | window [-Infinity, Infinity] → [-Infinity, Infinity] | result=31.45 (via alphaBeta)
#02 Kc3 [d2c3]   | nodes=5122   time=54480µs | window [31.45, Infinity] → [31.45, Infinity] | result=148.28 (via alphaBeta)
#03 Bd3 [c4d3]   | nodes=1      time=  13µs | window [148.28, Infinity] → [148.28, Infinity] | result=72.06 (via alphaBeta)
#04 Rf2 [f1f2]   | nodes=1658   time=12982µs | window [148.28, Infinity] → [148.28, Infinity] | result=148.23 (via alphaBeta)
#05 Rh1 [f1h1]   | nodes=494    time=2534µs | window [148.28, Infinity] → [148.28, Infinity] | result=148.17 (via alphaBeta)
#06 Ke3 [d2e3] * | nodes=297    time=2058µs | window [148.28, Infinity] → [148.28, Infinity] | result=148.17 (via alphaBeta)
#07 Rf7 [f1f7] * | nodes=622    time=3992µs | window [148.28, Infinity] → [148.28, Infinity] | result=148.21 (via alphaBeta)
#08 Rg1 [f1g1]   | nodes=584    time=7650µs | window [148.28, Infinity] → [148.28, Infinity] | result=147.52 (via alphaBeta)
#09 Ke1 [d2e1]   | nodes=168    time= 950µs | window [148.28, Infinity] → [148.28, Infinity] | result=148.13 (via alphaBeta)
#10 Bb3 [c4b3]   | nodes=364    time=2121µs | window [148.28, Infinity] → [148.28, Infinity] | result=147.95 (via alphaBeta)
#11 Kc2 [d2c2]   | nodes=518    time=2683µs | window [148.28, Infinity] → [148.28, Infinity] | result=148.15 (via alphaBeta)
#12 Kc1 [d2c1]   | nodes=206    time=1078µs | window [148.28, Infinity] → [148.28, Infinity] | result=148.04 (via alphaBeta)
#13 Rf8 [f1f8]   | nodes=168    time= 882µs | window [148.28, Infinity] → [148.28, Infinity] | result=-4.91 (via alphaBeta)
#14 Rf6 [f1f6]   | nodes=141    time= 637µs | window [148.28, Infinity] → [148.28, Infinity] | result=136.69 (via alphaBeta)
#15 Rf5 [f1f5]   | nodes=184    time= 771µs | window [148.28, Infinity] → [148.28, Infinity] | result=136.70 (via alphaBeta)
#16 Rf4 [f1f4]   | nodes=119    time= 536µs | window [148.28, Infinity] → [148.28, Infinity] | result=135.39 (via alphaBeta)
#17 Rf3 [f1f3]   | nodes=197    time= 913µs | window [148.28, Infinity] → [148.28, Infinity] | result=136.96 (via alphaBeta)
#18 Re1 [f1e1]   | nodes=208    time=1410µs | window [148.28, Infinity] → [148.28, Infinity] | result=148.17 (via alphaBeta)
#19 Rd1 [f1d1]   | nodes=279    time=2018µs | window [148.28, Infinity] → [148.28, Infinity] | result=148.28 (via alphaBeta)
#20 Rc1 [f1c1]   | nodes=286    time=1465µs | window [148.28, Infinity] → [148.28, Infinity] | result=147.52 (via alphaBeta)
#21 Rb1 [f1b1]   | nodes=362    time=1660µs | window [148.28, Infinity] → [148.28, Infinity] | result=143.25 (via alphaBeta)
#22 Ra1 [f1a1]   | nodes=218    time=1010µs | window [148.28, Infinity] → [148.28, Infinity] | result=148.17 (via alphaBeta)
#23 Bg8 [c4g8]   | nodes=157    time= 725µs | window [148.28, Infinity] → [148.28, Infinity] | result=140.56 (via alphaBeta)
#24 Bf7 [c4f7]   | nodes=231    time=1035µs | window [148.28, Infinity] → [148.28, Infinity] | result=146.54 (via alphaBeta)
#25 Be6 [c4e6]   | nodes=191    time= 912µs | window [148.28, Infinity] → [148.28, Infinity] | result=139.62 (via alphaBeta)
#26 Ba6 [c4a6]   | nodes=39     time= 254µs | window [148.28, Infinity] → [148.28, Infinity] | result=132.47 (via alphaBeta)
#27 Bd5 [c4d5]   | nodes=125    time= 683µs | window [148.28, Infinity] → [148.28, Infinity] | result=139.25 (via alphaBeta)
#28 Bb5 [c4b5]   | nodes=196    time= 821µs | window [148.28, Infinity] → [148.28, Infinity] | result=13.67 (via alphaBeta)
#29 Ba2 [c4a2]   | nodes=124    time= 603µs | window [148.28, Infinity] → [148.28, Infinity] | result=148.23 (via alphaBeta)
#30 a4 [a3a4] *  | nodes=205    time=1783µs | window [148.28, Infinity] → [148.28, Infinity] | result=147.65 (via alphaBeta)
Best so far: Kc3 [d2c3] score=148.28
Final window: [148.28, Infinity] after 108 ms
Depth  6 (attempt 1, white to move, window=[18.00, 282.00])
#01 Be2 [c4e2]   | nodes=461    time=7368µs | window [18.00, 282.00] → [18.00, 282.00] | result=18.00 (via alphaBeta)
#02 Kc3 [d2c3]   | nodes=3423   time=18829µs | window [18.00, 282.00] → [18.00, 282.00] | result=12.77 (via alphaBeta)
#03 Bd3 [c4d3]   | nodes=600    time=4497µs | window [18.00, 282.00] → [18.00, 282.00] | result=14.61 (via alphaBeta)
#04 Rf2 [f1f2]   | nodes=2662   time=34601µs | window [18.00, 282.00] → [18.00, 282.00] | result=18.00 (via alphaBeta)
#05 Rh1 [f1h1]   | nodes=1186   time=7229µs | window [18.00, 282.00] → [18.00, 282.00] | result=17.99 (via alphaBeta)
#06 Rf7 [f1f7] * | nodes=2523   time=16768µs | window [18.00, 282.00] → [18.00, 282.00] | result=18.00 (via alphaBeta)
#07 Ke3 [d2e3] * | nodes=2441   time=23645µs | window [18.00, 282.00] → [18.00, 282.00] | result=17.55 (via alphaBeta)
#08 Ba6 [c4a6]   | nodes=159    time= 703µs | window [18.00, 282.00] → [18.00, 282.00] | result=17.70 (via alphaBeta)
#09 Rg1 [f1g1]   | nodes=2015   time=22630µs | window [18.00, 282.00] → [18.00, 282.00] | result=17.95 (via alphaBeta)
#10 Rf6 [f1f6]   | nodes=325    time=1366µs | window [18.00, 282.00] → [18.00, 282.00] | result=12.81 (via alphaBeta)
#11 Bb3 [c4b3]   | nodes=769    time=3962µs | window [18.00, 282.00] → [18.00, 282.00] | result=16.81 (via alphaBeta)
#12 Rf5 [f1f5]   | nodes=419    time=1515µs | window [18.00, 282.00] → [18.00, 282.00] | result=1.20 (via alphaBeta)
#13 Ke1 [d2e1]   | nodes=338    time=1943µs | window [18.00, 282.00] → [18.00, 282.00] | result=17.41 (via alphaBeta)
#14 Rf8 [f1f8]   | nodes=317    time=1135µs | window [18.00, 282.00] → [18.00, 282.00] | result=13.15 (via alphaBeta)
#15 Kc2 [d2c2]   | nodes=1892   time=9378µs | window [18.00, 282.00] → [18.00, 282.00] | result=16.35 (via alphaBeta)
#16 Kc1 [d2c1]   | nodes=598    time=3698µs | window [18.00, 282.00] → [18.00, 282.00] | result=17.92 (via alphaBeta)
#17 Rf4 [f1f4]   | nodes=239    time= 890µs | window [18.00, 282.00] → [18.00, 282.00] | result=12.20 (via alphaBeta)
#18 Rf3 [f1f3]   | nodes=410    time=1637µs | window [18.00, 282.00] → [18.00, 282.00] | result=13.86 (via alphaBeta)
#19 Re1 [f1e1]   | nodes=2084   time=13420µs | window [18.00, 282.00] → [18.00, 282.00] | result=16.57 (via alphaBeta)
#20 Rd1 [f1d1]   | nodes=1411   time=9744µs | window [18.00, 282.00] → [18.00, 282.00] | result=18.00 (via alphaBeta)
#21 Rc1 [f1c1]   | nodes=820    time=5575µs | window [18.00, 282.00] → [18.00, 282.00] | result=17.23 (via alphaBeta)
#22 Rb1 [f1b1]   | nodes=599    time=2971µs | window [18.00, 282.00] → [18.00, 282.00] | result=16.76 (via alphaBeta)
#23 Ra1 [f1a1]   | nodes=483    time=2278µs | window [18.00, 282.00] → [18.00, 282.00] | result=17.99 (via alphaBeta)
#24 Bg8 [c4g8]   | nodes=351    time=1341µs | window [18.00, 282.00] → [18.00, 282.00] | result=14.14 (via alphaBeta)
#25 Bf7 [c4f7]   | nodes=3952   time=26906µs | window [18.00, 282.00] → [18.00, 282.00] | result=33.31 (via alphaBeta)
#26 Be6 [c4e6]   | nodes=7953   time=51518µs | window [33.31, 282.00] → [33.31, 282.00] | result=142.38 (via alphaBeta)
#27 Bd5 [c4d5]   | nodes=4348   time=36785µs | window [142.38, 282.00] → [142.38, 282.00] | result=142.10 (via alphaBeta)
#28 Bb5 [c4b5]   | nodes=407    time=1698µs | window [142.38, 282.00] → [142.38, 282.00] | result=123.77 (via alphaBeta)
#29 Ba2 [c4a2]   | nodes=319    time=1428µs | window [142.38, 282.00] → [142.38, 282.00] | result=141.00 (via alphaBeta)
#30 a4 [a3a4] *  | nodes=573    time=4786µs | window [142.38, 282.00] → [142.38, 282.00] | result=141.74 (via alphaBeta)
Best so far: Be6 [c4e6] score=142.38
Final window: [142.38, 282.00] after 320 ms
Depth  7 (attempt 1, white to move, window=[21.00, 263.00])
#01 Be2 [c4e2]   | nodes=7787   time=63046µs | window [21.00, 263.00] → [21.00, 263.00] | result=388.73 (via alphaBeta)
Cutoff after Be2 [c4e2] (alpha=388.73, beta=263.00)
Best so far: Be2 [c4e2] score=388.73
Final window: [388.73, 263.00] after 63 ms
Depth  7 (attempt 2, white to move, window=[236.00, 542.00])
#01 Be2 [c4e2]   | nodes=11681  time=97847µs | window [236.00, 542.00] → [236.00, 542.00] | result=409.68 (via alphaBeta)
#02 a4 [a3a4] *  | nodes=5009   time=54323µs | window [409.68, 542.00] → [409.68, 542.00] | result=377.55 (via alphaBeta)
#03 Kc3 [d2c3]   | nodes=11910  time=68566µs | window [409.68, 542.00] → [409.68, 542.00] | result=408.41 (via alphaBeta)
#04 Rh1 [f1h1]   | nodes=4474   time=23098µs | window [409.68, 542.00] → [409.68, 542.00] | result=397.73 (via alphaBeta)
#05 Rf2 [f1f2]   | nodes=4230   time=27307µs | window [409.68, 542.00] → [409.68, 542.00] | result=409.68 (via alphaBeta)
#06 Ke3 [d2e3] * | nodes=6496   time=41289µs | window [409.68, 542.00] → [409.68, 542.00] | result=409.68 (via alphaBeta)
#07 Rd1 [f1d1]   | nodes=5980   time=39239µs | window [409.68, 542.00] → [409.68, 542.00] | result=409.68 (via alphaBeta)
#08 Ke1 [d2e1]   | nodes=975    time=5069µs | window [409.68, 542.00] → [409.68, 542.00] | result=386.32 (via alphaBeta)
#09 Bd3 [c4d3]   | nodes=1443   time=7212µs | window [409.68, 542.00] → [409.68, 542.00] | result=373.41 (via alphaBeta)
#10 Rb1 [f1b1]   | nodes=2546   time=12474µs | window [409.68, 542.00] → [409.68, 542.00] | result=400.72 (via alphaBeta)
#11 Rf5 [f1f5]   | nodes=1149   time=4626µs | window [409.68, 542.00] → [409.68, 542.00] | result=365.40 (via alphaBeta)
#12 Rf7 [f1f7] * | nodes=9594   time=45454µs | window [409.68, 542.00] → [409.68, 542.00] | result=409.68 (via alphaBeta)
#13 Rf6 [f1f6]   | nodes=891    time=4476µs | window [409.68, 542.00] → [409.68, 542.00] | result=387.22 (via alphaBeta)
#14 Rg1 [f1g1]   | nodes=7284   time=41911µs | window [409.68, 542.00] → [409.68, 542.00] | result=401.31 (via alphaBeta)
#15 Ba6 [c4a6]   | nodes=460    time=2260µs | window [409.68, 542.00] → [409.68, 542.00] | result=394.52 (via alphaBeta)
#16 Bb3 [c4b3]   | nodes=2913   time=13264µs | window [409.68, 542.00] → [409.68, 542.00] | result=386.48 (via alphaBeta)
#17 Rf4 [f1f4]   | nodes=729    time=3567µs | window [409.68, 542.00] → [409.68, 542.00] | result=355.56 (via alphaBeta)
#18 Rc1 [f1c1]   | nodes=3013   time=20156µs | window [409.68, 542.00] → [409.68, 542.00] | result=401.31 (via alphaBeta)
#19 Ra1 [f1a1]   | nodes=1206   time=4597µs | window [409.68, 542.00] → [409.68, 542.00] | result=397.73 (via alphaBeta)
#20 Kc2 [d2c2]   | nodes=4918   time=22502µs | window [409.68, 542.00] → [409.68, 542.00] | result=400.72 (via alphaBeta)
#21 Kc1 [d2c1]   | nodes=1579   time=7617µs | window [409.68, 542.00] → [409.68, 542.00] | result=386.47 (via alphaBeta)
#22 Rf8 [f1f8]   | nodes=906    time=4339µs | window [409.68, 542.00] → [409.68, 542.00] | result=375.87 (via alphaBeta)
#23 Rf3 [f1f3]   | nodes=1089   time=4694µs | window [409.68, 542.00] → [409.68, 542.00] | result=358.26 (via alphaBeta)
#24 Re1 [f1e1]   | nodes=1098   time=7561µs | window [409.68, 542.00] → [409.68, 542.00] | result=396.28 (via alphaBeta)
#25 Bg8 [c4g8]   | nodes=988    time=3627µs | window [409.68, 542.00] → [409.68, 542.00] | result=375.87 (via alphaBeta)
#26 Bf7 [c4f7]   | nodes=1596   time=6857µs | window [409.68, 542.00] → [409.68, 542.00] | result=385.66 (via alphaBeta)
#27 Be6 [c4e6]   | nodes=2075   time=11199µs | window [409.68, 542.00] → [409.68, 542.00] | result=397.31 (via alphaBeta)
#28 Bd5 [c4d5]   | nodes=4047   time=25546µs | window [409.68, 542.00] → [409.68, 542.00] | result=387.22 (via alphaBeta)
#29 Bb5 [c4b5]   | nodes=665    time=2955µs | window [409.68, 542.00] → [409.68, 542.00] | result=353.00 (via alphaBeta)
#30 Ba2 [c4a2]   | nodes=741    time=2810µs | window [409.68, 542.00] → [409.68, 542.00] | result=386.10 (via alphaBeta)
Best so far: Be2 [c4e2] score=409.68
Final window: [409.68, 542.00] after 617 ms

-- Transposition table lookups for expected moves --
Rf7  → TT depth=6 score=409.68 type=UPPERBOUND bestMove=Kh8 [g7h8]
Ke3  → TT depth=6 score=409.68 type=UPPERBOUND bestMove=Kh6 [g7h6]
a4   → TT depth=6 score=377.55 type=UPPERBOUND bestMove=Bf3 [g4f3]
========================================================
==>
Expected :true
Actual   :false
<Click to see difference>


In this position 8/6k1/1pp5/2p3p1/2B1p1b1/P7/3K4/5R2 w - - 0 36
The engine played during a cutechess tournament Rf5. Moving the Rook from f1 to f5....
I cannot reproduce the issue with the BestMoveSearchTest....
Is it related to UCI somehow?!?!?

This is the PGN he played as v3.8.7 (white) Move 36 the fatal mistake happens... but why?:

[Event "387"]
[Site "?"]
[Date "2025.11.01"]
[Round "2"]
[White "v3.8.7"]
[Black "v3.6.9"]
[Result "0-1"]
[GameId "UOKoubrv"]
[WhiteElo "?"]
[BlackElo "?"]
[Variant "Standard"]
[TimeControl "120+0"]
[ECO "D85"]
[Opening "Grünfeld Defense: Exchange Variation"]
[Termination "Unknown"]
[Annotator "lichess.org"]

1. d4 Nf6 2. c4 g6 3. Nc3 d5 4. cxd5 Nxd5 { D85 Grünfeld Defense: Exchange Variation } 5. e4 Nxc3 6. bxc3 Nc6 7. d5 Bg7 8. dxc6 Bxc3+ 9. Bd2 Qxd2+ 10. Qxd2 Bxd2+ 11. Kxd2 bxc6 12. Rc1 Bd7 13. Nf3 Rb8 14. Bc4 f6 15. Rb1 Rb6 16. Rhd1 c5 17. Ke3 Rf8 18. Rxb6 axb6 19. Rb1 e6 20. Ke2 Ke7 21. Ke3 Rd8 22. e5 Bc6 23. exf6+ Kxf6 24. Kf4 e5+ 25. Ke3 g5 26. h3 h5 27. g4 hxg4 28. hxg4 Rh8 29. Nd2 Rd8 30. f3 Kg7 31. Rh1 Rxd2 32. Kxd2 Bxf3 33. Re1 e4 34. Rf1 c6 35. a3 Bxg4 36. Rf5 Bxf5 37. Kc1 b5 38. Kb2 bxc4 39. Kc1 e3 40. Kd1 Bd3 41. a4 c3 42. a5 g4 43. Ke1 c2 44. a6 c1=Q# { Black wins. } 0-1

This is how the engine gets booted up:

@echo off
setlocal enableextensions enabledelayedexpansion

rem === Optional: Aufrufparameter =============================================
rem   %1 = Pfad zur Tuning-YAML (optional)
rem   %2 = Engine-Name, der im UCI/GUI angezeigt wird (optional)
rem ===========================================================================

rem === Defaults (kannst du unten/oben anpassen) ==============================
set "SYZYGY_DIR=C:\Syzygy"
set "XMS=8g"
set "XMX=8g"
set "HASH_MB=1024"
set "ENGINE_NAME=Alieknek"
rem ===========================================================================

rem === Pfade relativ zu diesem Skript ========================================
set "SCRIPT_DIR=%~dp0"
set "TARGET_DIR=%SCRIPT_DIR%target"

rem Neueste UCI-JAR aus target\ greifen
set "JAR="
for /f "delims=" %%F in ('dir /b /od "%TARGET_DIR%\chess-engine-*-uci.jar"') do set "JAR=%%F"
if not defined JAR (
echo [ERROR] No *-uci.jar found in "%TARGET_DIR%".
exit /b 1
)
set "JAR=%TARGET_DIR%\%JAR%"

rem Java bestimmen
if defined JAVA_HOME (
set "JAVA=%JAVA_HOME%\bin\java.exe"
) else (
set "JAVA=java"
)

rem Threads = logische CPU-Anzahl (kannst du ueberschreiben: set THREADS=12)
if not defined THREADS set "THREADS=%NUMBER_OF_PROCESSORS%"

rem JSyzygy.dll suchen (falls vorhanden)
set "NATIVE_DLL=%TARGET_DIR%\native\Release\JSyzygy.dll"
if not exist "%NATIVE_DLL%" set "NATIVE_DLL=%TARGET_DIR%\classes\natives\win-x86_64\Release\JSyzygy.dll"
if not exist "%NATIVE_DLL%" set "NATIVE_DLL=%TARGET_DIR%\classes\natives\win-x86_64\JSyzygy.dll"
set "NATIVE_PROP="
if exist "%NATIVE_DLL%" set "NATIVE_PROP=-Dchessengine.syzygy.nativeLibrary=%NATIVE_DLL%"

rem Syzygy-Dirs aus ENV erlauben
if defined CHESSENGINE_SYZYGY_PATHS set "SYZYGY_DIR=%CHESSENGINE_SYZYGY_PATHS%"
if defined CHESSENGINE_SYZYGY_PATH  set "SYZYGY_DIR=%CHESSENGINE_SYZYGY_PATH%"
if defined SYZYGY_PATH              set "SYZYGY_DIR=%SYZYGY_PATH%"

rem Optional: Tuning-Datei & Engine-Name aus Parametern
set "TUNING_FILE=%~1"
if not defined TUNING_FILE (
rem Fallback auf dein Seed im Repo
set "TUNING_FILE=%SCRIPT_DIR%src\main\resources\tuning\seed-tunings.yaml"
)
if exist "%~2" (
rem Wenn 2. Param ein File wäre, ignorieren (nur Name erwartet)
) else if not "%~2"=="" (
set "ENGINE_NAME=%~2"
)

echo.
echo === Launching Chess Engine ===
echo   JAR           : %JAR%
echo   JAVA          : %JAVA%
echo   Threads       : %THREADS%
echo   Engine Name   : %ENGINE_NAME%
echo   Tuning YAML   : %TUNING_FILE%
echo   Syzygy        : %SYZYGY_DIR%
if defined NATIVE_PROP echo   Native DLL    : %NATIVE_DLL%
echo.

rem WICHTIG: -jar OHNE Klassenname (Main-Class im Manifest des *-uci.jar*!)
"%JAVA%" ^
-Xms%XMS% -Xmx%XMX% ^
--enable-native-access=ALL-UNNAMED ^
--enable-preview ^
-XX:+UseG1GC -XX:MaxGCPauseMillis=20 -XX:+AlwaysPreTouch -XX:+UseNUMA ^
-Dchessengine.searchThreads=%THREADS% ^
-Dchessengine.rootParallelLimit=128 ^
-Dchessengine.tt.mb=%HASH_MB% ^
-Dchessengine.syzygy.paths="%SYZYGY_DIR%" ^
%NATIVE_PROP% ^
-Dchessengine.tuning.file="%TUNING_FILE%" ^
-Dchessengine.engineName="%ENGINE_NAME%" ^
-jar "%JAR%"

endlocal



