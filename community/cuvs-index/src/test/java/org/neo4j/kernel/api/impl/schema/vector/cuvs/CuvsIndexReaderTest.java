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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.neo4j.internal.kernel.api.IndexQueryConstraints;
import org.neo4j.internal.kernel.api.PropertyIndexQuery;
import org.neo4j.internal.kernel.api.QueryContext;
import org.neo4j.internal.kernel.api.exceptions.schema.IndexNotApplicableKernelException;
import org.neo4j.internal.schema.IndexDescriptor;
import org.neo4j.internal.schema.IndexProviderDescriptor;
import org.neo4j.internal.schema.IndexPrototype;
import org.neo4j.internal.schema.IndexQuery.IndexQueryType;
import org.neo4j.internal.schema.IndexType;
import org.neo4j.internal.schema.SchemaDescriptors;
import org.neo4j.kernel.api.impl.schema.vector.VectorSimilarityFunctions;
import org.neo4j.kernel.api.index.IndexProgressor;
import org.neo4j.kernel.api.vector.VectorSimilarityFunction;
import org.neo4j.kernel.impl.index.schema.IndexUsageTracking;

public class CuvsIndexReaderTest {

    @Test
    void shouldCreateReaderWithValidIndex() throws Exception {
        // Given
        System.setProperty("cuvs.development.mode", "true");
        IndexDescriptor descriptor = createTestDescriptor();
        SimpleCuvsIndex index = new SimpleCuvsIndex(
            descriptor,
            Paths.get("/tmp/test-cuvs-index"),
            VectorSimilarityFunctions.EUCLIDEAN,
            mock(org.neo4j.io.fs.FileSystemAbstraction.class)
        );
        
        try {
            index.initialize();
            
            // Add some test vectors
            List<SimpleCuvsIndex.VectorData> vectors = List.of(
                new SimpleCuvsIndex.VectorData(1L, new float[]{1.0f, 2.0f, 3.0f}, Map.of()),
                new SimpleCuvsIndex.VectorData(2L, new float[]{4.0f, 5.0f, 6.0f}, Map.of())
            );
            index.addVectors(vectors);

            // When
            CuvsIndexReader reader = new CuvsIndexReader(
                descriptor,
                mock(IndexUsageTracking.class),
                index,
                java.util.OptionalInt.of(3)
            );

            // Then
            assertNotNull(reader);
        } finally {
            System.clearProperty("cuvs.development.mode");
        }
    }

    @Test
    void shouldRejectNonNearestNeighborsQuery() {
        // Given
        IndexDescriptor descriptor = createTestDescriptor();
        SimpleCuvsIndex index = mock(SimpleCuvsIndex.class);
        CuvsIndexReader reader = new CuvsIndexReader(
            descriptor,
            mock(IndexUsageTracking.class),
            index,
            java.util.OptionalInt.of(3)
        );

        PropertyIndexQuery query = mock(PropertyIndexQuery.class);
        when(query.type()).thenReturn(IndexQueryType.EXACT);

        // When/Then
        assertThrows(IndexNotApplicableKernelException.class, () ->
            reader.query(
                mock(IndexProgressor.EntityValueClient.class),
                mock(QueryContext.class),
                IndexQueryConstraints.unconstrained(),
                query
            )
        );
    }

    @Test
    void shouldRejectCompositeQueries() {
        // Given
        IndexDescriptor descriptor = createTestDescriptor();
        SimpleCuvsIndex index = mock(SimpleCuvsIndex.class);
        CuvsIndexReader reader = new CuvsIndexReader(
            descriptor,
            mock(IndexUsageTracking.class),
            index,
            java.util.OptionalInt.of(3)
        );

        PropertyIndexQuery query1 = mock(PropertyIndexQuery.class);
        PropertyIndexQuery query2 = mock(PropertyIndexQuery.class);

        // When/Then
        assertThrows(IndexNotApplicableKernelException.class, () ->
            reader.query(
                mock(IndexProgressor.EntityValueClient.class),
                mock(QueryContext.class),
                IndexQueryConstraints.unconstrained(),
                query1, query2
            )
        );
    }

    @Test
    void shouldValidateVectorDimensions() {
        // Given
        IndexDescriptor descriptor = createTestDescriptor();
        SimpleCuvsIndex index = mock(SimpleCuvsIndex.class);
        CuvsIndexReader reader = new CuvsIndexReader(
            descriptor,
            mock(IndexUsageTracking.class),
            index,
            java.util.OptionalInt.of(3) // Index expects 3 dimensions
        );

        PropertyIndexQuery.NearestNeighborsPredicate query = mock(PropertyIndexQuery.NearestNeighborsPredicate.class);
        when(query.type()).thenReturn(IndexQueryType.NEAREST_NEIGHBORS);
        when(query.query()).thenReturn(new float[]{1.0f, 2.0f}); // Only 2 dimensions

        // When/Then
        assertThrows(IndexNotApplicableKernelException.class, () ->
            reader.query(
                mock(IndexProgressor.EntityValueClient.class),
                mock(QueryContext.class),
                IndexQueryConstraints.unconstrained(),
                query
            )
        );
    }

    @Test
    void shouldAcceptValidNearestNeighborsQuery() throws Exception {
        // Given
        System.setProperty("cuvs.development.mode", "true");
        IndexDescriptor descriptor = createTestDescriptor();
        SimpleCuvsIndex index = new SimpleCuvsIndex(
            descriptor,
            Paths.get("/tmp/test-cuvs-index"),
            VectorSimilarityFunctions.EUCLIDEAN,
            mock(org.neo4j.io.fs.FileSystemAbstraction.class)
        );
        
        try {
            index.initialize();
            
            // Add some test vectors
            List<SimpleCuvsIndex.VectorData> vectors = List.of(
                new SimpleCuvsIndex.VectorData(1L, new float[]{1.0f, 2.0f, 3.0f}, Map.of()),
                new SimpleCuvsIndex.VectorData(2L, new float[]{4.0f, 5.0f, 6.0f}, Map.of())
            );
            index.addVectors(vectors);

            CuvsIndexReader reader = new CuvsIndexReader(
                descriptor,
                mock(IndexUsageTracking.class),
                index,
                java.util.OptionalInt.of(3)
            );

            PropertyIndexQuery.NearestNeighborsPredicate query = mock(PropertyIndexQuery.NearestNeighborsPredicate.class);
            when(query.type()).thenReturn(IndexQueryType.NEAREST_NEIGHBORS);
            when(query.query()).thenReturn(new float[]{1.0f, 2.0f, 3.0f}); // Correct dimensions
            when(query.numberOfNeighbors()).thenReturn(5);

            IndexProgressor.EntityValueClient client = mock(IndexProgressor.EntityValueClient.class);

            // When
            reader.query(
                client,
                mock(QueryContext.class),
                IndexQueryConstraints.unconstrained(),
                query
            );

            // Then - should not throw exception
            // Note: The actual search results depend on CUVS implementation
        } finally {
            System.clearProperty("cuvs.development.mode");
        }
    }

    private IndexDescriptor createTestDescriptor() {
        return IndexPrototype.forSchema(SchemaDescriptors.forLabel(1, 1))
            .withIndexProvider(new IndexProviderDescriptor("cuvs", "1.0"))
            .withIndexType(IndexType.VECTOR)
            .withName("test_vector_index")
            .materialise(1);
    }
}
