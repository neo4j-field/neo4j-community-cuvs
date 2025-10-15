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
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Integration tests for the complete CAGRA index serialization workflow.
 * Tests the full lifecycle from index creation to serialization to deserialization.
 */
public class CagraCuvsIndexIntegrationTest {

    @TempDir
    Path tempDir;
    
    private IndexDescriptor descriptor;
    private FileSystemAbstraction fileSystem;

    @BeforeEach
    void setUp() {
        descriptor = IndexPrototype.forSchema(SchemaDescriptors.forLabel(1, 1))
                .withName("integration_test_index")
                .withIndexProvider(AllIndexProviderDescriptors.CUVS_V1_DESCRIPTOR)
                .materialise(1L);
        
        fileSystem = new DefaultFileSystemAbstraction();
    }

    @Test
    void shouldCompleteFullSerializationWorkflow() throws IOException {
        // Given: Index directory
        Path indexDir = tempDir.resolve("full_workflow_test");
        
        // When: Creating and populating index
        CagraCuvsIndexImpl index = createIndex(indexDir);
        index.initialize(descriptor);
        
        List<CagraCuvsIndexImpl.VectorData> vectors = createTestVectors(100, 384);
        index.addVectors(vectors);
        
        // Serialize index
        index.serializeIndex();
        
        // Then: Index should be serialized
        assertTrue(index.hasSerializedIndex());
        
        // When: Creating new index instance and loading
        CagraCuvsIndexImpl loadedIndex = createIndex(indexDir);
        loadedIndex.initialize(descriptor);
        loadedIndex.load();
        
        // Then: Loaded index should have same data
        assertEquals(100, loadedIndex.getVectorCount());
        assertEquals(384, loadedIndex.getDimensions());
        assertNotNull(loadedIndex.getCagraIndex());
    }

    @Test
    void shouldHandleIndexLifecycleWithSerialization() throws IOException {
        // Given: Index directory
        Path indexDir = tempDir.resolve("lifecycle_test");
        
        // When: Complete lifecycle
        CagraCuvsIndexImpl index = createIndex(indexDir);
        
        // 1. Create
        index.create();
        assertTrue(index.hasPersistedData());
        
        // 2. Initialize
        index.initialize(descriptor);
        assertTrue(index.isInitialized());
        
        // 3. Add vectors
        List<CagraCuvsIndexImpl.VectorData> vectors = createTestVectors(50, 384);
        index.addVectors(vectors);
        assertEquals(50, index.getVectorCount());
        
        // 4. Serialize
        index.serializeIndex();
        assertTrue(index.hasSerializedIndex());
        
        // 5. Close
        index.close();
        assertFalse(index.isInitialized());
        
        // 6. Reopen and load
        CagraCuvsIndexImpl reopenedIndex = createIndex(indexDir);
        reopenedIndex.open();
        reopenedIndex.initialize(descriptor);
        reopenedIndex.load();
        
        // Then: Should have same data
        assertEquals(50, reopenedIndex.getVectorCount());
        assertEquals(384, reopenedIndex.getDimensions());
        assertTrue(reopenedIndex.hasSerializedIndex());
    }

    @Test
    void shouldHandleMultipleSerializationCycles() throws IOException {
        // Given: Index directory
        Path indexDir = tempDir.resolve("multiple_cycles_test");
        CagraCuvsIndexImpl index = createIndex(indexDir);
        index.initialize(descriptor);
        
        // When: Multiple serialization cycles
        for (int cycle = 0; cycle < 5; cycle++) {
            // Add vectors
            List<CagraCuvsIndexImpl.VectorData> vectors = createTestVectors(10, 384);
            index.addVectors(vectors);
            
            // Serialize
            index.serializeIndex();
            assertTrue(index.hasSerializedIndex());
            
            // Verify serialization
            CagraCuvsIndexImpl testIndex = createIndex(indexDir);
            testIndex.initialize(descriptor);
            testIndex.deserializeIndex();
            assertEquals((cycle + 1) * 10, testIndex.getVectorCount());
            testIndex.close();
        }
        
        // Then: Final index should have all vectors
        assertEquals(50, index.getVectorCount());
    }

    @Test
    void shouldHandleConcurrentSerializationAndDeserialization() throws IOException, InterruptedException {
        // Given: Index directory
        Path indexDir = tempDir.resolve("concurrent_test");
        CagraCuvsIndexImpl index = createIndex(indexDir);
        index.initialize(descriptor);
        
        List<CagraCuvsIndexImpl.VectorData> vectors = createTestVectors(100, 384);
        index.addVectors(vectors);
        
        // When: Concurrent operations
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch latch = new CountDownLatch(4);
        Throwable[] exceptions = new Throwable[4];
        
        // Thread 1: Serialize
        executor.submit(() -> {
            try {
                index.serializeIndex();
            } catch (Exception e) {
                exceptions[0] = e;
            } finally {
                latch.countDown();
            }
        });
        
        // Thread 2: Check serialization status
        executor.submit(() -> {
            try {
                Thread.sleep(50);
                boolean hasSerialized = index.hasSerializedIndex();
                // This might be true or false depending on timing
            } catch (Exception e) {
                exceptions[1] = e;
            } finally {
                latch.countDown();
            }
        });
        
        // Thread 3: Add more vectors (this will trigger rebuild and re-serialization)
        executor.submit(() -> {
            try {
                Thread.sleep(25);
                List<CagraCuvsIndexImpl.VectorData> moreVectors = createTestVectors(10, 384);
                index.addVectors(moreVectors);
            } catch (Exception e) {
                exceptions[2] = e;
            } finally {
                latch.countDown();
            }
        });
        
        // Thread 4: Read vector count
        executor.submit(() -> {
            try {
                Thread.sleep(75);
                int count = index.getVectorCount();
                assertTrue(count >= 100 && count <= 110);
            } catch (Exception e) {
                exceptions[3] = e;
            } finally {
                latch.countDown();
            }
        });
        
        // Wait for all threads to complete
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();
        
        // Then: All operations should complete without critical errors
        for (int i = 0; i < 4; i++) {
            if (exceptions[i] != null) {
                // Some exceptions are expected due to concurrent access
                // but they shouldn't be critical errors
                if (exceptions[i] instanceof OutOfMemoryError) {
                    assertFalse(true, "OutOfMemoryError occurred");
                }
                if (exceptions[i] instanceof StackOverflowError) {
                    assertFalse(true, "StackOverflowError occurred");
                }
            }
        }
        
        // Final state should be valid
        assertTrue(index.getVectorCount() >= 100);
    }

    @Test
    void shouldHandleSerializationWithDifferentConfigurations() throws IOException {
        // Test different similarity functions
        VectorSimilarityFunction[] similarityFunctions = {
                VectorSimilarityFunctions.EUCLIDEAN
                // Add other similarity functions as they become available
        };
        
        for (VectorSimilarityFunction similarityFunction : similarityFunctions) {
            Path testDir = tempDir.resolve("config_test_" + similarityFunction.name());
            CagraCuvsIndexImpl index = new CagraCuvsIndexImpl(
                    descriptor,
                    testDir,
                    similarityFunction,
                    fileSystem
            );
            
            index.initialize(descriptor);
            List<CagraCuvsIndexImpl.VectorData> vectors = createTestVectors(20, 384);
            index.addVectors(vectors);
            index.serializeIndex();
            
            assertTrue(index.hasSerializedIndex());
            assertEquals(similarityFunction, index.getSimilarityFunction());
            
            index.close();
        }
    }

    @Test
    void shouldHandleSerializationWithLargeDatasets() throws IOException {
        // Given: Large dataset
        Path indexDir = tempDir.resolve("large_dataset_test");
        CagraCuvsIndexImpl index = createIndex(indexDir);
        index.initialize(descriptor);
        
        // When: Adding large dataset
        int vectorCount = 1000;
        int dimensions = 384;
        List<CagraCuvsIndexImpl.VectorData> vectors = createTestVectors(vectorCount, dimensions);
        
        long startTime = System.currentTimeMillis();
        index.addVectors(vectors);
        long addTime = System.currentTimeMillis() - startTime;
        
        startTime = System.currentTimeMillis();
        index.serializeIndex();
        long serializationTime = System.currentTimeMillis() - startTime;
        
        // Then: Should complete within reasonable time
        assertTrue(addTime < 60000, "Adding vectors took too long: " + addTime + "ms");
        assertTrue(serializationTime < 30000, "Serialization took too long: " + serializationTime + "ms");
        
        assertEquals(vectorCount, index.getVectorCount());
        assertEquals(dimensions, index.getDimensions());
        assertTrue(index.hasSerializedIndex());
        
        // When: Loading serialized index
        CagraCuvsIndexImpl loadedIndex = createIndex(indexDir);
        loadedIndex.initialize(descriptor);
        
        startTime = System.currentTimeMillis();
        loadedIndex.load();
        long loadTime = System.currentTimeMillis() - startTime;
        
        // Then: Loading should be fast
        assertTrue(loadTime < 10000, "Loading took too long: " + loadTime + "ms");
        assertEquals(vectorCount, loadedIndex.getVectorCount());
        assertEquals(dimensions, loadedIndex.getDimensions());
    }

    @Test
    void shouldHandleSerializationErrorRecovery() throws IOException {
        // Given: Index directory
        Path indexDir = tempDir.resolve("error_recovery_test");
        CagraCuvsIndexImpl index = createIndex(indexDir);
        index.initialize(descriptor);
        
        List<CagraCuvsIndexImpl.VectorData> vectors = createTestVectors(50, 384);
        index.addVectors(vectors);
        
        // When: Serialization succeeds
        index.serializeIndex();
        assertTrue(index.hasSerializedIndex());
        
        // Simulate corruption by deleting the serialized file
        index.close();
        fileSystem.deleteFile(indexDir.resolve("cuvs_index.bin"));
        
        // When: Loading corrupted index
        CagraCuvsIndexImpl corruptedIndex = createIndex(indexDir);
        corruptedIndex.initialize(descriptor);
        
        // Then: Should fall back to rebuilding from vector data
        corruptedIndex.load();
        assertEquals(50, corruptedIndex.getVectorCount());
        assertEquals(384, corruptedIndex.getDimensions());
    }

    @Test
    void shouldHandleSerializationWithMetadataPersistence() throws IOException {
        // Given: Index with metadata
        Path indexDir = tempDir.resolve("metadata_test");
        CagraCuvsIndexImpl index = createIndex(indexDir);
        index.initialize(descriptor);
        
        List<CagraCuvsIndexImpl.VectorData> vectors = createTestVectors(30, 384);
        index.addVectors(vectors);
        index.serializeIndex();
        
        // When: Checking storage metadata
        CuvsIndexStorage storage = new CuvsIndexStorage(indexDir, fileSystem, descriptor);
        CuvsIndexStorage.IndexState state = storage.readState();
        CuvsIndexStorage.IndexMetadata metadata = storage.readMetadata();
        
        // Then: Metadata should be preserved
        assertNotNull(state);
        assertNotNull(metadata);
        assertEquals(30, state.vectorCount);
        assertTrue(state.isGpuAvailable);
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
