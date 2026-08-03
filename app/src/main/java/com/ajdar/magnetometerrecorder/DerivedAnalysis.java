package com.ajdar.magnetometerrecorder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class DerivedAnalysis {
    public static final double PRESSURE_FS = 50.0;
    public static final double PRESSURE_WINDOW_S = 10.0;
    public static final double PRESSURE_STEP_S = 1.0;
    public static final double HR_MIN_BPM = 35.0;
    public static final double HR_MAX_BPM = 180.0;
    public static final double RESP_FS = 25.0;
    public static final double RESP_LO_HZ = 0.20;
    public static final double RESP_HI_HZ = 0.22;
    public static final String PRESSURE_SELECTOR_KEY = "mobile_pressure_events_cs6_ts10_cf0p08_sm5";
    public static final String RESP_SELECTOR_KEY = "mobile_mag_by_sign-1_bp0p20-0p22_peak_count";

    private DerivedAnalysis() {}

    public static final class PressurePoint {
        public final double t;
        public final double hpa;

        public PressurePoint(double t, double hpa) {
            this.t = t;
            this.hpa = hpa;
        }
    }

    public static final class MagPoint {
        public final double t;
        public final double bx;
        public final double by;
        public final double bz;
        public final double babs;

        public MagPoint(double t, double bx, double by, double bz, double babs) {
            this.t = t;
            this.bx = bx;
            this.by = by;
            this.bz = bz;
            this.babs = babs;
        }
    }

    public static final class HrWindow {
        public final double centerS;
        public final double startS;
        public final double endS;
        public final double rawHrBpm;
        public double finalHrBpm;
        public final double peakHrBpm;
        public final double acfHrBpm;
        public final double spectralHrBpm;
        public final double confidence;
        public double pressureSqi = Double.NaN;
        public double hrAfterSqiBpm = Double.NaN;
        public String pressureReliability = "missing";
        public final String dominantFamily;
        public final String selectorReason;

        HrWindow(
                double centerS,
                double startS,
                double endS,
                double rawHrBpm,
                double peakHrBpm,
                double acfHrBpm,
                double spectralHrBpm,
                double confidence,
                String dominantFamily,
                String selectorReason
        ) {
            this.centerS = centerS;
            this.startS = startS;
            this.endS = endS;
            this.rawHrBpm = rawHrBpm;
            this.finalHrBpm = rawHrBpm;
            this.peakHrBpm = peakHrBpm;
            this.acfHrBpm = acfHrBpm;
            this.spectralHrBpm = spectralHrBpm;
            this.confidence = confidence;
            this.dominantFamily = dominantFamily;
            this.selectorReason = selectorReason;
        }
    }

    public static final class RespWindow {
        public final double centerS;
        public final double startS;
        public final double endS;
        public final double respirationRateBpm;
        public final double breathCountRateBpm;
        public final int breathCount;
        public final double invalidFraction;

        RespWindow(
                double centerS,
                double startS,
                double endS,
                double respirationRateBpm,
                double breathCountRateBpm,
                int breathCount,
                double invalidFraction
        ) {
            this.centerS = centerS;
            this.startS = startS;
            this.endS = endS;
            this.respirationRateBpm = respirationRateBpm;
            this.breathCountRateBpm = breathCountRateBpm;
            this.breathCount = breathCount;
            this.invalidFraction = invalidFraction;
        }
    }

    public static final class AnalysisResult {
        public String recordingName = "recording";
        public final ArrayList<HrWindow> pressureHr = new ArrayList<>();
        public final ArrayList<RespWindow> respiration = new ArrayList<>();
        public final ArrayList<RespWindow> respirationMinuteBins = new ArrayList<>();
        public final ArrayList<Double> pressurePeakTimes = new ArrayList<>();
        public final ArrayList<Double> respirationPeakTimes = new ArrayList<>();
        public double pressureMedianHrBpm = Double.NaN;
        public double respirationMedianBpm = Double.NaN;
        public double pressureNativeFs = Double.NaN;
        public double magNativeFs = Double.NaN;
        public double pressureCoverage = 0.0;
        public String pressureMessage = "Pressure HR not computed.";
        public String respirationMessage = "Respiration not computed.";
        public double[] pressureWaveTime = new double[0];
        public double[] pressureWave = new double[0];
        public double[] respirationWaveTime = new double[0];
        public double[] respirationWave = new double[0];
    }

    private static final class Grid {
        final double[] t;
        final double[] x;
        final double nativeFs;

        Grid(double[] t, double[] x, double nativeFs) {
            this.t = t;
            this.x = x;
            this.nativeFs = nativeFs;
        }
    }

    private static final class Estimate {
        final double bpm;
        final double confidence;

        Estimate(double bpm, double confidence) {
            this.bpm = bpm;
            this.confidence = confidence;
        }
    }

    private static final class PeakDetection {
        final Estimate estimate;
        final int[] peaks;
        final boolean inverted;

        PeakDetection(Estimate estimate, int[] peaks, boolean inverted) {
            this.estimate = estimate;
            this.peaks = peaks;
            this.inverted = inverted;
        }
    }

    public static AnalysisResult analyze(String recordingName, List<PressurePoint> pressure, List<MagPoint> mag) {
        AnalysisResult result = new AnalysisResult();
        result.recordingName = recordingName == null || recordingName.trim().isEmpty()
                ? "recording"
                : recordingName.trim();
        analyzePressure(pressure, result);
        analyzeRespiration(mag, result);
        return result;
    }

    private static void analyzePressure(List<PressurePoint> input, AnalysisResult result) {
        if (input == null || input.size() < 20) {
            result.pressureMessage = "Pressure HR needs pressure samples from a phone barometer.";
            return;
        }
        Grid grid = resamplePressure(input, PRESSURE_FS);
        result.pressureNativeFs = grid.nativeFs;
        if (grid.t.length < (int) (PRESSURE_WINDOW_S * PRESSURE_FS)) {
            result.pressureMessage = "Pressure HR needs at least 10 seconds of pressure data.";
            return;
        }

        double[][] featureBank = new double[][] {
                robustZ(filtFiltBandpass(grid.x, PRESSURE_FS, 0.5, 3.5)),
                robustZ(filtFiltBandpass(grid.x, PRESSURE_FS, 0.8, 5.0)),
                robustZ(filtFiltBandpass(grid.x, PRESSURE_FS, 1.0, 5.0))
        };
        addPressurePeakVisualization(grid, featureBank, result);

        ArrayList<Double> raw = new ArrayList<>();
        for (double center = grid.t[0] + 0.5 * PRESSURE_WINDOW_S;
             center <= grid.t[grid.t.length - 1] - 0.5 * PRESSURE_WINDOW_S + 1e-9;
             center += PRESSURE_STEP_S) {
            int left = lowerBound(grid.t, center - 0.5 * PRESSURE_WINDOW_S);
            int right = upperBound(grid.t, center + 0.5 * PRESSURE_WINDOW_S);
            if (right - left < (int) (4.0 * PRESSURE_FS)) continue;

            ArrayList<Estimate> peakCandidates = new ArrayList<>();
            ArrayList<Estimate> acfCandidates = new ArrayList<>();
            ArrayList<Estimate> spectralCandidates = new ArrayList<>();
            for (double[] feature : featureBank) {
                double[] segment = Arrays.copyOfRange(feature, left, right);
                segment = detrend(segment);
                peakCandidates.add(peakHr(segment, PRESSURE_FS));
                acfCandidates.add(acfHr(segment, PRESSURE_FS));
                spectralCandidates.add(spectralHr(segment, PRESSURE_FS));
            }

            Estimate peak = aggregate(peakCandidates, 8.0);
            Estimate acf = aggregate(acfCandidates, 10.0);
            Estimate spectral = aggregate(spectralCandidates, 10.0);
            Estimate fused = fusePressureFamilies(peak, acf, spectral);
            String dominant = dominantFamily(peak, acf, spectral);
            String reason = String.format(Locale.US,
                    "%s dominated; peak %.1f, acf %.1f, spectral %.1f bpm",
                    dominant,
                    peak.bpm,
                    acf.bpm,
                    spectral.bpm);
            HrWindow window = new HrWindow(
                    center,
                    center - 0.5 * PRESSURE_WINDOW_S,
                    center + 0.5 * PRESSURE_WINDOW_S,
                    fused.bpm,
                    peak.bpm,
                    acf.bpm,
                    spectral.bpm,
                    fused.confidence,
                    dominant,
                    reason);
            result.pressureHr.add(window);
            raw.add(fused.bpm);
        }

        double[] smoothed = rollingMedian(toPrimitive(raw), 5);
        int valid = 0;
        int reliableAfterSqi = 0;
        int unreliableAbove85 = 0;
        ArrayList<Double> finals = new ArrayList<>();
        for (int i = 0; i < result.pressureHr.size(); i++) {
            HrWindow w = result.pressureHr.get(i);
            w.finalHrBpm = smoothed.length > i ? smoothed[i] : w.rawHrBpm;
            w.pressureSqi = pressureSqi(w.finalHrBpm, w.confidence, w.peakHrBpm, w.acfHrBpm, w.spectralHrBpm);
            w.pressureReliability = pressureReliability(w.finalHrBpm, w.pressureSqi);
            w.hrAfterSqiBpm = "reliable".equals(w.pressureReliability) ? w.finalHrBpm : Double.NaN;
            if (isFinite(w.finalHrBpm)) {
                valid++;
                finals.add(w.finalHrBpm);
            }
            if (isFinite(w.hrAfterSqiBpm)) reliableAfterSqi++;
            if ("unreliable_above_85_bpm".equals(w.pressureReliability)) unreliableAbove85++;
        }
        result.pressureCoverage = result.pressureHr.isEmpty() ? 0.0 : (double) valid / result.pressureHr.size();
        result.pressureMedianHrBpm = medianList(finals);
        if (valid > 0) {
            String highNote = unreliableAbove85 > 0
                    ? String.format(Locale.US, " %d windows are marked unreliable because pressure HR is above 85 bpm.", unreliableAbove85)
                    : "";
            result.pressureMessage = String.format(Locale.US,
                    "Pressure HR computed: median %.1f bpm across %d windows; %d pass SQI.",
                    result.pressureMedianHrBpm,
                    valid,
                    reliableAfterSqi) + highNote;
        } else {
            result.pressureMessage = "Pressure HR could not find stable cardiac-rate windows.";
        }
    }

    private static double pressureSqi(double hrBpm, double confidence, double peakHrBpm, double acfHrBpm, double spectralHrBpm) {
        if (!isFinite(hrBpm)) return 0.0;
        double agreement = pressureFamilyAgreement(hrBpm, peakHrBpm, acfHrBpm, spectralHrBpm);
        double highRatePenalty = hrBpm > 85.0 ? Math.exp(-(hrBpm - 85.0) / 12.0) : 1.0;
        return clamp((0.60 * clamp(confidence, 0.0, 1.0) + 0.40 * agreement) * highRatePenalty, 0.0, 1.0);
    }

    private static double pressureFamilyAgreement(double hrBpm, double... candidates) {
        double sum = 0.0;
        int count = 0;
        for (double candidate : candidates) {
            if (isFinite(candidate)) {
                sum += Math.exp(-Math.abs(candidate - hrBpm) / 10.0);
                count++;
            }
        }
        return count > 0 ? clamp(sum / count, 0.0, 1.0) : 0.0;
    }

    private static String pressureReliability(double hrBpm, double sqi) {
        if (!isFinite(hrBpm)) return "missing";
        if (hrBpm > 85.0) return "unreliable_above_85_bpm";
        if (sqi < 0.35) return "unreliable_low_sqi";
        return "reliable";
    }

    private static void analyzeRespiration(List<MagPoint> input, AnalysisResult result) {
        if (input == null || input.size() < 20) {
            result.respirationMessage = "Respiration needs magnetometer samples.";
            return;
        }
        Grid grid = resampleMagBy(input, RESP_FS);
        result.magNativeFs = grid.nativeFs;
        if (grid.t.length < (int) (15.0 * RESP_FS)) {
            result.respirationMessage = "Respiration needs at least 15 seconds of magnetometer data.";
            return;
        }

        double[] signedBy = new double[grid.x.length];
        for (int i = 0; i < signedBy.length; i++) signedBy[i] = -grid.x[i];
        double[] wave = robustZ(filtFiltBandpass(signedBy, RESP_FS, RESP_LO_HZ, RESP_HI_HZ));
        result.respirationWaveTime = grid.t;
        result.respirationWave = wave;

        int[] peaks = detectPeaks(wave, (int) Math.round(0.80 * RESP_FS), 0.20);
        for (int p : peaks) result.respirationPeakTimes.add(grid.t[p]);

        double[][] inst = instantRatesFromPeaks(peaks, grid.t, 6.0, 30.0);
        double[] rateTimes = inst[0];
        double[] rates = inst[1];
        if (rates.length == 0) {
            result.respirationMessage = "Respiration could not find plausible breath peaks.";
            return;
        }
        result.respirationMedianBpm = median(rates);

        double totalEnd = grid.t[grid.t.length - 1];
        double plotWindow = totalEnd >= 40.0 ? 30.0 : Math.max(15.0, totalEnd * 0.75);
        double plotStep = totalEnd >= 40.0 ? 5.0 : 2.0;
        for (double center = grid.t[0] + 0.5 * plotWindow;
             center <= totalEnd - 0.5 * plotWindow + 1e-9;
             center += plotStep) {
            double start = center - 0.5 * plotWindow;
            double end = center + 0.5 * plotWindow;
            double value = medianRatesInWindow(rateTimes, rates, start, end);
            int count = countPeaks(peaks, grid.t, start, end);
            result.respiration.add(new RespWindow(
                    center,
                    start,
                    end,
                    value,
                    count * 60.0 / Math.max(1e-9, end - start),
                    count,
                    invalidFraction(peaks, grid.t, start, end, 10.0)));
        }

        if (result.respiration.isEmpty()) {
            result.respiration.add(new RespWindow(
                    0.5 * totalEnd,
                    0.0,
                    totalEnd,
                    result.respirationMedianBpm,
                    peaks.length * 60.0 / Math.max(1e-9, totalEnd),
                    peaks.length,
                    invalidFraction(peaks, grid.t, 0.0, totalEnd, 10.0)));
        }

        for (double start = 0.0; start < totalEnd; start += 60.0) {
            double end = Math.min(start + 60.0, totalEnd);
            int count = countPeaks(peaks, grid.t, start, end);
            double inv = invalidFraction(peaks, grid.t, start, end, 10.0);
            double rate = medianRatesInWindow(rateTimes, rates, start, end);
            double countRate = count * 60.0 / Math.max(1e-9, end - start);
            if (inv > 0.50) {
                rate = Double.NaN;
                countRate = Double.NaN;
            }
            result.respirationMinuteBins.add(new RespWindow(
                    0.5 * (start + end),
                    start,
                    end,
                    rate,
                    countRate,
                    count,
                    inv));
        }

        result.respirationMessage = String.format(Locale.US,
                "Respiration computed from magnetometer By: median %.1f breaths/min, %d detected breaths.",
                result.respirationMedianBpm,
                peaks.length);
    }

    private static Grid resamplePressure(List<PressurePoint> points, double fs) {
        ArrayList<PressurePoint> clean = new ArrayList<>();
        for (PressurePoint p : points) {
            if (p != null && isFinite(p.t) && isFinite(p.hpa)) clean.add(p);
        }
        Collections.sort(clean, Comparator.comparingDouble(p -> p.t));
        ArrayList<Double> t = new ArrayList<>();
        ArrayList<Double> x = new ArrayList<>();
        double last = Double.NEGATIVE_INFINITY;
        for (PressurePoint p : clean) {
            if (p.t > last) {
                t.add(p.t);
                x.add(p.hpa);
                last = p.t;
            }
        }
        return resample(toPrimitive(t), toPrimitive(x), fs);
    }

    private static Grid resampleMagBy(List<MagPoint> points, double fs) {
        ArrayList<MagPoint> clean = new ArrayList<>();
        for (MagPoint p : points) {
            if (p != null && isFinite(p.t) && isFinite(p.by)) clean.add(p);
        }
        Collections.sort(clean, Comparator.comparingDouble(p -> p.t));
        ArrayList<Double> t = new ArrayList<>();
        ArrayList<Double> x = new ArrayList<>();
        double last = Double.NEGATIVE_INFINITY;
        for (MagPoint p : clean) {
            if (p.t > last) {
                t.add(p.t);
                x.add(p.by);
                last = p.t;
            }
        }
        return resample(toPrimitive(t), toPrimitive(x), fs);
    }

    private static Grid resample(double[] t, double[] x, double fs) {
        if (t.length < 2) return new Grid(new double[0], new double[0], Double.NaN);
        double nativeFs = estimateFs(t);
        double start = t[0];
        double end = t[t.length - 1];
        int n = Math.max(0, (int) Math.floor((end - start) * fs) + 1);
        double[] gt = new double[n];
        double[] gx = new double[n];
        int j = 0;
        for (int i = 0; i < n; i++) {
            double ti = start + i / fs;
            gt[i] = ti;
            while (j + 1 < t.length && t[j + 1] < ti) j++;
            if (j + 1 >= t.length) {
                gx[i] = x[x.length - 1];
            } else {
                double den = t[j + 1] - t[j];
                double a = den > 0 ? (ti - t[j]) / den : 0.0;
                gx[i] = x[j] + a * (x[j + 1] - x[j]);
            }
        }
        return new Grid(gt, gx, nativeFs);
    }

    private static double estimateFs(double[] t) {
        if (t.length < 3) return Double.NaN;
        double[] d = new double[t.length - 1];
        int n = 0;
        for (int i = 1; i < t.length; i++) {
            double v = t[i] - t[i - 1];
            if (v > 0 && isFinite(v)) d[n++] = v;
        }
        if (n == 0) return Double.NaN;
        return 1.0 / median(Arrays.copyOf(d, n));
    }

    private static Estimate peakHr(double[] segment, double fs) {
        return peakDetection(segment, fs).estimate;
    }

    private static PeakDetection peakDetection(double[] segment, double fs) {
        double[] z = robustZ(segment);
        int minDistance = Math.max(1, (int) Math.round(0.38 * fs));
        int[] positive = detectPeaks(z, minDistance, 0.15);
        double[] neg = new double[z.length];
        for (int i = 0; i < z.length; i++) neg[i] = -z[i];
        int[] negative = detectPeaks(neg, minDistance, 0.15);
        Estimate p = peaksToHr(positive, fs);
        Estimate n = peaksToHr(negative, fs);
        return n.confidence > p.confidence
                ? new PeakDetection(n, negative, true)
                : new PeakDetection(p, positive, false);
    }

    private static void addPressurePeakVisualization(Grid grid, double[][] featureBank, AnalysisResult result) {
        PeakDetection best = null;
        double[] bestFeature = null;
        for (double[] feature : featureBank) {
            PeakDetection candidate = peakDetection(feature, PRESSURE_FS);
            if (best == null || candidate.estimate.confidence > best.estimate.confidence) {
                best = candidate;
                bestFeature = feature;
            }
        }
        if (best == null || bestFeature == null) return;
        result.pressureWaveTime = Arrays.copyOf(grid.t, grid.t.length);
        result.pressureWave = Arrays.copyOf(bestFeature, bestFeature.length);
        if (best.inverted) {
            for (int i = 0; i < result.pressureWave.length; i++) {
                result.pressureWave[i] = -result.pressureWave[i];
            }
        }
        for (int peak : best.peaks) {
            if (peak >= 0 && peak < grid.t.length) result.pressurePeakTimes.add(grid.t[peak]);
        }
    }

    private static Estimate peaksToHr(int[] peaks, double fs) {
        if (peaks.length < 4) return new Estimate(Double.NaN, 0.0);
        ArrayList<Double> hr = new ArrayList<>();
        ArrayList<Double> intervals = new ArrayList<>();
        for (int i = 1; i < peaks.length; i++) {
            double dt = (peaks[i] - peaks[i - 1]) / fs;
            double bpm = 60.0 / Math.max(1e-9, dt);
            if (bpm >= HR_MIN_BPM && bpm <= HR_MAX_BPM) {
                hr.add(bpm);
                intervals.add(dt);
            }
        }
        if (hr.size() < 3) return new Estimate(Double.NaN, 0.0);
        double med = medianList(hr);
        double medInterval = medianList(intervals);
        double mad = 0.0;
        for (double v : intervals) mad += Math.abs(v - medInterval);
        mad /= Math.max(1, intervals.size());
        double regularity = Math.exp(-4.0 * mad / Math.max(1e-9, medInterval));
        double coverage = (double) hr.size() / Math.max(1, peaks.length - 1);
        return new Estimate(med, clamp(0.65 * coverage + 0.35 * regularity, 0.0, 1.0));
    }

    private static Estimate acfHr(double[] segment, double fs) {
        double[] x = robustZ(detrend(segment));
        if (x.length < (int) (4.0 * fs)) return new Estimate(Double.NaN, 0.0);
        int minLag = Math.max(1, (int) Math.floor(fs * 60.0 / HR_MAX_BPM));
        int maxLag = Math.min(x.length - 2, (int) Math.ceil(fs * 60.0 / HR_MIN_BPM));
        if (maxLag <= minLag) return new Estimate(Double.NaN, 0.0);
        double[] ac = new double[maxLag * 3 + 2];
        int maxAcLag = Math.min(ac.length - 1, x.length - 2);
        for (int lag = 0; lag <= maxAcLag; lag++) {
            double sum = 0.0;
            for (int i = 0; i + lag < x.length; i++) sum += x[i] * x[i + lag];
            ac[lag] = sum / Math.max(1, x.length - lag);
        }
        if (Math.abs(ac[0]) < 1e-12) return new Estimate(Double.NaN, 0.0);
        for (int i = 1; i <= maxAcLag; i++) ac[i] /= ac[0];
        ArrayList<Double> curve = new ArrayList<>();
        for (int lag = minLag; lag <= maxLag; lag++) curve.add(ac[lag]);
        double baseline = medianList(curve);
        int bestLag = -1;
        double bestScore = -Double.MAX_VALUE;
        for (int lag = minLag; lag <= maxLag; lag++) {
            boolean localPeak = (lag == minLag || ac[lag] >= ac[lag - 1])
                    && (lag == maxLag || ac[lag] >= ac[lag + 1]);
            if (!localPeak) continue;
            double score = ac[lag];
            if (2 * lag <= maxAcLag) score += 0.25 * ac[2 * lag];
            if (3 * lag <= maxAcLag) score += 0.12 * ac[3 * lag];
            if (score > bestScore) {
                bestScore = score;
                bestLag = lag;
            }
        }
        if (bestLag <= 0) return new Estimate(Double.NaN, 0.0);
        double bpm = 60.0 * fs / bestLag;
        double confidence = clamp((ac[bestLag] - baseline) / (1.0 + Math.abs(baseline)), 0.0, 1.0);
        return new Estimate(bpm, confidence);
    }

    private static Estimate spectralHr(double[] segment, double fs) {
        double[] x = robustZ(detrend(segment));
        if (x.length < (int) (4.0 * fs)) return new Estimate(Double.NaN, 0.0);
        double bestBpm = Double.NaN;
        double bestScore = -Double.MAX_VALUE;
        ArrayList<Double> powers = new ArrayList<>();
        for (int bpm = (int) HR_MIN_BPM; bpm <= (int) HR_MAX_BPM; bpm++) {
            double freq = bpm / 60.0;
            double power = goertzelPower(x, fs, freq);
            double score = power;
            if (2.0 * freq < fs / 2.0) score += 0.25 * goertzelPower(x, fs, 2.0 * freq);
            if (3.0 * freq < fs / 2.0) score += 0.10 * goertzelPower(x, fs, 3.0 * freq);
            if (freq / 2.0 >= HR_MIN_BPM / 60.0) score -= 0.35 * goertzelPower(x, fs, freq / 2.0);
            powers.add(power);
            if (score > bestScore) {
                bestScore = score;
                bestBpm = bpm;
            }
        }
        double med = medianList(powers);
        double confidence = isFinite(bestScore) && bestScore > 0.0
                ? clamp((bestScore - med) / (bestScore + med + 1e-12), 0.0, 1.0)
                : 0.0;
        return new Estimate(bestBpm, confidence);
    }

    private static double goertzelPower(double[] x, double fs, double freq) {
        double w = 2.0 * Math.PI * freq / fs;
        double coeff = 2.0 * Math.cos(w);
        double s0;
        double s1 = 0.0;
        double s2 = 0.0;
        int n = x.length;
        for (int i = 0; i < n; i++) {
            double win = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / Math.max(1, n - 1));
            double v = x[i] * win;
            s0 = v + coeff * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        return s1 * s1 + s2 * s2 - coeff * s1 * s2;
    }

    private static Estimate aggregate(List<Estimate> estimates, double radiusBpm) {
        ArrayList<Double> values = new ArrayList<>();
        ArrayList<Double> confidences = new ArrayList<>();
        for (Estimate e : estimates) {
            if (e != null && isFinite(e.bpm)) {
                values.add(e.bpm);
                confidences.add(clamp(e.confidence, 0.0, 1.0));
            }
        }
        if (values.isEmpty()) return new Estimate(Double.NaN, 0.0);
        int best = 0;
        int bestSupport = -1;
        for (int i = 0; i < values.size(); i++) {
            int support = 0;
            for (double v : values) if (Math.abs(v - values.get(i)) <= radiusBpm) support++;
            if (support > bestSupport) {
                bestSupport = support;
                best = i;
            }
        }
        ArrayList<Double> cluster = new ArrayList<>();
        ArrayList<Double> weights = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            double diff = Math.abs(values.get(i) - values.get(best));
            if (diff <= radiusBpm) {
                cluster.add(values.get(i));
                weights.add((0.10 + 0.90 * confidences.get(i)) * Math.exp(-diff / radiusBpm));
            }
        }
        double confidence = 0.0;
        for (double c : confidences) confidence += c;
        confidence = clamp((bestSupport / (double) values.size()) * (confidence / values.size()), 0.0, 1.0);
        return new Estimate(weightedMedian(toPrimitive(cluster), toPrimitive(weights)), confidence);
    }

    private static Estimate fusePressureFamilies(Estimate peak, Estimate acf, Estimate spectral) {
        double[] values = new double[] {peak.bpm, acf.bpm, spectral.bpm};
        double[] conf = new double[] {peak.confidence, acf.confidence, spectral.confidence};
        double[] prior = new double[] {1.25, 0.80, 0.70};
        double center = weightedMedian(values, new double[] {
                prior[0] * Math.max(0.08, conf[0]),
                prior[1] * Math.max(0.08, conf[1]),
                prior[2] * Math.max(0.08, conf[2])
        });
        if (!isFinite(center)) return new Estimate(Double.NaN, 0.0);
        double total = 0.0;
        double value = 0.0;
        double confidence = 0.0;
        for (int i = 0; i < values.length; i++) {
            if (!isFinite(values[i])) continue;
            double consensus = Math.exp(-Math.abs(values[i] - center) / 6.0);
            double w = prior[i] * (0.08 + 0.92 * conf[i]) * consensus;
            total += w;
            value += w * values[i];
            confidence += w * conf[i];
        }
        if (total <= 0.0) return new Estimate(Double.NaN, 0.0);
        return new Estimate(value / total, clamp(confidence / total, 0.0, 1.0));
    }

    private static String dominantFamily(Estimate peak, Estimate acf, Estimate spectral) {
        double pw = isFinite(peak.bpm) ? 1.25 * Math.max(0.08, peak.confidence) : 0.0;
        double aw = isFinite(acf.bpm) ? 0.80 * Math.max(0.08, acf.confidence) : 0.0;
        double sw = isFinite(spectral.bpm) ? 0.70 * Math.max(0.08, spectral.confidence) : 0.0;
        if (pw >= aw && pw >= sw) return "peak";
        if (aw >= pw && aw >= sw) return "acf";
        if (sw > 0.0) return "spectral";
        return "missing";
    }

    private static int[] detectPeaks(double[] x, int minDistance, double prominenceK) {
        double sd = std(x);
        double threshold = prominenceK * (isFinite(sd) && sd > 0 ? sd : 1.0);
        ArrayList<Integer> candidates = new ArrayList<>();
        for (int i = 1; i + 1 < x.length; i++) {
            if (!isFinite(x[i])) continue;
            if (x[i] > x[i - 1] && x[i] >= x[i + 1] && x[i] >= threshold) {
                candidates.add(i);
            }
        }
        candidates.sort((a, b) -> Double.compare(x[b], x[a]));
        ArrayList<Integer> selected = new ArrayList<>();
        for (int idx : candidates) {
            boolean far = true;
            for (int kept : selected) {
                if (Math.abs(idx - kept) < minDistance) {
                    far = false;
                    break;
                }
            }
            if (far) selected.add(idx);
        }
        Collections.sort(selected);
        int[] out = new int[selected.size()];
        for (int i = 0; i < selected.size(); i++) out[i] = selected.get(i);
        return out;
    }

    private static double[][] instantRatesFromPeaks(int[] peaks, double[] t, double minBpm, double maxBpm) {
        ArrayList<Double> tt = new ArrayList<>();
        ArrayList<Double> rr = new ArrayList<>();
        for (int i = 1; i < peaks.length; i++) {
            double dt = t[peaks[i]] - t[peaks[i - 1]];
            double bpm = 60.0 / Math.max(1e-9, dt);
            if (bpm >= minBpm && bpm <= maxBpm) {
                tt.add(0.5 * (t[peaks[i]] + t[peaks[i - 1]]));
                rr.add(bpm);
            }
        }
        return new double[][] {toPrimitive(tt), toPrimitive(rr)};
    }

    private static double medianRatesInWindow(double[] t, double[] rates, double start, double end) {
        ArrayList<Double> values = new ArrayList<>();
        for (int i = 0; i < t.length; i++) {
            if (t[i] >= start && t[i] < end && isFinite(rates[i])) values.add(rates[i]);
        }
        return medianList(values);
    }

    private static int countPeaks(int[] peaks, double[] t, double start, double end) {
        int count = 0;
        for (int p : peaks) {
            if (t[p] >= start && t[p] < end) count++;
        }
        return count;
    }

    private static double invalidFraction(int[] peaks, double[] t, double start, double end, double gapS) {
        if (end <= start) return 1.0;
        if (peaks.length == 0) return 1.0;
        double invalid = 0.0;
        double first = t[peaks[0]];
        if (first - start > gapS) invalid += overlapLength(start, Math.min(end, first), start, end);
        for (int i = 1; i < peaks.length; i++) {
            double a = t[peaks[i - 1]];
            double b = t[peaks[i]];
            if (b - a > gapS) invalid += overlapLength(a, b, start, end);
        }
        double last = t[peaks[peaks.length - 1]];
        if (end - last > gapS) invalid += overlapLength(Math.max(start, last), end, start, end);
        return clamp(invalid / (end - start), 0.0, 1.0);
    }

    private static double overlapLength(double a, double b, double start, double end) {
        double x = Math.max(a, start);
        double y = Math.min(b, end);
        return Math.max(0.0, y - x);
    }

    private static double[] filtFiltBandpass(double[] x, double fs, double lo, double hi) {
        double[] y = applyBiquadBandpass(x, fs, lo, hi);
        reverseInPlace(y);
        y = applyBiquadBandpass(y, fs, lo, hi);
        reverseInPlace(y);
        return y;
    }

    private static double[] applyBiquadBandpass(double[] x, double fs, double lo, double hi) {
        double f0 = Math.sqrt(lo * hi);
        double q = Math.max(0.20, f0 / Math.max(1e-6, hi - lo));
        double omega = 2.0 * Math.PI * f0 / fs;
        double alpha = Math.sin(omega) / (2.0 * q);
        double b0 = alpha;
        double b1 = 0.0;
        double b2 = -alpha;
        double a0 = 1.0 + alpha;
        double a1 = -2.0 * Math.cos(omega);
        double a2 = 1.0 - alpha;
        b0 /= a0;
        b1 /= a0;
        b2 /= a0;
        a1 /= a0;
        a2 /= a0;
        double[] y = new double[x.length];
        double x1 = 0.0;
        double x2 = 0.0;
        double y1 = 0.0;
        double y2 = 0.0;
        double mean = mean(x);
        for (int i = 0; i < x.length; i++) {
            double x0 = isFinite(x[i]) ? x[i] - mean : 0.0;
            double y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            y[i] = y0;
            x2 = x1;
            x1 = x0;
            y2 = y1;
            y1 = y0;
        }
        return y;
    }

    private static double[] robustZ(double[] x) {
        double med = median(x);
        double[] d = new double[x.length];
        for (int i = 0; i < x.length; i++) d[i] = Math.abs(x[i] - med);
        double mad = median(d);
        double scale = mad > 0 && isFinite(mad) ? 1.4826 * mad : std(x);
        if (!isFinite(scale) || scale <= 1e-12) return new double[x.length];
        double[] z = new double[x.length];
        for (int i = 0; i < x.length; i++) z[i] = (x[i] - med) / scale;
        return z;
    }

    private static double[] detrend(double[] x) {
        if (x.length < 2) return x;
        double first = x[0];
        double last = x[x.length - 1];
        double[] y = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            double trend = first + (last - first) * i / Math.max(1, x.length - 1);
            y[i] = x[i] - trend;
        }
        return y;
    }

    private static double[] rollingMedian(double[] x, int width) {
        double[] y = new double[x.length];
        int half = Math.max(0, width / 2);
        for (int i = 0; i < x.length; i++) {
            ArrayList<Double> local = new ArrayList<>();
            for (int j = Math.max(0, i - half); j <= Math.min(x.length - 1, i + half); j++) {
                if (isFinite(x[j])) local.add(x[j]);
            }
            y[i] = medianList(local);
        }
        return y;
    }

    private static double weightedMedian(double[] values, double[] weights) {
        ArrayList<double[]> rows = new ArrayList<>();
        for (int i = 0; i < values.length && i < weights.length; i++) {
            if (isFinite(values[i]) && isFinite(weights[i]) && weights[i] > 0.0) {
                rows.add(new double[] {values[i], weights[i]});
            }
        }
        if (rows.isEmpty()) return Double.NaN;
        rows.sort(Comparator.comparingDouble(a -> a[0]));
        double total = 0.0;
        for (double[] r : rows) total += r[1];
        double c = 0.0;
        for (double[] r : rows) {
            c += r[1];
            if (c >= 0.5 * total) return r[0];
        }
        return rows.get(rows.size() - 1)[0];
    }

    private static double mean(double[] x) {
        double s = 0.0;
        int n = 0;
        for (double v : x) {
            if (isFinite(v)) {
                s += v;
                n++;
            }
        }
        return n > 0 ? s / n : 0.0;
    }

    private static double std(double[] x) {
        double m = mean(x);
        double s = 0.0;
        int n = 0;
        for (double v : x) {
            if (isFinite(v)) {
                double d = v - m;
                s += d * d;
                n++;
            }
        }
        return n > 1 ? Math.sqrt(s / (n - 1)) : Double.NaN;
    }

    private static double median(double[] x) {
        ArrayList<Double> values = new ArrayList<>();
        for (double v : x) if (isFinite(v)) values.add(v);
        return medianList(values);
    }

    private static double medianList(List<Double> values) {
        ArrayList<Double> clean = new ArrayList<>();
        for (Double v : values) if (v != null && isFinite(v)) clean.add(v);
        if (clean.isEmpty()) return Double.NaN;
        Collections.sort(clean);
        int n = clean.size();
        if ((n & 1) == 1) return clean.get(n / 2);
        return 0.5 * (clean.get(n / 2 - 1) + clean.get(n / 2));
    }

    private static double[] toPrimitive(List<Double> list) {
        double[] out = new double[list.size()];
        for (int i = 0; i < list.size(); i++) out[i] = list.get(i);
        return out;
    }

    private static void reverseInPlace(double[] x) {
        for (int i = 0, j = x.length - 1; i < j; i++, j--) {
            double tmp = x[i];
            x[i] = x[j];
            x[j] = tmp;
        }
    }

    private static int lowerBound(double[] a, double v) {
        int lo = 0;
        int hi = a.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid] < v) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    private static int upperBound(double[] a, double v) {
        int lo = 0;
        int hi = a.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid] <= v) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    private static double clamp(double v, double lo, double hi) {
        if (!isFinite(v)) return lo;
        return Math.max(lo, Math.min(hi, v));
    }

    private static boolean isFinite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }
}
