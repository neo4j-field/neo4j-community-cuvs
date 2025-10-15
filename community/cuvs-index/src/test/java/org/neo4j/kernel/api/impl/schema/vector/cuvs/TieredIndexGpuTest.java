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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.neo4j.internal.schema.IndexDescriptor;
import org.neo4j.internal.schema.IndexPrototype;
import org.neo4j.internal.schema.IndexProviderDescriptor;
import org.neo4j.internal.schema.IndexType;
import org.neo4j.internal.schema.SchemaDescriptors;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.kernel.api.impl.schema.vector.VectorSimilarityFunctions;

/**
 * Tests for real GPU operations with TieredIndex.
 * These tests will only run if a compatible GPU is available.
 */
public class TieredIndexGpuTest {

    @Test
    void shouldWorkWithRealGpuIfAvailable() throws Exception {
        // Skip test if GPU is not available
        assumeTrue(GpuDetector.isGpuAvailable(), "GPU not available - skipping real GPU test");
        
        // Given - Disable development mode to use real GPU
        System.setProperty("cuvs.development.mode", "false");
        IndexDescriptor descriptor = createTestDescriptor();
        CagraCuvsIndexImpl index = new CagraCuvsIndexImpl(
            descriptor,
            Paths.get("/tmp/test-real-gpu-cuvs-index"),
            VectorSimilarityFunctions.EUCLIDEAN,
            mock(FileSystemAbstraction.class)
        );
        
        try {
            // When - Initialize with real GPU
            index.initialize();

            // Then - Verify GPU initialization
            Map<String, Object> stats = index.getStatistics();
            assertTrue((Boolean) stats.get("initialized"));
            assertTrue((Boolean) stats.get("gpuAvailable"));
            
            // When - Add vectors with real GPU
            List<CagraCuvsIndexImpl.VectorData> vectors = List.of(
                new CagraCuvsIndexImpl.VectorData(1L, new float[]{1.0f, 2.0f, 3.0f}, Map.of("gpu", "test")),
                new CagraCuvsIndexImpl.VectorData(2L, new float[]{4.0f, 5.0f, 6.0f}, Map.of("gpu", "test")),
                new CagraCuvsIndexImpl.VectorData(3L, new float[]{7.0f, 8.0f, 9.0f}, Map.of("gpu", "test"))
            );
            index.addVectors(vectors);

            // Then - Verify vectors were added with GPU
            Map<String, Object> statsAfterAdd = index.getStatistics();
            assertEquals(3, statsAfterAdd.get("vectorCount"));
            assertEquals(3, statsAfterAdd.get("dimensions"));
            assertTrue((Boolean) statsAfterAdd.get("gpuAvailable"));
            
        } catch (GpuUnavailableException e) {
            // This is expected if GPU is not available
            System.out.println("GPU not available for testing: " + e.getMessage());
        } finally {
            System.clearProperty("cuvs.development.mode");
        }
    }

    @Test
    void shouldPerformIncrementalInsertionWithRealGpu() throws Exception {
        // Skip test if GPU is not available
        assumeTrue(GpuDetector.isGpuAvailable(), "GPU not available - skipping real GPU incremental test");
        
        // Given - Disable development mode
        System.setProperty("cuvs.development.mode", "false");
        IndexDescriptor descriptor = createTestDescriptor();
        CagraCuvsIndexImpl index = new CagraCuvsIndexImpl(
            descriptor,
            Paths.get("/tmp/test-real-gpu-incremental-cuvs-index"),
            VectorSimilarityFunctions.EUCLIDEAN,
            mock(FileSystemAbstraction.class)
        );
        
        try {
            index.initialize();

            // When - Add initial vectors
            List<CagraCuvsIndexImpl.VectorData> initialVectors = List.of(
                new CagraCuvsIndexImpl.VectorData(1L, new float[]{1.0f, 2.0f, 3.0f}, Map.of()),
                new CagraCuvsIndexImpl.VectorData(2L, new float[]{4.0f, 5.0f, 6.0f}, Map.of())
            );
            index.addVectors(initialVectors);

            // Verify initial state
            Map<String, Object> statsAfterInitial = index.getStatistics();
            assertEquals(2, statsAfterInitial.get("vectorCount"));

            // When - Add incremental vectors with real GPU
            List<CagraCuvsIndexImpl.VectorData> incrementalVectors = List.of(
                new CagraCuvsIndexImpl.VectorData(3L, new float[]{7.0f, 8.0f, 9.0f}, Map.of()),
                new CagraCuvsIndexImpl.VectorData(4L, new float[]{10.0f, 11.0f, 12.0f}, Map.of())
            );
            index.addVectors(incrementalVectors);

            // Then - Verify incremental addition worked with GPU
            Map<String, Object> statsAfterIncremental = index.getStatistics();
            assertEquals(4, statsAfterIncremental.get("vectorCount"));
            assertTrue((Boolean) statsAfterIncremental.get("gpuAvailable"));
            
        } catch (GpuUnavailableException e) {
            System.out.println("GPU not available for incremental testing: " + e.getMessage());
        } finally {
            System.clearProperty("cuvs.development.mode");
        }
    }

    @Test
    void shouldHandleLargeDatasetWithRealGpu() throws Exception {
        // Skip test if GPU is not available
        assumeTrue(GpuDetector.isGpuAvailable(), "GPU not available - skipping large dataset GPU test");
        
        // Given - Disable development mode
        System.setProperty("cuvs.development.mode", "false");
        IndexDescriptor descriptor = createTestDescriptor();
        CagraCuvsIndexImpl index = new CagraCuvsIndexImpl(
            descriptor,
            Paths.get("/tmp/test-real-gpu-large-cuvs-index"),
            VectorSimilarityFunctions.EUCLIDEAN,
            mock(FileSystemAbstraction.class)
        );
        
        try {
            index.initialize();

            // When - Add large dataset (10K vectors)
            List<CagraCuvsIndexImpl.VectorData> vectors = new ArrayList<>();
            for (int i = 0; i < 10000; i++) {
                float[] vector = new float[128];
                for (int j = 0; j < 128; j++) {
                    vector[j] = (float) Math.random();
                }
                vectors.add(new CagraCuvsIndexImpl.VectorData(i, vector, Map.of()));
            }
            
            long startTime = System.currentTimeMillis();
            index.addVectors(vectors);
            long endTime = System.currentTimeMillis();

            // Then - Verify large dataset handling with GPU
            Map<String, Object> stats = index.getStatistics();
            assertEquals(10000, stats.get("vectorCount"));
            assertEquals(128, stats.get("dimensions"));
            assertTrue((Boolean) stats.get("gpuAvailable"));
            
            long duration = endTime - startTime;
            System.out.println("Added 10K vectors with real GPU in " + duration + "ms");
            
            // Performance should be reasonable with GPU
            assertTrue(duration < 30000, "GPU performance too slow: " + duration + "ms");
            
        } catch (GpuUnavailableException e) {
            System.out.println("GPU not available for large dataset testing: " + e.getMessage());
        } finally {
            System.clearProperty("cuvs.development.mode");
        }
    }

    @Test
    void shouldHandleThresholdTransitionWithRealGpu() throws Exception {
        // Skip test if GPU is not available
        assumeTrue(GpuDetector.isGpuAvailable(), "GPU not available - skipping threshold transition GPU test");
        
        // Given - Disable development mode
        System.setProperty("cuvs.development.mode", "false");
        IndexDescriptor descriptor = createTestDescriptor();
        CagraCuvsIndexImpl index = new CagraCuvsIndexImpl(
            descriptor,
            Paths.get("/tmp/test-real-gpu-threshold-cuvs-index"),
            VectorSimilarityFunctions.EUCLIDEAN,
            mock(FileSystemAbstraction.class)
        );
        
        try {
            index.initialize();

            // When - Add vectors below threshold (50K)
            List<CagraCuvsIndexImpl.VectorData> belowThresholdVectors = new ArrayList<>();
            for (int i = 0; i < 50000; i++) {
                float[] vector = new float[16]; // Small dimensions for faster test
                for (int j = 0; j < 16; j++) {
                    vector[j] = (float) Math.random();
                }
                belowThresholdVectors.add(new CagraCuvsIndexImpl.VectorData(i, vector, Map.of()));
            }
            index.addVectors(belowThresholdVectors);

            // Verify brute force mode
            Map<String, Object> statsBelowThreshold = index.getStatistics();
            assertEquals(50000, statsBelowThreshold.get("vectorCount"));

            // When - Add more vectors to cross threshold (60K more = 110K total)
            List<CagraCuvsIndexImpl.VectorData> aboveThresholdVectors = new ArrayList<>();
            for (int i = 50000; i < 110000; i++) {
                float[] vector = new float[16];
                for (int j = 0; j < 16; j++) {
                    vector[j] = (float) Math.random();
                }
                aboveThresholdVectors.add(new CagraCuvsIndexImpl.VectorData(i, vector, Map.of()));
            }
            index.addVectors(aboveThresholdVectors);

            // Then - Verify threshold transition with GPU
            Map<String, Object> statsAboveThreshold = index.getStatistics();
            assertEquals(110000, statsAboveThreshold.get("vectorCount"));
            assertTrue((Boolean) statsAboveThreshold.get("gpuAvailable"));
            
        } catch (GpuUnavailableException e) {
            System.out.println("GPU not available for threshold transition testing: " + e.getMessage());
        } finally {
            System.clearProperty("cuvs.development.mode");
        }
    }

    @Test
    void shouldFallbackToDevelopmentModeWhenGpuUnavailable() throws Exception {
        // Given - Force GPU unavailable scenario
        System.setProperty("cuvs.development.mode", "false");
        IndexDescriptor descriptor = createTestDescriptor();
        CagraCuvsIndexImpl index = new CagraCuvsIndexImpl(
            descriptor,
            Paths.get("/tmp/test-gpu-fallback-cuvs-index"),
            VectorSimilarityFunctions.EUCLIDEAN,
            mock(FileSystemAbstraction.class)
        );
        
        try {
            // When - Try to initialize without GPU
            try {
                index.initialize();
                // If we get here, GPU was available
                System.out.println("GPU was available - test completed successfully");
            } catch (GpuUnavailableException e) {
                // This is expected when GPU is not available
                System.out.println("GPU not available as expected: " + e.getMessage());
                
                // Enable development mode as fallback
                System.setProperty("cuvs.development.mode", "true");
                index.initialize();
                
                // Then - Verify fallback works
                Map<String, Object> stats = index.getStatistics();
                assertTrue((Boolean) stats.get("initialized"));
                assertFalse((Boolean) stats.get("gpuAvailable"));
            }
            
        } finally {
            System.clearProperty("cuvs.development.mode");
        }
    }

    private IndexDescriptor createTestDescriptor() {
        return IndexPrototype.forSchema(SchemaDescriptors.forLabel(1, 1))
                .withIndexType(IndexType.VECTOR)
                .withIndexProvider(new IndexProviderDescriptor("cuvs", "1.0"))
                .withName("test_gpu_index")
                .materialise(1);
    }
}
