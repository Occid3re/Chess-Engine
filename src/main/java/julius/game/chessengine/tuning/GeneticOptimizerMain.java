package julius.game.chessengine.tuning;

import com.fasterxml.jackson.core.JsonProcessingException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;

/**
 * Simple command line entry point that runs the {@link GeneticOptimizer} on top of
 * the YAML configuration supplied by the user.
 */
public final class GeneticOptimizerMain {

    public static void main(String[] args) throws Exception {
        CliArguments cli;
        try {
            cli = CliArguments.parse(args);
        } catch (CliArguments.CliArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println();
            printUsage();
            return;
        }

        if (cli.help) {
            printUsage();
            return;
        }

        if (cli.tuningFile == null) {
            System.err.println("Error: --tunings=<file> must be provided");
            System.err.println();
            printUsage();
            return;
        }

        Path tuningFile = cli.tuningFile.toAbsolutePath().normalize();
        if (!Files.exists(tuningFile)) {
            System.err.printf(Locale.ROOT, "Tuning file %s does not exist.%n", tuningFile);
            return;
        }

        EngineTuningSet seedPopulation;
        try {
            seedPopulation = EngineTuningLoader.load(tuningFile);
        } catch (IOException e) {
            System.err.printf(Locale.ROOT, "Failed to read %s: %s%n", tuningFile, e.getMessage());
            return;
        }

        if (seedPopulation.isEmpty()) {
            System.err.printf(Locale.ROOT, "Seed population in %s was empty.%n", tuningFile);
            return;
        }

        GeneticOptions defaults = GeneticOptions.defaults();
        GeneticOptions options = new GeneticOptions(
                cli.generations.orElse(defaults.generations()),
                cli.retainCount.orElse(defaults.retainCount()),
                cli.populationSize.orElse(defaults.populationSize()),
                cli.matchesPerPair.orElse(defaults.matchesPerPair()),
                cli.mutationStrength.orElse(defaults.mutationStrength()),
                cli.moveTimeMillis.orElse(defaults.moveTimeMillis()),
                cli.maxPlies.orElse(defaults.maxPlies())
        );

        Random random = cli.randomSeed.map(Random::new).orElseGet(Random::new);

        System.out.printf(Locale.ROOT,
                "Running genetic tuning with %d generations, population %d (retaining %d), %d matches per pair.%n",
                options.generations(), options.populationSize(), options.retainCount(), options.matchesPerPair());
        System.out.printf(Locale.ROOT,
                "Each game lasts up to %d plies with a move time of %d ms. Mutation strength: %.2f%n",
                options.maxPlies(), options.moveTimeMillis(), options.mutationStrength());

        GeneticOptimizer optimizer = new GeneticOptimizer(new MatchRunner(), random);
        GeneticOptimizer.GeneticResult result = optimizer.evolve(seedPopulation, options);

        List<ScoreEntry> leaderboard = buildLeaderboard(result.matches());
        if (!leaderboard.isEmpty()) {
            System.out.println();
            System.out.println("Leaderboard:");
            leaderboard.stream()
                    .limit(10)
                    .forEach(entry -> System.out.printf(Locale.ROOT,
                            "  %-32s %6.2f pts across %d games%n",
                            entry.name(), entry.points(), entry.games()));
        }

        System.out.println();
        System.out.printf(Locale.ROOT, "Generated %d configurations in the final population.%n",
                result.population().population().size());

        if (cli.outputFile.isPresent()) {
            Path output = cli.outputFile.get().toAbsolutePath().normalize();
            try {
                EngineTuningWriter.write(output, result.population());
                System.out.printf(Locale.ROOT, "Saved final population to %s%n", output);
            } catch (IOException e) {
                System.err.printf(Locale.ROOT, "Failed to write %s: %s%n", output, e.getMessage());
            }
        } else {
            try {
                System.out.println();
                System.out.println("Final population (YAML):");
                System.out.println(EngineTuningWriter.toYaml(result.population()));
            } catch (JsonProcessingException e) {
                System.err.printf(Locale.ROOT, "Failed to serialise population: %s%n", e.getMessage());
            }
        }
    }

    private static List<ScoreEntry> buildLeaderboard(List<MatchRunner.MatchResult> matches) {
        Map<String, ScoreAccumulator> scores = new HashMap<>();
        for (MatchRunner.MatchResult match : matches) {
            scores.computeIfAbsent(match.whiteTuning().name(), ScoreAccumulator::new)
                    .record(match.whiteScore());
            scores.computeIfAbsent(match.blackTuning().name(), ScoreAccumulator::new)
                    .record(match.blackScore());
        }
        List<ScoreEntry> entries = new ArrayList<>(scores.size());
        for (ScoreAccumulator acc : scores.values()) {
            entries.add(new ScoreEntry(acc.name, acc.points, acc.games));
        }
        entries.sort(Comparator.comparingDouble(ScoreEntry::points).reversed());
        return entries;
    }

    private static void printUsage() {
        System.out.println("Usage: java -cp <jar> " + GeneticOptimizerMain.class.getName() + " --tunings=<file> [options]\n"
                + "Options:\n"
                + "  --tunings=<file>        YAML file describing the seed population (required).\n"
                + "  --output=<file>         Destination for the resulting population. If omitted,\n"
                + "                         the YAML is printed to stdout.\n"
                + "  --generations=<n>       Number of generations to evolve (default: 5).\n"
                + "  --retain=<n>            How many configurations survive each generation (default: 4).\n"
                + "  --population=<n>        Target population size per generation (default: 8).\n"
                + "  --matches=<n>           Matches played per pairing (default: 2).\n"
                + "  --mutation=<f>          Mutation strength between 0.0 and 1.0 (default: 0.15).\n"
                + "  --move-time=<ms>        Milliseconds allocated per move (default: 50).\n"
                + "  --max-plies=<n>         Maximum plies per game (default: 512).\n"
                + "  --seed=<n>              Seed for the random generator (optional).\n"
                + "  --help                  Display this message.\n");
    }

    private record ScoreEntry(String name, double points, int games) {
    }

    private static final class ScoreAccumulator {
        private final String name;
        private double points = 0.0;
        private int games = 0;

        private ScoreAccumulator(String name) {
            this.name = name;
        }

        private void record(double score) {
            points += score;
            games++;
        }
    }

    private static final class CliArguments {
        private final Path tuningFile;
        private final Optional<Path> outputFile;
        private final Optional<Integer> generations;
        private final Optional<Integer> retainCount;
        private final Optional<Integer> populationSize;
        private final Optional<Integer> matchesPerPair;
        private final Optional<Double> mutationStrength;
        private final Optional<Long> moveTimeMillis;
        private final Optional<Integer> maxPlies;
        private final Optional<Long> randomSeed;
        private final boolean help;

        private CliArguments(Path tuningFile,
                             Optional<Path> outputFile,
                             Optional<Integer> generations,
                             Optional<Integer> retainCount,
                             Optional<Integer> populationSize,
                             Optional<Integer> matchesPerPair,
                             Optional<Double> mutationStrength,
                             Optional<Long> moveTimeMillis,
                             Optional<Integer> maxPlies,
                             Optional<Long> randomSeed,
                             boolean help) {
            this.tuningFile = tuningFile;
            this.outputFile = outputFile;
            this.generations = generations;
            this.retainCount = retainCount;
            this.populationSize = populationSize;
            this.matchesPerPair = matchesPerPair;
            this.mutationStrength = mutationStrength;
            this.moveTimeMillis = moveTimeMillis;
            this.maxPlies = maxPlies;
            this.randomSeed = randomSeed;
            this.help = help;
        }

        private static CliArguments parse(String[] args) {
            Path tuningFile = null;
            Optional<Path> outputFile = Optional.empty();
            Optional<Integer> generations = Optional.empty();
            Optional<Integer> retainCount = Optional.empty();
            Optional<Integer> populationSize = Optional.empty();
            Optional<Integer> matchesPerPair = Optional.empty();
            Optional<Double> mutationStrength = Optional.empty();
            Optional<Long> moveTimeMillis = Optional.empty();
            Optional<Integer> maxPlies = Optional.empty();
            Optional<Long> randomSeed = Optional.empty();
            boolean help = false;

            for (String arg : args) {
                if (arg == null || arg.isBlank()) {
                    continue;
                }
                String trimmed = arg.trim();
                if ("--help".equals(trimmed) || "-h".equals(trimmed)) {
                    help = true;
                    continue;
                }
                if (!trimmed.startsWith("--")) {
                    throw new CliArgumentException("Unrecognised argument: " + trimmed);
                }
                int idx = trimmed.indexOf('=');
                if (idx < 0) {
                    throw new CliArgumentException("Missing value for argument " + trimmed);
                }
                String key = trimmed.substring(2, idx);
                String value = trimmed.substring(idx + 1);
                if (value.isBlank()) {
                    throw new CliArgumentException("Missing value for argument --" + key);
                }
                switch (key) {
                    case "tunings" -> tuningFile = Path.of(value);
                    case "output" -> outputFile = Optional.of(Path.of(value));
                    case "generations" -> generations = Optional.of(parseInt(value, "generations"));
                    case "retain" -> retainCount = Optional.of(parseInt(value, "retain"));
                    case "population" -> populationSize = Optional.of(parseInt(value, "population"));
                    case "matches" -> matchesPerPair = Optional.of(parseInt(value, "matches"));
                    case "mutation" -> mutationStrength = Optional.of(parseDouble(value, "mutation"));
                    case "move-time" -> moveTimeMillis = Optional.of(parseLong(value, "move-time"));
                    case "max-plies" -> maxPlies = Optional.of(parseInt(value, "max-plies"));
                    case "seed" -> randomSeed = Optional.of(parseLong(value, "seed"));
                    default -> throw new CliArgumentException("Unknown option --" + key);
                }
            }

            return new CliArguments(tuningFile, outputFile, generations, retainCount, populationSize,
                    matchesPerPair, mutationStrength, moveTimeMillis, maxPlies, randomSeed, help);
        }

        private static int parseInt(String value, String option) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new CliArgumentException("Option --" + option + " requires an integer value");
            }
        }

        private static long parseLong(String value, String option) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                throw new CliArgumentException("Option --" + option + " requires a long value");
            }
        }

        private static double parseDouble(String value, String option) {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException e) {
                throw new CliArgumentException("Option --" + option + " requires a floating point value");
            }
        }

        private static final class CliArgumentException extends RuntimeException {
            private CliArgumentException(String message) {
                super(Objects.requireNonNull(message, "message"));
            }
        }
    }
}

