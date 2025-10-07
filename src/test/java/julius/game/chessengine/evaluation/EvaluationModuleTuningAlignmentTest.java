package julius.game.chessengine.evaluation;

import julius.game.chessengine.board.BitBoard;
import julius.game.chessengine.board.FEN;
import julius.game.chessengine.engine.GameStateEnum;
import julius.game.chessengine.tuning.EngineTuning;
import julius.game.chessengine.tuning.EngineTuningBootstrap;
import julius.game.chessengine.tuning.EngineTuningLoader;
import julius.game.chessengine.tuning.EngineTuningSet;
import julius.game.chessengine.tuning.Tuning;
import julius.game.chessengine.utils.Score;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntSupplier;

class EvaluationModuleTuningAlignmentTest {

    private static final Path SEED_TUNING = Path.of("src", "main", "resources", "tuning", "seed-tunings.yaml");
    private static final String TEST_FEN = "r3k2r/pppb1ppp/2n1bn2/2Pp4/3P4/2N1PN2/PPPB1PPP/R3K2R w KQkq d6 7 12";
    private static final String BLACK_IN_CHECK_FEN = "4k3/8/8/8/8/8/4R3/4K3 b - - 0 1";
    private static final String WHITE_IN_CHECK_FEN = "4k3/4r3/8/8/8/8/8/4K3 w - - 0 1";

    @Test
    void seedTuningParametersAlignWithYaml(TestReporter reporter) throws IOException {
        EngineTuningSet set = EngineTuningLoader.load(SEED_TUNING);
        EngineTuning baseline = set.primary();
        Assertions.assertThat(baseline).isNotNull();

        EngineTuningBootstrap.reloadDefaults();

        Map<String, PhaseWeight> expectedWeights = readModuleWeights();
        Map<String, EvaluationWeights.ModuleWeight> actualWeights = baseline.evaluationWeights().asMap();
        Assertions.assertThat(actualWeights).hasSameSizeAs(expectedWeights);

        expectedWeights.forEach((module, expected) -> {
            EvaluationWeights.ModuleWeight actual = actualWeights.get(module);
            Assertions.assertThat(actual)
                    .withFailMessage("Missing module weight for %s", module)
                    .isNotNull();
            Assertions.assertThat(actual.midgame()).isEqualTo(expected.midgame());
            Assertions.assertThat(actual.endgame()).isEqualTo(expected.endgame());
            reporter.publishEntry("module-weight",
                    module + " mid=" + actual.midgame() + " end=" + actual.endgame());
        });

        Map<String, Double> expectedNumeric = readNumericParameters();
        Map<String, Double> actualNumeric = baseline.numericParameters();
        Assertions.assertThat(actualNumeric).isEqualTo(expectedNumeric);

        Map<String, IntSupplier> trackedParameters = Map.ofEntries(
                Map.entry("material.pawnvalue", Tuning::pawnValue),
                Map.entry("material.knightvalue", Tuning::knightValue),
                Map.entry("material.bishopvalue", Tuning::bishopValue),
                Map.entry("material.rookvalue", Tuning::rookValue),
                Map.entry("material.queenvalue", Tuning::queenValue),
                Map.entry("material.bishoppairbonus", Tuning::bishopPairBonus),
                Map.entry("pawnstructure.centerpawnbonus", Tuning::centerPawnBonus),
                Map.entry("pawnstructure.passedpawnbonus", Tuning::passedPawnBonus),
                Map.entry("pawnstructure.connectedpawnbonus", Tuning::connectedPawnBonus),
                Map.entry("pawnstructure.islandpenalty", Tuning::pawnIslandPenalty),
                Map.entry("pawnstructure.doubledpawnpenalty", Tuning::doubledPawnPenalty),
                Map.entry("pawnstructure.isolatedpawnpenalty", Tuning::isolatedPawnPenalty),
                Map.entry("pawnstructure.advancedpawnbonus", Tuning::advancedPawnBonus),
                Map.entry("pawnstructure.blockedpawnpenalty", Tuning::blockedPawnPenalty),
                Map.entry("pawnstructure.backwardpawnpenalty", Tuning::backwardPawnPenalty),
                Map.entry("pawnstructure.passedpawnfreepathbonusperrank", Tuning::passedPawnFreePathBonusPerRank),
                Map.entry("pawnstructure.rookhalfopenfilebonus", Tuning::rookHalfOpenFileBonus),
                Map.entry("pawnstructure.rookopenfilebonus", Tuning::rookOpenFileBonus),
                Map.entry("threat.hangingpawnpenalty", Tuning::hangingPawnPenalty),
                Map.entry("activity.midgamemobilityknight", Tuning::activityMidgameKnightMobility),
                Map.entry("kingsafety.missingpawnshieldpenalty", Tuning::missingPawnShieldPenalty),
                Map.entry("kingsafety.attackweightqueen", Tuning::kingSafetyQueenAttackWeight),
                Map.entry("evaluation.blendscale", Tuning::evaluationBlendScale),
                Map.entry("space.safesquaremidgame", Tuning::spaceSafeSquareMidgame),
                Map.entry("space.safesquareendgame", Tuning::spaceSafeSquareEndgame),
                Map.entry("space.outpostmidgame", Tuning::spaceOutpostMidgame),
                Map.entry("space.outpostendgame", Tuning::spaceOutpostEndgame),
                Map.entry("space.crampmidgame", Tuning::spaceCrampMidgame),
                Map.entry("space.crampendgame", Tuning::spaceCrampEndgame)
        );

        trackedParameters.forEach((key, supplier) -> {
            Double expected = expectedNumeric.get(key);
            Assertions.assertThat(expected)
                    .withFailMessage("Missing numeric parameter %s in seed YAML", key)
                    .isNotNull();
            int actual = supplier.getAsInt();
            int expectedInt = (int) Math.round(expected);
            Assertions.assertThat(actual)
                    .withFailMessage("Mismatch for %s", key)
                    .isEqualTo(expectedInt);
            reporter.publishEntry("numeric-param", key + "=" + actual);
        });
    }

    @Test
    void evaluationPipelineAggregatesUsingSeedWeights(TestReporter reporter) throws IOException {
        EngineTuningSet set = EngineTuningLoader.load(SEED_TUNING);
        EngineTuning baseline = set.primary();
        Assertions.assertThat(baseline).isNotNull();

        EngineTuningBootstrap.reloadDefaults();

        List<EvaluationModule> modules = createModules();

        EvaluationWeights weights = baseline.evaluationWeights();
        EvaluationPipeline pipeline = new EvaluationPipeline(modules, weights);

        BitBoard board = FEN.translateFENtoBitBoard(TEST_FEN);
        EvaluationContext context = EvaluationContext.from(board, GameStateEnum.PLAY);
        pipeline.initialize(context);

        int midgame = pipeline.getMidgameScore();
        int endgame = pipeline.getEndgameScore();
        int blended = pipeline.getBlendedScore();
        reporter.publishEntry("pipeline-totals",
                "midgame=" + midgame + " endgame=" + endgame + " blended=" + blended);

        double expectedMid = 0.0;
        double expectedEnd = 0.0;
        for (EvaluationModule module : modules) {
            EvaluationWeights.ModuleWeight weight = weights.weightFor(module.getClass());
            int rawMid = module.getMidgameScore();
            int rawEnd = module.getEndgameScore();
            expectedMid += rawMid * weight.midgame();
            expectedEnd += rawEnd * weight.endgame();
            reporter.publishEntry("module-contribution",
                    module.getClass().getSimpleName() + " rawMid=" + rawMid +
                            " rawEnd=" + rawEnd + " weightMid=" + weight.midgame() +
                            " weightEnd=" + weight.endgame());
        }

        int expectedMidRounded = (int) Math.round(expectedMid);
        int expectedEndRounded = (int) Math.round(expectedEnd);
        Assertions.assertThat(midgame).isEqualTo(expectedMidRounded);
        Assertions.assertThat(endgame).isEqualTo(expectedEndRounded);

        int blendScale = baseline.numericParameters().get("evaluation.blendscale").intValue();
        int phase = context.getPhase();
        long blendedNumerator = (long) expectedMidRounded * (blendScale - phase)
                + (long) expectedEndRounded * phase;
        int expectedBlended = (int) (blendedNumerator / blendScale);
        Assertions.assertThat(blended).isEqualTo(expectedBlended);
        reporter.publishEntry("blend-details",
                "phase=" + phase + " blendScale=" + blendScale +
                        " expected=" + expectedBlended);
    }

    @Test
    void evaluationPipelineAppliesCheckAdjustment(TestReporter reporter) throws IOException {
        EngineTuningSet set = EngineTuningLoader.load(SEED_TUNING);
        EngineTuning baseline = set.primary();
        Assertions.assertThat(baseline).isNotNull();

        EngineTuningBootstrap.reloadDefaults();

        WeightedResult blackPlay = evaluatePosition(baseline, BLACK_IN_CHECK_FEN,
                GameStateEnum.PLAY, reporter, "black-check-play");
        WeightedResult blackCheck = evaluatePosition(baseline, BLACK_IN_CHECK_FEN,
                GameStateEnum.BLACK_IN_CHECK, reporter, "black-check-adjusted");

        assertAggregationMatchesModuleTotals(blackPlay, 0);
        assertAggregationMatchesModuleTotals(blackCheck, Score.CHECK);

        Assertions.assertThat(blackCheck.weightedMidgame())
                .isCloseTo(blackPlay.weightedMidgame(), Assertions.offset(1e-6));
        Assertions.assertThat(blackCheck.weightedEndgame())
                .isCloseTo(blackPlay.weightedEndgame(), Assertions.offset(1e-6));

        Assertions.assertThat(blackCheck.midgame() - blackPlay.midgame())
                .isEqualTo(expectedRoundedDifference(blackPlay.weightedMidgame(), Score.CHECK));
        Assertions.assertThat(blackCheck.endgame() - blackPlay.endgame())
                .isEqualTo(expectedRoundedDifference(blackPlay.weightedEndgame(), Score.CHECK));

        WeightedResult whitePlay = evaluatePosition(baseline, WHITE_IN_CHECK_FEN,
                GameStateEnum.PLAY, reporter, "white-check-play");
        WeightedResult whiteCheck = evaluatePosition(baseline, WHITE_IN_CHECK_FEN,
                GameStateEnum.WHITE_IN_CHECK, reporter, "white-check-adjusted");

        assertAggregationMatchesModuleTotals(whitePlay, 0);
        assertAggregationMatchesModuleTotals(whiteCheck, -Score.CHECK);

        Assertions.assertThat(whiteCheck.weightedMidgame())
                .isCloseTo(whitePlay.weightedMidgame(), Assertions.offset(1e-6));
        Assertions.assertThat(whiteCheck.weightedEndgame())
                .isCloseTo(whitePlay.weightedEndgame(), Assertions.offset(1e-6));

        Assertions.assertThat(whiteCheck.midgame() - whitePlay.midgame())
                .isEqualTo(expectedRoundedDifference(whitePlay.weightedMidgame(), -Score.CHECK));
        Assertions.assertThat(whiteCheck.endgame() - whitePlay.endgame())
                .isEqualTo(expectedRoundedDifference(whitePlay.weightedEndgame(), -Score.CHECK));
    }

    private void assertAggregationMatchesModuleTotals(WeightedResult result, int checkAdjustment) {
        int expectedMid = (int) Math.round(result.weightedMidgame() + checkAdjustment);
        int expectedEnd = (int) Math.round(result.weightedEndgame() + checkAdjustment);
        Assertions.assertThat(result.midgame()).isEqualTo(expectedMid);
        Assertions.assertThat(result.endgame()).isEqualTo(expectedEnd);

        int blendScale = result.blendScale();
        int phase = Math.max(0, Math.min(result.phase(), blendScale));
        long numerator = (long) expectedMid * (blendScale - phase)
                + (long) expectedEnd * phase;
        int expectedBlended = (int) (numerator / blendScale);
        Assertions.assertThat(result.blended()).isEqualTo(expectedBlended);
    }

    private static int expectedRoundedDifference(double base, int adjustment) {
        int without = (int) Math.round(base);
        int with = (int) Math.round(base + adjustment);
        return with - without;
    }

    private WeightedResult evaluatePosition(EngineTuning baseline, String fen,
                                            GameStateEnum gameState,
                                            TestReporter reporter,
                                            String label) throws IOException {
        BitBoard board = FEN.translateFENtoBitBoard(fen);
        EvaluationContext context = EvaluationContext.from(board, gameState);
        List<EvaluationModule> modules = createModules();

        EvaluationWeights weights = baseline.evaluationWeights();
        EvaluationPipeline pipeline = new EvaluationPipeline(modules, weights);
        pipeline.initialize(context);

        int midgame = pipeline.getMidgameScore();
        int endgame = pipeline.getEndgameScore();
        int blended = pipeline.getBlendedScore();

        double weightedMid = 0.0;
        double weightedEnd = 0.0;
        for (EvaluationModule module : modules) {
            EvaluationWeights.ModuleWeight weight = weights.weightFor(module.getClass());
            int rawMid = module.getMidgameScore();
            int rawEnd = module.getEndgameScore();
            weightedMid += rawMid * weight.midgame();
            weightedEnd += rawEnd * weight.endgame();
            reporter.publishEntry(label + "-module",
                    module.getClass().getSimpleName() + " rawMid=" + rawMid
                            + " rawEnd=" + rawEnd + " weightMid=" + weight.midgame()
                            + " weightEnd=" + weight.endgame());
        }

        reporter.publishEntry(label + "-totals",
                "midgame=" + midgame + " endgame=" + endgame + " blended=" + blended
                        + " weightedMid=" + weightedMid + " weightedEnd=" + weightedEnd);

        int blendScale = baseline.numericParameters().get("evaluation.blendscale").intValue();
        return new WeightedResult(weightedMid, weightedEnd, midgame, endgame, blended,
                context.getPhase(), blendScale);
    }

    private List<EvaluationModule> createModules() {
        MaterialModule material = new MaterialModule();
        PawnStructureModule pawnStructure = new PawnStructureModule();
        material.setPawnChangeListener(pawnStructure);
        return List.of(
                material,
                pawnStructure,
                new ActivityModule(),
                new SpaceControlModule(),
                new KingSafetyModule(),
                new ThreatModule()
        );
    }

    private record WeightedResult(double weightedMidgame,
                                  double weightedEndgame,
                                  int midgame,
                                  int endgame,
                                  int blended,
                                  int phase,
                                  int blendScale) {
    }

    private Map<String, PhaseWeight> readModuleWeights() throws IOException {
        Map<String, Object> root = readDocument();
        List<Map<String, Object>> population = toListOfMaps(root.get("population"));
        if (population.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> evaluation = toMap(population.get(0).get("evaluation"));
        Map<String, Object> modules = toMap(evaluation.get("modules"));
        Map<String, PhaseWeight> weights = new LinkedHashMap<>();
        modules.forEach((name, value) -> {
            Map<String, Object> entry = toMap(value);
            PhaseWeight weight = new PhaseWeight(toDouble(entry.get("midgame")),
                    toDouble(entry.get("endgame")));
            weights.put(name.toLowerCase(Locale.ROOT), weight);
        });
        return weights;
    }

    private Map<String, Double> readNumericParameters() throws IOException {
        Map<String, Object> root = readDocument();
        List<Map<String, Object>> population = toListOfMaps(root.get("population"));
        if (population.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> numeric = toMap(population.get(0).get("numericParameters"));
        Map<String, Double> normalized = new LinkedHashMap<>();
        numeric.forEach((key, value) -> normalized.put(
                key.toLowerCase(Locale.ROOT),
                toDouble(value)
        ));
        return normalized;
    }

    private Map<String, Object> readDocument() throws IOException {
        try (InputStream in = Files.newInputStream(SEED_TUNING)) {
            Object loaded = new Yaml().load(in);
            if (loaded instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cast = (Map<String, Object>) map;
                return cast;
            }
            return Map.of();
        }
    }

    private static List<Map<String, Object>> toListOfMaps(Object value) {
        if (value instanceof List<?> list) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cast = (List<Map<String, Object>>) (List<?>) list;
            return cast;
        }
        return List.of();
    }

    private static Map<String, Object> toMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) map;
            return cast;
        }
        return Map.of();
    }

    private static double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return 0.0;
        }
        return Double.parseDouble(value.toString());
    }

    private record PhaseWeight(double midgame, double endgame) {
    }
}
