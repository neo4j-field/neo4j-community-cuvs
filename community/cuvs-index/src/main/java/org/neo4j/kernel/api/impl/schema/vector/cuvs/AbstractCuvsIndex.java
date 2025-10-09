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
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.internal.schema.IndexDescriptor;
import org.neo4j.kernel.api.IndexFileSnapshotter;
import org.neo4j.kernel.api.index.ValueIndexReader;
import org.neo4j.kernel.impl.index.schema.IndexUsageTracking;

/**
 * Abstract base class for CUVS indexes.
 * 
 * <p>This class provides common functionality for CUVS-based indexes, similar to how
 * {@code AbstractLuceneIndex} works for Lucene-based indexes. It implements the core
 * lifecycle management and state tracking that all CUVS indexes need.
 * 
 * <p>CUVS indexes are fundamentally different from Lucene indexes:
 * <ul>
 *   <li><strong>GPU-based storage:</strong> Data is stored in GPU memory, not on disk</li>
 *   <li><strong>Resource management:</strong> GPU resources require careful lifecycle management</li>
 *   <li><strong>Batch operations:</strong> Optimized for bulk operations rather than incremental updates</li>
 *   <li><strong>Vector similarity:</strong> Specialized for k-NN similarity search</li>
 * </ul>
 * 
 * <p>Subclasses must implement the abstract methods to provide CUVS-specific functionality
 * while inheriting the common index lifecycle management.
 * 
 * @param <READER> the type of index reader this index produces
 */
public abstract class AbstractCuvsIndex<READER extends ValueIndexReader> 
        implements CuvsDatabaseIndex, IndexFileSnapshotter {
    
    protected final IndexDescriptor descriptor;
    private final AtomicBoolean open = new AtomicBoolean(false);
    private final AtomicBoolean created = new AtomicBoolean(false);
    private volatile boolean readOnly = false;
    private volatile boolean permanentlyReadOnly = false;
    private volatile boolean valid = true;

    protected AbstractCuvsIndex(IndexDescriptor descriptor) {
        this.descriptor = descriptor;
    }

    @Override
    public void create() throws IOException {
        if (created.get()) {
            throw new IllegalStateException("Index has already been created");
        }
        if (open.get()) {
            throw new IllegalStateException("Cannot create index while it's open");
        }
        
        try {
            doCreate();
            created.set(true);
        } catch (Exception e) {
            valid = false;
            throw new IOException("Failed to create CUVS index", e);
        }
    }

    @Override
    public void open() throws IOException {
        if (!created.get()) {
            throw new IllegalStateException("Index must be created before opening");
        }
        if (open.get()) {
            throw new IllegalStateException("Index is already open");
        }
        
        try {
            doOpen();
            open.set(true);
        } catch (Exception e) {
            valid = false;
            throw new IOException("Failed to open CUVS index", e);
        }
    }

    @Override
    public boolean isOpen() {
        return open.get();
    }

    @Override
    public boolean exists() throws IOException {
        return created.get();
    }

    @Override
    public boolean isValid() {
        return valid;
    }

    @Override
    public IndexDescriptor getDescriptor() {
        return descriptor;
    }

    @Override
    public boolean isReadOnly() {
        return readOnly;
    }

    @Override
    public boolean isPermanentlyOnly() {
        return permanentlyReadOnly;
    }

    @Override
    public void close() throws IOException {
        if (open.get()) {
            try {
                doClose();
            } finally {
                open.set(false);
            }
        }
    }

    @Override
    public void drop() {
        try {
            if (open.get()) {
                try {
                    close();
                } catch (IOException e) {
                    // Log but don't fail drop operation
                    // CUVS indexes are in-memory, so close failures are not critical
                }
            }
            try {
                doDrop();
            } catch (IOException e) {
                // Log but don't fail drop operation
                // CUVS indexes are in-memory, so drop failures are not critical
            }
        } finally {
            created.set(false);
            valid = false;
        }
    }

    @Override
    public ResourceIterator<Path> snapshotFiles() throws IOException {
        // CUVS indexes store data in GPU memory, so there are no traditional "files" to snapshot.
        // This method returns an empty iterator as GPU memory snapshots are handled differently.
        return new ResourceIterator<Path>() {
            @Override
            public boolean hasNext() {
                return false;
            }

            @Override
            public Path next() {
                throw new UnsupportedOperationException("No files to iterate");
            }

            @Override
            public void close() {
                // No cleanup needed
            }
        };
    }

    /**
     * Mark this index as read-only.
     * 
     * @param permanently if true, the index cannot be made writable again
     */
    protected void setReadOnly(boolean permanently) {
        this.readOnly = true;
        this.permanentlyReadOnly = permanently;
    }

    /**
     * Mark this index as invalid.
     * This should be called when the index enters an unrecoverable error state.
     */
    protected void markInvalid() {
        this.valid = false;
    }

    // ===== ABSTRACT METHODS TO BE IMPLEMENTED BY SUBCLASSES =====

    /**
     * Perform the actual index creation.
     * This method is called by {@link #create()} after validation.
     * 
     * @throws IOException if creation fails
     */
    protected abstract void doCreate() throws IOException;

    /**
     * Perform the actual index opening.
     * This method is called by {@link #open()} after validation.
     * 
     * @throws IOException if opening fails
     */
    protected abstract void doOpen() throws IOException;

    /**
     * Perform the actual index closing.
     * This method is called by {@link #close()} after validation.
     * 
     * @throws IOException if closing fails
     */
    protected abstract void doClose() throws IOException;

    /**
     * Perform the actual index dropping.
     * This method is called by {@link #drop()} after validation.
     * 
     * @throws IOException if dropping fails
     */
    protected abstract void doDrop() throws IOException;
}
