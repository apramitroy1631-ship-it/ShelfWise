package com.shelfwise.core.dataimport;

/** What one import run did with one file: the counts shown in the import report. */
public record FileResult(String file, int added, int skipped, int rejected) {

    static FileResult of(String file) {
        return new FileResult(file, 0, 0, 0);
    }

    FileResult plusAdded() {
        return new FileResult(file, added + 1, skipped, rejected);
    }

    FileResult plusSkipped() {
        return new FileResult(file, added, skipped + 1, rejected);
    }

    FileResult plusRejected() {
        return new FileResult(file, added, skipped, rejected + 1);
    }
}
