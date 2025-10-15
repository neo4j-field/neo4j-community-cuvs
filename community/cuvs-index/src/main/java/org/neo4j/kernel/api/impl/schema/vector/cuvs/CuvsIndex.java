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
import java.util.List;
import org.neo4j.internal.schema.IndexDescriptor;
import org.neo4j.kernel.api.index.ValueIndexReader;

/**
 * Base interface for all CUVS vector indexes.
 * Defines common operations that all CUVS index implementations must support.
 */
public interface CuvsIndex {
    
    /**
     * Initialize the index with the given descriptor.
     * @param descriptor The index descriptor containing configuration
     * @throws IOException if initialization fails
     */
    void initialize(IndexDescriptor descriptor) throws IOException;
    
    /**
     * Add a single vector to the index.
     * @param vectorData The vector data to add
     * @throws IOException if the operation fails
     */
    void addVector(CagraCuvsIndexImpl.VectorData vectorData) throws IOException;
    
    /**
     * Add multiple vectors to the index in batch.
     * @param vectorsToAdd List of vectors to add
     * @throws IOException if the operation fails
     */
    void addVectors(List<CagraCuvsIndexImpl.VectorData> vectorsToAdd) throws IOException;
    
    /**
     * Load the index state from disk.
     * @throws IOException if loading fails
     */
    void load() throws IOException;
    
    /**
     * Close the index and release resources.
     * @throws IOException if closing fails
     */
    void close() throws IOException;
    
    /**
     * Drop the index and delete all associated data.
     * @throws IOException if the operation fails
     */
    void drop() throws IOException;
    
    /**
     * Check if the index is initialized and ready for use.
     * @return true if initialized, false otherwise
     */
    boolean isInitialized();
    
    /**
     * Get the current number of vectors in the index.
     * @return the vector count
     */
    int getVectorCount();
    
    /**
     * Get the vector dimensions for this index.
     * @return the number of dimensions
     */
    int getDimensions();
    
    /**
     * Get the index reader for performing searches.
     * @return the index reader
     */
    ValueIndexReader getReader();
    
    /**
     * Check if the index has persisted data on disk.
     * @return true if persisted data exists
     */
    boolean hasPersistedData();
    
    /**
     * Get the underlying CUVS resources.
     * @return the CUVS resources, or null if not initialized
     */
    Object getCuvsResources();
    
    /**
     * Get the underlying CUVS index object.
     * @return the CUVS index, or null if not initialized
     */
    Object getCuvsIndex();
    
    /**
     * Data class representing a vector with its associated node ID.
     */
    class VectorData {
        private final long nodeId;
        private final float[] vector;
        private final java.util.Map<String, Object> metadata;
        
        public VectorData(long nodeId, float[] vector) {
            this(nodeId, vector, java.util.Collections.emptyMap());
        }
        
        public VectorData(long nodeId, float[] vector, java.util.Map<String, Object> metadata) {
            this.nodeId = nodeId;
            this.vector = vector;
            this.metadata = metadata != null ? metadata : java.util.Collections.emptyMap();
        }
        
        public long getNodeId() {
            return nodeId;
        }
        
        public float[] getVector() {
            return vector;
        }
        
        public java.util.Map<String, Object> getMetadata() {
            return metadata;
        }
    }
}
