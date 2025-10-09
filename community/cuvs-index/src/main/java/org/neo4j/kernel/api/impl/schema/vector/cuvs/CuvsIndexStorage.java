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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.neo4j.internal.schema.IndexDescriptor;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.kernel.api.vector.VectorSimilarityFunction;

/**
 * Manages file storage for CUVS indexes.
 * Handles directory structure, metadata persistence, and index file management.
 */
public class CuvsIndexStorage {
    
    // File names for different components
    private static final String METADATA_FILE = "cuvs_metadata.properties";
    private static final String INDEX_DATA_FILE = "cuvs_index.bin";
    private static final String VECTOR_DATA_FILE = "cuvs_vectors.bin";
    private static final String STATE_FILE = "cuvs_state.properties";
    
    // Metadata keys
    private static final String KEY_DIMENSIONS = "dimensions";
    private static final String KEY_SIMILARITY_FUNCTION = "similarity_function";
    private static final String KEY_EF_CONSTRUCTION = "ef_construction";
    private static final String KEY_M = "m";
    private static final String KEY_NUM_THREADS = "num_threads";
    private static final String KEY_INDEX_VERSION = "index_version";
    private static final String KEY_CREATED_TIMESTAMP = "created_timestamp";
    private static final String KEY_LAST_MODIFIED = "last_modified";
    
    // State keys
    private static final String KEY_INDEX_STATE = "index_state";
    private static final String KEY_VECTOR_COUNT = "vector_count";
    private static final String KEY_IS_GPU_AVAILABLE = "is_gpu_available";
    
    private final Path indexDirectory;
    private final FileSystemAbstraction fileSystem;
    private final IndexDescriptor descriptor;
    
    public CuvsIndexStorage(Path indexDirectory, FileSystemAbstraction fileSystem, IndexDescriptor descriptor) {
        this.indexDirectory = indexDirectory;
        this.fileSystem = fileSystem;
        this.descriptor = descriptor;
    }
    
    /**
     * Creates the index directory structure and initializes storage.
     */
    public void create() throws IOException {
        if (!fileSystem.fileExists(indexDirectory)) {
            fileSystem.mkdirs(indexDirectory);
        }
        
        // Create initial metadata file
        createMetadataFile();
        createStateFile();
    }
    
    /**
     * Opens existing index storage.
     */
    public void open() throws IOException {
        if (!fileSystem.fileExists(indexDirectory)) {
            throw new IOException("Index directory does not exist: " + indexDirectory);
        }
        
        if (!fileSystem.fileExists(getMetadataFile())) {
            throw new IOException("Index metadata file does not exist: " + getMetadataFile());
        }
    }
    
    /**
     * Drops the index storage (removes all files).
     */
    public void drop() throws IOException {
        if (fileSystem.fileExists(indexDirectory)) {
            fileSystem.deleteRecursively(indexDirectory);
        }
    }
    
    /**
     * Checks if the index storage exists and is valid.
     */
    public boolean exists() {
        return fileSystem.fileExists(indexDirectory) && 
               fileSystem.fileExists(getMetadataFile());
    }
    
    /**
     * Gets the path to the metadata file.
     */
    public Path getMetadataFile() {
        return indexDirectory.resolve(METADATA_FILE);
    }
    
    /**
     * Gets the path to the index data file.
     */
    public Path getIndexDataFile() {
        return indexDirectory.resolve(INDEX_DATA_FILE);
    }
    
    /**
     * Gets the path to the vector data file.
     */
    public Path getVectorDataFile() {
        return indexDirectory.resolve(VECTOR_DATA_FILE);
    }
    
    /**
     * Gets the path to the state file.
     */
    public Path getStateFile() {
        return indexDirectory.resolve(STATE_FILE);
    }
    
    /**
     * Gets the index directory.
     */
    public Path getIndexDirectory() {
        return indexDirectory;
    }
    
    /**
     * Creates the initial metadata file with index configuration.
     */
    private void createMetadataFile() throws IOException {
        Properties metadata = new Properties();
        metadata.setProperty(KEY_INDEX_VERSION, "1.0");
        metadata.setProperty(KEY_CREATED_TIMESTAMP, String.valueOf(System.currentTimeMillis()));
        metadata.setProperty(KEY_LAST_MODIFIED, String.valueOf(System.currentTimeMillis()));
        
        // Extract configuration from descriptor
        Map<String, org.neo4j.values.storable.Value> config = descriptor.getIndexConfig().asMap();
        
        // Set dimensions
        if (config.containsKey("vector_dimensions")) {
            metadata.setProperty(KEY_DIMENSIONS, config.get("vector_dimensions").toString());
        }
        
        // Set similarity function
        if (config.containsKey("vector_similarity_function")) {
            metadata.setProperty(KEY_SIMILARITY_FUNCTION, config.get("vector_similarity_function").toString());
        }
        
        // Set CUVS-specific parameters (with defaults)
        metadata.setProperty(KEY_EF_CONSTRUCTION, "100");
        metadata.setProperty(KEY_M, "16");
        metadata.setProperty(KEY_NUM_THREADS, "2");
        
        writePropertiesFile(getMetadataFile(), metadata);
    }
    
    /**
     * Creates the initial state file.
     */
    private void createStateFile() throws IOException {
        Properties state = new Properties();
        state.setProperty(KEY_INDEX_STATE, "POPULATING");
        state.setProperty(KEY_VECTOR_COUNT, "0");
        state.setProperty(KEY_IS_GPU_AVAILABLE, "false");
        
        writePropertiesFile(getStateFile(), state);
    }
    
    /**
     * Updates the index state.
     */
    public void updateState(String indexState, int vectorCount, boolean isGpuAvailable) throws IOException {
        Properties state = new Properties();
        state.setProperty(KEY_INDEX_STATE, indexState);
        state.setProperty(KEY_VECTOR_COUNT, String.valueOf(vectorCount));
        state.setProperty(KEY_IS_GPU_AVAILABLE, String.valueOf(isGpuAvailable));
        state.setProperty(KEY_LAST_MODIFIED, String.valueOf(System.currentTimeMillis()));
        
        writePropertiesFile(getStateFile(), state);
    }
    
    /**
     * Reads the current index state.
     */
    public IndexState readState() throws IOException {
        Properties state = readPropertiesFile(getStateFile());
        
        return new IndexState(
            state.getProperty(KEY_INDEX_STATE, "UNKNOWN"),
            Integer.parseInt(state.getProperty(KEY_VECTOR_COUNT, "0")),
            Boolean.parseBoolean(state.getProperty(KEY_IS_GPU_AVAILABLE, "false"))
        );
    }
    
    /**
     * Reads the index metadata.
     */
    public IndexMetadata readMetadata() throws IOException {
        Properties metadata = readPropertiesFile(getMetadataFile());
        
        return new IndexMetadata(
            Integer.parseInt(metadata.getProperty(KEY_DIMENSIONS, "0")),
            metadata.getProperty(KEY_SIMILARITY_FUNCTION, "L2"),
            Integer.parseInt(metadata.getProperty(KEY_EF_CONSTRUCTION, "100")),
            Integer.parseInt(metadata.getProperty(KEY_M, "16")),
            Integer.parseInt(metadata.getProperty(KEY_NUM_THREADS, "2")),
            metadata.getProperty(KEY_INDEX_VERSION, "1.0"),
            Long.parseLong(metadata.getProperty(KEY_CREATED_TIMESTAMP, "0")),
            Long.parseLong(metadata.getProperty(KEY_LAST_MODIFIED, "0"))
        );
    }
    
    /**
     * Gets all files that should be included in snapshots.
     */
    public List<Path> getSnapshotFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        
        if (fileSystem.fileExists(getMetadataFile())) {
            files.add(getMetadataFile());
        }
        if (fileSystem.fileExists(getStateFile())) {
            files.add(getStateFile());
        }
        if (fileSystem.fileExists(getIndexDataFile())) {
            files.add(getIndexDataFile());
        }
        if (fileSystem.fileExists(getVectorDataFile())) {
            files.add(getVectorDataFile());
        }
        
        return files;
    }
    
    /**
     * Deletes all index files and directories.
     */
    public void deleteAll() throws IOException {
        // Delete all files
        if (fileSystem.fileExists(getMetadataFile())) {
            fileSystem.deleteFile(getMetadataFile());
        }
        if (fileSystem.fileExists(getIndexDataFile())) {
            fileSystem.deleteFile(getIndexDataFile());
        }
        if (fileSystem.fileExists(getVectorDataFile())) {
            fileSystem.deleteFile(getVectorDataFile());
        }
        if (fileSystem.fileExists(getStateFile())) {
            fileSystem.deleteFile(getStateFile());
        }
        
        // Delete the index directory if it exists and is empty
        if (fileSystem.fileExists(indexDirectory)) {
            try {
                fileSystem.deleteRecursively(indexDirectory);
            } catch (IOException e) {
                // Directory might not be empty, that's okay
            }
        }
    }
    
    /**
     * Writes a Properties file to disk.
     */
    private void writePropertiesFile(Path file, Properties properties) throws IOException {
        try (var outputStream = fileSystem.openAsOutputStream(file, false)) {
            properties.store(outputStream, "CUVS Index " + file.getFileName());
        }
    }
    
    /**
     * Reads a Properties file from disk.
     */
    private Properties readPropertiesFile(Path file) throws IOException {
        Properties properties = new Properties();
        if (fileSystem.fileExists(file)) {
            try (var inputStream = fileSystem.openAsInputStream(file)) {
                properties.load(inputStream);
            }
        }
        return properties;
    }
    
    /**
     * Represents the current state of the index.
     */
    public static class IndexState {
        public final String indexState;
        public final int vectorCount;
        public final boolean isGpuAvailable;
        
        public IndexState(String indexState, int vectorCount, boolean isGpuAvailable) {
            this.indexState = indexState;
            this.vectorCount = vectorCount;
            this.isGpuAvailable = isGpuAvailable;
        }
    }
    
    /**
     * Represents the metadata of the index.
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
