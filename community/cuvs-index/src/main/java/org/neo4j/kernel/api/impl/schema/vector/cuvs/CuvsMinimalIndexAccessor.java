/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [https://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.api.impl.schema.vector.cuvs;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.internal.schema.IndexDescriptor;
import org.neo4j.kernel.api.index.MinimalIndexAccessor;
import org.neo4j.values.storable.Value;

/**
 * Minimal index accessor for CUVS indexes.
 * Handles cleanup of failed CUVS indexes and provides index configuration.
 * 
 * <p>CUVS indexes store metadata and vector data on disk, so this accessor
 * is responsible for cleaning up those files when an index fails or is dropped.
 */
public class CuvsMinimalIndexAccessor implements MinimalIndexAccessor {
    private final IndexDescriptor descriptor;
    private final CuvsIndexStorage storage;
    private final boolean readOnly;

    public CuvsMinimalIndexAccessor(IndexDescriptor descriptor, CuvsIndexStorage storage, boolean readOnly) {
        this.descriptor = descriptor;
        this.storage = storage;
        this.readOnly = readOnly;
    }

    @Override
    public void drop() {
        if (readOnly) {
            throw new IllegalStateException("Cannot drop read-only index.");
        }
        
        try {
            storage.drop();
        } catch (IOException e) {
            throw new RuntimeException("Failed to drop CUVS index", e);
        }
    }

    @Override
    public ResourceIterator<Path> snapshotFiles() throws IOException {
        return new ResourceIterator<Path>() {
            private final java.util.Iterator<Path> files = storage.getSnapshotFiles().iterator();

            @Override
            public boolean hasNext() {
                return files.hasNext();
            }

            @Override
            public Path next() {
                return files.next();
            }

            @Override
            public void close() {
                // No cleanup needed for file iteration
            }
        };
    }

    @Override
    public Map<String, Value> indexConfig() {
        return descriptor.getIndexConfig().asMap();
    }
}
