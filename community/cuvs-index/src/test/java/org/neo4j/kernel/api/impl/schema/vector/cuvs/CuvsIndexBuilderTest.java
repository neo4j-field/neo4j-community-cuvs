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

import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.neo4j.configuration.Config;
import org.neo4j.dbms.database.readonly.DatabaseReadOnlyChecker;
import org.neo4j.internal.schema.IndexDescriptor;
import org.neo4j.internal.schema.IndexPrototype;
import org.neo4j.internal.schema.IndexProviderDescriptor;
import org.neo4j.internal.schema.IndexType;
import org.neo4j.internal.schema.SchemaDescriptors;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.kernel.api.impl.schema.vector.VectorIndexConfig;
import org.neo4j.kernel.api.impl.schema.vector.VectorSimilarityFunctions;
import org.neo4j.kernel.api.vector.VectorSimilarityFunction;

public class CuvsIndexBuilderTest {

    @Test
    void shouldCreateBuilderWithRequiredParameters() {
        // Given
        IndexDescriptor descriptor = createTestDescriptor();
        VectorIndexConfig config = mock(VectorIndexConfig.class);
        VectorSimilarityFunction similarityFunction = VectorSimilarityFunctions.EUCLIDEAN;
        DatabaseReadOnlyChecker readOnlyChecker = mock(DatabaseReadOnlyChecker.class);
        Config neo4jConfig = mock(Config.class);
        FileSystemAbstraction fileSystem = mock(FileSystemAbstraction.class);

        // When
        CuvsIndexBuilder builder = CuvsIndexBuilder.create(
            descriptor,
            config,
            similarityFunction,
            readOnlyChecker,
            neo4jConfig,
            fileSystem
        );

        // Then
        assertNotNull(builder);
    }

    @Test
    void shouldBuildIndexWithIndexDirectory() {
        // Given
        IndexDescriptor descriptor = createTestDescriptor();
        VectorIndexConfig config = mock(VectorIndexConfig.class);
        VectorSimilarityFunction similarityFunction = VectorSimilarityFunctions.EUCLIDEAN;
        DatabaseReadOnlyChecker readOnlyChecker = mock(DatabaseReadOnlyChecker.class);
        Config neo4jConfig = mock(Config.class);
        FileSystemAbstraction fileSystem = mock(FileSystemAbstraction.class);
        
        Path indexDirectory = Paths.get("/tmp/test-index");

        CuvsIndexBuilder builder = CuvsIndexBuilder.create(
            descriptor,
            config,
            similarityFunction,
            readOnlyChecker,
            neo4jConfig,
            fileSystem
        );

        // When
        SimpleCuvsIndex index = builder
            .withIndexDirectory(indexDirectory)
            .build();

        // Then
        assertNotNull(index);
        assertEquals(descriptor, index.getDescriptor());
    }

    @Test
    void shouldBuildPermanentlyReadOnlyIndex() {
        // Given
        IndexDescriptor descriptor = createTestDescriptor();
        VectorIndexConfig config = mock(VectorIndexConfig.class);
        VectorSimilarityFunction similarityFunction = VectorSimilarityFunctions.EUCLIDEAN;
        DatabaseReadOnlyChecker readOnlyChecker = mock(DatabaseReadOnlyChecker.class);
        Config neo4jConfig = mock(Config.class);
        FileSystemAbstraction fileSystem = mock(FileSystemAbstraction.class);
        
        Path indexDirectory = Paths.get("/tmp/test-index");

        CuvsIndexBuilder builder = CuvsIndexBuilder.create(
            descriptor,
            config,
            similarityFunction,
            readOnlyChecker,
            neo4jConfig,
            fileSystem
        );

        // When
        SimpleCuvsIndex index = builder
            .withIndexDirectory(indexDirectory)
            .permanentlyReadOnly()
            .build();

        // Then
        assertNotNull(index);
        assertEquals(descriptor, index.getDescriptor());
    }

    @Test
    void shouldThrowExceptionWhenBuildingWithoutIndexDirectory() {
        // Given
        IndexDescriptor descriptor = createTestDescriptor();
        VectorIndexConfig config = mock(VectorIndexConfig.class);
        VectorSimilarityFunction similarityFunction = VectorSimilarityFunctions.EUCLIDEAN;
        DatabaseReadOnlyChecker readOnlyChecker = mock(DatabaseReadOnlyChecker.class);
        Config neo4jConfig = mock(Config.class);
        FileSystemAbstraction fileSystem = mock(FileSystemAbstraction.class);

        CuvsIndexBuilder builder = CuvsIndexBuilder.create(
            descriptor,
            config,
            similarityFunction,
            readOnlyChecker,
            neo4jConfig,
            fileSystem
        );

        // When/Then
        assertThrows(IllegalStateException.class, builder::build);
    }

    @Test
    void shouldSupportFluentBuilderPattern() {
        // Given
        IndexDescriptor descriptor = createTestDescriptor();
        VectorIndexConfig config = mock(VectorIndexConfig.class);
        VectorSimilarityFunction similarityFunction = VectorSimilarityFunctions.EUCLIDEAN;
        DatabaseReadOnlyChecker readOnlyChecker = mock(DatabaseReadOnlyChecker.class);
        Config neo4jConfig = mock(Config.class);
        FileSystemAbstraction fileSystem = mock(FileSystemAbstraction.class);
        
        Path indexDirectory = Paths.get("/tmp/test-index");

        // When
        SimpleCuvsIndex index = CuvsIndexBuilder.create(
            descriptor,
            config,
            similarityFunction,
            readOnlyChecker,
            neo4jConfig,
            fileSystem
        )
        .withIndexDirectory(indexDirectory)
        .permanentlyReadOnly()
        .build();

        // Then
        assertNotNull(index);
    }

    private IndexDescriptor createTestDescriptor() {
        return IndexPrototype.forSchema(SchemaDescriptors.forLabel(1, 1))
            .withIndexProvider(new IndexProviderDescriptor("cuvs", "1.0"))
            .withIndexType(IndexType.VECTOR)
            .withName("test_vector_index")
            .materialise(1);
    }
}
