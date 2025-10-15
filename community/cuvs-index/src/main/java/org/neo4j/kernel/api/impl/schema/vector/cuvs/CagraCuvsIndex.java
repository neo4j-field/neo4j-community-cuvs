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

/**
 * CAGRA-specific interface for CUVS vector indexes.
 * Extends the base CuvsIndex interface with CAGRA-specific operations.
 */
public interface CagraCuvsIndex extends CuvsIndex {
    
    /**
     * Get the underlying CAGRA index object.
     * @return the CAGRA index, or null if not initialized
     */
    com.nvidia.cuvs.CagraIndex getCagraIndex();
    
    /**
     * Get the CAGRA index parameters.
     * @return the CAGRA parameters, or null if not initialized
     */
    com.nvidia.cuvs.CagraIndexParams getCagraParams();
    
    /**
     * Serialize the CAGRA index to disk for fast loading later.
     * @throws IOException if serialization fails
     */
    void serializeIndex() throws IOException;
    
    /**
     * Deserialize the CAGRA index from disk.
     * @throws IOException if deserialization fails
     */
    void deserializeIndex() throws IOException;
    
    /**
     * Check if a serialized CAGRA index exists on disk.
     * @return true if serialized index exists
     */
    boolean hasSerializedIndex();
    
    /**
     * Get the CUVS resources used by this index.
     * @return the CUVS resources, or null if not initialized
     */
    com.nvidia.cuvs.CuVSResources getCuvsResources();
}
