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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.kernel.api.vector.VectorSimilarityFunction;

/**
 * Handles serialization and deserialization of CUVS index data.
 * Since NVIDIA CUVS doesn't provide direct serialization, we store the raw vector data
 * and rebuild the index on load.
 */
public class CuvsIndexSerializer {
    
    private static final int SERIALIZATION_VERSION = 1;
    private static final int MAGIC_NUMBER = 0xC0DE0001;
    
    private final FileSystemAbstraction fileSystem;
    
    public CuvsIndexSerializer(FileSystemAbstraction fileSystem) {
        this.fileSystem = fileSystem;
    }
    
    /**
     * Serializes the CUVS index data to disk.
     */
    public void serializeIndex(CuvsIndexData indexData, Path indexDataFile, Path vectorDataFile) throws IOException {
        // Serialize index metadata
        try (var outputStream = fileSystem.openAsOutputStream(indexDataFile, false)) {
            var dataOutput = new DataOutputStream(outputStream);
            
            // Write header
            dataOutput.writeInt(MAGIC_NUMBER);
            dataOutput.writeInt(SERIALIZATION_VERSION);
            
            // Write index metadata
            dataOutput.writeInt(indexData.metadata.dimensions);
            dataOutput.writeUTF(indexData.metadata.similarityFunction);
            dataOutput.writeInt(indexData.metadata.efConstruction);
            dataOutput.writeInt(indexData.metadata.m);
            dataOutput.writeInt(indexData.metadata.numThreads);
            dataOutput.writeBoolean(false); // isGpuAvailable - not stored in metadata
            dataOutput.writeLong(indexData.metadata.createdTimestamp);
            dataOutput.writeLong(indexData.metadata.lastModified);
            
            // Write vector count
            dataOutput.writeInt(indexData.vectorData.size());
        }
        
        // Serialize vector data
        try (var outputStream = fileSystem.openAsOutputStream(vectorDataFile, false)) {
            var dataOutput = new DataOutputStream(outputStream);
            
            // Write header
            dataOutput.writeInt(MAGIC_NUMBER);
            dataOutput.writeInt(SERIALIZATION_VERSION);
            
            // Write vector count
            dataOutput.writeInt(indexData.vectorData.size());
            
            // Write each vector
            for (var vectorData : indexData.vectorData) {
                dataOutput.writeLong(vectorData.entityId);
                dataOutput.writeInt(vectorData.vector.length);
                for (float value : vectorData.vector) {
                    dataOutput.writeFloat(value);
                }
                // Write metadata if any
                dataOutput.writeInt(vectorData.metadata.size());
                for (var entry : vectorData.metadata.entrySet()) {
                    dataOutput.writeUTF(entry.getKey());
                    dataOutput.writeUTF(entry.getValue());
                }
            }
        }
    }
    
    /**
     * Deserializes the CUVS index data from disk.
     */
    public CuvsIndexData deserializeIndex(Path indexDataFile, Path vectorDataFile) throws IOException {
        // Deserialize index metadata
        IndexMetadata metadata;
        try (var inputStream = fileSystem.openAsInputStream(indexDataFile)) {
            var dataInput = new DataInputStream(inputStream);
            
            // Read and validate header
            int magic = dataInput.readInt();
            if (magic != MAGIC_NUMBER) {
                throw new IOException("Invalid magic number in index file: " + magic);
            }
            
            int version = dataInput.readInt();
            if (version != SERIALIZATION_VERSION) {
                throw new IOException("Unsupported serialization version: " + version);
            }
            
            // Read index metadata
            int dimensions = dataInput.readInt();
            String similarityFunction = dataInput.readUTF();
            int efConstruction = dataInput.readInt();
            int m = dataInput.readInt();
            int numThreads = dataInput.readInt();
            boolean isGpuAvailable = dataInput.readBoolean();
            long createdTimestamp = dataInput.readLong();
            long lastModified = dataInput.readLong();
            
            metadata = new IndexMetadata(
                dimensions, similarityFunction, efConstruction, m, numThreads,
                "1.0", createdTimestamp, lastModified
            );
        }
        
        // Deserialize vector data
        List<VectorData> vectorData = new ArrayList<>();
        try (var inputStream = fileSystem.openAsInputStream(vectorDataFile)) {
            var dataInput = new DataInputStream(inputStream);
            
            // Read and validate header
            int magic = dataInput.readInt();
            if (magic != MAGIC_NUMBER) {
                throw new IOException("Invalid magic number in vector file: " + magic);
            }
            
            int version = dataInput.readInt();
            if (version != SERIALIZATION_VERSION) {
                throw new IOException("Unsupported serialization version: " + version);
            }
            
            // Read vector count
            int vectorCount = dataInput.readInt();
            
            // Read each vector
            for (int i = 0; i < vectorCount; i++) {
                long entityId = dataInput.readLong();
                int vectorLength = dataInput.readInt();
                float[] vector = new float[vectorLength];
                for (int j = 0; j < vectorLength; j++) {
                    vector[j] = dataInput.readFloat();
                }
                
                // Read metadata
                int metadataSize = dataInput.readInt();
                Map<String, String> vectorMetadata = new java.util.HashMap<>();
                for (int j = 0; j < metadataSize; j++) {
                    String key = dataInput.readUTF();
                    String value = dataInput.readUTF();
                    vectorMetadata.put(key, value);
                }
                
                vectorData.add(new VectorData(entityId, vector, vectorMetadata));
            }
        }
        
        return new CuvsIndexData(metadata, vectorData);
    }
    
    /**
     * Checks if the serialized index files exist and are valid.
     */
    public boolean isValidIndex(Path indexDataFile, Path vectorDataFile) {
        if (!fileSystem.fileExists(indexDataFile) || !fileSystem.fileExists(vectorDataFile)) {
            return false;
        }
        
        try {
            // Quick validation by reading headers
            try (var inputStream = fileSystem.openAsInputStream(indexDataFile)) {
                var dataInput = new DataInputStream(inputStream);
                int magic = dataInput.readInt();
                int version = dataInput.readInt();
                return magic == MAGIC_NUMBER && version == SERIALIZATION_VERSION;
            }
        } catch (IOException e) {
            return false;
        }
    }
    
    /**
     * Represents the complete index data to be serialized.
     */
    public static class CuvsIndexData {
        public final IndexMetadata metadata;
        public final List<VectorData> vectorData;
        
        public CuvsIndexData(IndexMetadata metadata, List<VectorData> vectorData) {
            this.metadata = metadata;
            this.vectorData = vectorData;
        }
    }
    
    /**
     * Represents a single vector with its metadata.
     */
    public static class VectorData {
        public final long entityId;
        public final float[] vector;
        public final Map<String, String> metadata;
        
        public VectorData(long entityId, float[] vector, Map<String, String> metadata) {
            this.entityId = entityId;
            this.vector = vector;
            this.metadata = metadata;
        }
    }
    
    /**
     * Represents index metadata.
     */
    public static class IndexMetadata {
        public final int dimensions;
        public final String similarityFunction;
        public final int efConstruction;
        public final int m;
        public final int numThreads;
        public final String indexVersion;
        public final long createdTimestamp;
        public final long lastModified;
        
        public IndexMetadata(int dimensions, String similarityFunction, int efConstruction, 
                           int m, int numThreads, String indexVersion, 
                           long createdTimestamp, long lastModified) {
            this.dimensions = dimensions;
            this.similarityFunction = similarityFunction;
            this.efConstruction = efConstruction;
            this.m = m;
            this.numThreads = numThreads;
            this.indexVersion = indexVersion;
            this.createdTimestamp = createdTimestamp;
            this.lastModified = lastModified;
        }
    }
}
