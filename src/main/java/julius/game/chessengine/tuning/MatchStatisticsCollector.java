package julius.game.chessengine.tuning;

import julius.game.chessengine.engine.GameStateEnum;

import java.util.Collection;
import java.util.Comparator;
import java.util.IntSummaryStatistics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Collects match outcomes for a set of {@link EngineTuning} participants and provides aggregated
 * statistics that can be rendered in human-friendly tables. Designed for tuning sessions where we
 * want to keep an eye on leaderboards and colour performance trends while matches are running.
 */
public final class MatchStatisticsCollector {

    private static final int TABLE_NAME_WIDTH = 22;

    private final Map<EngineTuning, ScoreCard> scoreboard = new LinkedHashMap<>();
    private final IntSummaryStatistics plies = new IntSummaryStatistics();

    private int totalMatches;
    private int whiteWins;
    private int blackWins;
    private int draws;

    public void registerParticipant(EngineTuning tuning) {
        if (tuning != null) {
            scoreboard.computeIfAbsent(tuning, ScoreCard::new);
        }
    }

    public void registerParticipants(Collection<EngineTuning> tunings) {
        if (tunings == null) {
            return;
        }
        tunings.forEach(this::registerParticipant);
    }

    public void recordMatch(EngineTuning white, EngineTuning black, MatchRunner.MatchResult result) {
        Objects.requireNonNull(white, "white");
        Objects.requireNonNull(black, "black");
        Objects.requireNonNull(result, "result");

        registerParticipant(white);
        registerParticipant(black);

        totalMatches++;
        plies.accept(result.plies());

        GameStateEnum state = result.finalState();
        switch (state) {
            case WHITE_WON -> whiteWins++;
            case BLACK_WON -> blackWins++;
            case DRAW -> draws++;
            default -> draws++;
        }

        scoreboard.get(white).record(state, result.whiteScore(), true, result.plies());
        scoreboard.get(black).record(state, result.blackScore(), false, result.plies());
    }

    public List<ScoreCard> leaderboard() {
        return scoreboard.values().stream()
                .sorted(ScoreCard.LEADERBOARD_ORDER)
                .toList();
    }

    public String formatTable(int maxRows) {
        if (scoreboard.isEmpty()) {
            return "  (no participants)\n";
        }
        List<ScoreCard> ordered = leaderboard();
        int display = maxRows <= 0 ? ordered.size() : Math.min(maxRows, ordered.size());
        StringBuilder table = new StringBuilder();
        table.append(String.format(Locale.ROOT, "%-4s  %-" + TABLE_NAME_WIDTH + "s  %6s  %9s  %9s  %9s  %8s%n",
                "Rank", "Engine", "Points", "W-D-L", "White", "Black", "AvgPl"));
        for (int i = 0; i < display; i++) {
            ScoreCard card = ordered.get(i);
            String points = String.format(Locale.ROOT, "%.2f", card.points());
            String avg = card.games() > 0
                    ? String.format(Locale.ROOT, "%.1f", card.averagePlies())
                    : "-";
            table.append(String.format(Locale.ROOT, "%4d  %-" + TABLE_NAME_WIDTH + "s  %6s  %9s  %9s  %9s  %8s%n",
                    i + 1,
                    ellipsize(card.tuning().name()),
                    points,
                    card.recordSummary(),
                    card.whiteSummary(),
                    card.blackSummary(),
                    avg));
        }
        return table.toString();
    }

    public int totalMatches() {
        return totalMatches;
    }

    public int whiteWins() {
        return whiteWins;
    }

    public int blackWins() {
        return blackWins;
    }

    public int draws() {
        return draws;
    }

    public double decisiveRate() {
        if (totalMatches == 0) {
            return 0.0;
        }
        return ((double) (whiteWins + blackWins) * 100.0) / totalMatches;
    }

    public double averagePlies() {
        return totalMatches == 0 ? 0.0 : plies.getAverage();
    }

    public int minPlies() {
        return totalMatches == 0 ? 0 : plies.getMin();
    }

    public int maxPlies() {
        return totalMatches == 0 ? 0 : plies.getMax();
    }

    public boolean isEmpty() {
        return scoreboard.isEmpty();
    }

    public List<Map.Entry<EngineTuning, Double>> toPointRanking() {
        return leaderboard().stream()
                .map(card -> Map.entry(card.tuning(), card.points()))
                .toList();
    }

    public static MatchStatisticsCollector fromMatches(List<MatchRunner.MatchResult> matches) {
        MatchStatisticsCollector collector = new MatchStatisticsCollector();
        if (matches != null) {
            for (MatchRunner.MatchResult match : matches) {
                collector.recordMatch(match.whiteTuning(), match.blackTuning(), match);
            }
        }
        return collector;
    }

    private static String ellipsize(String value) {
        if (value == null) {
            return "";
        }
        if (value.length() <= MatchStatisticsCollector.TABLE_NAME_WIDTH) {
            return value;
        }
        if (MatchStatisticsCollector.TABLE_NAME_WIDTH <= 3) {
            return value.substring(0, MatchStatisticsCollector.TABLE_NAME_WIDTH);
        }
        return value.substring(0, MatchStatisticsCollector.TABLE_NAME_WIDTH - 3) + "...";
    }

    public static final class ScoreCard {
        private static final Comparator<ScoreCard> LEADERBOARD_ORDER = Comparator
                .comparingDouble(ScoreCard::points).reversed()
                .thenComparingInt(ScoreCard::wins).reversed()
                .thenComparingInt(ScoreCard::games).reversed()
                .thenComparing(card -> card.tuning().name(), String.CASE_INSENSITIVE_ORDER);

        private final EngineTuning tuning;
        private double points;
        private int games;
        private int wins;
        private int draws;
        private int losses;
        private int whiteWins;
        private int whiteDraws;
        private int whiteLosses;
        private int blackWins;
        private int blackDraws;
        private int blackLosses;
        private int totalPlies;

        private ScoreCard(EngineTuning tuning) {
            this.tuning = tuning;
        }

        public EngineTuning tuning() {
            return tuning;
        }

        public double points() {
            return points;
        }

        public int games() {
            return games;
        }

        public int wins() {
            return wins;
        }

        public double averagePlies() {
            return games == 0 ? 0.0 : (double) totalPlies / games;
        }

        private void record(GameStateEnum state, double score, boolean asWhite, int plies) {
            this.points += score;
            this.games++;
            this.totalPlies += plies;

            switch (state) {
                case WHITE_WON -> {
                    if (asWhite) {
                        wins++;
                        whiteWins++;
                    } else {
                        losses++;
                        blackLosses++;
                    }
                }
                case BLACK_WON -> {
                    if (asWhite) {
                        losses++;
                        whiteLosses++;
                    } else {
                        wins++;
                        blackWins++;
                    }
                }
                case DRAW -> {
                    draws++;
                    if (asWhite) {
                        whiteDraws++;
                    } else {
                        blackDraws++;
                    }
                }
                default -> {
                    draws++;
                    if (asWhite) {
                        whiteDraws++;
                    } else {
                        blackDraws++;
                    }
                }
            }
        }

        public String recordSummary() {
            return wins + "-" + draws + "-" + losses;
        }

        public String whiteSummary() {
            return whiteWins + "-" + whiteDraws + "-" + whiteLosses;
        }

        public String blackSummary() {
            return blackWins + "-" + blackDraws + "-" + blackLosses;
        }
    }
}

