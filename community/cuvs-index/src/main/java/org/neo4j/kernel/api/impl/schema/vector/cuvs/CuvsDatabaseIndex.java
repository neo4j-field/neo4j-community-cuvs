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
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.internal.schema.IndexDescriptor;
import org.neo4j.kernel.api.IndexFileSnapshotter;
import org.neo4j.kernel.api.index.ValueIndexReader;
import org.neo4j.kernel.impl.index.schema.IndexUsageTracking;

/**
 * CUVS index that provides the standard database index interface.
 * This is the CUVS equivalent of DatabaseIndex for Lucene.
 */
public interface CuvsDatabaseIndex extends IndexFileSnapshotter, Closeable {
    /**
     * Creates new index.
     * As part of creation process index will allocate all required folders and resources.
     * <p>
     * <b>Index creation does not automatically open it. To be able to use index please open it first.</b>
     *
     * @throws IOException
     */
    void create() throws IOException;

    /**
     * Open index with all allocated resources.
     *
     * @throws IOException
     */
    void open() throws IOException;

    /**
     * Check if index is open.
     *
     * @return true if index is open, false otherwise
     */
    boolean isOpen();

    /**
     * Check if index exists.
     *
     * @return true if index exists, false otherwise
     * @throws IOException
     */
    boolean exists() throws IOException;

    /**
     * Verify state of the index.
     *
     * @return true if index is in valid state, false otherwise
     */
    boolean isValid();

    /**
     * Get index reader for querying.
     *
     * @param usageTracker usage tracking for the reader
     * @return index reader
     * @throws IOException
     */
    ValueIndexReader getIndexReader(IndexUsageTracking usageTracker) throws IOException;

    /**
     * Get the index descriptor.
     *
     * @return index descriptor
     */
    IndexDescriptor getDescriptor();

    /**
     * Check if index is read-only.
     *
     * @return true if read-only, false otherwise
     */
    boolean isReadOnly();

    /**
     * Check if index is permanently read-only.
     *
     * @return true if permanently read-only, false otherwise
     */
    boolean isPermanentlyOnly();

    /**
     * Close index and deletes all its resources.
     */
    void drop();

    /**
     * Get snapshot files for backup/clustering.
     *
     * @return iterator over snapshot files
     * @throws IOException
     */
    ResourceIterator<Path> snapshotFiles() throws IOException;
    
    /**
     * Get the current vector count.
     * @return Number of vectors in the index
     */
    int getVectorCount();
    
    /**
     * Get the vector dimensions.
     * @return Number of dimensions per vector
     */
    int getDimensions();
}
