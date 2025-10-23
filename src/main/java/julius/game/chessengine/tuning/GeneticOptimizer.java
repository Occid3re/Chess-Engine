package julius.game.chessengine.tuning;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.stream.Collectors;

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

        FriendlyNameGenerator names = new FriendlyNameGenerator(random);
        List<EngineTuning> population = new ArrayList<>(seedPopulation.population().size());
        for (EngineTuning tuning : seedPopulation.population()) {
            EngineTuning participant = tuning;
            if (names.isBoringName(participant.name())) {
                participant = participant.rename(names.next());
            } else {
                names.registerName(participant.name());
            }
            population.add(participant);
        }

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
            population.add(parent.mutate(random, options.mutationStrength()).rename(names.next()));
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
                MatchStatisticsCollector stats = new MatchStatisticsCollector();
                stats.registerParticipants(population);
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
                    EngineTuning white = completed.assignment().swapped() ? b : a;
                    EngineTuning black = completed.assignment().swapped() ? a : b;
                    MatchRunner.MatchResult result = completed.result();
                    stats.recordMatch(white, black, result);
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

                List<MatchStatisticsCollector.ScoreCard> leaderboard = stats.leaderboard();

                if (log.isInfoEnabled()) {
                    String decisive = String.format(Locale.ROOT, "%.1f%%", stats.decisiveRate());
                    log.info("Generation {} summary: {} games | decisive {} | white wins {} | black wins {} | draws {} | avg plies {:.1f} (min {} max {})",
                            generationIndex,
                            stats.totalMatches(),
                            decisive,
                            stats.whiteWins(),
                            stats.blackWins(),
                            stats.draws(),
                            stats.averagePlies(),
                            stats.minPlies(),
                            stats.maxPlies());
                    log.info("Generation {} standings:\n{}", generationIndex, stats.formatTable(8));
                }

                List<EngineTuning> retained = leaderboard.stream()
                        .limit(Math.max(options.retainCount(), 2))
                        .map(MatchStatisticsCollector.ScoreCard::tuning)
                        .collect(Collectors.toCollection(ArrayList::new));

                population = new ArrayList<>(retained);
                int retainedCount = population.size();

                while (population.size() < options.populationSize()) {
                    EngineTuning parent = population.get(random.nextInt(population.size()));
                    EngineTuning offspring = parent.mutate(random, options.mutationStrength())
                            .rename(names.next());
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

    private static final class FriendlyNameGenerator {
        private static final String UUID_PATTERN = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";
        private static final String[] ADJECTIVES = {
                "Agile", "Alert", "Ambitious", "Arcane", "Artful", "Astute", "Balanced", "Blazing", "Bold",
                "Brilliant", "Brisk", "Buoyant", "Clever", "Cool", "Crafty", "Curious", "Cunning", "Daring",
                "Dazzling", "Defiant", "Determined", "Dynamic", "Electric", "Elegant", "Elusive", "Enigmatic",
                "Feral", "Feisty", "Fiery", "Focused", "Gallant", "Gentle", "Gifted", "Glorious", "Graceful",
                "Gritty", "Heroic", "Hyper", "Impetuous", "Ingenious", "Intrepid", "Inventive", "Jazzy",
                "Joyful", "Keen", "Lively", "Lucid", "Majestic", "Mercurial", "Mighty", "Mischievous", "Nimble",
                "Nocturnal", "Noble", "Oddball", "Patient", "Persistent", "Playful", "Proud", "Quick", "Quiet",
                "Quirky", "Radiant", "Raging", "Rapid", "Rebellious", "Resolute", "Ruthless", "Sassy", "Savvy", "Serene",
                "Sharp", "Silent", "Slick", "Smart", "Sneaky", "Spirited", "Steady", "Stormy", "Strong",
                "Tenacious", "Tactical", "Tireless", "Tricky", "Unruly", "Valiant", "Vibrant", "Vigilant",
                "Vivid", "Wild", "Whimsical", "Wily", "Wise", "Xenial", "Yearning", "Young", "Zealous", "Zesty",
                "Stoic", "Infernal", "Shrewd", "Unstoppable", "Fearless", "Temporal", "Spectral", "Unnatural", "Mysterious"
        };

        private static final String[] CREATURES = {
                "Albatross", "Alien", "Antelope", "Ape", "Badger", "Basilisk", "Bat", "Bear", "Beetle", "Bison",
                "Boar", "Bull", "Butterfly", "Cat", "Cobra", "Coyote", "Crane", "Crow", "Deer", "Demon",
                "Dog", "Dolphin", "Dragon", "Eagle", "Elephant", "Falcon", "Ferret", "Fox", "Frog", "Gazelle",
                "Gecko", "Giraffe", "Golem", "Gorilla", "Griffin", "Hawk", "Hedgehog", "Hound", "Hydra",
                "Ibis", "Iguana", "Jackal", "Jaguar", "Kangaroo", "Kraken", "Leopard", "Lion", "Lynx",
                "Mammoth", "Mantis", "Minotaur", "Mole", "Monkey", "Mongoose", "Narwhal", "Nighthawk", "Octopus",
                "Orca", "Otter", "Owl", "Panther", "Pegasus", "Phoenix", "Piranha", "Puffin", "Python",
                "Raccoon", "Ram", "Raven", "Rhino", "Salamander", "Scorpion", "Seahorse", "Shark", "Sloth",
                "Sphinx", "Spider", "Squid", "Stag", "Stoat", "Swan", "Tiger", "Tortoise", "Unicorn",
                "Viper", "Walrus", "Wolf", "Wolverine", "Wyvern", "Yak", "Yeti", "Zebra", "Zergling",
                "Basilisk", "Cerberus", "Manticore", "Chimera", "Centaur", "Djinn", "Leviathan", "Hippogriff"
        };

        private static final String[] CHESS_TERMS = {
                "Attack", "Battery", "Bind", "Blockade", "Blunder", "Break", "Bridge", "Cage", "Capture",
                "Center", "Chain", "Clamp", "Clearance", "Combination", "Counterplay", "Deflection", "Discovered",
                "Dominance", "Endgame", "Exchange", "Fianchetto", "File", "Flank", "Flurry", "Fork", "Frontline",
                "Gambit", "Grip", "Harmony", "Helm", "Initiative", "Interference", "Jail", "KingHunt", "KnightFork",
                "Lifeline", "Line", "Lockdown", "Luft", "Maneuver", "MatingNet", "MinorityAttack", "Midden",
                "Opposition", "Outpost", "Overload", "Pincer", "Pin", "PoisonedPawn", "Pressure", "Probe", "Push",
                "QueenTrap", "Raze", "Recapture", "Relay", "Repetition", "Sac", "Scythe", "Shadow", "Shatter",
                "Shield", "Simplification", "Skewer", "Smother", "Snowplow", "SpaceGain", "Squeeze", "Storm",
                "Structure", "Study", "Sunder", "Swindle", "Switch", "Tempo", "Thrust", "Trap", "Transition",
                "Triangulation", "Tsunami", "Twilight", "Underpromotion", "Undermining", "Vault", "Vector",
                "Weakness", "Windmill", "Zigzag", "Zug", "Zugzwang", "Zwiebel", "Infiltration", "Counterattack",
                "Initiation", "Anchor", "Carapace", "Throne", "Formation", "Retreat", "Defence", "TempoGain"
        };

        private final Random random;
        private final Set<String> usedNames = new HashSet<>();

        private FriendlyNameGenerator(Random random) {
            this.random = Objects.requireNonNull(random, "random");
        }

        boolean isBoringName(String name) {
            if (name == null || name.isBlank()) {
                return true;
            }
            if (name.endsWith("_mut")) {
                return true;
            }
            return name.matches(UUID_PATTERN);
        }

        void registerName(String name) {
            if (name != null && !name.isBlank()) {
                usedNames.add(name);
            }
        }

        String next() {
            for (int attempt = 0; attempt < 64; attempt++) {
                String candidate = buildCandidate(false);
                if (usedNames.add(candidate)) {
                    return candidate;
                }
            }
            for (int attempt = 0; attempt < 128; attempt++) {
                String candidate = buildCandidate(true);
                if (usedNames.add(candidate)) {
                    return candidate;
                }
            }
            String fallback = String.format(Locale.ROOT, "Tuning-%04d", random.nextInt(10_000));
            if (usedNames.add(fallback)) {
                return fallback;
            }
            return fallback + "-" + Integer.toHexString(random.nextInt()).toUpperCase(Locale.ROOT);
        }

        private String buildCandidate(boolean allowSuffix) {
            String adjective = ADJECTIVES[random.nextInt(ADJECTIVES.length)];
            String creature = CREATURES[random.nextInt(CREATURES.length)];
            String base = adjective + "-" + creature;
            if (random.nextDouble() < 0.55) {
                String tactic = CHESS_TERMS[random.nextInt(CHESS_TERMS.length)];
                base = base + "-" + tactic;
            }
            if (allowSuffix) {
                int suffix = 10 + random.nextInt(90);
                base = base + "-" + suffix;
            }
            return base;
        }
    }
}
