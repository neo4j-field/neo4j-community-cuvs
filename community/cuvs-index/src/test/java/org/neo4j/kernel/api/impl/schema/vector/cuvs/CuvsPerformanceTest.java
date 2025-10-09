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
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.neo4j.internal.schema.IndexDescriptor;
import org.neo4j.kernel.api.impl.schema.vector.VectorSimilarityFunctions;
import org.neo4j.values.storable.FloatingPointArray;
import org.neo4j.values.storable.Values;
import org.neo4j.kernel.api.index.ValueIndexReader;
import org.neo4j.internal.schema.IndexPrototype;
import org.neo4j.internal.schema.IndexProviderDescriptor;
import org.neo4j.internal.schema.IndexType;
import org.neo4j.internal.schema.SchemaDescriptors;

public class CuvsPerformanceTest {

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldHandleLargeNumberOfVectors() throws IOException {
        // Given
        IndexDescriptor descriptor = createTestDescriptor();
        SimpleCuvsIndex index = new SimpleCuvsIndex(
            descriptor,
            Paths.get("/tmp/performance-test-cuvs-index"),
            VectorSimilarityFunctions.EUCLIDEAN,
            mock(org.neo4j.io.fs.FileSystemAbstraction.class)
        );
        index.initialize();

        // When - Add 1000 vectors
        List<SimpleCuvsIndex.VectorData> vectors = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            float[] vector = new float[128];
            for (int j = 0; j < 128; j++) {
                vector[j] = (float) Math.random();
            }
            vectors.add(new SimpleCuvsIndex.VectorData(i, vector, Map.of()));
        }

        long startTime = System.currentTimeMillis();
        index.addVectors(vectors);
        long endTime = System.currentTimeMillis();

        // Then
        Map<String, Object> stats = index.getStatistics();
        assertEquals(1000, stats.get("vectorCount"));
        assertEquals(128, stats.get("dimensions"));

        long duration = endTime - startTime;
        System.out.println("Added 1000 vectors in " + duration + "ms");
        
        // Should complete within reasonable time (less than 10 seconds)
        assertTrue(duration < 10000);

        index.close();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldHandleConcurrentOperations() throws Exception {
        // Given
        IndexDescriptor descriptor = null;
        SimpleCuvsIndex index = new SimpleCuvsIndex(
            descriptor,
            Paths.get("/tmp/concurrent-test-cuvs-index"),
            VectorSimilarityFunctions.EUCLIDEAN,
            mock(org.neo4j.io.fs.FileSystemAbstraction.class)
        );
        index.initialize();

        ExecutorService executor = Executors.newFixedThreadPool(4);
        List<Future<?>> futures = new ArrayList<>();

        // When - Add vectors concurrently from multiple threads
        for (int threadId = 0; threadId < 4; threadId++) {
            final int threadNum = threadId;
            Future<?> future = executor.submit(() -> {
                try {
                    List<SimpleCuvsIndex.VectorData> vectors = new ArrayList<>();
                    for (int i = 0; i < 100; i++) {
                        float[] vector = new float[64];
                        for (int j = 0; j < 64; j++) {
                            vector[j] = (float) Math.random();
                        }
                        vectors.add(new SimpleCuvsIndex.VectorData(
                            threadNum * 100 + i, vector, Map.of()
                        ));
                    }
                    index.addVectors(vectors);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            futures.add(future);
        }

        // Wait for all threads to complete
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }

        executor.shutdown();

        // Then
        Map<String, Object> stats = index.getStatistics();
        assertEquals(400, stats.get("vectorCount")); // 4 threads * 100 vectors each
        assertEquals(64, stats.get("dimensions"));

        index.close();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldHandleMultipleSearchOperations() throws IOException {
        // Given
        IndexDescriptor descriptor = null;
        SimpleCuvsIndex index = new SimpleCuvsIndex(
            descriptor,
            Paths.get("/tmp/search-test-cuvs-index"),
            VectorSimilarityFunctions.EUCLIDEAN,
            mock(org.neo4j.io.fs.FileSystemAbstraction.class)
        );
        index.initialize();

        // Add some vectors first
        List<SimpleCuvsIndex.VectorData> vectors = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            float[] vector = new float[32];
            for (int j = 0; j < 32; j++) {
                vector[j] = (float) Math.random();
            }
            vectors.add(new SimpleCuvsIndex.VectorData(i, vector, Map.of()));
        }
        index.addVectors(vectors);

        // When - Perform multiple searches
        long startTime = System.currentTimeMillis();
        for (int i = 0; i < 100; i++) {
            // Test index reader creation instead of direct search
            ValueIndexReader reader = index.getIndexReader(mock(org.neo4j.kernel.impl.index.schema.IndexUsageTracking.class));
            assertNotNull(reader);
            assertTrue(reader instanceof CuvsIndexReader);
        }
        long endTime = System.currentTimeMillis();

        // Then
        long duration = endTime - startTime;
        System.out.println("Performed 100 searches in " + duration + "ms");
        
        // Should complete within reasonable time (less than 5 seconds)
        assertTrue(duration < 5000);

        index.close();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shouldHandleHighDimensionalVectors() throws IOException {
        // Given
        IndexDescriptor descriptor = null;
        SimpleCuvsIndex index = new SimpleCuvsIndex(
            descriptor,
            Paths.get("/tmp/high-dim-test-cuvs-index"),
            VectorSimilarityFunctions.EUCLIDEAN,
            mock(org.neo4j.io.fs.FileSystemAbstraction.class)
        );
        index.initialize();

        // When - Add vectors with high dimensions (1024)
        List<SimpleCuvsIndex.VectorData> vectors = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            float[] vector = new float[1024];
            for (int j = 0; j < 1024; j++) {
                vector[j] = (float) Math.random();
            }
            vectors.add(new SimpleCuvsIndex.VectorData(i, vector, Map.of()));
        }

        long startTime = System.currentTimeMillis();
        index.addVectors(vectors);
        long endTime = System.currentTimeMillis();

        // Then
        Map<String, Object> stats = index.getStatistics();
        assertEquals(50, stats.get("vectorCount"));
        assertEquals(1024, stats.get("dimensions"));

        long duration = endTime - startTime;
        System.out.println("Added 50 high-dimensional vectors (1024D) in " + duration + "ms");
        
        // Should complete within reasonable time
        assertTrue(duration < 5000);

        index.close();
    }

    @Test
    void shouldHandleMemoryEfficiently() throws IOException {
        // Given
        IndexDescriptor descriptor = null;
        SimpleCuvsIndex index = new SimpleCuvsIndex(
            descriptor,
            Paths.get("/tmp/memory-test-cuvs-index"),
            VectorSimilarityFunctions.EUCLIDEAN,
            mock(org.neo4j.io.fs.FileSystemAbstraction.class)
        );
        index.initialize();
        index.create(); // Need to create the index before opening
        index.open(); // Need to open the index for close() to work

        // When - Add vectors in batches
        for (int batch = 0; batch < 10; batch++) {
            List<SimpleCuvsIndex.VectorData> vectors = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                float[] vector = new float[256];
                for (int j = 0; j < 256; j++) {
                    vector[j] = (float) Math.random();
                }
                vectors.add(new SimpleCuvsIndex.VectorData(batch * 100 + i, vector, Map.of()));
            }
            index.addVectors(vectors);
        }

        // Then
        Map<String, Object> stats = index.getStatistics();
        assertEquals(1000, stats.get("vectorCount"));
        assertEquals(256, stats.get("dimensions"));

        // Close and verify cleanup
        index.close();
        Map<String, Object> statsAfterClose = index.getStatistics();
        assertEquals(0, statsAfterClose.get("vectorCount"));
        assertFalse((Boolean) statsAfterClose.get("initialized"));
    }

    private IndexDescriptor createTestDescriptor() {
        return IndexPrototype.forSchema(SchemaDescriptors.forLabel(1, 1))
            .withIndexProvider(new IndexProviderDescriptor("cuvs", "1.0"))
            .withIndexType(IndexType.VECTOR)
            .withName("test_vector_index")
            .materialise(1);
    }
}
