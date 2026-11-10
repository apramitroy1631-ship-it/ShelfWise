package com.shelfwise.core.dataimport;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The one date reader for every imported file (D6, D9). Rules, in order:
 * <ol>
 *   <li>A timezone in the value is used as written.</li>
 *   <li>No timezone means IST (+05:30): every value in the data that has a timezone uses +05:30.</li>
 *   <li>{@code dd/mm/yyyy} is day-first (proven from the data: day > 12 occurs, month > 12 never).</li>
 *   <li>12-hour times: 12:xx AM is just after midnight, 12:xx PM just after noon.</li>
 *   <li>A 13-digit number is epoch milliseconds: an exact moment, no assumption needed.</li>
 *   <li>Anything else is rejected, never guessed.</li>
 * </ol>
 * A date without a time means 00:00 IST that day.
 */
final class ImportDates {

    static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

    private static final Pattern EPOCH_MILLIS = Pattern.compile("\\d{13}");

    private static final List<DateTimeFormatter> LOCAL_DATE_TIMES = List.of(
            strict("uuuu-MM-dd'T'HH:mm:ss"),    // 2026-09-30T17:00:15
            strict("uuuu-MM-dd HH:mm:ss"),      // 2026-09-23 12:50:56
            strict("dd/MM/uuuu HH:mm"),         // 10/03/2026 00:24  (day first)
            strict("dd-MMM-uuuu HH:mm"),        // 29-Sep-2026 11:50
            strict("MMM dd, uuuu hh:mm a"));    // Jun 29, 2026 07:41 PM

    private static final List<DateTimeFormatter> LOCAL_DATES = List.of(
            strict("uuuu-MM-dd"),               // 2026-09-18
            strict("dd/MM/uuuu"));              // 16/10/2026  (day first)

    private ImportDates() {
    }

    /** Parses a text value; empty if it matches none of the known formats. */
    static Optional<OffsetDateTime> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String text = value.strip();
        if (EPOCH_MILLIS.matcher(text).matches()) {
            return Optional.of(fromEpochMillis(Long.parseLong(text)));
        }
        try {
            return Optional.of(OffsetDateTime.parse(text));   // ISO with offset, e.g. 2025-11-27T14:49:57+05:30
        } catch (DateTimeParseException notIsoWithOffset) {
            // try the formats without a timezone
        }
        for (DateTimeFormatter format : LOCAL_DATE_TIMES) {
            try {
                return Optional.of(LocalDateTime.parse(text, format).atOffset(IST));
            } catch (DateTimeParseException ignored) {
                // next format
            }
        }
        for (DateTimeFormatter format : LOCAL_DATES) {
            try {
                return Optional.of(LocalDate.parse(text, format).atStartOfDay().atOffset(IST));
            } catch (DateTimeParseException ignored) {
                // next format
            }
        }
        return Optional.empty();
    }

    /** orders.json sometimes stores the time as a JSON number of epoch milliseconds. */
    static OffsetDateTime fromEpochMillis(long millis) {
        return Instant.ofEpochMilli(millis).atOffset(IST);
    }

    private static DateTimeFormatter strict(String pattern) {
        return DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT);
    }
}
