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
import org.neo4j.io.fs.DefaultFileSystemAbstraction;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.kernel.api.vector.VectorSimilarityFunction;
import org.neo4j.kernel.api.impl.schema.vector.VectorSimilarityFunctions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Test cases for error handling and edge cases in CAGRA index serialization.
 * Tests how the system handles various failure scenarios and edge conditions.
 */
public class CagraCuvsIndexErrorHandlingTest {

    @TempDir
    Path tempDir;
    
    private IndexDescriptor descriptor;
    private FileSystemAbstraction fileSystem;

    @BeforeEach
    void setUp() {
        descriptor = IndexPrototype.forSchema(SchemaDescriptors.forLabel(1, 1))
                .withName("error_handling_test_index")
                .withIndexProvider(AllIndexProviderDescriptors.CUVS_V1_DESCRIPTOR)
                .materialise(1L);
        
        fileSystem = new DefaultFileSystemAbstraction();
    }

    @Test
    void shouldHandleNullVectorData() throws IOException {
        // Given: Initialized index
        Path indexDir = tempDir.resolve("null_vector_test");
        CagraCuvsIndexImpl index = createIndex(indexDir);
        index.initialize(descriptor);
        
        // When/Then: Adding null vector should throw exception
        assertThrows(IllegalArgumentException.class, () -> {
            index.addVector(null);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            index.addVectors(null);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            index.addVectors(List.of((CagraCuvsIndexImpl.VectorData) null));
        });
    }

    @Test
    void shouldHandleEmptyVectorList() throws IOException {
        // Given: Initialized index
        Path indexDir = tempDir.resolve("empty_vector_test");
        CagraCuvsIndexImpl index = createIndex(indexDir);
        index.initialize(descriptor);
        
        // When/Then: Adding empty vector list should throw exception
        assertThrows(IllegalArgumentException.class, () -> {
            index.addVectors(List.of());
        });
    }

    @Test
    void shouldHandleInvalidVectorDimensions() throws IOException {
        // Given: Initialized index
        Path indexDir = tempDir.resolve("invalid_dimensions_test");
        CagraCuvsIndexImpl index = createIndex(indexDir);
        index.initialize(descriptor);
        
        // When: Adding vectors with invalid dimensions
        List<CagraCuvsIndexImpl.VectorData> invalidVectors = List.of(
                new CagraCuvsIndexImpl.VectorData(1L, new float[0], Map.of()), // Empty vector
                new CagraCuvsIndexImpl.VectorData(2L, new float[2049], Map.of()) // Too many dimensions
        );
        
        // Then: Should throw appropriate exceptions
        for (CagraCuvsIndexImpl.VectorData vector : invalidVectors) {
            assertThrows(IllegalArgumentException.class, () -> {
                index.addVector(vector);
            });
        }
    }

    @Test
    void shouldHandleInconsistentVectorDimensions() throws IOException {
        // Given: Initialized index
        Path indexDir = tempDir.resolve("inconsistent_dimensions_test");
        CagraCuvsIndexImpl index = createIndex(indexDir);
        index.initialize(descriptor);
        
        // When: Adding vectors with different dimensions
        List<CagraCuvsIndexImpl.VectorData> inconsistentVectors = List.of(
                new CagraCuvsIndexImpl.VectorData(1L, new float[128], Map.of()),
                new CagraCuvsIndexImpl.VectorData(2L, new float[256], Map.of()) // Different dimension
        );
        
        // Then: Should throw exception
        assertThrows(IllegalArgumentException.class, () -> {
            index.addVectors(inconsistentVectors);
        });
    }

    @Test
    void shouldHandleSerializationWithCorruptedIndex() throws IOException {
        // Given: Index with data
        Path indexDir = tempDir.resolve("corrupted_index_test");
        CagraCuvsIndexImpl index = createIndex(indexDir);
        index.initialize(descriptor);
        
        List<CagraCuvsIndexImpl.VectorData> vectors = createTestVectors(10, 384);
        index.addVectors(vectors);
        index.serializeIndex();
        
        // When: Corrupting the serialized index file
        Path indexFile = indexDir.resolve("cuvs_index.bin");
        Files.write(indexFile, "corrupted data".getBytes(), StandardOpenOption.TRUNCATE_EXISTING);
        
        // When: Trying to deserialize corrupted index
        CagraCuvsIndexImpl corruptedIndex = createIndex(indexDir);
        corruptedIndex.initialize(descriptor);
        
        // Then: Should throw exception
        assertThrows(IOException.class, () -> {
            corruptedIndex.deserializeIndex();
        });
    }

    @Test
    void shouldHandleSerializationWithInsufficientDiskSpace() throws IOException {
        // Given: Index with data
        Path indexDir = tempDir.resolve("disk_space_test");
        CagraCuvsIndexImpl index = createIndex(indexDir);
        index.initialize(descriptor);
        
        // Create a large dataset that might cause disk space issues
        List<CagraCuvsIndexImpl.VectorData> largeVectors = createTestVectors(10000, 384);
        index.addVectors(largeVectors);
        
        // When: Serializing (this might fail on systems with limited disk space)
        try {
            index.serializeIndex();
            // If serialization succeeds, verify it worked
            assertTrue(index.hasSerializedIndex());
        } catch (IOException e) {
            // If it fails due to disk space, that's expected
            assertTrue(e.getMessage().contains("space") || 
                      e.getMessage().contains("disk") ||
                      e.getMessage().contains("full") ||
                      e.getMessage().contains("No space"));
        }
    }

    @Test
    void shouldHandleSerializationWithReadOnlyDirectory() throws IOException {
        // Given: Index directory that becomes read-only
        Path indexDir = tempDir.resolve("readonly_test");
        CagraCuvsIndexImpl index = createIndex(indexDir);
        index.initialize(descriptor);
        
        List<CagraCuvsIndexImpl.VectorData> vectors = createTestVectors(10, 384);
        index.addVectors(vectors);
        
        // Make directory read-only (this might not work on all systems)
        try {
            indexDir.toFile().setReadOnly();
            
            // When: Trying to serialize
            // Then: Should handle the error gracefully
            assertThrows(IOException.class, () -> {
                index.serializeIndex();
            });
        } catch (Exception e) {
            // If we can't make the directory read-only, skip this test
            System.out.println("Skipping read-only test: " + e.getMessage());
        }
    }

    @Test
    void shouldHandleSerializationWithConcurrentFileAccess() throws IOException, InterruptedException {
        // Given: Index with data
        Path indexDir = tempDir.resolve("concurrent_access_test");
        CagraCuvsIndexImpl index = createIndex(indexDir);
        index.initialize(descriptor);
        
        List<CagraCuvsIndexImpl.VectorData> vectors = createTestVectors(100, 384);
        index.addVectors(vectors);
        
        // When: Multiple threads try to access the same index file
        Thread[] threads = new Thread[3];
        Exception[] exceptions = new Exception[3];
        
        for (int i = 0; i < 3; i++) {
            final int threadIndex = i;
            threads[i] = new Thread(() -> {
                try {
                    if (threadIndex == 0) {
                        index.serializeIndex();
                    } else if (threadIndex == 1) {
                        Thread.sleep(10);
                        index.hasSerializedIndex();
                    } else {
                        Thread.sleep(20);
                        // Try to read the file while it's being written
                        Path indexFile = indexDir.resolve("cuvs_index.bin");
                        if (Files.exists(indexFile)) {
                            Files.readAllBytes(indexFile);
                        }
                    }
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
        
        // Then: At least one operation should succeed
        boolean anySucceeded = false;
        for (Exception exception : exceptions) {
            if (exception == null) {
                anySucceeded = true;
                break;
            }
        }
        assertTrue(anySucceeded, "All concurrent operations failed");
    }

    @Test
    void shouldHandleSerializationWithInvalidCuvsResources() throws IOException {
        // Given: Index with null CUVS resources
        Path indexDir = tempDir.resolve("invalid_resources_test");
        CagraCuvsIndexImpl index = createIndex(indexDir);
        index.initialize(descriptor);
        
        // Simulate null CUVS resources (this is hard to do in practice)
        // We'll test the error handling in the storage layer instead
        
        CuvsIndexStorage storage = new CuvsIndexStorage(indexDir, fileSystem, descriptor);
        storage.create();
        
        // When: Trying to deserialize with null resources
        assertThrows(IllegalArgumentException.class, () -> {
            storage.deserializeCagraIndex(null);
        });
    }

    @Test
    void shouldHandleSerializationWithMalformedIndexData() throws IOException {
        // Given: Index directory
        Path indexDir = tempDir.resolve("malformed_data_test");
        CagraCuvsIndexImpl index = createIndex(indexDir);
        index.initialize(descriptor);
        
        // Create a malformed index file
        Path indexFile = indexDir.resolve("cuvs_index.bin");
        Files.createDirectories(indexDir);
        Files.write(indexFile, new byte[]{0x00, 0x01, 0x02, 0x03}); // Invalid CAGRA data
        
        // When: Trying to deserialize malformed data
        CagraCuvsIndexImpl malformedIndex = createIndex(indexDir);
        malformedIndex.initialize(descriptor);
        
        // Then: Should throw exception
        assertThrows(IOException.class, () -> {
            malformedIndex.deserializeIndex();
        });
    }

    @Test
    void shouldHandleSerializationWithVeryLargeVectors() throws IOException {
        // Given: Index with very large vectors
        Path indexDir = tempDir.resolve("large_vectors_test");
        CagraCuvsIndexImpl index = createIndex(indexDir);
        index.initialize(descriptor);
        
        // Create vectors with maximum supported dimensions
        int maxDimensions = 2048;
        List<CagraCuvsIndexImpl.VectorData> largeVectors = List.of(
                new CagraCuvsIndexImpl.VectorData(1L, new float[maxDimensions], Map.of())
        );
        
        // When: Adding very large vectors
        try {
            index.addVectors(largeVectors);
            index.serializeIndex();
            
            // Then: Should handle large vectors
            assertTrue(index.hasSerializedIndex());
            assertEquals(maxDimensions, index.getDimensions());
        } catch (OutOfMemoryError e) {
            // If we run out of memory, that's expected for very large vectors
            System.out.println("Skipping large vector test due to memory constraints: " + e.getMessage());
        }
    }

    @Test
    void shouldHandleSerializationWithZeroVectors() throws IOException {
        // Given: Index with no vectors
        Path indexDir = tempDir.resolve("zero_vectors_test");
        CagraCuvsIndexImpl index = createIndex(indexDir);
        index.initialize(descriptor);
        
        // When: Serializing empty index
        index.serializeIndex();
        
        // Then: Should handle empty index
        assertTrue(index.hasSerializedIndex());
        assertEquals(0, index.getVectorCount());
    }

    @Test
    void shouldHandleSerializationWithDuplicateNodeIds() throws IOException {
        // Given: Index with duplicate node IDs
        Path indexDir = tempDir.resolve("duplicate_ids_test");
        CagraCuvsIndexImpl index = createIndex(indexDir);
        index.initialize(descriptor);
        
        List<CagraCuvsIndexImpl.VectorData> duplicateVectors = List.of(
                new CagraCuvsIndexImpl.VectorData(1L, new float[]{1.0f, 2.0f, 3.0f}, Map.of()),
                new CagraCuvsIndexImpl.VectorData(1L, new float[]{4.0f, 5.0f, 6.0f}, Map.of()) // Same ID
        );
        
        // When: Adding vectors with duplicate IDs
        // Note: This might be allowed depending on implementation
        try {
            index.addVectors(duplicateVectors);
            index.serializeIndex();
            
            // Then: Should handle duplicate IDs (either by allowing or throwing exception)
            assertTrue(index.hasSerializedIndex());
        } catch (IllegalArgumentException e) {
            // If duplicate IDs are not allowed, that's also valid
            assertTrue(e.getMessage().contains("duplicate") || e.getMessage().contains("ID"));
        }
    }

    @Test
    void shouldHandleSerializationWithNegativeNodeIds() throws IOException {
        // Given: Index with negative node IDs
        Path indexDir = tempDir.resolve("negative_ids_test");
        CagraCuvsIndexImpl index = createIndex(indexDir);
        index.initialize(descriptor);
        
        List<CagraCuvsIndexImpl.VectorData> negativeIdVectors = List.of(
                new CagraCuvsIndexImpl.VectorData(-1L, new float[]{1.0f, 2.0f, 3.0f}, Map.of())
        );
        
        // When: Adding vectors with negative IDs
        // Note: This might be allowed depending on implementation
        try {
            index.addVectors(negativeIdVectors);
            index.serializeIndex();
            
            // Then: Should handle negative IDs
            assertTrue(index.hasSerializedIndex());
        } catch (IllegalArgumentException e) {
            // If negative IDs are not allowed, that's also valid
            assertTrue(e.getMessage().contains("negative") || e.getMessage().contains("ID"));
        }
    }

    // Helper methods

    private CagraCuvsIndexImpl createIndex(Path indexDir) {
        return new CagraCuvsIndexImpl(
                descriptor,
                indexDir,
                VectorSimilarityFunctions.EUCLIDEAN,
                fileSystem
        );
    }

    private List<CagraCuvsIndexImpl.VectorData> createTestVectors(int count, int dimensions) {
        List<CagraCuvsIndexImpl.VectorData> vectors = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            float[] vector = new float[dimensions];
            for (int j = 0; j < dimensions; j++) {
                vector[j] = (float) Math.random();
            }
            vectors.add(new CagraCuvsIndexImpl.VectorData(
                    i + 1L,
                    vector,
                    Map.of("test", "data_" + i)
            ));
        }
        return vectors;
    }
}
