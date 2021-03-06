package com.robinhowlett.chartparser.charts.pdf;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.robinhowlett.chartparser.exceptions.ChartParserException;
import com.robinhowlett.chartparser.fractionals.FractionalPoint;
import com.robinhowlett.chartparser.fractionals.FractionalService;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.robinhowlett.chartparser.charts.pdf.Purse.FOREIGN_CURRENCY_DISCLAIMER;
import static com.robinhowlett.chartparser.charts.pdf.Purse.PURSE_PATTERN;

/**
 * Parses the textual description of the race distance and converts it into a {@link RaceDistance}
 * instance, including calculating the race distance in feet, furlongs, and with compact
 * description. The scheduled and actual surface race on is additionally stored. It also parses and
 * stores, in a {@link TrackRecord} instance, the details of the track record for this
 * distance/surface.
 */
@JsonPropertyOrder({"distance", "surface", "trackCondition", "scheduledSurface", "offTurf",
        "trackRecord"})
public class DistanceSurfaceTrackRecord {

    static final Pattern DIST_SURF_RECORD_PATTERN =
            Pattern.compile("^((About )?(One|Two|Three|Four|Five|Six|Seven|Eight|Nine)[\\w\\s]+) " +
                    "On The ([A-Za-z\\s]+)(\\s?- Originally Scheduled For the " +
                    "([A-Za-z0-9\\-\\s]+))?(\\|Track Record: \\((.+) - ([\\d:\\.]+) - (.+)\\))?");

    private static final List<String> NUMERATORS = Arrays.asList("zero", "one", "two", "three",
            "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen",
            "fourteen", "fifteen");

    private static final List<String> TENS = Arrays.asList("zero", "ten", "twenty", "thirty",
            "forty", "fifty", "sixty", "seventy", "eighty", "ninety");

    private static final Pattern MILES_ONLY_PATTERN =
            Pattern.compile("^(about)? ?([\\w]+)( and ([\\w ]+))? miles?$");

    private static final Pattern FURLONGS_ONLY_PATTERN =
            Pattern.compile("^(about)? ?([\\w]+)( and ([\\w ]+))? furlongs?$");

    private static final Pattern YARDS_ONLY_PATTERN =
            Pattern.compile("^(about)? ?((\\w+) thousand)? ?(([\\w]+) hundred ?( ?and )?([\\w " +
                    "]+)?)? yards?$");

    private static final Pattern MILES_YARDS_PATTERN =
            Pattern.compile("(about)? ?([\\w]+) miles? and ([\\w ]+) yards?");

    private static final Pattern FURLONGS_YARDS_PATTERN =
            Pattern.compile("(about)? ?([\\w]+) furlongs? and ([\\w ]+) yards?");

    private static final Pattern MISSING_YARDS_PATTERN =
            Pattern.compile("^(about)? ?((\\w+) thousand)? ?(([\\w]+) hundred ?( ?and )?([\\w " +
                    "]+)?)?$");

    @JsonProperty("distance")
    private final RaceDistance raceDistance;
    private final String surface;
    private final String scheduledSurface;
    private final TrackRecord trackRecord;
    private String trackCondition;

    public DistanceSurfaceTrackRecord(String distanceDescription, String surface,
            String scheduledSurface, TrackRecord trackRecord) throws ChartParserException {
        this.raceDistance = (distanceDescription != null ?
                parseRaceDistance(distanceDescription) : null);
        this.surface = surface;
        this.scheduledSurface = (scheduledSurface != null ? scheduledSurface : surface);
        this.trackRecord = trackRecord;
    }

    public static DistanceSurfaceTrackRecord parse(final List<List<ChartCharacter>> lines)
            throws ChartParserException {
        boolean found = false;
        StringBuilder distanceSurfaceTrackRecordBuilder = new StringBuilder();
        String prefix = "";
        for (List<ChartCharacter> line : lines) {
            String text = Chart.convertToText(line);
            if (found) {
                Matcher purseMatcher = PURSE_PATTERN.matcher(text);
                Matcher currencyMatcher = FOREIGN_CURRENCY_DISCLAIMER.matcher(text);
                if (purseMatcher.find() || currencyMatcher.find()) {
                    break;
                } else {
                    distanceSurfaceTrackRecordBuilder.append(prefix).append(text);
                }
            }
            Matcher matcher = DIST_SURF_RECORD_PATTERN.matcher(text);
            if (matcher.find() && isValidDistanceText(text)) {
                found = true;
                // prefix a space at the start of each line (except for the first)
                distanceSurfaceTrackRecordBuilder.append(prefix).append(text);
                prefix = " ";
            }
        }

        String distanceSurfaceTrackRecord = distanceSurfaceTrackRecordBuilder.toString();
        Optional<DistanceSurfaceTrackRecord> distanceSurface =
                parseDistanceSurface(distanceSurfaceTrackRecord);
        if (distanceSurface.isPresent()) {
            return distanceSurface.get();
        }

        throw new NoRaceDistanceFound(distanceSurfaceTrackRecord);
    }

    public static boolean isValidDistanceText(String text) {
        return !((text.toLowerCase().contains("claiming price") ||
                text.toLowerCase().contains("allowed") ||
                text.toLowerCase().contains("non winners") ||
                text.toLowerCase().contains("other than"))
                && !text.toLowerCase().contains("track record"));
    }

    static Optional<DistanceSurfaceTrackRecord> parseDistanceSurface(String text)
            throws ChartParserException {
        Matcher matcher = DIST_SURF_RECORD_PATTERN.matcher(text);
        if (matcher.find()) {
            String distanceDescription = matcher.group(1);
            String surface = matcher.group(4).trim();
            String scheduledSurface = null;

            // detect off-turf races
            String scheduledSurfaceFlag = matcher.group(5);
            if (scheduledSurfaceFlag != null) {
                scheduledSurface = matcher.group(6);
            }

            TrackRecord trackRecord = null;
            if (matcher.group(7) != null) {
                String holder = matcher.group(8);
                String time = matcher.group(9);
                Optional<Long> recordTime =
                        FractionalService.calculateMillisecondsForFraction(time);

                String raceDateText = matcher.group(10);
                LocalDate raceDate = TrackRaceDateRaceNumber.parseRaceDate(raceDateText);

                trackRecord = new TrackRecord(new Horse(holder), (recordTime.isPresent() ?
                        FractionalPoint.convertToTime(recordTime.get()) : null),
                        (recordTime.isPresent() ? recordTime.get() : null), raceDate);
            }

            return Optional.of(new DistanceSurfaceTrackRecord(distanceDescription, surface,
                    scheduledSurface, trackRecord));
        }
        return Optional.empty();
    }

    static RaceDistance parseRaceDistance(String distanceDescription) throws ChartParserException {
        String lcDistanceDescription = distanceDescription.toLowerCase();
        Matcher milesOnlyMatcher = MILES_ONLY_PATTERN.matcher(lcDistanceDescription);
        if (milesOnlyMatcher.find()) {
            return forMiles(distanceDescription, milesOnlyMatcher);
        }
        Matcher furlongsOnlyMatcher = FURLONGS_ONLY_PATTERN.matcher(lcDistanceDescription);
        if (furlongsOnlyMatcher.find()) {
            return forFurlongs(distanceDescription, furlongsOnlyMatcher);
        }
        Matcher yardsOnlyMatcher = YARDS_ONLY_PATTERN.matcher(lcDistanceDescription);
        if (yardsOnlyMatcher.find()) {
            return forYards(distanceDescription, yardsOnlyMatcher);
        }
        Matcher milesAndYardsMatcher = MILES_YARDS_PATTERN.matcher(lcDistanceDescription);
        if (milesAndYardsMatcher.find()) {
            return forMilesAndYards(distanceDescription, milesAndYardsMatcher);
        }
        Matcher furlongsAndYardsMatcher = FURLONGS_YARDS_PATTERN.matcher(lcDistanceDescription);
        if (furlongsAndYardsMatcher.find()) {
            return forFurlongsAndYards(distanceDescription, furlongsAndYardsMatcher);
        }
        // sometimes the "Yards" part is missing
        Matcher missingYardsMatcher = MISSING_YARDS_PATTERN.matcher(lcDistanceDescription);
        if (missingYardsMatcher.find()) {
            return forYards(distanceDescription, missingYardsMatcher);
        }

        throw new ChartParserException(String.format("Unable to parse race distance from text: " +
                "%s", distanceDescription));
    }

    private static RaceDistance forMiles(String distanceDescription, Matcher matcher)
            throws ChartParserException {
        String compact = "m";
        int feet = 0;
        boolean isExact = (matcher.group(1) == null);

        String fractionalMiles = matcher.group(4);
        if (fractionalMiles != null && !fractionalMiles.isEmpty()) {
            String[] fraction = fractionalMiles.split(" ");
            String denominator = fraction[1];
            switch (denominator) {
                case "sixteenth":
                case "sixteenths":
                    feet = 330; // 5280 divided by 16
                    compact = "/16m";
                    break;
                case "eighth":
                case "eighths":
                    feet = 660;
                    compact = "/8m";
                    break;
                case "fourth":
                case "fourths":
                    feet = 1320;
                    compact = "/4m";
                    break;
                case "half":
                    feet = 2640;
                    compact = "/2m";
                    break;
                default:
                    throw new ChartParserException(String.format("Unable to parse a fractional " +
                            "mile denominator from text: %s", denominator));
            }
            String numerator = fraction[0];
            int num = NUMERATORS.indexOf(numerator);
            feet = num * feet;
            compact = String.format(" %s%s", num, compact);
        }

        String wholeMiles = matcher.group(2);
        int mileNumerator = NUMERATORS.indexOf(wholeMiles);
        feet += (mileNumerator * 5280);

        compact = (isExact ? "" : "Abt ").concat(String.format("%d%s", mileNumerator, compact));

        return new RaceDistance(distanceDescription, compact, isExact, feet);
    }

    private static RaceDistance forFurlongs(String distanceDescription, Matcher matcher)
            throws ChartParserException {
        String compact = "f";
        int feet = 0;
        boolean isExact = (matcher.group(1) == null);

        String fractionalFurlongs = matcher.group(4);
        if (fractionalFurlongs != null && !fractionalFurlongs.isEmpty()) {
            String[] fraction = fractionalFurlongs.split(" ");
            String denominator = fraction[1];
            switch (denominator) {
                case "fourth":
                case "fourths":
                    feet = 165;
                    compact = "/4f";
                    break;
                case "half":
                    feet = 330;
                    compact = "/2f";
                    break;
                default:
                    throw new ChartParserException(String.format("Unable to parse a fractional " +
                            "furlong denominator from text: %s", denominator));
            }
            String numerator = fraction[0];
            int num = NUMERATORS.indexOf(numerator);
            feet = num * feet;
            compact = String.format(" %s%s", num, compact);
        }

        String wholeFurlongs = matcher.group(2);
        int furlongNumerator = NUMERATORS.indexOf(wholeFurlongs);
        feet += (furlongNumerator * 660);
        compact = (isExact ? "" : "Abt ").concat(String.format("%d%s", furlongNumerator, compact));

        return new RaceDistance(distanceDescription, compact, isExact, feet);
    }

    private static RaceDistance forYards(String distanceDescription, Matcher matcher) {
        int feet = 0;
        boolean isExact = (matcher.group(1) == null);

        String yards = matcher.group(7);
        if (yards != null && !yards.isEmpty()) {
            String[] splitYards = yards.split(" ");
            if (splitYards.length == 2) {
                feet = (TENS.indexOf(splitYards[0]) * 10 * 3) +
                        (NUMERATORS.indexOf(splitYards[1]) * 3);
            } else {
                int inTensYards = TENS.indexOf(yards);
                if (inTensYards < 0) {
                    feet = (NUMERATORS.indexOf(yards) * 3);
                } else {
                    feet = inTensYards * 10 * 3;
                }
            }
        }

        String thousandYards = matcher.group(3);
        if (thousandYards != null) {
            int yardsInThousands = NUMERATORS.indexOf(thousandYards);
            feet += (yardsInThousands * 3000);
        }

        String hundredYards = matcher.group(5);
        if (hundredYards != null) {
            int yardsInHundreds = NUMERATORS.indexOf(hundredYards);
            feet += (yardsInHundreds * 300);
        }

        String compact = (isExact ? "" : "Abt ").concat(String.format("%dy", (feet / 3)));

        return new RaceDistance(distanceDescription, compact, isExact, feet);
    }

    private static RaceDistance forMilesAndYards(String distanceDescription, Matcher matcher) {
        String compact = null;
        int feet = 0;
        boolean isExact = (matcher.group(1) == null);

        String yards = matcher.group(3);
        if (yards != null && !yards.isEmpty()) {
            feet = TENS.indexOf(yards) * 10 * 3;
            compact = String.format("%dy", feet / 3);
        }

        String wholeMiles = matcher.group(2);
        int mileNumerator = NUMERATORS.indexOf(wholeMiles);
        feet += (mileNumerator * 5280);
        compact = (isExact ? "" : "Abt ").concat(String.format("%dm %s", mileNumerator, compact));

        return new RaceDistance(distanceDescription, compact, isExact, feet);
    }

    private static RaceDistance forFurlongsAndYards(String distanceDescription, Matcher matcher) {
        String compact = null;
        int feet = 0;
        boolean isExact = (matcher.group(1) == null);

        String yards = matcher.group(3);
        if (yards != null && !yards.isEmpty()) {
            feet = TENS.indexOf(yards) * 10 * 3;
            compact = String.format("%dy", feet / 3);
        }

        String wholeFurlongs = matcher.group(2);
        int furlongNumerator = NUMERATORS.indexOf(wholeFurlongs);
        feet += (furlongNumerator * 660);
        compact = (isExact ? "" : "Abt ").concat(
                String.format("%df %s", furlongNumerator, compact));

        return new RaceDistance(distanceDescription, compact, isExact, feet);
    }

    /**
     * Stores the textual description of the race distance, the distance expressed in feet and
     * furlongs, a compact description of the race distance and whether the distance is exact or
     * estimated ("About")
     */
    @JsonPropertyOrder({"text", "compact", "feet", "furlongs", "exact", "runUp"})
    public static class RaceDistance {
        private final String text;
        private final String compact;
        private final boolean exact;
        private final int feet;
        private final double furlongs;
        private Integer runUp;
        private Integer tempRail;

        RaceDistance(String text, String compact, boolean exact, int feet) {
            this(text, compact, exact, feet, null, null);
        }

        @JsonCreator
        private RaceDistance(String text, String compact, boolean exact, int feet, Integer runUp, Integer tempRail) {
            this.text = text;
            this.compact = compact;
            this.exact = exact;
            this.feet = feet;
            this.furlongs = Chart.round((double) feet / 660, 2).doubleValue();
            this.runUp = runUp;
            this.tempRail = tempRail;
        }

        public static String lookupCompact(int feet) {
            LinkedHashMap<Integer, String> compacts = new LinkedHashMap<Integer, String>() {{
                put(450, "150y");
                put(660, "1f");
                put(1320, "2f");
                put(1650, "2 1/2f");
                put(1980, "3f");
                put(2145, "3 1/4f");
                put(2310, "3 1/2f");
                put(2475, "3 3/4f");
                put(2640, "4f");
                put(2970, "4 1/2f");
                put(3000, "1000y");
                put(3300, "5f");
                put(3465, "5 1/4f");
                put(3630, "5 1/2f");
                put(3960, "6f");
                put(4290, "6 1/2f");
                put(4620, "7f");
                put(4950, "7 1/2f");
                put(5280, "1m");
                put(5370, "1m 30y");
                put(5400, "1m 40y");
                put(5490, "1m 70y");
                put(5610, "1 1/16m");
                put(5940, "1 1/8m");
                put(6270, "1 3/16m");
                put(6600, "1 1/4m");
                put(6930, "1 5/16m");
                put(7260, "1 3/8m");
                put(7590, "1 7/16m");
                put(7920, "1 1/2m");
                put(8250, "1 9/16m");
                put(8580, "1 5/8m");
                put(8910, "1 11/16m");
                put(9240, "1 3/4m");
                put(9570, "1 13/16m");
                put(9900, "1 7/8m");
                put(10230, "1 15/16m");
                put(10560, "2m");
                put(10680, "2m 40y");
                put(10770, "2m 70y");
                put(10890, "2 1/16m");
                put(11220, "2 1/8m");
                put(11550, "2 3/16m");
                put(11880, "2 1/4m");
                put(12210, "2 5/16m");
                put(15840, "3m");
                put(17160, "3 1/4f");
                put(18480, "3 1/2f");
                put(21120, "4m");
            }};

            if (compacts.containsKey(feet)) {
                return compacts.get(feet);
            }

            return null;
        }

        public String getText() {
            return text;
        }

        public String getCompact() {
            return compact;
        }

        public boolean isExact() {
            return exact;
        }

        public int getFeet() {
            return feet;
        }

        public double getFurlongs() {
            return furlongs;
        }

        public Integer getRunUp() {
            return runUp;
        }

        public void setRunUp(Integer runUp) {
            this.runUp = runUp;
        }

        public Integer getTempRail() {
            return tempRail;
        }

        public void setTempRail(Integer tempRail) {
            this.tempRail = tempRail;
        }

        @Override
        public String
        toString() {
            return "RaceDistance{" +
                    "text='" + text + '\'' +
                    ", compact='" + compact + '\'' +
                    ", exact=" + exact +
                    ", feet=" + feet +
                    ", furlongs=" + furlongs +
                    ", runUp=" + runUp +
                    ", tempRail=" + tempRail +
                    '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            RaceDistance that = (RaceDistance) o;

            if (exact != that.exact) return false;
            if (feet != that.feet) return false;
            if (Double.compare(that.furlongs, furlongs) != 0) return false;
            if (text != null ? !text.equals(that.text) : that.text != null) return false;
            if (compact != null ? !compact.equals(that.compact) : that.compact != null)
                return false;
            if (runUp != null ? !runUp.equals(that.runUp) : that.runUp != null) return false;
            return tempRail != null ? tempRail.equals(that.tempRail) : that.tempRail == null;
        }

        @Override
        public int hashCode() {
            int result;
            long temp;
            result = text != null ? text.hashCode() : 0;
            result = 31 * result + (compact != null ? compact.hashCode() : 0);
            result = 31 * result + (exact ? 1 : 0);
            result = 31 * result + feet;
            temp = Double.doubleToLongBits(furlongs);
            result = 31 * result + (int) (temp ^ (temp >>> 32));
            result = 31 * result + (runUp != null ? runUp.hashCode() : 0);
            result = 31 * result + (tempRail != null ? tempRail.hashCode() : 0);
            return result;
        }
    }

    /**
     * Stories the name of the track record holder, the track record time (as both a String and in
     * milliseconds) and the date when the record was set
     */
    public static class TrackRecord {
        private final Horse holder;
        private final String time;
        private final Long millis;
        private final LocalDate raceDate;

        public TrackRecord(Horse holder, String time, Long millis, LocalDate raceDate) {
            this.holder = holder;
            this.time = time;
            this.millis = millis;
            this.raceDate = raceDate;
        }

        public Horse getHolder() {
            return holder;
        }

        public String getTime() {
            return time;
        }

        public Long getMillis() {
            return millis;
        }

        public LocalDate getRaceDate() {
            return raceDate;
        }

        @Override
        public String toString() {
            return "TrackRecord{" +
                    "holder='" + holder + '\'' +
                    ", time='" + time + '\'' +
                    ", millis=" + millis +
                    ", raceDate=" + raceDate +
                    '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            TrackRecord that = (TrackRecord) o;

            if (holder != null ? !holder.equals(that.holder) : that.holder != null) return false;
            if (time != null ? !time.equals(that.time) : that.time != null) return false;
            if (millis != null ? !millis.equals(that.millis) : that.millis != null) return false;
            return raceDate != null ? raceDate.equals(that.raceDate) : that.raceDate == null;
        }

        @Override
        public int hashCode() {
            int result = holder != null ? holder.hashCode() : 0;
            result = 31 * result + (time != null ? time.hashCode() : 0);
            result = 31 * result + (millis != null ? millis.hashCode() : 0);
            result = 31 * result + (raceDate != null ? raceDate.hashCode() : 0);
            return result;
        }
    }

    public String getSurface() {
        return surface;
    }

    public String getScheduledSurface() {
        return scheduledSurface;
    }

    public boolean isOffTurf() {
        return (!surface.equals(scheduledSurface));
    }

    public TrackRecord getTrackRecord() {
        return trackRecord;
    }

    public RaceDistance getRaceDistance() {
        return raceDistance;
    }

    public String getTrackCondition() {
        return trackCondition;
    }

    public void setTrackCondition(String trackCondition) {
        this.trackCondition = trackCondition;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        DistanceSurfaceTrackRecord that = (DistanceSurfaceTrackRecord) o;

        if (raceDistance != null ? !raceDistance.equals(that.raceDistance) : that.raceDistance !=
                null)
            return false;
        if (surface != null ? !surface.equals(that.surface) : that.surface != null) return false;
        if (scheduledSurface != null ? !scheduledSurface.equals(that.scheduledSurface) : that
                .scheduledSurface != null)
            return false;
        if (trackRecord != null ? !trackRecord.equals(that.trackRecord) : that.trackRecord != null)
            return false;
        return trackCondition != null ? trackCondition.equals(that.trackCondition) : that
                .trackCondition == null;
    }

    @Override
    public int hashCode() {
        int result = raceDistance != null ? raceDistance.hashCode() : 0;
        result = 31 * result + (surface != null ? surface.hashCode() : 0);
        result = 31 * result + (scheduledSurface != null ? scheduledSurface.hashCode() : 0);
        result = 31 * result + (trackRecord != null ? trackRecord.hashCode() : 0);
        result = 31 * result + (trackCondition != null ? trackCondition.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "DistanceSurfaceTrackRecord{" +
                "raceDistance=" + raceDistance +
                ", surface='" + surface + '\'' +
                ", scheduledSurface='" + scheduledSurface + '\'' +
                ", trackRecord=" + trackRecord +
                ", trackCondition='" + trackCondition + '\'' +
                '}';
    }

    public static class NoRaceDistanceFound extends ChartParserException {
        public NoRaceDistanceFound(String distanceSurfaceTrackRecord) {
            super(String.format("Unable to identify a valid race feet, surface, and/or track " +
                    "record: %s", distanceSurfaceTrackRecord));
        }
    }
}
