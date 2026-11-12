package org.gautelis.federation.http;

import java.util.*;
import org.gautelis.federation.Identity.*;

/** Exact logical repository dispatch, independent of hostnames and concrete adapters. */
public final class ReaderDispatch implements SourceReader {
    private final Map<Repository,SourceReader> readers;
    public ReaderDispatch(Map<Repository,SourceReader> readers) { this.readers=Map.copyOf(readers); }
    @Override public Snapshot read(Representation representation,Set<Field> fields) {
        var reader=readers.get(representation.resource().repository());
        if (reader==null) throw new RepositoryFailure(RepositoryFailure.Kind.CONFIGURATION);
        var result=reader.read(representation,Set.copyOf(fields));
        if (!representation.equals(result.representation()) || !fields.equals(result.fields().keySet()))
            throw new RepositoryFailure(RepositoryFailure.Kind.INVALID_RESPONSE);
        return result;
    }
}
