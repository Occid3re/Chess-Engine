# AI Refactor Map

This document tracks where every field and method in `AI` will live after splitting the
monolithic search implementation into cohesive packages. The goal is to let the legacy
`engine.ai.AI` façade keep its current API while delegating the real work to the new
search sub-packages.

## Field relocation

### `engine.ai` façade dependencies

| Field | Destination | Notes |
| --- | --- | --- |
| `mainEngine` | `search.engine.SearchEngine` (constructor dependency) | The façade will own a `SearchEngine` built around the main `Engine`. `AI` keeps only the reference required to satisfy existing getters while the search engine consumes it. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L34-L223】 |

### Search configuration (`engine/search/config`)

| Field | Destination | Notes |
| --- | --- | --- |
| `searchThreads` | `SearchConfig` | Tunable UCI option that controls worker pool size. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L37-L50】 |
| `lazySmpThreads` | `SearchConfig` | Lazy-SMP helper count; retained as config knob. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L44-L50】 |
| `hashSizeMb` | `SearchConfig` + `tt.TTPolicy` | Requested TT size moves into config; TTPolicy uses it to size tables. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L57-L259】 |
| `MIN_HASH_SIZE_MB`, `MAX_HASH_SIZE_MB` | `SearchConfig` | Bounds for the public Hash option. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L88-L315】 |
| `ROOT_PARALLEL_LIMIT` | `SearchConfig` | Cap for root move fan-out exposed as advanced tuning. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L91-L101】 |
| `maxDepth` | `SearchLimits` | Iterative-deepening depth limit. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L191-L204】 |
| `useNullMovePruning` | `SearchConfig` (fed into `AlphaBetaSearch`) | Toggle surfaced as search option, consumed by pruning hooks. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L199-L204】 |

### Time control (`engine/search/time`)

| Field | Destination | Notes |
| --- | --- | --- |
| `timeLimit`, `INFINITE_TIME_LIMIT` | `SearchLimits`/`TimeManager` | Stored with other per-search limits and interpreted by the time manager. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L191-L204】 |
| `scheduler` | `TimeManager` | Auto-play polling becomes a scheduled job managed alongside clocks. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L168-L721】 |
| `searchStartTimeNanos` | `TimeManager` | Tracks elapsed search time for limits and diagnostics. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L205-L633】 |

### Search engine coordination (`engine/search/engine`)

| Field | Destination | Notes |
| --- | --- | --- |
| `calculationCoordinator`, `calculationThreads` | `SearchEngine` | Worker orchestration threads move into the engine façade. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L48-L50】【F:src/main/java/julius/game/chessengine/ai/AI.java†L378-L760】 |
| `activeSearch`, `threadSearchTask`, `searchIdGenerator` | `SearchEngine` | Live search bookkeeping and worker-local task handles. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L54-L57】【F:src/main/java/julius/game/chessengine/ai/AI.java†L399-L700】 |
| `searchPool` | `SearchEngine` | Root-split executor belongs to the engine-level coordinator. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L91-L600】 |
| `calculationLock`, `keepCalculating` | `SearchEngine` | Global stop flag and wake-up lock consolidate near worker control. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L168-L676】 |
| `currentBoardState`, `beforeCalculationBoardState` | `SearchEngine` | Position-tracking for incremental recalculation. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L174-L846】 |
| `currentBestMove`, `previousBestMove`, `previousBestMoveHash`, `bestMoveForHash`, `searchResultReady` | `SearchEngine` | Root result cache exposed through the stable API. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L177-L760】【F:src/main/java/julius/game/chessengine/ai/AI.java†L809-L909】 |
| `calculatedLine`, `lastCompletedPrincipalVariation` | `SearchEngine` + `util.PV` | Storage moves into PV helper but owned by the engine coordinator. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L184-L189】【F:src/main/java/julius/game/chessengine/ai/AI.java†L226-L240】 |
| `lastDiagnostics` | `SearchEngine` | Final iteration diagnostics kept with the task. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L205-L910】 |

### Search context & heuristics (`engine/search/context` + `engine/search/ordering`)

| Field | Destination | Notes |
| --- | --- | --- |
| `globalHeuristics`, `threadHeuristics`, `heuristicsLock` | `SearchContext` | Shared + worker-local heuristic state. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L131-L147】 |
| `NUM_KILLER_MOVES`, `HISTORY_BUCKETS` | `ordering.History`/`Killers` | Constants defining heuristic table shapes. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L131-L166】 |
| `MAX_MOVE_LIST_SIZE`, `sortBuffers` | `ordering.MoveOrdering` | Scratch buffers for ordering logic. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L150-L155】 |

### Static exchange evaluation (`engine/search/see`)

| Field | Destination | Notes |
| --- | --- | --- |
| `SEE_CACHE_MASK`, `seeCacheKeys`, `seeCacheVals`, `seeCacheGenerations`, `seeCacheGenerationCounters` | `StaticExchangeEvaluation` | Dedicated SEE cache per worker. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L102-L108】【F:src/main/java/julius/game/chessengine/ai/AI.java†L2177-L2337】 |
| `seeCacheThreadLocal` | `StaticExchangeEvaluation` | Map-based SEE fallback cache for heuristics. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L154-L2191】 |
| `BISHOP_HELPER`, `ROOK_HELPER` | `StaticExchangeEvaluation` | Sliding attack helpers consumed by SEE/move ordering. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L110-L112】【F:src/main/java/julius/game/chessengine/ai/AI.java†L2409-L2437】 |

### Transposition tables (`engine/search/tt`)

| Field | Destination | Notes |
| --- | --- | --- |
| `transpositionTable`, `captureTranspositionTable` | `TranspositionTableManager` | Manager owns both TT instances. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L114-L138】【F:src/main/java/julius/game/chessengine/ai/AI.java†L242-L339】 |
| `transpositionTableCapacity`, `captureTranspositionTableCapacity` | `TranspositionTableManager` | Reported capacities computed during resize. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L118-L257】 |
| `MAIN_TT_ENTRY_BYTES`, `CAPTURE_TT_ENTRY_BYTES`, `MAIN_TT_WEIGHT`, `CAPTURE_TT_WEIGHT` | `TTPolicy` | Memory budgeting parameters. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L65-L260】 |
| `MIN/MAX_*_TT_ENTRIES` | `TTPolicy` | Capacity bounds centralised with sizing rules. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L74-L253】 |

### Search accounting & constants (`engine/search/util`)

| Field | Destination | Notes |
| --- | --- | --- |
| `EXIT_FLAG` | `SearchConstants` | Shared sentinel returned by aborting searches. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L102-L103】 |
| `nodesVisited`, `nullMoveCount` | `NodeCounter`/`SearchDiagnostics` | Move into instrumentation helpers. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L205-L211】【F:src/main/java/julius/game/chessengine/ai/AI.java†L1450-L2164】 |

### Alpha-beta specific constants (`engine/search/alphabeta`)

| Field | Destination | Notes |
| --- | --- | --- |
| `MAX_CHECK_EXTENSIONS_IN_A_ROW`, `ABS_PLY_LIMIT_MARGIN` | `AlphaBetaSearch` | Extension limits tied to the recursive searcher. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L51-L53】【F:src/main/java/julius/game/chessengine/ai/AI.java†L1450-L1747】 |

### Principal variation helpers (`engine/search/util`)

| Field | Destination | Notes |
| --- | --- | --- |
| `calculatedLine`, `lastCompletedPrincipalVariation` | `util.PrincipalVariation` | PV storage/helper class used by the façade. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L184-L240】【F:src/main/java/julius/game/chessengine/ai/AI.java†L1312-L1390】 |

## Method relocation

Methods are grouped by whether they operate at the root/coordination layer or form part of the recursive search core.

### Root/coordination layer

| Method | Destination | Notes |
| --- | --- | --- |
| `AI(Engine)` | `engine.ai.AI` | Constructor builds a `SearchEngine`, `SearchConfig`, and `TimeManager` from legacy dependencies. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L213-L224】 |
| `getCalculatedLine` | `engine.ai.AI` delegating to `util.PrincipalVariation` | Facade remains, data served from PV helper. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L184-L189】 |
| `updatePrincipalVariation`, `clearPrincipalVariation`, `resetCurrentPrincipalVariation`, `fillCalculatedLine` | `util.PrincipalVariation` | PV lifecycle extracted from core engine. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L226-L240】【F:src/main/java/julius/game/chessengine/ai/AI.java†L1312-L1390】 |
| `setHashSizeMb`, `rebuildTranspositionTables`, `computeTableCapacity`, `roundUpToPowerOfTwo` | `tt.TranspositionTableManager`/`TTPolicy` | TT sizing and rebuild logic consolidated. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L240-L339】 |
| `setMaxDepth` | `SearchLimits` | Depth tuning remains a façade call forwarded into config/context. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L339-L349】 |
| `setTimeLimit`, `computeDeadlineNanos`, `getSearchElapsedMillis` | `TimeManager` | Deadline computation and elapsed tracking centralised. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L347-L628】【F:src/main/java/julius/game/chessengine/ai/AI.java†L787-L808】 |
| `getCurrentBestMoveInt`, `performMove`, `updateBoardStateHash` | `SearchEngine` | Root result exposure and move execution remain façade delegates. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L360-L909】【F:src/main/java/julius/game/chessengine/ai/AI.java†L724-L909】【F:src/main/java/julius/game/chessengine/ai/AI.java†L2630-L2667】 |
| `startCalculationThread`, `searchWorkerLoop`, `runSearchOnTask`, `calculateLine`, `performCalculation`, `completeSearchTask` | `SearchEngine` | Thread orchestration and task lifecycle. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L378-L909】 |
| `iterativeDeepening`, `prepareIterationState`, `prepareHelperHeuristics`, `mergeThreadHeuristics` | `SearchEngine` + `SearchContext` | Iteration driver plus heuristic preparation now split between coordinator and context. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L448-L597】 |
| `rebuildSearchPool`, `setSearchThreads`, `searchRootMoves`, `getBestMoveParallel`, `getBestMove` | `SearchEngine` | Root move management and optional parallel split. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L538-L2149】 |
| `reset`, `stopCalculation`, `requestStop` | `SearchEngine` | Worker shutdown and state clearing. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L599-L699】 |
| `startAutoPlay` | `SearchEngine` + `TimeManager` | Auto-play scheduling uses the time subsystem but lives behind the façade. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L701-L722】 |
| `maybeRotateRootMoves`, `rotateMoveListLeft` | `ordering.MoveOrdering` | Root move rotation logic goes with move ordering. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L922-L947】 |
| `abortRequested`, `shouldStopCalculating`, `positionChanged` | `SearchEngine` | Stop conditions consolidated around coordinator state. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L954-L1308】【F:src/main/java/julius/game/chessengine/ai/AI.java†L2630-L2647】 |
| `instrumentation`, `resetSearchCounters`, `nodesVisited/nullMoveCount` updates | `SearchDiagnostics`/`NodeCounter` | Instrumentation accessors centralised. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L969-L1127】【F:src/main/java/julius/game/chessengine/ai/AI.java†L628-L633】 |
| `applyRootScore`, `updateFallbackMate`, `isWinningMateForUs`, `isLosingMateForUs` | `SearchEngine` | Root scoring helpers stay with root coordination. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L1209-L1288】 |
| `publishExactMateToTT` | `tt.TranspositionTableManager` | TT helper invoked from search engine/alpha-beta. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L1290-L1304】 |
| `updateKillerMoves`, `incrementHistory`, `clearHistoryTable`, `snapshotKillerMoves` | `ordering.History/Killers` | Heuristic updates move with history tables. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L2661-L2682】 |
| `calculateMvvLvaScore` | `ordering.MoveOrdering` | Capture scoring integrates with ordering module. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L2684-L2694】 |

### Recursive / per-node search core

| Method | Destination | Notes |
| --- | --- | --- |
| `alphaBeta`, `maximizer`, `minimizer` | `AlphaBetaSearch` | Core recursive search split into dedicated class. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L1450-L2164】 |
| `quiescenceSearch`, `getPossibleCapturesOrPromotions` | `quiescence.QuiescenceSearch` | Capture-only search extracted for clarity. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L2536-L2599】 |
| `evaluateBoard`, `evaluateStaticPosition` | `AlphaBetaSearch` (pluggable evaluator) | Static evaluation wrapper used by both alpha-beta and quiescence. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L2496-L2628】 |
| `isSideInCheck`, `attacksOpponentQueenNow`, `attacksOpponentRookNow`, `attacksOpponentKingZone` | `AlphaBetaSearch` | Detection helpers tied to pruning/extension logic. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L1578-L1634】 |
| `computeNullMoveReduction`, `getReductionEstimate`, `countPawnsOnFile`, `openedFileTowardKing` | `AlphaBetaSearch` | Null-move and pruning heuristics. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L1623-L1684】 |
| `lmrReduction`, `computeStandPatMargin` | `AlphaBetaSearch` | Late move reduction and futility margin tuning. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L1685-L1717】 |
| `moveGivesCheck`, `pieceAttacksSquare`, `sortMovesByEfficiency` | `ordering.MoveOrdering` | Move ordering and checking heuristics extracted. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L2177-L2475】 |

## Pruning and extension toggles

All pruning/extension controls become explicit knobs on `AlphaBetaSearch`, sourced from `SearchConfig`:

- **Null-move pruning (NMP):** driven by `useNullMovePruning`, `computeNullMoveReduction`, and verification logic inside `alphaBeta`. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L199-L1755】
- **Late move reductions (LMR):** implemented via `lmrReduction` and the `canReduce` guards in `maximizer`/`minimizer`. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L1685-L2150】
- **Internal iterative reduction (IIR):** realised by the re-search path that first probes with a reduced depth and escalates when the result is promising; the hook lives alongside the LMR block and will be formalised as an AlphaBeta setting. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L1878-L2133】
- **Futility pruning:** controlled by the `allowStandPatPrune` checks using `computeStandPatMargin`. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L2047-L2085】

## Concurrency and timing responsibilities

- **SearchEngine:** owns worker threads, `SearchTask` coordination, stop flags, PV publication, and shared state updates triggered by `startCalculationThread`, `calculateLine`, and `performCalculation`. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L378-L909】
- **TimeManager:** computes deadlines, maintains `searchStartTimeNanos`, schedules auto-play polling, and surfaces elapsed time to diagnostics. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L347-L728】【F:src/main/java/julius/game/chessengine/ai/AI.java†L787-L909】

## Transposition table policy and concurrency

Table sizing, aging, and concurrency selection (`FixedSizeTranspositionTable` vs `PlainFixedSizeTranspositionTable`) move into `search.tt` components so they can handle capacity, age increments, and thread-safe access centrally. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L242-L339】【F:src/main/java/julius/game/chessengine/ai/AI.java†L500-L520】

## Heuristic state

Global and per-thread heuristic buffers (`Heuristics`, killers, history, counter-move tables, SEE caches) relocate to the search context and ordering packages. The façade will simply ask the `SearchEngine` for snapshots when exposing diagnostics. 【F:src/main/java/julius/game/chessengine/ai/AI.java†L131-L233】【F:src/main/java/julius/game/chessengine/ai/AI.java†L2177-L2976】
