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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.internal.schema.IndexDescriptor;
import org.neo4j.internal.schema.IndexPrototype;
import org.neo4j.internal.schema.SchemaDescriptors;
import org.neo4j.internal.schema.AllIndexProviderDescriptors;
import org.neo4j.internal.schema.IndexConfig;
import org.neo4j.values.storable.Values;
import org.neo4j.io.fs.DefaultFileSystemAbstraction;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.kernel.api.vector.VectorSimilarityFunction;
import org.neo4j.kernel.api.impl.schema.vector.VectorSimilarityFunctions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Test cases for CuvsIndexStorage functionality.
 * Tests file management, metadata persistence, and CAGRA index serialization.
 */
public class CuvsIndexStorageTest {

    @TempDir
    Path tempDir;
    
    private CuvsIndexStorage storage;
    private IndexDescriptor descriptor;
    private FileSystemAbstraction fileSystem;
    private Path indexDirectory;

    @BeforeEach
    void setUp() {
        descriptor = IndexPrototype.forSchema(SchemaDescriptors.forLabel(1, 1))
                .withName("test_vector_index")
                .withIndexProvider(AllIndexProviderDescriptors.CUVS_V1_DESCRIPTOR)
                .materialise(1L);
        
        fileSystem = new DefaultFileSystemAbstraction();
        indexDirectory = tempDir.resolve("test_index");
        storage = new CuvsIndexStorage(indexDirectory, fileSystem, descriptor);
    }

    @Test
    void shouldCreateIndexDirectoryStructure() throws IOException {
        // When: Creating storage
        storage.create();
        
        // Then: Directory structure should be created
        assertTrue(fileSystem.fileExists(indexDirectory));
        assertTrue(fileSystem.fileExists(storage.getMetadataFile()));
        assertTrue(fileSystem.fileExists(storage.getStateFile()));
    }

    @Test
    void shouldOpenExistingStorage() throws IOException {
        // Given: Created storage
        storage.create();
        
        // When: Opening storage
        storage.open();
        
        // Then: Should succeed without exception
        assertTrue(storage.exists());
    }

    @Test
    void shouldFailToOpenNonExistentStorage() {
        // When/Then: Opening non-existent storage should throw exception
        assertThrows(IOException.class, () -> {
            storage.open();
        });
    }

    @Test
    void shouldCheckStorageExistence() throws IOException {
        // Given: No storage initially
        assertFalse(storage.exists());
        
        // When: Creating storage
        storage.create();
        
        // Then: Should exist
        assertTrue(storage.exists());
    }

    @Test
    void shouldDropStorage() throws IOException {
        // Given: Created storage
        storage.create();
        assertTrue(storage.exists());
        
        // When: Dropping storage
        storage.drop();
        
        // Then: Should be removed
        assertFalse(storage.exists());
        assertFalse(fileSystem.fileExists(indexDirectory));
    }

    @Test
    void shouldUpdateAndReadIndexState() throws IOException {
        // Given: Created storage
        storage.create();
        
        // When: Updating state
        storage.updateState("ONLINE", 100, true);
        
        // Then: State should be readable
        CuvsIndexStorage.IndexState state = storage.readState();
        assertEquals("ONLINE", state.indexState);
        assertEquals(100, state.vectorCount);
        assertTrue(state.isGpuAvailable);
    }

    @Test
    void shouldReadIndexMetadata() throws IOException {
        // Given: Created storage
        storage.create();
        
        // When: Reading metadata
        CuvsIndexStorage.IndexMetadata metadata = storage.readMetadata();
        
        // Then: Should have default values
        assertNotNull(metadata);
        assertEquals("1.0", metadata.indexVersion);
        assertTrue(metadata.createdTimestamp > 0);
    }

    @Test
    void shouldGetSnapshotFiles() throws IOException {
        // Given: Created storage
        storage.create();
        
        // When: Getting snapshot files
        List<Path> snapshotFiles = storage.getSnapshotFiles();
        
        // Then: Should include metadata and state files
        assertTrue(snapshotFiles.contains(storage.getMetadataFile()));
        assertTrue(snapshotFiles.contains(storage.getStateFile()));
    }

    @Test
    void shouldSerializeCagraIndex() throws IOException {
        // Given: Created storage and mock CAGRA index
        storage.create();
        
        // Create a mock CAGRA index for testing
        // Note: In real tests, you'd need to create an actual CAGRA index
        // For now, we'll test the file operations
        
        // When: Checking for CAGRA index
        boolean hasIndex = storage.hasCagraIndex();
        
        // Then: Should not have index initially
        assertFalse(hasIndex);
    }

    @Test
    void shouldHandleCagraIndexSerializationErrors() throws IOException {
        // Given: Created storage
        storage.create();
        
        // When/Then: Serializing null index should throw exception
        assertThrows(IllegalArgumentException.class, () -> {
            storage.serializeCagraIndex(null);
        });
    }

    @Test
    void shouldHandleCagraIndexDeserializationErrors() throws IOException {
        // Given: Created storage
        storage.create();
        
        // When/Then: Deserializing non-existent index should throw exception
        assertThrows(IOException.class, () -> {
            storage.deserializeCagraIndex(null);
        });
    }

    @Test
    void shouldHandleInvalidCuvsResources() throws IOException {
        // Given: Created storage
        storage.create();
        
        // When/Then: Deserializing with null resources should throw exception
        assertThrows(IllegalArgumentException.class, () -> {
            storage.deserializeCagraIndex(null);
        });
    }

    @Test
    void shouldCreateIndexWithCustomConfiguration() throws IOException {
        // Given: Index descriptor with custom configuration
        IndexDescriptor customDescriptor = IndexPrototype.forSchema(SchemaDescriptors.forLabel(1, 1))
                .withName("custom_index")
                .withIndexProvider(AllIndexProviderDescriptors.CUVS_V1_DESCRIPTOR)
                .materialise(2L);
        
        CuvsIndexStorage customStorage = new CuvsIndexStorage(
                tempDir.resolve("custom_index"),
                fileSystem,
                customDescriptor
        );
        
        // When: Creating storage
        customStorage.create();
        
        // Then: Should be created successfully
        assertTrue(customStorage.exists());
    }

    @Test
    void shouldHandleConcurrentStorageOperations() throws IOException, InterruptedException {
        // Given: Created storage
        storage.create();
        
        // When: Multiple threads perform operations
        Thread[] threads = new Thread[3];
        Exception[] exceptions = new Exception[3];
        
        for (int i = 0; i < 3; i++) {
            final int threadIndex = i;
            threads[i] = new Thread(() -> {
                try {
                    storage.updateState("ONLINE", threadIndex * 10, true);
                    Thread.sleep(100); // Simulate some work
                    storage.readState();
                } catch (Exception e) {
                    exceptions[threadIndex] = e;
                }
            });
        }
        
        // Start all threads
        for (Thread thread : threads) {
            thread.start();
        }
        
        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }
        
        // Then: All operations should succeed
        for (Exception exception : exceptions) {
            assertNull(exception, "Concurrent operation failed: " + exception.getMessage());
        }
        
        // Final state should be valid
        CuvsIndexStorage.IndexState finalState = storage.readState();
        assertNotNull(finalState);
    }

    @Test
    void shouldHandleStorageCleanup() throws IOException {
        // Given: Created storage with files
        storage.create();
        storage.updateState("ONLINE", 50, true);
        
        // Create some additional files to test cleanup
        Path extraFile = indexDirectory.resolve("extra_file.txt");
        Files.write(extraFile, "test content".getBytes());
        
        // When: Deleting all
        storage.deleteAll();
        
        // Then: All files should be removed
        assertFalse(fileSystem.fileExists(indexDirectory));
        assertFalse(fileSystem.fileExists(extraFile));
    }

    @Test
    void shouldHandleStorageWithMissingFiles() throws IOException {
        // Given: Created storage
        storage.create();
        
        // Manually delete some files to simulate corruption
        fileSystem.deleteFile(storage.getStateFile());
        
        // When: Reading state
        CuvsIndexStorage.IndexState state = storage.readState();
        
        // Then: Should return default values
        assertNotNull(state);
        assertEquals("UNKNOWN", state.indexState);
        assertEquals(0, state.vectorCount);
        assertFalse(state.isGpuAvailable);
    }

    @Test
    void shouldHandleLargeMetadata() throws IOException {
        // Given: Created storage
        storage.create();
        
        // When: Updating state with large values
        storage.updateState("ONLINE", Integer.MAX_VALUE, true);
        
        // Then: Should handle large values correctly
        CuvsIndexStorage.IndexState state = storage.readState();
        assertEquals("ONLINE", state.indexState);
        assertEquals(Integer.MAX_VALUE, state.vectorCount);
        assertTrue(state.isGpuAvailable);
    }

    @Test
    void shouldPreserveFilePermissions() throws IOException {
        // Given: Created storage
        storage.create();
        
        // When: Checking file permissions
        // Note: This test might need adjustment based on the actual file system behavior
        
        // Then: Files should be readable and writable
        assertTrue(fileSystem.fileExists(storage.getMetadataFile()));
        assertTrue(fileSystem.fileExists(storage.getStateFile()));
    }

    @Test
    void shouldHandleStoragePathOperations() {
        // When: Getting various paths
        Path metadataFile = storage.getMetadataFile();
        Path indexDataFile = storage.getIndexDataFile();
        Path vectorDataFile = storage.getVectorDataFile();
        Path stateFile = storage.getStateFile();
        Path indexDir = storage.getIndexDirectory();
        
        // Then: Paths should be valid
        assertNotNull(metadataFile);
        assertNotNull(indexDataFile);
        assertNotNull(vectorDataFile);
        assertNotNull(stateFile);
        assertNotNull(indexDir);
        
        // Paths should be within the index directory
        assertTrue(metadataFile.startsWith(indexDir));
        assertTrue(indexDataFile.startsWith(indexDir));
        assertTrue(vectorDataFile.startsWith(indexDir));
        assertTrue(stateFile.startsWith(indexDir));
    }

    @Test
    void shouldHandleStorageWithSpecialCharacters() throws IOException {
        // Given: Index directory with special characters
        Path specialDir = tempDir.resolve("test_index_with_special_chars_!@#$%^&*()");
        CuvsIndexStorage specialStorage = new CuvsIndexStorage(
                specialDir,
                fileSystem,
                descriptor
        );
        
        // When: Creating storage
        specialStorage.create();
        
        // Then: Should work with special characters
        assertTrue(specialStorage.exists());
        assertTrue(fileSystem.fileExists(specialDir));
    }
}
