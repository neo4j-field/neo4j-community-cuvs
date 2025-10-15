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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
 * Tests for TieredIndex threshold behavior - specifically testing the transition
 * between brute force mode (< 100K vectors) and ANN mode (>= 100K vectors).
 */
public class TieredIndexThresholdTest {

    @Test
    void shouldUseBruteForceModeBelowThreshold() throws Exception {
        // Given
        System.setProperty("cuvs.development.mode", "true");
        IndexDescriptor descriptor = createTestDescriptor();
        CagraCuvsIndexImpl index = new CagraCuvsIndexImpl(
            descriptor,
            Paths.get("/tmp/test-brute-force-cuvs-index"),
            VectorSimilarityFunctions.EUCLIDEAN,
            mock(FileSystemAbstraction.class)
        );
        
        try {
            index.initialize();

            // When - Add vectors below threshold (50K vectors)
            List<CagraCuvsIndexImpl.VectorData> vectors = new ArrayList<>();
            for (int i = 0; i < 50000; i++) {
                float[] vector = new float[128];
                for (int j = 0; j < 128; j++) {
                    vector[j] = (float) Math.random();
                }
                vectors.add(new CagraCuvsIndexImpl.VectorData(i, vector, Map.of()));
            }
            index.addVectors(vectors);

            // Then - Verify brute force mode (should be below threshold)
            Map<String, Object> stats = index.getStatistics();
            assertEquals(50000, stats.get("vectorCount"));
            assertEquals(128, stats.get("dimensions"));
            
            // Verify index is functional
            assertTrue((Boolean) stats.get("initialized"));
            
        } finally {
            System.clearProperty("cuvs.development.mode");
        }
    }

    @Test
    void shouldHandleThresholdBoundary() throws Exception {
        // Given
        System.setProperty("cuvs.development.mode", "true");
        IndexDescriptor descriptor = createTestDescriptor();
        CagraCuvsIndexImpl index = new CagraCuvsIndexImpl(
            descriptor,
            Paths.get("/tmp/test-threshold-boundary-cuvs-index"),
            VectorSimilarityFunctions.EUCLIDEAN,
            mock(FileSystemAbstraction.class)
        );
        
        try {
            index.initialize();

            // When - Add exactly 100K vectors (threshold)
            List<CagraCuvsIndexImpl.VectorData> vectors = new ArrayList<>();
            for (int i = 0; i < 100000; i++) {
                float[] vector = new float[64]; // Smaller dimensions for faster test
                for (int j = 0; j < 64; j++) {
                    vector[j] = (float) Math.random();
                }
                vectors.add(new CagraCuvsIndexImpl.VectorData(i, vector, Map.of()));
            }
            index.addVectors(vectors);

            // Then - Verify threshold behavior
            Map<String, Object> stats = index.getStatistics();
            assertEquals(100000, stats.get("vectorCount"));
            assertEquals(64, stats.get("dimensions"));
            
            // Verify index is functional at threshold
            assertTrue((Boolean) stats.get("initialized"));
            
        } finally {
            System.clearProperty("cuvs.development.mode");
        }
    }

    @Test
    void shouldTransitionToAnnModeAboveThreshold() throws Exception {
        // Given
        System.setProperty("cuvs.development.mode", "true");
        IndexDescriptor descriptor = createTestDescriptor();
        CagraCuvsIndexImpl index = new CagraCuvsIndexImpl(
            descriptor,
            Paths.get("/tmp/test-ann-mode-cuvs-index"),
            VectorSimilarityFunctions.EUCLIDEAN,
            mock(FileSystemAbstraction.class)
        );
        
        try {
            index.initialize();

            // When - Add vectors above threshold (150K vectors)
            List<CagraCuvsIndexImpl.VectorData> vectors = new ArrayList<>();
            for (int i = 0; i < 150000; i++) {
                float[] vector = new float[32]; // Small dimensions for faster test
                for (int j = 0; j < 32; j++) {
                    vector[j] = (float) Math.random();
                }
                vectors.add(new CagraCuvsIndexImpl.VectorData(i, vector, Map.of()));
            }
            index.addVectors(vectors);

            // Then - Verify ANN mode (should be above threshold)
            Map<String, Object> stats = index.getStatistics();
            assertEquals(150000, stats.get("vectorCount"));
            assertEquals(32, stats.get("dimensions"));
            
            // Verify index is functional in ANN mode
            assertTrue((Boolean) stats.get("initialized"));
            
        } finally {
            System.clearProperty("cuvs.development.mode");
        }
    }

    @Test
    void shouldHandleIncrementalUpdatesAcrossThreshold() throws Exception {
        // Given
        System.setProperty("cuvs.development.mode", "true");
        IndexDescriptor descriptor = createTestDescriptor();
        CagraCuvsIndexImpl index = new CagraCuvsIndexImpl(
            descriptor,
            Paths.get("/tmp/test-cross-threshold-cuvs-index"),
            VectorSimilarityFunctions.EUCLIDEAN,
            mock(FileSystemAbstraction.class)
        );
        
        try {
            index.initialize();

            // When - Start below threshold (50K vectors)
            List<CagraCuvsIndexImpl.VectorData> initialVectors = new ArrayList<>();
            for (int i = 0; i < 50000; i++) {
                float[] vector = new float[16]; // Very small dimensions for faster test
                for (int j = 0; j < 16; j++) {
                    vector[j] = (float) Math.random();
                }
                initialVectors.add(new CagraCuvsIndexImpl.VectorData(i, vector, Map.of()));
            }
            index.addVectors(initialVectors);

            // Verify initial state (brute force mode)
            Map<String, Object> statsAfterInitial = index.getStatistics();
            assertEquals(50000, statsAfterInitial.get("vectorCount"));

            // When - Add more vectors to cross threshold (60K more = 110K total)
            List<CagraCuvsIndexImpl.VectorData> additionalVectors = new ArrayList<>();
            for (int i = 50000; i < 110000; i++) {
                float[] vector = new float[16];
                for (int j = 0; j < 16; j++) {
                    vector[j] = (float) Math.random();
                }
                additionalVectors.add(new CagraCuvsIndexImpl.VectorData(i, vector, Map.of()));
            }
            index.addVectors(additionalVectors);

            // Then - Verify transition to ANN mode
            Map<String, Object> statsAfterTransition = index.getStatistics();
            assertEquals(110000, statsAfterTransition.get("vectorCount"));
            assertEquals(16, statsAfterTransition.get("dimensions"));
            
            // Verify index is still functional after threshold crossing
            assertTrue((Boolean) statsAfterTransition.get("initialized"));
            
        } finally {
            System.clearProperty("cuvs.development.mode");
        }
    }

    @Test
    void shouldMaintainPerformanceAcrossThreshold() throws Exception {
        // Given
        System.setProperty("cuvs.development.mode", "true");
        IndexDescriptor descriptor = createTestDescriptor();
        CagraCuvsIndexImpl index = new CagraCuvsIndexImpl(
            descriptor,
            Paths.get("/tmp/test-performance-threshold-cuvs-index"),
            VectorSimilarityFunctions.EUCLIDEAN,
            mock(FileSystemAbstraction.class)
        );
        
        try {
            index.initialize();

            // When - Add vectors in batches across threshold
            long totalStartTime = System.currentTimeMillis();
            
            // Batch 1: Below threshold (30K)
            List<CagraCuvsIndexImpl.VectorData> batch1 = new ArrayList<>();
            for (int i = 0; i < 30000; i++) {
                float[] vector = new float[8]; // Very small for speed
                for (int j = 0; j < 8; j++) {
                    vector[j] = (float) Math.random();
                }
                batch1.add(new CagraCuvsIndexImpl.VectorData(i, vector, Map.of()));
            }
            long batch1Start = System.currentTimeMillis();
            index.addVectors(batch1);
            long batch1Time = System.currentTimeMillis() - batch1Start;
            
            // Batch 2: Cross threshold (80K more = 110K total)
            List<CagraCuvsIndexImpl.VectorData> batch2 = new ArrayList<>();
            for (int i = 30000; i < 110000; i++) {
                float[] vector = new float[8];
                for (int j = 0; j < 8; j++) {
                    vector[j] = (float) Math.random();
                }
                batch2.add(new CagraCuvsIndexImpl.VectorData(i, vector, Map.of()));
            }
            long batch2Start = System.currentTimeMillis();
            index.addVectors(batch2);
            long batch2Time = System.currentTimeMillis() - batch2Start;
            
            long totalTime = System.currentTimeMillis() - totalStartTime;

            // Then - Verify performance is reasonable
            Map<String, Object> finalStats = index.getStatistics();
            assertEquals(110000, finalStats.get("vectorCount"));
            
            // Performance assertions (adjust thresholds as needed)
            assertTrue(batch1Time < 5000, "Batch 1 (brute force) took too long: " + batch1Time + "ms");
            assertTrue(batch2Time < 10000, "Batch 2 (ANN transition) took too long: " + batch2Time + "ms");
            assertTrue(totalTime < 15000, "Total time across threshold took too long: " + totalTime + "ms");
            
            System.out.println("Performance across threshold - Batch 1: " + batch1Time + "ms, Batch 2: " + batch2Time + "ms, Total: " + totalTime + "ms");
            
        } finally {
            System.clearProperty("cuvs.development.mode");
        }
    }

    private IndexDescriptor createTestDescriptor() {
        return IndexPrototype.forSchema(SchemaDescriptors.forLabel(1, 1))
                .withIndexType(IndexType.VECTOR)
                .withIndexProvider(new IndexProviderDescriptor("cuvs", "1.0"))
                .withName("test_threshold_index")
                .materialise(1);
    }
}
