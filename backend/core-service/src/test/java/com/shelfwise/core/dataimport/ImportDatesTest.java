package com.shelfwise.core.dataimport;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Every date format found in the provided files (D6, D9), using real examples from the data. */
class ImportDatesTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            // ISO with offset: used as written
            "2025-11-27T14:49:57+05:30 | 2025-11-27T14:49:57+05:30",
            // ISO without timezone: IST
            "2026-09-30T17:00:15       | 2026-09-30T17:00:15+05:30",
            "2026-09-23 12:50:56       | 2026-09-23T12:50:56+05:30",
            // day-first, with and without a time
            "10/03/2026 00:24          | 2026-03-10T00:24+05:30",
            "16/10/2026                | 2026-10-16T00:00+05:30",
            // date only
            "2026-09-18                | 2026-09-18T00:00+05:30",
            // month name formats
            "29-Sep-2026 11:50         | 2026-09-29T11:50+05:30",
            "Jun 29, 2026 07:41 PM     | 2026-06-29T19:41+05:30",
            // the 12-hour trap: 12 AM is just after midnight, 12 PM just after noon
            "Feb 12, 2026 12:55 AM     | 2026-02-12T00:55+05:30",
            "Oct 22, 2026 12:57 PM     | 2026-10-22T12:57+05:30",
            // epoch milliseconds (inventory.csv line 4): an exact moment
            "1792477631000             | 2026-10-20T11:57:11+05:30"
    })
    void readsEveryFormatInTheData(String input, String expected) {
        OffsetDateTime parsed = ImportDates.parse(input).orElseThrow();
        assertThat(parsed.toInstant()).isEqualTo(OffsetDateTime.parse(expected).toInstant());
    }

    /** dd/mm is day-first: 13/02 is 13 February; 02/13 would be month 13, so it is rejected, not guessed. */
    @ParameterizedTest
    @ValueSource(strings = {"02/13/2026", "31/02/2026", "next tuesday", "2026-13-01", ""})
    void rejectsAnythingItCannotReadForSure(String input) {
        assertThat(ImportDates.parse(input)).isEmpty();
    }
}
