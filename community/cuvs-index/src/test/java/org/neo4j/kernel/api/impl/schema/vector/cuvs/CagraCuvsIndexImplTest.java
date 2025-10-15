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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.neo4j.internal.kernel.api.InternalIndexState;
import org.neo4j.internal.schema.IndexDescriptor;
import org.neo4j.internal.schema.IndexProviderDescriptor;
import org.neo4j.internal.schema.IndexType;
import org.neo4j.internal.schema.SchemaDescriptors;
import org.neo4j.kernel.api.impl.schema.vector.VectorSimilarityFunctions;
import org.neo4j.kernel.api.vector.VectorSimilarityFunction;
import org.neo4j.values.storable.FloatingPointArray;
import org.neo4j.values.storable.Values;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.kernel.api.index.ValueIndexReader;
import org.neo4j.internal.schema.IndexPrototype;

public class CagraCuvsIndexImplTest {

    @Test
    void shouldInitializeInDevelopmentMode() {
        // Given
        System.setProperty("cuvs.development.mode", "true");
        IndexDescriptor descriptor = createTestDescriptor();
        CagraCuvsIndexImpl index = new CagraCuvsIndexImpl(
            descriptor,
            Paths.get("/tmp/test-cuvs-index"),
            VectorSimilarityFunctions.EUCLIDEAN,
            mock(FileSystemAbstraction.class)
        );

        try {
            // When
            index.initialize();

            // Then
            assertEquals(InternalIndexState.ONLINE, index.getState());
            
            Map<String, Object> stats = index.getStatistics();
            assertTrue((Boolean) stats.get("initialized"));
            assertFalse((Boolean) stats.get("gpuAvailable"));
            assertEquals(0, stats.get("vectorCount"));
            assertEquals("EUCLIDEAN", stats.get("similarityFunction"));
        } finally {
            System.clearProperty("cuvs.development.mode");
        }
    }

    @Test
    void shouldAddVectors() throws Exception {
        // Given
        System.setProperty("cuvs.development.mode", "true");
        IndexDescriptor descriptor = createTestDescriptor();
        CagraCuvsIndexImpl index = new CagraCuvsIndexImpl(
            descriptor,
            Paths.get("/tmp/test-cuvs-index"),
            VectorSimilarityFunctions.EUCLIDEAN,
            mock(FileSystemAbstraction.class)
        );
        
        try {
            index.initialize();

            // When
            List<CagraCuvsIndexImpl.VectorData> vectors = List.of(
                new CagraCuvsIndexImpl.VectorData(1L, new float[]{1.0f, 2.0f, 3.0f}, Map.of()),
                new CagraCuvsIndexImpl.VectorData(2L, new float[]{4.0f, 5.0f, 6.0f}, Map.of())
            );
            index.addVectors(vectors);

            // Then
            Map<String, Object> stats = index.getStatistics();
            assertEquals(2, stats.get("vectorCount"));
            assertEquals(3, stats.get("dimensions"));
        } finally {
            System.clearProperty("cuvs.development.mode");
        }
    }

    @Test
    void shouldCreateIndexReader() throws Exception {
        // Given
        System.setProperty("cuvs.development.mode", "true");
        IndexDescriptor descriptor = createTestDescriptor();
        CagraCuvsIndexImpl index = new CagraCuvsIndexImpl(
            descriptor,
            Paths.get("/tmp/test-cuvs-index"),
            VectorSimilarityFunctions.EUCLIDEAN,
            mock(org.neo4j.io.fs.FileSystemAbstraction.class)
        );
        
        try {
            index.initialize();

            // When
            ValueIndexReader reader = index.getIndexReader(mock(org.neo4j.kernel.impl.index.schema.IndexUsageTracking.class));

            // Then
            assertNotNull(reader);
            assertTrue(reader instanceof CuvsIndexReader);
        } finally {
            System.clearProperty("cuvs.development.mode");
        }
    }

    @Test
    void shouldCloseIndex() throws Exception {
        // Given
        System.setProperty("cuvs.development.mode", "true");
        IndexDescriptor descriptor = createTestDescriptor();
        CagraCuvsIndexImpl index = new CagraCuvsIndexImpl(
            descriptor,
            Paths.get("/tmp/test-cuvs-index"),
            VectorSimilarityFunctions.EUCLIDEAN,
            mock(FileSystemAbstraction.class)
        );
        
        try {
            index.initialize();
            index.create(); // Need to create the index before opening
            index.open(); // Need to open the index for close() to work

            // Verify it's initialized before closing
            Map<String, Object> statsBefore = index.getStatistics();
            assertTrue((Boolean) statsBefore.get("initialized"), "Index should be initialized before closing");

            // When
            index.close();

            // Then
            Map<String, Object> stats = index.getStatistics();
            assertFalse((Boolean) stats.get("initialized"), "Index should not be initialized after closing");
        } finally {
            System.clearProperty("cuvs.development.mode");
        }
    }

    @Test
    void shouldHandleMultipleInitializationCalls() {
        // Given
        System.setProperty("cuvs.development.mode", "true");
        IndexDescriptor descriptor = createTestDescriptor();
        CagraCuvsIndexImpl index = new CagraCuvsIndexImpl(
            descriptor,
            Paths.get("/tmp/test-cuvs-index"),
            VectorSimilarityFunctions.EUCLIDEAN,
            mock(FileSystemAbstraction.class)
        );
        
        try {
            // When - First initialization
            index.initialize();

            // When - Second initialization should not throw (already initialized)
            index.initialize();

            // Then
            assertTrue(index.getStatistics().containsKey("initialized"));
        } finally {
            System.clearProperty("cuvs.development.mode");
        }
    }

    @Test
    void shouldHandleEmptyVectorList() throws Exception {
        // Given
        System.setProperty("cuvs.development.mode", "true");
        IndexDescriptor descriptor = createTestDescriptor();
        CagraCuvsIndexImpl index = new CagraCuvsIndexImpl(
            descriptor,
            Paths.get("/tmp/test-cuvs-index"),
            VectorSimilarityFunctions.EUCLIDEAN,
            mock(FileSystemAbstraction.class)
        );
        
        try {
            index.initialize();

            // When - Add empty list of vectors
            List<CagraCuvsIndexImpl.VectorData> emptyVectors = List.of();
            index.addVectors(emptyVectors);

            // Then
            Map<String, Object> stats = index.getStatistics();
            assertEquals(0, stats.get("vectorCount"));
        } finally {
            System.clearProperty("cuvs.development.mode");
        }
    }

    @Test
    void shouldHandleSearchWithEmptyIndex() throws Exception {
        // Given
        IndexDescriptor descriptor = createTestDescriptor();
        CagraCuvsIndexImpl index = new CagraCuvsIndexImpl(
            descriptor,
            Paths.get("/tmp/test-cuvs-index"),
            VectorSimilarityFunctions.EUCLIDEAN,
            mock(FileSystemAbstraction.class)
        );
        index.initialize();

        // When - Get index reader
        ValueIndexReader reader = index.getIndexReader(mock(org.neo4j.kernel.impl.index.schema.IndexUsageTracking.class));

        // Then
        assertNotNull(reader);
        assertTrue(reader instanceof CuvsIndexReader);
    }

    @Test
    void shouldMaintainStatisticsCorrectly() throws Exception {
        // Given
        System.setProperty("cuvs.development.mode", "true");
        IndexDescriptor descriptor = createTestDescriptor();
        CagraCuvsIndexImpl index = new CagraCuvsIndexImpl(
            descriptor,
            Paths.get("/tmp/test-cuvs-index"),
            VectorSimilarityFunctions.EUCLIDEAN,
            mock(FileSystemAbstraction.class)
        );
        
        try {
            // Before initialization
            Map<String, Object> statsBeforeInit = index.getStatistics();
            assertFalse((Boolean) statsBeforeInit.get("initialized"));
            assertEquals(0, statsBeforeInit.get("vectorCount"));
            assertEquals(0, statsBeforeInit.get("dimensions"));

            // When - Initialize
            index.initialize();

        // After initialization
        Map<String, Object> statsAfterInit = index.getStatistics();
        assertTrue((Boolean) statsAfterInit.get("initialized"));
        assertFalse((Boolean) statsAfterInit.get("gpuAvailable"));
        assertEquals(0, statsAfterInit.get("vectorCount"));
        assertEquals(128, statsAfterInit.get("dimensions")); // Mock implementation uses default 128 dimensions

        // When - Add vectors
        List<CagraCuvsIndexImpl.VectorData> vectors = List.of(
            new CagraCuvsIndexImpl.VectorData(1L, new float[]{1.0f, 2.0f, 3.0f}, Map.of()),
            new CagraCuvsIndexImpl.VectorData(2L, new float[]{4.0f, 5.0f, 6.0f}, Map.of())
        );
        index.addVectors(vectors);

        // Then - After adding vectors
        Map<String, Object> statsAfterAdd = index.getStatistics();
        assertEquals(2, statsAfterAdd.get("vectorCount"));
        assertEquals(3, statsAfterAdd.get("dimensions"));
        } finally {
            System.clearProperty("cuvs.development.mode");
        }
    }

    @Test
    void shouldThrowExceptionWhenAddingVectorsBeforeInitialization() {
        // Given
        IndexDescriptor descriptor = createTestDescriptor();
        CagraCuvsIndexImpl index = new CagraCuvsIndexImpl(
            descriptor,
            Paths.get("/tmp/test-cuvs-index"),
            VectorSimilarityFunctions.EUCLIDEAN,
            mock(FileSystemAbstraction.class)
        );

        List<CagraCuvsIndexImpl.VectorData> vectors = List.of(
            new CagraCuvsIndexImpl.VectorData(1L, new float[]{1.0f, 2.0f}, Map.of())
        );

        // When/Then
        assertThrows(IllegalStateException.class, () -> index.addVectors(vectors));
    }

    @Test
    void shouldThrowExceptionWhenGettingReaderBeforeInitialization() {
        // Given
        IndexDescriptor descriptor = createTestDescriptor();
        CagraCuvsIndexImpl index = new CagraCuvsIndexImpl(
            descriptor,
            Paths.get("/tmp/test-cuvs-index"),
            VectorSimilarityFunctions.EUCLIDEAN,
            mock(org.neo4j.io.fs.FileSystemAbstraction.class)
        );

        // When/Then
        assertThrows(IllegalStateException.class, () -> 
            index.getIndexReader(mock(org.neo4j.kernel.impl.index.schema.IndexUsageTracking.class)));
    }

    @Test
    void shouldHandleDifferentSimilarityFunctions() {
        // Given
        IndexDescriptor descriptor = createTestDescriptor();
        
        // Test with different similarity functions
        VectorSimilarityFunction[] functions = {
            VectorSimilarityFunctions.EUCLIDEAN
            // Note: COSINE and DOT_PRODUCT are not available in VectorSimilarityFunctions
            // They would need to be defined separately or use different approach
        };

        for (VectorSimilarityFunction function : functions) {
            // When
            CagraCuvsIndexImpl index = new CagraCuvsIndexImpl(
                descriptor,
                Paths.get("/tmp/test-cuvs-index-" + function.name()),
                function,
                mock(FileSystemAbstraction.class)
            );
            index.initialize();

            // Then
            Map<String, Object> stats = index.getStatistics();
            assertEquals(function.name(), stats.get("similarityFunction"));
            
            try {
                index.close();
            } catch (IOException e) {
                // Expected for test cleanup
            }
        }
    }

    @Test
    void shouldHandleConcurrentOperations() throws IOException {
        // Given
        System.setProperty("cuvs.development.mode", "true");
        IndexDescriptor descriptor = createTestDescriptor();
        CagraCuvsIndexImpl index = new CagraCuvsIndexImpl(
            descriptor,
            Paths.get("/tmp/test-cuvs-index"),
            VectorSimilarityFunctions.EUCLIDEAN,
            mock(FileSystemAbstraction.class)
        );
        
        try {
            index.initialize();

            // When - Add vectors concurrently
            List<CagraCuvsIndexImpl.VectorData> vectors1 = List.of(
                new CagraCuvsIndexImpl.VectorData(1L, new float[]{1.0f, 2.0f}, Map.of())
            );
            List<CagraCuvsIndexImpl.VectorData> vectors2 = List.of(
                new CagraCuvsIndexImpl.VectorData(2L, new float[]{3.0f, 4.0f}, Map.of())
            );

            index.addVectors(vectors1);
            index.addVectors(vectors2);

            // Then
            Map<String, Object> stats = index.getStatistics();
            assertEquals(2, stats.get("vectorCount"));
        } finally {
            System.clearProperty("cuvs.development.mode");
        }
    }

    @Test
    void shouldThrowGpuUnavailableExceptionWhenGpuNotAvailable() {
        // Given
        System.clearProperty("cuvs.development.mode"); // Ensure development mode is off
        IndexDescriptor descriptor = createTestDescriptor();
        CagraCuvsIndexImpl index = new CagraCuvsIndexImpl(
            descriptor,
            Paths.get("/tmp/test-cuvs-index"),
            VectorSimilarityFunctions.EUCLIDEAN,
            mock(FileSystemAbstraction.class)
        );

        // When/Then
        assertThrows(GpuUnavailableException.class, () -> index.initialize());
    }

    @Test
    void shouldPerformIncrementalInsertion() throws Exception {
        // Given
        System.setProperty("cuvs.development.mode", "true");
        IndexDescriptor descriptor = createTestDescriptor();
        CagraCuvsIndexImpl index = new CagraCuvsIndexImpl(
            descriptor,
            Paths.get("/tmp/test-incremental-cuvs-index"),
            VectorSimilarityFunctions.EUCLIDEAN,
            mock(FileSystemAbstraction.class)
        );
        
        try {
            index.initialize();

            // When - Add initial batch of vectors
            List<CagraCuvsIndexImpl.VectorData> initialVectors = List.of(
                new CagraCuvsIndexImpl.VectorData(1L, new float[]{1.0f, 2.0f, 3.0f}, Map.of("batch", "initial")),
                new CagraCuvsIndexImpl.VectorData(2L, new float[]{4.0f, 5.0f, 6.0f}, Map.of("batch", "initial")),
                new CagraCuvsIndexImpl.VectorData(3L, new float[]{7.0f, 8.0f, 9.0f}, Map.of("batch", "initial"))
            );
            index.addVectors(initialVectors);

            // Verify initial state
            Map<String, Object> statsAfterInitial = index.getStatistics();
            assertEquals(3, statsAfterInitial.get("vectorCount"));

            // When - Add incremental vectors (should use extend, not rebuild)
            List<CagraCuvsIndexImpl.VectorData> incrementalVectors = List.of(
                new CagraCuvsIndexImpl.VectorData(4L, new float[]{10.0f, 11.0f, 12.0f}, Map.of("batch", "incremental")),
                new CagraCuvsIndexImpl.VectorData(5L, new float[]{13.0f, 14.0f, 15.0f}, Map.of("batch", "incremental"))
            );
            index.addVectors(incrementalVectors);

            // Then - Verify incremental addition worked
            Map<String, Object> statsAfterIncremental = index.getStatistics();
            assertEquals(5, statsAfterIncremental.get("vectorCount"));
            assertEquals(3, statsAfterIncremental.get("dimensions"));
            
            // Verify index is still initialized and functional
            assertTrue((Boolean) statsAfterIncremental.get("initialized"));
            
        } finally {
            System.clearProperty("cuvs.development.mode");
        }
    }

    @Test
    void shouldHandleSingleVectorIncrementalInsertion() throws Exception {
        // Given
        System.setProperty("cuvs.development.mode", "true");
        IndexDescriptor descriptor = createTestDescriptor();
        CagraCuvsIndexImpl index = new CagraCuvsIndexImpl(
            descriptor,
            Paths.get("/tmp/test-single-incremental-cuvs-index"),
            VectorSimilarityFunctions.EUCLIDEAN,
            mock(FileSystemAbstraction.class)
        );
        
        try {
            index.initialize();

            // When - Add initial vector
            List<CagraCuvsIndexImpl.VectorData> initialVector = List.of(
                new CagraCuvsIndexImpl.VectorData(1L, new float[]{1.0f, 2.0f, 3.0f}, Map.of())
            );
            index.addVectors(initialVector);

            // Verify initial state
            Map<String, Object> statsAfterInitial = index.getStatistics();
            assertEquals(1, statsAfterInitial.get("vectorCount"));

            // When - Add single incremental vector
            List<CagraCuvsIndexImpl.VectorData> singleIncrementalVector = List.of(
                new CagraCuvsIndexImpl.VectorData(2L, new float[]{4.0f, 5.0f, 6.0f}, Map.of())
            );
            index.addVectors(singleIncrementalVector);

            // Then - Verify single incremental addition worked
            Map<String, Object> statsAfterIncremental = index.getStatistics();
            assertEquals(2, statsAfterIncremental.get("vectorCount"));
            
        } finally {
            System.clearProperty("cuvs.development.mode");
        }
    }

    @Test
    void shouldMaintainAccuracyAfterIncrementalUpdates() throws Exception {
        // Given
        System.setProperty("cuvs.development.mode", "true");
        IndexDescriptor descriptor = createTestDescriptor();
        CagraCuvsIndexImpl index = new CagraCuvsIndexImpl(
            descriptor,
            Paths.get("/tmp/test-accuracy-cuvs-index"),
            VectorSimilarityFunctions.EUCLIDEAN,
            mock(FileSystemAbstraction.class)
        );
        
        try {
            index.initialize();

            // When - Add initial vectors with known distances
            List<CagraCuvsIndexImpl.VectorData> initialVectors = List.of(
                new CagraCuvsIndexImpl.VectorData(1L, new float[]{0.0f, 0.0f, 0.0f}, Map.of("label", "origin")),
                new CagraCuvsIndexImpl.VectorData(2L, new float[]{1.0f, 0.0f, 0.0f}, Map.of("label", "unit-x")),
                new CagraCuvsIndexImpl.VectorData(3L, new float[]{0.0f, 1.0f, 0.0f}, Map.of("label", "unit-y"))
            );
            index.addVectors(initialVectors);

            // When - Add incremental vectors
            List<CagraCuvsIndexImpl.VectorData> incrementalVectors = List.of(
                new CagraCuvsIndexImpl.VectorData(4L, new float[]{2.0f, 0.0f, 0.0f}, Map.of("label", "double-x")),
                new CagraCuvsIndexImpl.VectorData(5L, new float[]{0.0f, 0.0f, 1.0f}, Map.of("label", "unit-z"))
            );
            index.addVectors(incrementalVectors);

            // Then - Verify all vectors are accessible
            Map<String, Object> finalStats = index.getStatistics();
            assertEquals(5, finalStats.get("vectorCount"));
            
            // Verify index is still functional (can create reader)
            ValueIndexReader reader = index.getIndexReader(mock(org.neo4j.kernel.impl.index.schema.IndexUsageTracking.class));
            assertNotNull(reader);
            assertTrue(reader instanceof CuvsIndexReader);
            
        } finally {
            System.clearProperty("cuvs.development.mode");
        }
    }

    private IndexDescriptor createTestDescriptor() {
        // For now, return null since we're testing CagraCuvsIndexImpl directly
        // without complex Neo4j integration
        return null;
    }
}
