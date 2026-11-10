package com.shelfwise.core.dataimport;

import java.util.List;

import org.springframework.r2dbc.core.DatabaseClient;

import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Inserts many rows with one prepared statement per chunk (R2DBC batching), inside the current
 * transaction. Used for the large files: orders.json alone produces about 48,000 rows.
 */
final class BatchInsert {

    private static final int CHUNK = 1000;

    private BatchInsert() {
    }

    /**
     * @param sql   an INSERT with positional parameters $1, $2, ...
     * @param types the Java type of each parameter, needed to bind nulls
     * @param rows  one Object[] per row, in parameter order
     */
    static Mono<Long> run(DatabaseClient db, String sql, Class<?>[] types, List<Object[]> rows) {
        if (rows.isEmpty()) {
            return Mono.just(0L);
        }
        return Flux.range(0, (rows.size() + CHUNK - 1) / CHUNK)
                .concatMap(chunk -> db.inConnection(connection -> {
                    Statement statement = connection.createStatement(sql);
                    List<Object[]> part = rows.subList(chunk * CHUNK, Math.min(rows.size(), (chunk + 1) * CHUNK));
                    for (int r = 0; r < part.size(); r++) {
                        if (r > 0) {
                            statement.add();
                        }
                        Object[] row = part.get(r);
                        for (int i = 0; i < types.length; i++) {
                            if (row[i] == null) {
                                statement.bindNull(i, types[i]);
                            } else {
                                statement.bind(i, row[i]);
                            }
                        }
                    }
                    return Flux.from(statement.execute())
                            .flatMap(Result::getRowsUpdated)
                            .reduce(0L, Long::sum);
                }))
                .reduce(0L, Long::sum);
    }
}
