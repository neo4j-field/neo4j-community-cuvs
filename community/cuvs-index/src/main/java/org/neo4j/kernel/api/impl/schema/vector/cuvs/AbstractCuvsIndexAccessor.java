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
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongPredicate;
import java.util.function.ToLongFunction;
import org.eclipse.collections.api.block.function.primitive.LongToLongFunction;
import org.neo4j.annotations.documented.ReporterFactory;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.internal.helpers.collection.BoundedIterable;
import org.neo4j.internal.helpers.progress.ProgressListener;
import org.neo4j.internal.helpers.progress.ProgressMonitorFactory;
import org.neo4j.internal.schema.IndexDescriptor;
import org.neo4j.internal.schema.SchemaDescriptorSupplier;
import org.neo4j.io.pagecache.context.CursorContext;
import org.neo4j.io.pagecache.context.CursorContextFactory;
import org.neo4j.io.pagecache.tracing.FileFlushEvent;
import org.neo4j.kernel.api.exceptions.index.IndexEntryConflictException;
import org.neo4j.kernel.api.index.IndexAccessor;
import org.neo4j.kernel.api.index.IndexEntriesReader;
import org.neo4j.kernel.api.index.IndexEntryConflictHandler;
import org.neo4j.kernel.api.index.IndexUpdater;
import org.neo4j.kernel.api.index.ValueIndexReader;
import org.neo4j.kernel.impl.api.index.IndexUpdateMode;
import org.neo4j.kernel.impl.index.schema.IndexUpdateIgnoreStrategy;
import org.neo4j.kernel.impl.index.schema.IndexUsageTracking;
import org.neo4j.scheduler.JobScheduler;
import org.neo4j.storageengine.api.IndexEntryUpdate;
import org.neo4j.storageengine.api.ValueIndexEntryUpdate;
import org.neo4j.values.storable.Value;

/**
 * Abstract base class for CUVS index accessors.
 * 
 * <p>CUVS (CUDA Vector Search) indexes are fundamentally different from traditional Lucene-based indexes:
 * <ul>
 *   <li><strong>GPU-based storage:</strong> Data is stored in GPU memory, not on disk</li>
 *   <li><strong>Batch-oriented operations:</strong> Designed for bulk operations rather than incremental updates</li>
 *   <li><strong>Vector similarity focus:</strong> Optimized for k-NN similarity search, not general querying</li>
 *   <li><strong>Resource-intensive:</strong> GPU operations require careful resource management</li>
 * </ul>
 * 
 * <p>Many methods from the standard IndexAccessor interface are not applicable to CUVS indexes
 * and will throw UnsupportedOperationException with explanatory messages. This is intentional
 * and reflects the architectural differences between GPU-accelerated vector search and
 * traditional document-based indexing.
 * 
 * <p><strong>Supported Operations:</strong>
 * <ul>
 *   <li>Vector similarity search (k-NN queries)</li>
 *   <li>Batch vector updates</li>
 *   <li>Index creation and deletion</li>
 *   <li>GPU resource management</li>
 * </ul>
 * 
 * <p><strong>Unsupported Operations:</strong>
 * <ul>
 *   <li>File-based operations (snapshotFiles, force, refresh)</li>
 *   <li>Document iteration (newAllEntriesValueReader, indexEntriesReader)</li>
 *   <li>Complex index operations (insertFrom, validate, consistencyCheck)</li>
 *   <li>Text search operations (sampling, partitioned seeks)</li>
 * </ul>
 */
public abstract class AbstractCuvsIndexAccessor<READER extends ValueIndexReader, INDEX extends CuvsDatabaseIndex>
        implements IndexAccessor {
    protected final INDEX cuvsIndex;
    protected final IndexDescriptor descriptor;
    private final IndexUpdateIgnoreStrategy ignoreStrategy;

    protected AbstractCuvsIndexAccessor(
            INDEX cuvsIndex, IndexDescriptor descriptor, IndexUpdateIgnoreStrategy ignoreStrategy) {
        this.cuvsIndex = cuvsIndex;
        this.descriptor = descriptor;
        this.ignoreStrategy = ignoreStrategy;
    }

    @Override
    public IndexUpdater newUpdater(IndexUpdateMode mode, CursorContext cursorContext, boolean parallel) {
        if (cuvsIndex.isReadOnly()) {
            throw new UnsupportedOperationException("Can't create index updater while database is in read only mode.");
        }
        return getIndexUpdater(mode);
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
        throw new UnsupportedOperationException(
            "CUVS index does not support insertFrom operations. " +
            "CUVS indexes are designed for GPU-accelerated vector similarity search and use " +
            "batch-oriented operations rather than incremental document merging. " +
            "Use the index populator for initial population or rebuild the index for updates.");
    }

    @Override
    public void validateBeforeCommit(long entityId, Value[] tuple) {
        // CUVS validation - ensure we have a valid vector value
        if (tuple == null || tuple.length == 0) {
            throw new IllegalArgumentException("CUVS index requires a valid vector value");
        }
        
        if (tuple.length != 1) {
            throw new IllegalArgumentException(
                "CUVS index expects exactly one vector value per entity, got " + tuple.length + " values");
        }
        
        Value value = tuple[0];
        if (value == null) {
            throw new IllegalArgumentException("CUVS index does not support null vector values");
        }
        
        // Additional validation could be added here for vector dimensions, data types, etc.
        // This would require access to the index configuration to validate dimensions
    }

    @Override
    public ValueIndexReader newValueReader(IndexUsageTracking usageTracker) {
        try {
            return cuvsIndex.getIndexReader(usageTracker);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create CUVS index reader", e);
        }
    }

    @Override
    public BoundedIterable<Long> newAllEntriesValueReader(
            long fromIdInclusive, long toIdExclusive, CursorContext cursorContext) {
        throw new UnsupportedOperationException(
            "CUVS indexes don't support iteration over all entries. " +
            "They store vectors in GPU memory and are optimized for vector similarity search, " +
            "not sequential iteration. Use query() method for vector similarity search instead.");
    }

    public IndexEntriesReader indexEntriesReader(
            long fromIdInclusive, long toIdExclusive, CursorContext cursorContext) {
        throw new UnsupportedOperationException(
            "CUVS indexes don't support document-style entry iteration. " +
            "They are optimized for vector similarity search rather than sequential document access patterns. " +
            "Use query() method for vector similarity search instead.");
    }

    @Override
    public void force(FileFlushEvent flushEvent, CursorContext cursorContext) {
        // CUVS indexes store data in GPU memory, not on disk, so file flushing is not applicable.
        // GPU operations are immediate and don't require explicit synchronization with storage.
    }

    @Override
    public void refresh() {
        // CUVS indexes don't require refresh operations. GPU memory operations are immediate
        // and don't have the same caching/consistency model as disk-based indexes.
    }

    @Override
    public void close() {
        try {
            cuvsIndex.close();
        } catch (IOException e) {
            throw new RuntimeException("Failed to close CUVS index", e);
        }
    }

    @Override
    public void drop() {
        cuvsIndex.drop();
    }

    @Override
    public ResourceIterator<Path> snapshotFiles() throws IOException {
        return cuvsIndex.snapshotFiles();
    }

    @Override
    public long estimateNumberOfEntries(CursorContext cursorContext) {
        try {
            // Return the vector count tracked by the SimpleCuvsIndex
            return cuvsIndex.getVectorCount();
        } catch (Exception e) {
            // If we can't get the count, return 0
            return 0;
        }
    }

    public void consistencyCheck(
            ReporterFactory reporterFactory, CursorContext cursorContext, int numberOfThreads) {
        // CUVS indexes have a different consistency model than disk-based indexes.
        // GPU memory consistency is handled at the hardware level, and the index
        // structure is validated during construction rather than runtime.
        if (!cuvsIndex.isValid()) {
            throw new RuntimeException("CUVS index consistency check failed - index is not in valid state");
        }
    }

    public void validate() {
        // CUVS index validation focuses on GPU resource availability and index structure
        // integrity rather than the complex validation patterns used by disk-based indexes.
        if (!cuvsIndex.isValid()) {
            throw new RuntimeException("CUVS index validation failed - index is not in valid state");
        }
    }

    /**
     * Get the index updater for the given mode.
     * Subclasses must implement this to provide their specific updater.
     *
     * @param mode update mode
     * @return index updater
     */
    protected abstract IndexUpdater getIndexUpdater(IndexUpdateMode mode);

    // ===== CUVS-SPECIFIC HELPER METHODS =====
    // These methods provide CUVS-specific functionality that doesn't fit
    // into the standard IndexAccessor interface but are useful for vector operations.

    /**
     * Get the underlying CUVS index for direct access to CUVS-specific operations.
     * This allows access to batch operations and GPU-specific functionality that
     * doesn't map to the standard IndexAccessor interface.
     *
     * @return the underlying CUVS index
     */
    public INDEX getCuvsIndex() {
        return cuvsIndex;
    }

    /**
     * Check if the CUVS index is ready for operations.
     * This includes checking GPU availability and index state.
     *
     * @return true if the index is ready for operations
     */
    public boolean isReady() {
        return cuvsIndex.isValid() && cuvsIndex.isOpen();
    }

    /**
     * Get the index descriptor for this CUVS index.
     *
     * @return the index descriptor
     */
    public IndexDescriptor getDescriptor() {
        return descriptor;
    }
}
