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

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.LongPredicate;
import org.eclipse.collections.api.block.function.primitive.LongToLongFunction;
import org.neo4j.annotations.documented.ReporterFactory;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.internal.helpers.collection.BoundedIterable;
import org.neo4j.internal.helpers.progress.ProgressListener;
import org.neo4j.internal.helpers.progress.ProgressMonitorFactory;
import org.neo4j.internal.schema.IndexDescriptor;
import org.neo4j.io.pagecache.context.CursorContext;
import org.neo4j.io.pagecache.context.CursorContextFactory;
import org.neo4j.io.pagecache.tracing.FileFlushEvent;
import org.neo4j.kernel.api.exceptions.index.IndexEntryConflictException;
import org.neo4j.kernel.api.index.IndexAccessor;
import org.neo4j.kernel.api.index.IndexEntryConflictHandler;
import org.neo4j.kernel.api.index.IndexUpdater;
import org.neo4j.kernel.api.index.ValueIndexReader;
import org.neo4j.storageengine.api.IndexEntryUpdate;
import org.neo4j.kernel.api.vector.VectorCandidate;
import org.neo4j.kernel.impl.api.index.IndexUpdateMode;
import org.neo4j.kernel.impl.index.schema.ConsistencyCheckable;
import org.neo4j.kernel.impl.index.schema.IndexUsageTracking;
import org.neo4j.kernel.impl.index.schema.IndexUpdateIgnoreStrategy;
import org.neo4j.scheduler.JobScheduler;
import org.neo4j.values.storable.Value;

/**
 * CUVS (CUDA Vector Search) index accessor implementation.
 * Provides read and write access to a CUVS index for online operations.
 */
public class CuvsIndexAccessor extends AbstractCuvsIndexAccessor<CuvsIndexReader, CagraCuvsIndexImpl> {
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile boolean closed = false;

    public CuvsIndexAccessor(CagraCuvsIndexImpl cuvsIndex, IndexDescriptor descriptor, IndexUpdateIgnoreStrategy ignoreStrategy) {
        super(cuvsIndex, descriptor, ignoreStrategy);
        
        // Try to load existing index from disk
        try {
            if (cuvsIndex.hasPersistedData()) {
                cuvsIndex.load();
            }
        } catch (IOException e) {
            // If loading fails, we'll start with an empty index
            System.err.println("Failed to load CUVS index from disk: " + e.getMessage());
        }
    }

    @Override
    protected IndexUpdater getIndexUpdater(IndexUpdateMode mode) {
        lock.readLock().lock();
        try {
            if (closed) {
                throw new IllegalStateException("Index accessor is closed");
            }
            return new CuvsIndexUpdater(mode.requiresIdempotency(), mode.requiresRefresh());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void drop() {
        lock.writeLock().lock();
        try {
            if (!closed) {
                cuvsIndex.close();
                closed = true;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void force(FileFlushEvent flushEvent, CursorContext cursorContext) {
        // CUVS index doesn't require explicit flushing - GPU operations are immediate
        // This is a no-op for now, but could be used for GPU memory synchronization
    }

    @Override
    public void refresh() {
        // CUVS index doesn't require refresh - GPU operations are immediate
        // This is a no-op for now
    }

    @Override
    public void close() {
        lock.writeLock().lock();
        try {
            if (!closed) {
                cuvsIndex.close();
                closed = true;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public BoundedIterable<Long> newAllEntriesValueReader(long fromIdInclusive, long toIdExclusive, CursorContext cursorContext) {
        lock.readLock().lock();
        try {
            if (closed) {
                throw new IllegalStateException("Index accessor is closed");
            }
            
            // For CUVS, we don't have a traditional "all entries" reader
            // This would need to be implemented based on how CUVS stores entity IDs
            // For now, return an empty bounded iterable
            return new BoundedIterable<Long>() {
                @Override
                public long maxCount() {
                    return 0;
                }

                @Override
                public void close() {}

                @Override
                public Iterator<Long> iterator() {
                    return java.util.Collections.emptyIterator();
                }
            };
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public long estimateNumberOfEntries(CursorContext cursorContext) {
        lock.readLock().lock();
        try {
            if (closed) {
                return 0;
            }
            
            // Get vector count from CUVS index statistics
            var stats = cuvsIndex.getStatistics();
            return (Long) stats.get("vectorCount");
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void validateBeforeCommit(long entityId, Value[] tuple) {
        // Validate that the value is a valid vector
        if (tuple.length != 1) {
            throw new IllegalArgumentException("CUVS index expects exactly one vector value");
        }
        
        Value value = tuple[0];
        if (value == null) {
            throw new IllegalArgumentException("CUVS index does not support null values");
        }
        
        // Validate that it's a floating point array (vector)
        VectorCandidate candidate = VectorCandidate.maybeFrom(value);
        if (candidate == null) {
            throw new IllegalArgumentException("CUVS index requires a valid vector value");
        }
    }



    @Override
    public Map<String, Value> indexConfig() {
        return java.util.Map.of();
    }

    @Override
    public boolean consistencyCheck(
            ReporterFactory reporterFactory,
            CursorContextFactory contextFactory,
            int numThreads,
            ProgressMonitorFactory progressMonitorFactory) {
        try {
            // Basic consistency check for CUVS index
            // Check if the index is properly initialized and accessible
            if (cuvsIndex == null) {
                return false; // Index not initialized
            }
            
            // Basic consistency checks:
            // 1. Check if the index is properly initialized
            if (!cuvsIndex.isInitialized()) {
                return false;
            }
            
            // 2. Check if we can access basic index properties
            try {
                int vectorCount = cuvsIndex.getVectorCount();
                int dimensions = cuvsIndex.getDimensions();
                
                if (vectorCount < 0) {
                    return false;
                }
                
                if (dimensions <= 0) {
                    return false;
                }
                
                // Basic consistency check passed
                return true;
                
            } catch (Exception e) {
                // If we can't access index properties, consistency check fails
                return false;
            }
            
        } catch (Exception e) {
            // If consistency check fails, return false
            return false;
        }
    }

    @Override
    public long sizeInBytes() {
        try {
            // Basic size estimation for CUVS index
            if (cuvsIndex == null) {
                return 0; // Index not initialized
            }
            
            // Get vector count and dimensions from the CagraCuvsIndexImpl
            long vectorCount = cuvsIndex.getVectorCount();
            int dimensions = cuvsIndex.getDimensions();
            
            // Estimate: 4 bytes per float * dimensions * vector count
            // Plus some overhead for HNSW structure
            long estimatedSize = (long) vectorCount * dimensions * 4 * 2; // 2x for HNSW overhead
            return estimatedSize;
        } catch (Exception e) {
            // If size calculation fails, return 0
            return 0;
        }
    }

    @Override
    public void insertFrom(
            IndexAccessor other,
            LongToLongFunction entityIdConverter,
            boolean valueUniqueness,
            IndexEntryConflictHandler conflictHandler,
            LongPredicate entityFilter,
            int threads,
            JobScheduler jobScheduler,
            ProgressListener progress)
            throws IndexEntryConflictException {
        // CUVS doesn't support insertFrom yet
    }

    @Override
    public void validate(
            IndexAccessor other,
            boolean valueUniqueness,
            IndexEntryConflictHandler conflictHandler,
            LongPredicate entityFilter,
            int threads,
            JobScheduler jobScheduler) {
        // CUVS doesn't support validation yet
    }

    /**
     * Get the underlying CUVS index for direct access if needed.
     * @return the CagraCuvsIndexImpl instance
     */
    public CagraCuvsIndexImpl getCuvsIndex() {
        return cuvsIndex;
    }

    /**
     * CUVS-specific index updater implementation.
     */
    private class CuvsIndexUpdater implements IndexUpdater {
        private final boolean idempotent;
        private final boolean refresh;

        CuvsIndexUpdater(boolean idempotent, boolean refresh) {
            this.idempotent = idempotent;
            this.refresh = refresh;
        }

        @Override
        public void process(IndexEntryUpdate<?> update) throws IndexEntryConflictException {
            lock.writeLock().lock();
            try {
                if (closed) {
                    throw new IllegalStateException("Index accessor is closed");
                }

                long entityId = update.getEntityId();
                Value[] values = ((org.neo4j.storageengine.api.ValueIndexEntryUpdate<?>) update).values();

                switch (update.updateMode()) {
                    case ADDED:
                        add(entityId, values);
                        break;
                    case CHANGED:
                        change(entityId, values);
                        break;
                    case REMOVED:
                        remove(entityId);
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown update mode: " + update.updateMode());
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } finally {
                lock.writeLock().unlock();
            }
        }

        private void add(long entityId, Value[] values) throws IOException {
            VectorCandidate candidate = VectorCandidate.maybeFrom(values[0]);
            if (candidate != null) {
                // Convert to SimpleCuvsIndex.VectorData format
                float[] vector = new float[candidate.dimensions()];
                for (int i = 0; i < candidate.dimensions(); i++) {
                    vector[i] = candidate.floatElement(i);
                }
                var vectorData = new CagraCuvsIndexImpl.VectorData(entityId, vector, java.util.Map.of());
                
                // Use addVector() for incremental updates (not addVectors() for bulk loading)
                cuvsIndex.addVector(vectorData);
                
                // Save the index after updates
                try {
                    cuvsIndex.save();
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to save CUVS index after update", e);
                }
            }
        }

        private void change(long entityId, Value[] values) throws IOException {
            // For CUVS, we need to remove the old vector and add the new one
            // Since CUVS doesn't have direct update, we'll remove and re-add
            remove(entityId);
            add(entityId, values);
        }

        private void remove(long entityId) throws IOException {
            // CUVS doesn't have direct remove capability in the current mock implementation
            // This would need to be implemented when integrating with real CUVS API
            // For now, this is a no-op
        }

        @Override
        public void close() {
            // No cleanup needed for CUVS updater
        }
    }
}
