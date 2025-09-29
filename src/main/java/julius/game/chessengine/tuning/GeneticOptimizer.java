package julius.game.chessengine.tuning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import lombok.extern.log4j.Log4j2;

/**
 * Runs a simple genetic algorithm on top of the chess engine by repeatedly letting configurations
 * play against each other and mutating the top performers into the next generation.
 */
@Log4j2
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
        log.info("Starting genetic evolution with {} seed configurations (target population {}, generations {}, retain {}, mutation strength {}, matches per pair {}, match threads {}).",
                seedPopulation.population().size(),
                options.populationSize(),
                options.generations(),
                options.retainCount(),
                String.format(Locale.ROOT, "%.3f", options.mutationStrength()),
                options.matchesPerPair(),
                options.matchParallelism());
        if (log.isDebugEnabled()) {
            log.debug("Initial population: {}", population.stream().map(EngineTuning::name).toList());
        }
        while (population.size() < options.populationSize()) {
            EngineTuning parent = population.get(random.nextInt(population.size()));
            population.add(parent.mutate(random, options.mutationStrength()).rename(parent.name() + "_seed"));
        }
        if (log.isDebugEnabled()) {
            log.debug("Expanded initial population to {} entries: {}", population.size(),
                    population.stream().map(EngineTuning::name).toList());
        }

        List<MatchRunner.MatchResult> allMatches = new ArrayList<>();

        MatchRunner.MatchOptions matchOptions = new MatchRunner.MatchOptions(options.maxPlies(), options.moveTimeMillis());
        int parallelism = Math.max(1, options.matchParallelism());
        ExecutorService executor = parallelism > 1 ? Executors.newFixedThreadPool(parallelism, matchThreadFactory()) : null;

        try {
            for (int generation = 0; generation < options.generations(); generation++) {
                int generationIndex = generation + 1;
                Map<EngineTuning, Double> scoreboard = new HashMap<>();
                List<ScheduledMatch> scheduledMatches = new ArrayList<>();

                for (int i = 0; i < population.size(); i++) {
                    for (int j = i + 1; j < population.size(); j++) {
                        EngineTuning a = population.get(i);
                        EngineTuning b = population.get(j);
                        for (int match = 0; match < options.matchesPerPair(); match++) {
                            boolean swap = (match % 2) == 1;
                            scheduledMatches.add(new ScheduledMatch(a, b, swap));
                        }
                    }
                }

                log.info("Generation {}: scheduled {} matches for {} configurations", generationIndex,
                        scheduledMatches.size(), population.size());

                List<CompletedMatch> completedMatches = runScheduledMatches(scheduledMatches, matchOptions, executor);
                log.info("Generation {}: completed {} matches", generationIndex, completedMatches.size());

                for (CompletedMatch completed : completedMatches) {
                    allMatches.add(completed.result());
                    EngineTuning a = completed.assignment().first();
                    EngineTuning b = completed.assignment().second();
                    MatchRunner.MatchResult result = completed.result();
                    if (completed.assignment().swapped()) {
                        scoreboard.merge(a, result.blackScore(), Double::sum);
                        scoreboard.merge(b, result.whiteScore(), Double::sum);
                    } else {
                        scoreboard.merge(a, result.whiteScore(), Double::sum);
                        scoreboard.merge(b, result.blackScore(), Double::sum);
                    }
                    if (log.isDebugEnabled()) {
                        log.debug("Generation {} match: {} vs {} => {}-{} ({}, {} plies)",
                                generationIndex,
                                result.whiteTuning().name(),
                                result.blackTuning().name(),
                                String.format(Locale.ROOT, "%.2f", result.whiteScore()),
                                String.format(Locale.ROOT, "%.2f", result.blackScore()),
                                result.finalState(),
                                result.plies());
                    }
                }

                if (scoreboard.isEmpty()) {
                    for (EngineTuning tuning : population) {
                        scoreboard.putIfAbsent(tuning, 0.0);
                    }
                }

                List<Map.Entry<EngineTuning, Double>> leaderboard = scoreboard.entrySet().stream()
                        .sorted(Map.Entry.<EngineTuning, Double>comparingByValue(Comparator.reverseOrder()))
                        .toList();

                if (log.isInfoEnabled()) {
                    log.info("Generation {} leaderboard:", generationIndex);
                    leaderboard.stream()
                            .limit(Math.min(5, leaderboard.size()))
                            .forEach(entry -> log.info("  {} -> {}", entry.getKey().name(),
                                    String.format(Locale.ROOT, "%.2f", entry.getValue())));
                }

                List<EngineTuning> retained = leaderboard.stream()
                        .limit(Math.max(options.retainCount(), 2))
                        .map(Map.Entry::getKey)
                        .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);

                population = new ArrayList<>(retained);
                int retainedCount = population.size();

                while (population.size() < options.populationSize()) {
                    EngineTuning parent = population.get(random.nextInt(population.size()));
                    EngineTuning offspring = parent.mutate(random, options.mutationStrength())
                            .rename(parent.name() + "_g" + generation + "_mut");
                    population.add(offspring);
                    if (log.isDebugEnabled()) {
                        log.debug("Generation {}: created offspring {} from parent {}", generationIndex,
                                offspring.name(), parent.name());
                    }
                }

                log.info("Generation {}: retained {} configurations and produced {} offspring (population size {}).",
                        generationIndex, retainedCount, population.size() - retainedCount, population.size());
                if (log.isDebugEnabled()) {
                    log.debug("Generation {} population: {}", generationIndex,
                            population.stream().map(EngineTuning::name).toList());
                }
            }
        } finally {
            if (executor != null) {
                executor.shutdown();
            }
        }

        return new GeneticResult(new EngineTuningSet(population), List.copyOf(allMatches));
    }

    public record GeneticResult(EngineTuningSet population, List<MatchRunner.MatchResult> matches) {
    }

    private List<CompletedMatch> runScheduledMatches(List<ScheduledMatch> scheduledMatches,
                                                     MatchRunner.MatchOptions matchOptions,
                                                     ExecutorService executor) {
        if (scheduledMatches.isEmpty()) {
            return List.of();
        }

        if (executor == null) {
            List<CompletedMatch> results = new ArrayList<>(scheduledMatches.size());
            for (ScheduledMatch scheduled : scheduledMatches) {
                results.add(runSingleMatch(scheduled, matchOptions));
            }
            return results;
        }

        List<CompletableFuture<CompletedMatch>> futures = new ArrayList<>(scheduledMatches.size());
        for (ScheduledMatch scheduled : scheduledMatches) {
            futures.add(CompletableFuture.supplyAsync(() -> runSingleMatch(scheduled, matchOptions), executor));
        }

        List<CompletedMatch> results = new ArrayList<>(scheduledMatches.size());
        for (CompletableFuture<CompletedMatch> future : futures) {
            try {
                results.add(future.join());
            } catch (CompletionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new IllegalStateException("Failed to complete self-play match", cause);
            }
        }
        return results;
    }

    private CompletedMatch runSingleMatch(ScheduledMatch scheduledMatch, MatchRunner.MatchOptions matchOptions) {
        EngineTuning a = scheduledMatch.first();
        EngineTuning b = scheduledMatch.second();
        EngineTuning white = scheduledMatch.swapped() ? b : a;
        EngineTuning black = scheduledMatch.swapped() ? a : b;
        MatchRunner.MatchResult result = matchRunner.playMatch(white, black, matchOptions);
        return new CompletedMatch(scheduledMatch, result);
    }

    private ThreadFactory matchThreadFactory() {
        return new ThreadFactory() {
            private int counter;

            @Override
            public synchronized Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "genetic-match-" + (++counter));
                thread.setDaemon(true);
                return thread;
            }
        };
    }

    private record ScheduledMatch(EngineTuning first, EngineTuning second, boolean swapped) {
    }

    private record CompletedMatch(ScheduledMatch assignment, MatchRunner.MatchResult result) {
    }
}
