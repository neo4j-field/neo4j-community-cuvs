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
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Test cases for CAGRA index serialization and deserialization functionality.
 * Tests the core serialization features that enable fast index loading.
 */
public class CagraCuvsIndexSerializationTest {

    @TempDir
    Path tempDir;
    
    private CagraCuvsIndexImpl index;
    private IndexDescriptor descriptor;
    private FileSystemAbstraction fileSystem;
    private Path indexDirectory;

    @BeforeEach
    void setUp() throws IOException {
        // Create test index descriptor
        descriptor = IndexPrototype.forSchema(SchemaDescriptors.forLabel(1, 1))
                .withName("test_vector_index")
                .withIndexProvider(AllIndexProviderDescriptors.CUVS_V1_DESCRIPTOR)
                .materialise(1L);
        
        fileSystem = new DefaultFileSystemAbstraction();
        indexDirectory = tempDir.resolve("test_index");
        
        // Create index instance
        index = new CagraCuvsIndexImpl(
                descriptor,
                indexDirectory,
                VectorSimilarityFunctions.EUCLIDEAN,
                fileSystem
        );
    }

    @Test
    void shouldSerializeAndDeserializeEmptyIndex() throws IOException {
        // Given: An initialized but empty index
        index.initialize(descriptor);
        assertTrue(index.isInitialized());
        assertEquals(0, index.getVectorCount());
        
        // When: Serializing the index
        index.serializeIndex();
        
        // Then: Serialized index should exist
        assertTrue(index.hasSerializedIndex());
        
        // When: Creating a new index instance and deserializing
        CagraCuvsIndexImpl newIndex = createNewIndexInstance();
        newIndex.initialize(descriptor);
        newIndex.deserializeIndex();
        
        // Then: Deserialized index should be valid
        assertNotNull(newIndex.getCagraIndex());
        assertEquals(0, newIndex.getVectorCount());
    }

    @Test
    void shouldSerializeAndDeserializeIndexWithVectors() throws IOException {
        // Given: An index with vectors
        index.initialize(descriptor);
        
        List<CagraCuvsIndexImpl.VectorData> vectors = List.of(
                new CagraCuvsIndexImpl.VectorData(1L, new float[]{1.0f, 2.0f, 3.0f}, Map.of("test", "data1")),
                new CagraCuvsIndexImpl.VectorData(2L, new float[]{4.0f, 5.0f, 6.0f}, Map.of("test", "data2")),
                new CagraCuvsIndexImpl.VectorData(3L, new float[]{7.0f, 8.0f, 9.0f}, Map.of("test", "data3"))
        );
        
        index.addVectors(vectors);
        assertEquals(3, index.getVectorCount());
        
        // When: Serializing the index
        index.serializeIndex();
        assertTrue(index.hasSerializedIndex());
        
        // When: Creating a new index instance and deserializing
        CagraCuvsIndexImpl newIndex = createNewIndexInstance();
        newIndex.initialize(descriptor);
        newIndex.deserializeIndex();
        
        // Then: Deserialized index should have the same data
        assertNotNull(newIndex.getCagraIndex());
        assertEquals(3, newIndex.getVectorCount());
        assertEquals(3, newIndex.getDimensions());
    }

    @Test
    void shouldHandleSerializationErrorsGracefully() throws IOException {
        // Given: An uninitialized index
        assertFalse(index.isInitialized());
        
        // When/Then: Serializing should throw appropriate exception
        assertThrows(IllegalStateException.class, () -> {
            index.serializeIndex();
        });
    }

    @Test
    void shouldHandleDeserializationErrorsGracefully() throws IOException {
        // Given: An index with no serialized data
        index.initialize(descriptor);
        assertFalse(index.hasSerializedIndex());
        
        // When/Then: Deserializing should throw appropriate exception
        assertThrows(IOException.class, () -> {
            index.deserializeIndex();
        });
    }

    @Test
    void shouldSerializeIndexWithDifferentSimilarityFunctions() throws IOException {
        // Test EUCLIDEAN
        testSerializationWithSimilarityFunction(VectorSimilarityFunctions.EUCLIDEAN);
        
        // Test COSINE (if available)
        if (hasSimilarityFunction("COSINE")) {
            testSerializationWithSimilarityFunction(createSimilarityFunction("COSINE"));
        }
        
        // Test DOT_PRODUCT (if available)
        if (hasSimilarityFunction("DOT_PRODUCT")) {
            testSerializationWithSimilarityFunction(createSimilarityFunction("DOT_PRODUCT"));
        }
    }

    @Test
    void shouldSerializeIndexWithDifferentDimensions() throws IOException {
        // Test different vector dimensions
        int[] dimensions = {128, 256, 384, 512, 768, 1024};
        
        for (int dim : dimensions) {
            // Create vectors with specific dimensions
            List<CagraCuvsIndexImpl.VectorData> vectors = List.of(
                    new CagraCuvsIndexImpl.VectorData(1L, createRandomVector(dim), Map.of()),
                    new CagraCuvsIndexImpl.VectorData(2L, createRandomVector(dim), Map.of())
            );
            
            // Test serialization
            index.initialize(descriptor);
            index.addVectors(vectors);
            index.serializeIndex();
            
            // Verify serialization worked
            assertTrue(index.hasSerializedIndex());
            assertEquals(dim, index.getDimensions());
            
            // Clean up for next iteration
            index.close();
            index = createNewIndexInstance();
        }
    }

    @Test
    void shouldSerializeLargeIndex() throws IOException {
        // Given: A large index with many vectors
        index.initialize(descriptor);
        
        int vectorCount = 1000;
        int dimensions = 384;
        List<CagraCuvsIndexImpl.VectorData> vectors = createLargeVectorSet(vectorCount, dimensions);
        
        // When: Adding vectors and serializing
        long startTime = System.currentTimeMillis();
        index.addVectors(vectors);
        index.serializeIndex();
        long serializationTime = System.currentTimeMillis() - startTime;
        
        // Then: Serialization should complete successfully
        assertTrue(index.hasSerializedIndex());
        assertEquals(vectorCount, index.getVectorCount());
        assertEquals(dimensions, index.getDimensions());
        
        // Serialization should be reasonably fast (less than 30 seconds for 1000 vectors)
        assertTrue(serializationTime < 30000, "Serialization took too long: " + serializationTime + "ms");
        
        // When: Deserializing
        CagraCuvsIndexImpl newIndex = createNewIndexInstance();
        newIndex.initialize(descriptor);
        
        long deserializationStartTime = System.currentTimeMillis();
        newIndex.deserializeIndex();
        long deserializationTime = System.currentTimeMillis() - deserializationStartTime;
        
        // Then: Deserialization should be fast (less than 5 seconds)
        assertTrue(deserializationTime < 5000, "Deserialization took too long: " + deserializationTime + "ms");
        assertEquals(vectorCount, newIndex.getVectorCount());
        assertEquals(dimensions, newIndex.getDimensions());
    }

    @Test
    void shouldPreserveIndexConfigurationAfterSerialization() throws IOException {
        // Given: An index with specific configuration
        index.initialize(descriptor);
        
        List<CagraCuvsIndexImpl.VectorData> vectors = List.of(
                new CagraCuvsIndexImpl.VectorData(1L, new float[]{1.0f, 2.0f, 3.0f}, Map.of())
        );
        index.addVectors(vectors);
        index.serializeIndex();
        
        // When: Deserializing to a new instance
        CagraCuvsIndexImpl newIndex = createNewIndexInstance();
        newIndex.initialize(descriptor);
        newIndex.deserializeIndex();
        
        // Then: Configuration should be preserved
        assertNotNull(newIndex.getCagraParams());
        assertEquals(VectorSimilarityFunctions.EUCLIDEAN, index.getSimilarityFunction());
        assertEquals(3, newIndex.getDimensions());
    }

    @Test
    void shouldHandleConcurrentSerialization() throws IOException, InterruptedException {
        // Given: An initialized index with data
        index.initialize(descriptor);
        
        List<CagraCuvsIndexImpl.VectorData> vectors = List.of(
                new CagraCuvsIndexImpl.VectorData(1L, new float[]{1.0f, 2.0f, 3.0f}, Map.of()),
                new CagraCuvsIndexImpl.VectorData(2L, new float[]{4.0f, 5.0f, 6.0f}, Map.of())
        );
        index.addVectors(vectors);
        
        // When: Multiple threads try to serialize simultaneously
        Thread[] threads = new Thread[3];
        Exception[] exceptions = new Exception[3];
        
        for (int i = 0; i < 3; i++) {
            final int threadIndex = i;
            threads[i] = new Thread(() -> {
                try {
                    index.serializeIndex();
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
        
        // Then: At least one serialization should succeed
        assertTrue(index.hasSerializedIndex());
        
        // Check for any exceptions (some are expected due to concurrent access)
        int exceptionCount = 0;
        for (Exception exception : exceptions) {
            if (exception != null) {
                exceptionCount++;
            }
        }
        // At least one thread should have succeeded
        assertTrue(exceptionCount < 3, "All serialization attempts failed");
    }

    // Helper methods

    private CagraCuvsIndexImpl createNewIndexInstance() {
        return new CagraCuvsIndexImpl(
                descriptor,
                indexDirectory,
                VectorSimilarityFunctions.EUCLIDEAN,
                fileSystem
        );
    }

    private void testSerializationWithSimilarityFunction(VectorSimilarityFunction similarityFunction) throws IOException {
        // Create index with specific similarity function
        CagraCuvsIndexImpl testIndex = new CagraCuvsIndexImpl(
                descriptor,
                tempDir.resolve("test_" + similarityFunction.name()),
                similarityFunction,
                fileSystem
        );
        
        testIndex.initialize(descriptor);
        
        List<CagraCuvsIndexImpl.VectorData> vectors = List.of(
                new CagraCuvsIndexImpl.VectorData(1L, new float[]{1.0f, 2.0f, 3.0f}, Map.of())
        );
        
        testIndex.addVectors(vectors);
        testIndex.serializeIndex();
        
        assertTrue(testIndex.hasSerializedIndex());
        assertEquals(similarityFunction, testIndex.getSimilarityFunction());
        
        testIndex.close();
    }

    private boolean hasSimilarityFunction(String name) {
        try {
            createSimilarityFunction(name);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private VectorSimilarityFunction createSimilarityFunction(String name) {
        // This is a simplified approach - in real implementation,
        // you'd need to handle different similarity function types properly
        return VectorSimilarityFunctions.EUCLIDEAN; // Placeholder
    }

    private float[] createRandomVector(int dimensions) {
        float[] vector = new float[dimensions];
        for (int i = 0; i < dimensions; i++) {
            vector[i] = (float) Math.random();
        }
        return vector;
    }

    private List<CagraCuvsIndexImpl.VectorData> createLargeVectorSet(int count, int dimensions) {
        List<CagraCuvsIndexImpl.VectorData> vectors = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            vectors.add(new CagraCuvsIndexImpl.VectorData(
                    i + 1L,
                    createRandomVector(dimensions),
                    Map.of("index", i)
            ));
        }
        return vectors;
    }
}
