package com.shelfwise.core.dataimport;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Reads a CSV file into rows, keeping every value exactly as written. */
final class CsvFile {

    /** One data row: its line number in the file and its values by column name. */
    record Row(int line, Map<String, String> values) {

        String get(String column) {
            return values.get(column);
        }
    }

    private CsvFile() {
    }

    /** File reading is blocking I/O, so it runs off the request threads. */
    static Mono<List<Row>> read(Path file) {
        return Mono.fromCallable(() -> readBlocking(file)).subscribeOn(Schedulers.boundedElastic());
    }

    private static List<Row> readBlocking(Path file) throws IOException {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreSurroundingSpaces(false)   // 'SW-SNK-00091 ' must stay as written (D1)
                .setTrim(false)
                .get();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            List<Row> rows = new ArrayList<>();
            for (CSVRecord record : format.parse(reader)) {
                // +1 because the header is line 1, so the first record is line 2 of the file
                rows.add(new Row((int) record.getRecordNumber() + 1, new LinkedHashMap<>(record.toMap())));
            }
            return rows;
        }
    }
}
