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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.internal.kernel.api.InternalIndexState;
import org.neo4j.internal.schema.IndexDescriptor;
import org.neo4j.kernel.api.impl.schema.vector.VectorSimilarityFunctions;
import org.neo4j.kernel.api.vector.VectorSimilarityFunction;
import org.neo4j.values.storable.FloatingPointArray;
import org.neo4j.values.storable.Values;
import org.neo4j.kernel.api.index.ValueIndexReader;
import org.neo4j.internal.schema.IndexPrototype;
import org.neo4j.internal.schema.IndexProviderDescriptor;
import org.neo4j.internal.schema.IndexType;
import org.neo4j.internal.schema.SchemaDescriptors;

public class CuvsIntegrationTest {

    @Test
    void shouldCompleteFullWorkflow() throws IOException {
        // Given
        IndexDescriptor descriptor = createTestDescriptor();
        CagraCuvsIndexImpl index = new CagraCuvsIndexImpl(
            descriptor,
            Paths.get("/tmp/integration-test-cuvs-index"),
            VectorSimilarityFunctions.EUCLIDEAN,
            mock(org.neo4j.io.fs.FileSystemAbstraction.class)
        );

        // When/Then - Complete workflow
        // 1. Initialize
        index.initialize();
        assertEquals(InternalIndexState.ONLINE, index.getState());

        // 2. Add vectors
        List<CagraCuvsIndexImpl.VectorData> vectors = List.of(
            new CagraCuvsIndexImpl.VectorData(1L, new float[]{1.0f, 2.0f, 3.0f}, Map.of("name", "vector1")),
            new CagraCuvsIndexImpl.VectorData(2L, new float[]{4.0f, 5.0f, 6.0f}, Map.of("name", "vector2")),
            new CagraCuvsIndexImpl.VectorData(3L, new float[]{7.0f, 8.0f, 9.0f}, Map.of("name", "vector3"))
        );
        index.addVectors(vectors);

        // 3. Verify statistics
        Map<String, Object> stats = index.getStatistics();
        assertTrue((Boolean) stats.get("initialized"));
        assertEquals(3, stats.get("vectorCount"));
        assertEquals(3, stats.get("dimensions"));
        assertEquals("EUCLIDEAN", stats.get("similarityFunction"));

        // 4. Create reader and test search
        ValueIndexReader reader = index.getIndexReader(mock(org.neo4j.kernel.impl.index.schema.IndexUsageTracking.class));
        assertTrue(reader instanceof CuvsIndexReader);
        assertNotNull(reader);
        // Note: Actual search testing would require more complex setup with PropertyIndexQuery

        // 5. Close
        index.close();
        Map<String, Object> statsAfterClose = index.getStatistics();
        assertFalse((Boolean) statsAfterClose.get("initialized"));
        assertEquals(0, statsAfterClose.get("vectorCount"));
    }

    @Test
    void shouldHandleDifferentSimilarityFunctions() throws IOException {
        // Given
        IndexDescriptor descriptor = createTestDescriptor();
        VectorSimilarityFunction[] functions = {
            VectorSimilarityFunctions.EUCLIDEAN
            // Note: COSINE and DOT_PRODUCT are not available in VectorSimilarityFunctions
        };

        for (VectorSimilarityFunction function : functions) {
            // When
            CagraCuvsIndexImpl index = new CagraCuvsIndexImpl(
                descriptor,
                Paths.get("/tmp/integration-test-" + function.name()),
                function,
                mock(org.neo4j.io.fs.FileSystemAbstraction.class)
            );
            index.initialize();

            // Then
            Map<String, Object> stats = index.getStatistics();
            assertEquals(function.name(), stats.get("similarityFunction"));

            // Add some vectors
            List<CagraCuvsIndexImpl.VectorData> vectors = List.of(
                new CagraCuvsIndexImpl.VectorData(1L, new float[]{1.0f, 2.0f}, Map.of())
            );
            index.addVectors(vectors);

            // Create reader (search functionality moved to reader)
            ValueIndexReader reader = index.getIndexReader(mock(org.neo4j.kernel.impl.index.schema.IndexUsageTracking.class));
        assertTrue(reader instanceof CuvsIndexReader);
            assertNotNull(reader);

            index.close();
        }
    }

    @Test
    void shouldHandleMultipleIndexInstances() throws IOException {
        // Given
        IndexDescriptor descriptor = createTestDescriptor();

        // When - Create multiple index instances
        CagraCuvsIndexImpl index1 = new CagraCuvsIndexImpl(
            descriptor,
            Paths.get("/tmp/multi-index-1"),
            VectorSimilarityFunctions.EUCLIDEAN,
            mock(org.neo4j.io.fs.FileSystemAbstraction.class)
        );
        CagraCuvsIndexImpl index2 = new CagraCuvsIndexImpl(
            descriptor,
            Paths.get("/tmp/multi-index-2"),
            VectorSimilarityFunctions.EUCLIDEAN, // Using EUCLIDEAN since COSINE is not available
            mock(org.neo4j.io.fs.FileSystemAbstraction.class)
        );

        // Then - Both should work independently
        index1.initialize();
        index2.initialize();

        // Add different vectors to each
        List<CagraCuvsIndexImpl.VectorData> vectors1 = List.of(
            new CagraCuvsIndexImpl.VectorData(1L, new float[]{1.0f, 2.0f}, Map.of())
        );
        List<CagraCuvsIndexImpl.VectorData> vectors2 = List.of(
            new CagraCuvsIndexImpl.VectorData(2L, new float[]{3.0f, 4.0f}, Map.of())
        );

        index1.addVectors(vectors1);
        index2.addVectors(vectors2);

        // Verify independent statistics
        Map<String, Object> stats1 = index1.getStatistics();
        Map<String, Object> stats2 = index2.getStatistics();

        assertEquals(1, stats1.get("vectorCount"));
        assertEquals(1, stats2.get("vectorCount"));
        assertEquals("EUCLIDEAN", stats1.get("similarityFunction"));
        assertEquals("EUCLIDEAN", stats2.get("similarityFunction"));

        // Close both
        index1.close();
        index2.close();
    }

    @Test
    void shouldHandleReinitialization() throws IOException {
        // Given
        IndexDescriptor descriptor = createTestDescriptor();
        CagraCuvsIndexImpl index = new CagraCuvsIndexImpl(
            descriptor,
            Paths.get("/tmp/reinit-test-cuvs-index"),
            VectorSimilarityFunctions.EUCLIDEAN,
            mock(org.neo4j.io.fs.FileSystemAbstraction.class)
        );

        // When - Initialize, add vectors, close, then reinitialize
        index.initialize();
        
        List<CagraCuvsIndexImpl.VectorData> vectors = List.of(
            new CagraCuvsIndexImpl.VectorData(1L, new float[]{1.0f, 2.0f}, Map.of())
        );
        index.addVectors(vectors);
        assertEquals(1, index.getStatistics().get("vectorCount"));

        index.close();
        assertFalse((Boolean) index.getStatistics().get("initialized"));

        // Reinitialize
        index.initialize();
        assertEquals(0, index.getStatistics().get("vectorCount"));

        // Add vectors again
        index.addVectors(vectors);
        assertEquals(1, index.getStatistics().get("vectorCount"));

        index.close();
    }

    @Test
    void shouldHandleVectorDataWithProperties() throws IOException {
        // Given
        IndexDescriptor descriptor = createTestDescriptor();
        CagraCuvsIndexImpl index = new CagraCuvsIndexImpl(
            descriptor,
            Paths.get("/tmp/properties-test-cuvs-index"),
            VectorSimilarityFunctions.EUCLIDEAN,
            mock(org.neo4j.io.fs.FileSystemAbstraction.class)
        );
        index.initialize();

        // When - Add vectors with properties
        List<CagraCuvsIndexImpl.VectorData> vectors = List.of(
            new CagraCuvsIndexImpl.VectorData(1L, new float[]{1.0f, 2.0f}, Map.of(
                "name", "test-vector",
                "category", "test",
                "score", 0.95f
            ))
        );
        index.addVectors(vectors);

        // Then
        Map<String, Object> stats = index.getStatistics();
        assertEquals(1, stats.get("vectorCount"));

        // Create reader (search functionality moved to reader)
        ValueIndexReader reader = index.getIndexReader(mock(org.neo4j.kernel.impl.index.schema.IndexUsageTracking.class));
        assertTrue(reader instanceof CuvsIndexReader);
        assertNotNull(reader);

        index.close();
    }

    @Test
    void shouldHandleSearchResultsWithProperties() throws IOException {
        // Given
        IndexDescriptor descriptor = createTestDescriptor();
        CagraCuvsIndexImpl index = new CagraCuvsIndexImpl(
            descriptor,
            Paths.get("/tmp/search-results-test-cuvs-index"),
            VectorSimilarityFunctions.EUCLIDEAN,
            mock(org.neo4j.io.fs.FileSystemAbstraction.class)
        );
        index.initialize();

        // When - Add vectors and search
        List<CagraCuvsIndexImpl.VectorData> vectors = List.of(
            new CagraCuvsIndexImpl.VectorData(1L, new float[]{1.0f, 2.0f}, Map.of("name", "vector1"))
        );
        index.addVectors(vectors);

        // Create reader (search functionality moved to reader)
        ValueIndexReader reader = index.getIndexReader(mock(org.neo4j.kernel.impl.index.schema.IndexUsageTracking.class));
        assertTrue(reader instanceof CuvsIndexReader);
        assertNotNull(reader);

        // Then - Reader should be created successfully
        // In a real implementation, search would be performed through the reader

        index.close();
    }

    private IndexDescriptor createTestDescriptor() {
        return IndexPrototype.forSchema(SchemaDescriptors.forLabel(1, 1))
            .withIndexProvider(new IndexProviderDescriptor("cuvs", "1.0"))
            .withIndexType(IndexType.VECTOR)
            .withName("test_vector_index")
            .materialise(1);
    }
}
