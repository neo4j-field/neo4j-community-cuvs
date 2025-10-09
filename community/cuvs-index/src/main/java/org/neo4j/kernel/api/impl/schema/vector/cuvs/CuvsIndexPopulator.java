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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.neo4j.internal.schema.IndexDescriptor;
import org.neo4j.io.pagecache.context.CursorContext;
import org.neo4j.kernel.api.index.IndexPopulator;
import org.neo4j.kernel.api.index.IndexSample;
import org.neo4j.kernel.api.index.IndexUpdater;
import org.neo4j.kernel.api.vector.VectorCandidate;
import org.neo4j.kernel.impl.index.schema.IndexUpdateIgnoreStrategy;
import org.neo4j.storageengine.api.IndexEntryUpdate;
import org.neo4j.storageengine.api.ValueIndexEntryUpdate;

/**
 * CUVS index populator for initial population (bulk loading).
 * Handles batch operations during index creation, similar to VectorIndexPopulator.
 */
public class CuvsIndexPopulator implements IndexPopulator {
    private final SimpleCuvsIndex cuvsIndex;
    private final IndexDescriptor descriptor;
    private final IndexUpdateIgnoreStrategy ignoreStrategy;
    private final List<SimpleCuvsIndex.VectorData> pendingVectors = new ArrayList<>();
    private boolean closed = false;

    public CuvsIndexPopulator(
            SimpleCuvsIndex cuvsIndex,
            IndexDescriptor descriptor,
            IndexUpdateIgnoreStrategy ignoreStrategy) {
        this.cuvsIndex = cuvsIndex;
        this.descriptor = descriptor;
        this.ignoreStrategy = ignoreStrategy;
    }

    @Override
    public void create() throws IOException {
        // CUVS index creation is handled by SimpleCuvsIndex
        // This is a no-op as the index is already created
    }

    @Override
    public IndexUpdater newPopulatingUpdater(CursorContext cursorContext) {
        if (closed) {
            throw new IllegalStateException("Populator is closed");
        }
        return new CuvsIndexPopulatingUpdater();
    }

    @Override
    public void close(boolean populationCompletedSuccessfully, CursorContext cursorContext) {
        if (closed) {
            return;
        }
        
        try {
            if (populationCompletedSuccessfully && !pendingVectors.isEmpty()) {
                // Batch add all pending vectors to the CUVS index
                cuvsIndex.addVectors(pendingVectors);
                pendingVectors.clear();
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to add vectors to CUVS index", e);
        } finally {
            closed = true;
        }
    }

    @Override
    public void markAsFailed(String failure) {
        // CUVS doesn't have a specific failure marking mechanism
        // The index will be invalidated through the normal lifecycle
    }

    public void add(Collection<? extends IndexEntryUpdate<?>> updates, CursorContext cursorContext) {
        for (IndexEntryUpdate<?> update : updates) {
            if (update instanceof ValueIndexEntryUpdate<?> valueUpdate) {
                add(valueUpdate, cursorContext);
            }
        }
    }

    public void add(ValueIndexEntryUpdate<?> update, CursorContext cursorContext) {
        if (closed) {
            throw new IllegalStateException("Populator is closed");
        }
        
        if (ignoreStrategy.ignore(update.values())) {
            return;
        }
        
        // Convert update to VectorData and add to pending list
        long entityId = update.getEntityId();
        VectorCandidate candidate = VectorCandidate.maybeFrom(update.values()[0]);
        
        if (candidate != null) {
            // Convert vector to float array
            float[] vector = new float[candidate.dimensions()];
            for (int i = 0; i < candidate.dimensions(); i++) {
                vector[i] = candidate.floatElement(i);
            }
            
            SimpleCuvsIndex.VectorData vectorData = new SimpleCuvsIndex.VectorData(
                entityId, 
                vector, 
                java.util.Map.of()
            );
            
            pendingVectors.add(vectorData);
        }
    }

    public void includeSample(IndexEntryUpdate<?> update) {
        // CUVS doesn't use sampling - just add to pending vectors
        if (update instanceof ValueIndexEntryUpdate<?> valueUpdate) {
            add(valueUpdate, null); // CursorContext not needed for this operation
        }
    }

    public boolean sampleCompleted() {
        // CUVS doesn't use sampling - always return true
        return true;
    }

    @Override
    public void drop() {
        // CUVS index dropping is handled by SimpleCuvsIndex
        // This is a no-op as the index handles its own cleanup
    }

    @Override
    public org.neo4j.graphdb.ResourceIterator<java.nio.file.Path> snapshotFiles() throws java.io.IOException {
        // CUVS indexes don't have traditional files to snapshot
        // Return empty iterator as GPU memory snapshots are handled differently
        return new org.neo4j.graphdb.ResourceIterator<java.nio.file.Path>() {
            @Override
            public boolean hasNext() {
                return false;
            }

            @Override
            public java.nio.file.Path next() {
                throw new java.util.NoSuchElementException("No files to iterate");
            }

            @Override
            public void close() {
                // No cleanup needed
            }
        };
    }

    @Override
    public IndexSample sample(CursorContext cursorContext) {
        // CUVS doesn't use traditional sampling
        // Return a basic sample with the current vector count
        return new IndexSample(pendingVectors.size(), pendingVectors.size(), pendingVectors.size());
    }

    /**
     * CUVS-specific populating updater that batches updates.
     */
    private class CuvsIndexPopulatingUpdater implements IndexUpdater {
        @Override
        public void process(org.neo4j.storageengine.api.IndexEntryUpdate<?> update) {
            if (update instanceof ValueIndexEntryUpdate<?> valueUpdate) {
                add(valueUpdate, null); // CursorContext not needed for this operation
            }
        }

        @Override
        public void close() {
            // No cleanup needed - updates are batched
        }
    }
}