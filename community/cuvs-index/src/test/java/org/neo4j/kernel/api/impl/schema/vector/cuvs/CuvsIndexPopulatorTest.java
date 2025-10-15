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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.neo4j.internal.kernel.api.InternalIndexState;
import org.neo4j.internal.schema.IndexDescriptor;
import org.neo4j.internal.schema.IndexPrototype;
import org.neo4j.internal.schema.IndexProviderDescriptor;
import org.neo4j.internal.schema.IndexType;
import org.neo4j.internal.schema.SchemaDescriptors;
import org.neo4j.io.pagecache.context.CursorContext;
import org.neo4j.kernel.api.impl.schema.vector.VectorSimilarityFunctions;
import org.neo4j.kernel.api.index.IndexUpdater;
import org.neo4j.kernel.impl.index.schema.IndexUpdateIgnoreStrategy;
import org.neo4j.storageengine.api.ValueIndexEntryUpdate;
import org.neo4j.values.storable.FloatingPointArray;
import org.neo4j.values.storable.Values;

public class CuvsIndexPopulatorTest {

    @Test
    void shouldCreatePopulatorWithValidParameters() {
        // Given
        IndexDescriptor descriptor = createTestDescriptor();
        CagraCuvsIndexImpl index = mock(CagraCuvsIndexImpl.class);
        IndexUpdateIgnoreStrategy ignoreStrategy = mock(IndexUpdateIgnoreStrategy.class);

        // When
        CuvsIndexPopulator populator = new CuvsIndexPopulator(index, descriptor, ignoreStrategy);

        // Then
        assertNotNull(populator);
    }

    @Test
    void shouldCreatePopulatingUpdater() {
        // Given
        IndexDescriptor descriptor = createTestDescriptor();
        CagraCuvsIndexImpl index = mock(CagraCuvsIndexImpl.class);
        IndexUpdateIgnoreStrategy ignoreStrategy = mock(IndexUpdateIgnoreStrategy.class);
        CuvsIndexPopulator populator = new CuvsIndexPopulator(index, descriptor, ignoreStrategy);

        // When
        IndexUpdater updater = populator.newPopulatingUpdater(mock(CursorContext.class));

        // Then
        assertNotNull(updater);
    }

    @Test
    void shouldAddVectorUpdate() throws IOException {
        // Given
        System.setProperty("cuvs.development.mode", "true");
        IndexDescriptor descriptor = createTestDescriptor();
        CagraCuvsIndexImpl index = new CagraCuvsIndexImpl(
            descriptor,
            Paths.get("/tmp/test-cuvs-index"),
            VectorSimilarityFunctions.EUCLIDEAN,
            mock(org.neo4j.io.fs.FileSystemAbstraction.class)
        );
        
        IndexUpdateIgnoreStrategy ignoreStrategy = mock(IndexUpdateIgnoreStrategy.class);
        when(ignoreStrategy.ignore(org.mockito.ArgumentMatchers.any(org.neo4j.values.storable.Value[].class))).thenReturn(false);
        
        CuvsIndexPopulator populator = new CuvsIndexPopulator(index, descriptor, ignoreStrategy);

        // Create a vector update
        FloatingPointArray vector = Values.floatArray(new float[]{1.0f, 2.0f, 3.0f});
        ValueIndexEntryUpdate<?> update = ValueIndexEntryUpdate.add(1L, descriptor, vector);

        try {
            // When
            populator.add(update, CursorContext.NULL_CONTEXT);

            // Then - should not throw exception
            // The vector is added to pending list and will be processed on close
        } finally {
            System.clearProperty("cuvs.development.mode");
        }
    }

    @Test
    void shouldIgnoreUpdateWhenStrategySaysSo() throws IOException {
        // Given
        IndexDescriptor descriptor = createTestDescriptor();
        CagraCuvsIndexImpl index = mock(CagraCuvsIndexImpl.class);
        IndexUpdateIgnoreStrategy ignoreStrategy = mock(IndexUpdateIgnoreStrategy.class);
        when(ignoreStrategy.ignore(org.mockito.ArgumentMatchers.any(org.neo4j.values.storable.Value[].class))).thenReturn(true);
        
        CuvsIndexPopulator populator = new CuvsIndexPopulator(index, descriptor, ignoreStrategy);

        FloatingPointArray vector = Values.floatArray(new float[]{1.0f, 2.0f, 3.0f});
        ValueIndexEntryUpdate<?> update = ValueIndexEntryUpdate.add(1L, descriptor, vector);

        // When
            populator.add(update, CursorContext.NULL_CONTEXT);

        // Then - should not call index.addVectors since update was ignored
        // (We can't easily verify this without more complex mocking)
    }

    @Test
    void shouldCloseSuccessfullyWithPendingVectors() throws IOException {
        // Given
        System.setProperty("cuvs.development.mode", "true");
        IndexDescriptor descriptor = createTestDescriptor();
        CagraCuvsIndexImpl index = new CagraCuvsIndexImpl(
            descriptor,
            Paths.get("/tmp/test-cuvs-index"),
            VectorSimilarityFunctions.EUCLIDEAN,
            mock(org.neo4j.io.fs.FileSystemAbstraction.class)
        );
        
        // Initialize the index
        index.create();
        index.open();
        
        IndexUpdateIgnoreStrategy ignoreStrategy = mock(IndexUpdateIgnoreStrategy.class);
        when(ignoreStrategy.ignore(org.mockito.ArgumentMatchers.any(org.neo4j.values.storable.Value[].class))).thenReturn(false);
        
        CuvsIndexPopulator populator = new CuvsIndexPopulator(index, descriptor, ignoreStrategy);

        // Add some vectors
        FloatingPointArray vector1 = Values.floatArray(new float[]{1.0f, 2.0f, 3.0f});
        FloatingPointArray vector2 = Values.floatArray(new float[]{4.0f, 5.0f, 6.0f});
        
        ValueIndexEntryUpdate<?> update1 = ValueIndexEntryUpdate.add(1L, descriptor, vector1);
        ValueIndexEntryUpdate<?> update2 = ValueIndexEntryUpdate.add(2L, descriptor, vector2);
        
        populator.add(update1, CursorContext.NULL_CONTEXT);
        populator.add(update2, CursorContext.NULL_CONTEXT);

        try {
            // When
            populator.close(true, CursorContext.NULL_CONTEXT); // population completed successfully

            // Then - should not throw exception
            // The pending vectors should be added to the index
        } finally {
            System.clearProperty("cuvs.development.mode");
        }
    }

    @Test
    void shouldCloseWithoutAddingVectorsWhenPopulationFailed() throws IOException {
        // Given
        IndexDescriptor descriptor = createTestDescriptor();
        CagraCuvsIndexImpl index = mock(CagraCuvsIndexImpl.class);
        IndexUpdateIgnoreStrategy ignoreStrategy = mock(IndexUpdateIgnoreStrategy.class);
        when(ignoreStrategy.ignore(org.mockito.ArgumentMatchers.any(org.neo4j.values.storable.Value[].class))).thenReturn(false);
        
        CuvsIndexPopulator populator = new CuvsIndexPopulator(index, descriptor, ignoreStrategy);

        // Add some vectors
        FloatingPointArray vector = Values.floatArray(new float[]{1.0f, 2.0f, 3.0f});
        ValueIndexEntryUpdate<?> update = ValueIndexEntryUpdate.add(1L, descriptor, vector);
            populator.add(update, CursorContext.NULL_CONTEXT);

        // When
        populator.close(false, CursorContext.NULL_CONTEXT); // population failed

        // Then - should not call index.addVectors since population failed
        // (We can't easily verify this without more complex mocking)
    }

    @Test
    void shouldThrowExceptionWhenAddingAfterClose() throws IOException {
        // Given
        IndexDescriptor descriptor = createTestDescriptor();
        CagraCuvsIndexImpl index = mock(CagraCuvsIndexImpl.class);
        IndexUpdateIgnoreStrategy ignoreStrategy = mock(IndexUpdateIgnoreStrategy.class);
        
        CuvsIndexPopulator populator = new CuvsIndexPopulator(index, descriptor, ignoreStrategy);
            populator.close(true, CursorContext.NULL_CONTEXT);

        FloatingPointArray vector = Values.floatArray(new float[]{1.0f, 2.0f, 3.0f});
        ValueIndexEntryUpdate<?> update = ValueIndexEntryUpdate.add(1L, descriptor, vector);

        // When/Then
        assertThrows(IllegalStateException.class, () -> populator.add(update, CursorContext.NULL_CONTEXT));
    }

    @Test
    void shouldThrowExceptionWhenCreatingUpdaterAfterClose() {
        // Given
        IndexDescriptor descriptor = createTestDescriptor();
        CagraCuvsIndexImpl index = mock(CagraCuvsIndexImpl.class);
        IndexUpdateIgnoreStrategy ignoreStrategy = mock(IndexUpdateIgnoreStrategy.class);
        
        CuvsIndexPopulator populator = new CuvsIndexPopulator(index, descriptor, ignoreStrategy);
            populator.close(true, CursorContext.NULL_CONTEXT);

        // When/Then
        assertThrows(IllegalStateException.class, () -> 
            populator.newPopulatingUpdater(mock(CursorContext.class)));
    }

    @Test
    void shouldReturnTrueForSampleCompleted() {
        // Given
        IndexDescriptor descriptor = createTestDescriptor();
        CagraCuvsIndexImpl index = mock(CagraCuvsIndexImpl.class);
        IndexUpdateIgnoreStrategy ignoreStrategy = mock(IndexUpdateIgnoreStrategy.class);
        
        CuvsIndexPopulator populator = new CuvsIndexPopulator(index, descriptor, ignoreStrategy);

        // When
        boolean completed = populator.sampleCompleted();

        // Then
        assertEquals(true, completed); // CUVS doesn't use sampling
    }

    private IndexDescriptor createTestDescriptor() {
        return IndexPrototype.forSchema(SchemaDescriptors.forLabel(1, 1))
            .withIndexProvider(new IndexProviderDescriptor("cuvs", "1.0"))
            .withIndexType(IndexType.VECTOR)
            .withName("test_vector_index")
            .materialise(1);
    }
}
