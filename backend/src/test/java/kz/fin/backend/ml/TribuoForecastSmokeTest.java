package kz.fin.backend.ml;

import kz.fin.backend.domain.EconomicKernel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tribuo.Dataset;
import org.tribuo.Model;
import org.tribuo.MutableDataset;
import org.tribuo.Prediction;
import org.tribuo.data.csv.CSVLoader;
import org.tribuo.math.optimisers.AdaGrad;
import org.tribuo.regression.RegressionFactory;
import org.tribuo.regression.Regressor;
import org.tribuo.regression.evaluation.RegressionEvaluator;
import org.tribuo.regression.sgd.linear.LinearSGDTrainer;
import org.tribuo.regression.sgd.objectives.SquaredLoss;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test-only Tribuo integration proof.
 * Data are synthetic, predictions are NOT approved facts or financial forecasts.
 * Chronological holdout (no shuffled train/test split) and information-cutoff gates.
 */
class TribuoForecastSmokeTest {
    private static final String TENANT = "FIN_SMOKE_ORG";
    private static final YearMonth FIRST = YearMonth.of(2019, 1);
    private static final YearMonth HOLDOUT = YearMonth.of(2024, 1);
    private static final LocalDate CUTOFF = LocalDate.of(2024, 1, 5);
    private static final long SEED = 1701L;

    record ObservedMonth(String tenant, YearMonth month, LocalDate knownAt, double revenue) {
        ObservedMonth {
            Objects.requireNonNull(tenant);
            Objects.requireNonNull(month);
            Objects.requireNonNull(knownAt);
            if (!Double.isFinite(revenue)) throw new IllegalArgumentException("invalid revenue");
        }
    }
    record Split(List<ObservedMonth> training, List<ObservedMonth> holdout) {}

    @TempDir Path temp;

    private List<ObservedMonth> syntheticFixture() throws Exception {
        var stream = getClass().getResourceAsStream("/ml/retail_monthly_synthetic.tsv");
        assertNotNull(stream);
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            assertEquals("month\tknownAt\trevenue_mln", reader.readLine());
            List<ObservedMonth> samples = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] values = line.split("\\t", -1);
                assertEquals(3, values.length, line);
                samples.add(new ObservedMonth(TENANT, YearMonth.parse(values[0]),
                        LocalDate.parse(values[1]), Double.parseDouble(values[2])));
            }
            return List.copyOf(samples);
        }
    }

    private static Split chronologicalSplit(
            List<ObservedMonth> source, String tenant, YearMonth holdoutFrom, LocalDate cutoff) {
        Objects.requireNonNull(source);
        if (source.isEmpty()) throw new IllegalArgumentException("empty data");
        List<ObservedMonth> ordered = new ArrayList<>(source);
        ordered.sort(Comparator.comparing(ObservedMonth::month));
        List<ObservedMonth> training = new ArrayList<>();
        List<ObservedMonth> holdout = new ArrayList<>();
        YearMonth previous = null;
        for (ObservedMonth s : ordered) {
            if (!s.tenant().equals(tenant)) throw new IllegalArgumentException("mixed tenant");
            if (previous != null && !s.month().equals(previous.plusMonths(1))) {
                throw new IllegalArgumentException("missing or duplicate observation month");
            }
            previous = s.month();
            if (s.month().isBefore(holdoutFrom)) {
                if (s.knownAt().isAfter(cutoff))
                    throw new IllegalArgumentException("future knowledge leaked into training");
                training.add(s);
            } else holdout.add(s);
        }
        if (training.isEmpty() || holdout.isEmpty())
            throw new IllegalArgumentException("empty training or holdout");
        return new Split(List.copyOf(training), List.copyOf(holdout));
    }

    // Only calendar/trend features known before prediction; no target or later ACTUAL in X.
    private static String featureCsv(List<ObservedMonth> rows) {
        StringBuilder out = new StringBuilder("trend,season_sin,season_cos,target\n");
        for (ObservedMonth item : rows) {
            int t = (int) ChronoUnit.MONTHS.between(FIRST, item.month());
            int m = item.month().getMonthValue() - 1;
            double angle = 2.0 * Math.PI * m / 12.0;
            out.append(String.format(Locale.ROOT, "%.10f,%.10f,%.10f,%.10f%n",
                    t / 100.0, Math.sin(angle), Math.cos(angle), item.revenue()));
        }
        return out.toString();
    }

    private static LinearSGDTrainer trainer() {
        // Separate trainer per run so RNG invocation count cannot affect comparison.
        return new LinearSGDTrainer(new SquaredLoss(), new AdaGrad(0.03), 450, -1, SEED);
    }

    private record Metrics(double mlMae, double seasonalMae, double tribuoReportedMae,
                           int trainRows, int holdoutRows) {}

    private Metrics trainAndEvaluate(List<ObservedMonth> records) throws Exception {
        Split split = chronologicalSplit(records, TENANT, HOLDOUT, CUTOFF);
        assertEquals(60, split.training().size());
        assertEquals(12, split.holdout().size());
        assertTrue(split.training().stream().allMatch(r -> r.month().isBefore(HOLDOUT)
                && !r.knownAt().isAfter(CUTOFF)));
        assertTrue(split.holdout().stream().allMatch(r -> !r.month().isBefore(HOLDOUT)));
        assertFalse(featureCsv(split.training()).contains("knownAt"));
        assertFalse(featureCsv(split.training()).contains("tenant"));

        Path trainFile = temp.resolve("train.csv");
        Path evalFile = temp.resolve("holdout.csv");
        Files.writeString(trainFile, featureCsv(split.training()), StandardCharsets.UTF_8);
        Files.writeString(evalFile, featureCsv(split.holdout()), StandardCharsets.UTF_8);

        var loader = new CSVLoader<Regressor>(',', new RegressionFactory());
        Dataset<Regressor> trainData = new MutableDataset<>(loader.loadDataSource(trainFile, "target"));
        Dataset<Regressor> testData = new MutableDataset<>(loader.loadDataSource(evalFile, "target"));
        assertEquals(60, trainData.size());
        assertEquals(12, testData.size());

        Model<Regressor> model = trainer().train(trainData);
        assertNotNull(model.getProvenance());

        List<Prediction<Regressor>> predictions = model.predict(testData);
        assertEquals(split.holdout().size(), predictions.size());

        Map<YearMonth, Double> actualHistory = records.stream()
                .collect(Collectors.toMap(ObservedMonth::month, ObservedMonth::revenue));
        double errorSum = 0.0, seasonalErrorSum = 0.0;
        for (int i=0; i < predictions.size(); i++) {
            double actual = split.holdout().get(i).revenue();
            double estimate = predictions.get(i).getOutput().getValues()[0];
            assertTrue(Double.isFinite(estimate), "Tribuo produced invalid forecast");
            errorSum += Math.abs(estimate - actual);
            // Seasonal naive uses only the same month of the preceding year.
            Double seasonal = actualHistory.get(split.holdout().get(i).month().minusYears(1));
            assertNotNull(seasonal);
            seasonalErrorSum += Math.abs(seasonal - actual);

            // ML results are estimates, never approved source facts.
            var candidate = EconomicKernel.EconomicValue.estimated(
                    BigDecimal.valueOf(estimate).setScale(9, RoundingMode.HALF_UP).toPlainString(),
                    "TRIBUO_SYNTHETIC_SGD_V1");
            assertEquals(EconomicKernel.ValueState.ESTIMATED, candidate.state());
        }
        double mlMae = errorSum / predictions.size();
        double naiveMae = seasonalErrorSum / predictions.size();
        var regEval = new RegressionEvaluator().evaluate(model, testData);
        double libraryMae = regEval.mae(new Regressor("DIM-0", Double.NaN));
        return new Metrics(mlMae, naiveMae, libraryMae, trainData.size(), testData.size());
    }

    @Test void trainsAndBeatsSeasonalNaiveOnUnseenSyntheticYear() throws Exception {
        List<ObservedMonth> data = syntheticFixture();
        assertEquals(72, data.size());
        Metrics m = trainAndEvaluate(data);
        assertEquals(m.mlMae(), m.tribuoReportedMae(), 0.0000001);
        assertTrue(m.mlMae() < m.seasonalMae() * 0.90,
                () -> "Synthetic ML should beat naive; ML MAE=" + m.mlMae()
                      + " baseline MAE=" + m.seasonalMae());
        System.out.printf(Locale.ROOT,
                "FIN_TRIBUO_SMOKE train=%d holdout=%d ml_mae=%.8f seasonal_naive_mae=%.8f seed=%d%n",
                m.trainRows(),m.holdoutRows(),m.mlMae(),m.seasonalMae(),SEED);
    }

    @Test void rejectsLateArrivingInformationEvenForHistoricalPeriod() throws Exception {
        var changed = new ArrayList<>(syntheticFixture());
        ObservedMonth x = changed.get(24);
        changed.set(24, new ObservedMonth(x.tenant(),x.month(),
                LocalDate.of(2025, 1, 1), x.revenue()));
        assertThrows(IllegalArgumentException.class,
                () -> chronologicalSplit(changed,TENANT,HOLDOUT,CUTOFF));
    }

    @Test void refusesCrossOrganizationTrainingData() throws Exception {
        var changed = new ArrayList<>(syntheticFixture());
        ObservedMonth x = changed.get(5);
        changed.set(5, new ObservedMonth("OTHER_ORG", x.month(), x.knownAt(), x.revenue()));
        assertThrows(IllegalArgumentException.class,
                () -> chronologicalSplit(changed,TENANT,HOLDOUT,CUTOFF));
    }

    @Test void refusesDuplicateOrMissingMonth() throws Exception {
        var changed = new ArrayList<>(syntheticFixture());
        changed.remove(4);
        assertThrows(IllegalArgumentException.class,
                () -> chronologicalSplit(changed,TENANT,HOLDOUT,CUTOFF));
        var duplicates = new ArrayList<>(syntheticFixture());
        duplicates.add(duplicates.get(4));
        assertThrows(IllegalArgumentException.class,
                () -> chronologicalSplit(duplicates,TENANT,HOLDOUT,CUTOFF));
    }
}
