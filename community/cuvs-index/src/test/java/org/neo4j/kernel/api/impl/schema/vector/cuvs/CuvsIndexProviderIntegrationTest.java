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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.configuration.Config;
import org.neo4j.dbms.database.readonly.DatabaseReadOnlyChecker;
import org.neo4j.internal.kernel.api.InternalIndexState;
import org.neo4j.internal.schema.IndexDescriptor;
import org.neo4j.internal.schema.IndexPrototype;
import org.neo4j.internal.schema.IndexProviderDescriptor;
import org.neo4j.internal.schema.IndexType;
import org.neo4j.internal.schema.SchemaDescriptors;
import org.neo4j.io.memory.ByteBufferFactory;
import org.neo4j.kernel.api.index.IndexAccessor;
import org.neo4j.kernel.api.index.IndexPopulator;
import org.neo4j.kernel.api.index.IndexProvider;
import org.neo4j.kernel.api.index.MinimalIndexAccessor;
import org.neo4j.kernel.impl.api.index.IndexSamplingConfig;
import org.neo4j.memory.EmptyMemoryTracker;
import org.neo4j.monitoring.Monitors;
import org.neo4j.scheduler.JobScheduler;
import org.neo4j.storageengine.api.StorageEngineFactory;
import org.neo4j.storageengine.migration.StoreMigrationParticipant;
import org.neo4j.values.ElementIdMapper;

public class CuvsIndexProviderIntegrationTest {

    @Test
    void shouldCreateIndexProvider() {
        // Given
        CuvsIndexVersion version = CuvsIndexVersion.V1_0;
        IndexProviderDescriptor descriptor = version.descriptor();
        
        // When
        CuvsIndexProvider provider = createIndexProvider(version);
        
        // Then
        assertNotNull(provider);
        assertEquals(IndexType.VECTOR, provider.getIndexType());
        assertEquals(descriptor, provider.getProviderDescriptor());
    }

    @Test
    void shouldCreateIndexPopulator() {
        // Given
        CuvsIndexProvider provider = createIndexProvider(CuvsIndexVersion.V1_0);
        IndexDescriptor indexDescriptor = createTestIndexDescriptor();
        
        // When
        IndexPopulator populator = provider.getPopulator(
            indexDescriptor,
            new IndexSamplingConfig(1000, 0.1, true),
            ByteBufferFactory.heapBufferFactory(1024),
            EmptyMemoryTracker.INSTANCE,
            null, // tokenNameLookup
            ElementIdMapper.PLACEHOLDER,
            org.eclipse.collections.api.factory.Sets.immutable.empty(),
            null // indexingBehaviour
        );
        
        // Then
        assertNotNull(populator);
        assertTrue(populator instanceof CuvsIndexPopulator);
    }

    @Test
    void shouldCreateIndexAccessor() throws IOException {
        // Given
        CuvsIndexProvider provider = createIndexProvider(CuvsIndexVersion.V1_0);
        IndexDescriptor indexDescriptor = createTestIndexDescriptor();
        
        // When
        IndexAccessor accessor = provider.getOnlineAccessor(
            indexDescriptor,
            new IndexSamplingConfig(1000, 0.1, true),
            null, // tokenNameLookup
            ElementIdMapper.PLACEHOLDER,
            org.eclipse.collections.api.factory.Sets.immutable.empty(),
            false, // readOnly
            null // indexingBehaviour
        );
        
        // Then
        assertNotNull(accessor);
        assertTrue(accessor instanceof CuvsIndexAccessor);
    }

    @Test
    void shouldReturnPopulatingStateForNewIndex() {
        // Given
        CuvsIndexProvider provider = createIndexProvider(CuvsIndexVersion.V1_0);
        IndexDescriptor indexDescriptor = createTestIndexDescriptor();
        
        // When
        InternalIndexState state = provider.getInitialState(
            indexDescriptor,
            null, // cursorContext
            org.eclipse.collections.api.factory.Sets.immutable.empty()
        );
        
        // Then
        assertEquals(InternalIndexState.POPULATING, state);
    }

    @Test
    void shouldReturnMinimalIndexAccessor() {
        // Given
        CuvsIndexProvider provider = createIndexProvider(CuvsIndexVersion.V1_0);
        IndexDescriptor indexDescriptor = createTestIndexDescriptor();
        
        // When
        MinimalIndexAccessor minimalAccessor = provider.getMinimalIndexAccessor(
            indexDescriptor,
            false // forRebuildDuringRecovery
        );
        
        // Then
        assertNotNull(minimalAccessor);
        assertTrue(minimalAccessor instanceof CuvsMinimalIndexAccessor);
    }

    @Test
    void shouldReturnStoreMigrationParticipant() {
        // Given
        CuvsIndexProvider provider = createIndexProvider(CuvsIndexVersion.V1_0);
        
        // When
        StoreMigrationParticipant participant = provider.storeMigrationParticipant(
            null, // fileSystem
            null, // pageCache
            null, // pageCacheTracer
            null, // storageEngineFactory
            null // contextFactory
        );
        
        // Then
        assertEquals(StoreMigrationParticipant.NOT_PARTICIPATING, participant);
    }

    @Test
    void shouldValidatePrototype() {
        // Given
        CuvsIndexProvider provider = createIndexProvider(CuvsIndexVersion.V1_0);
        IndexPrototype prototype = IndexPrototype.forSchema(SchemaDescriptors.forLabel(1, 1))
            .withIndexType(IndexType.VECTOR)
            .withName("test_cuvs_index")
            .withIndexProvider(new IndexProviderDescriptor("cuvs", "1.0"));
        
        // When
        IndexPrototype validated = provider.validatePrototype(prototype);
        
        // Then
        assertEquals(prototype, validated);
    }

    @Test
    void shouldCompleteConfiguration() {
        // Given
        CuvsIndexProvider provider = createIndexProvider(CuvsIndexVersion.V1_0);
        IndexDescriptor index = createTestIndexDescriptor();
        
        // When
        IndexDescriptor completed = provider.completeConfiguration(index, null);
        
        // Then
        assertEquals(index, completed);
    }

    @Test
    void shouldGetPopulationFailure() {
        // Given
        CuvsIndexProvider provider = createIndexProvider(CuvsIndexVersion.V1_0);
        IndexDescriptor indexDescriptor = createTestIndexDescriptor();
        
        // When
        String failure = provider.getPopulationFailure(
            indexDescriptor,
            null, // cursorContext
            org.eclipse.collections.api.factory.Sets.immutable.empty()
        );
        
        // Then
        assertEquals(null, failure); // No failure detected
    }

    private CuvsIndexProvider createIndexProvider(CuvsIndexVersion version) {
        return new CuvsIndexProvider(
            version,
            null, // storageFactory
            new Monitors(),
            Config.defaults(),
            DatabaseReadOnlyChecker.writable(),
            null, // scheduler
            null // directoryStructureFactory
        );
    }

    private IndexDescriptor createTestIndexDescriptor() {
        return IndexPrototype.forSchema(SchemaDescriptors.forLabel(1, 1))
            .withIndexType(IndexType.VECTOR)
            .withName("test_cuvs_index")
            .withIndexProvider(new IndexProviderDescriptor("cuvs", "1.0"))
            .materialise(1L);
    }
}
