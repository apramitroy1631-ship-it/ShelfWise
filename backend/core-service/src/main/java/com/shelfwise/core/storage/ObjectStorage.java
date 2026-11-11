package com.shelfwise.core.storage;

import reactor.core.publisher.Mono;

/** Where uploaded files live (DL-04). The only implementation that talks to a real store uses the S3 API. */
public interface ObjectStorage {

    Mono<Void> put(String key, byte[] content, String contentType);

    /** The file's bytes; errors if the key is missing. */
    Mono<byte[]> get(String key);

    /** Best effort: used to clean up a file whose database record could not be saved. */
    Mono<Void> delete(String key);
}
