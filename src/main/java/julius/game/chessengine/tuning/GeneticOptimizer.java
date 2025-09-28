package julius.game.chessengine.tuning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * Runs a simple genetic algorithm on top of the chess engine by repeatedly letting configurations
 * play against each other and mutating the top performers into the next generation.
 */
public final class GeneticOptimizer {

    private final MatchRunner matchRunner;
    private final Random random;

    public GeneticOptimizer() {
        this(new MatchRunner(), new Random());
    }

    public GeneticOptimizer(MatchRunner matchRunner, Random random) {
        this.matchRunner = Objects.requireNonNull(matchRunner, "matchRunner");
        this.random = Objects.requireNonNull(random, "random");
    }

    public GeneticResult evolve(EngineTuningSet seedPopulation, GeneticOptions options) {
        Objects.requireNonNull(seedPopulation, "seedPopulation");
        Objects.requireNonNull(options, "options");
        if (seedPopulation.isEmpty()) {
            throw new IllegalArgumentException("Seed population must contain at least one tuning");
        }

        List<EngineTuning> population = new ArrayList<>(seedPopulation.population());
        while (population.size() < options.populationSize()) {
            EngineTuning parent = population.get(random.nextInt(population.size()));
            population.add(parent.mutate(random, options.mutationStrength()).rename(parent.name() + "_seed"));
        }

        List<MatchRunner.MatchResult> allMatches = new ArrayList<>();

        for (int generation = 0; generation < options.generations(); generation++) {
            Map<EngineTuning, Double> scoreboard = new HashMap<>();

            for (int i = 0; i < population.size(); i++) {
                for (int j = i + 1; j < population.size(); j++) {
                    EngineTuning a = population.get(i);
                    EngineTuning b = population.get(j);
                    for (int match = 0; match < options.matchesPerPair(); match++) {
                        boolean swap = (match % 2) == 1;
                        EngineTuning white = swap ? b : a;
                        EngineTuning black = swap ? a : b;
                        MatchRunner.MatchResult result = matchRunner.playMatch(
                                white,
                                black,
                                new MatchRunner.MatchOptions(options.maxPlies(), options.moveTimeMillis())
                        );
                        allMatches.add(result);
                        if (swap) {
                            scoreboard.merge(a, result.blackScore(), Double::sum);
                            scoreboard.merge(b, result.whiteScore(), Double::sum);
                        } else {
                            scoreboard.merge(a, result.whiteScore(), Double::sum);
                            scoreboard.merge(b, result.blackScore(), Double::sum);
                        }
                    }
                }
            }

            population = scoreboard.entrySet().stream()
                    .sorted(Map.Entry.<EngineTuning, Double>comparingByValue(Comparator.reverseOrder()))
                    .limit(Math.max(options.retainCount(), 2))
                    .map(Map.Entry::getKey)
                    .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);

            while (population.size() < options.populationSize()) {
                EngineTuning parent = population.get(random.nextInt(population.size()));
                EngineTuning offspring = parent.mutate(random, options.mutationStrength())
                        .rename(parent.name() + "_g" + generation + "_mut");
                population.add(offspring);
            }
        }

        return new GeneticResult(new EngineTuningSet(population), List.copyOf(allMatches));
    }

    public record GeneticResult(EngineTuningSet population, List<MatchRunner.MatchResult> matches) {
    }
}
