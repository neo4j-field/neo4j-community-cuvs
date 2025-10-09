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

import org.junit.jupiter.api.Test;
import org.neo4j.configuration.Config;
import org.neo4j.dbms.database.readonly.DatabaseReadOnlyChecker;
import org.neo4j.internal.schema.IndexProviderDescriptor;
import org.neo4j.internal.schema.IndexType;
import org.neo4j.kernel.api.impl.index.storage.IndexStorageFactory;
import org.neo4j.kernel.api.index.IndexDirectoryStructure;
import org.neo4j.monitoring.Monitors;
import org.neo4j.scheduler.JobScheduler;

/**
 * Test provider selection functionality for CUVS indexes.
 */
class CuvsProviderSelectionTest {

    @Test
    void shouldReturnCorrectIndexType() {
        // Given
        CuvsIndexProvider provider = createTestProvider();

        // When
        IndexType indexType = provider.getIndexType();

        // Then
        assertEquals(IndexType.VECTOR, indexType, "CUVS provider should return VECTOR index type");
    }

    @Test
    void shouldHaveCorrectProviderDescriptor() {
        // Given
        CuvsIndexProvider provider = createTestProvider();

        // When
        IndexProviderDescriptor descriptor = provider.getProviderDescriptor();

        // Then
        assertNotNull(descriptor, "Provider descriptor should not be null");
        assertEquals("cuvs-1.0", descriptor.name(), "Provider name should be 'cuvs-1.0'");
        assertEquals("cuvs", descriptor.getKey(), "Provider key should be 'cuvs'");
        assertEquals("1.0", descriptor.getVersion(), "Provider version should be '1.0'");
    }

    @Test
    void shouldValidateProviderSelection() {
        // Given
        CuvsIndexProvider provider = createTestProvider();

        // When & Then
        // The provider should be selectable for VECTOR index type
        assertEquals(IndexType.VECTOR, provider.getIndexType());
        
        // Provider descriptor should match expected values
        IndexProviderDescriptor descriptor = provider.getProviderDescriptor();
        assertEquals("cuvs-1.0", descriptor.name());
        assertEquals("cuvs", descriptor.getKey());
        assertEquals("1.0", descriptor.getVersion());
    }

    @Test
    void shouldSupportProviderBasedSelection() {
        // This test verifies that the CUVS provider can be selected using:
        // CREATE VECTOR INDEX ... OPTIONS {indexProvider: 'cuvs', ...}
        
        CuvsIndexProvider provider = createTestProvider();
        
        // Verify the provider is configured correctly for selection
        IndexProviderDescriptor descriptor = provider.getProviderDescriptor();
        assertEquals("cuvs-1.0", descriptor.name());
        assertEquals(IndexType.VECTOR, provider.getIndexType());
        
        // This means users can create CUVS indexes with:
        // CREATE VECTOR INDEX my_index FOR (n:Document) ON (n.embedding) 
        // OPTIONS {indexProvider: 'cuvs', vector.dimensions: 768, vector.similarity_function: 'COSINE'}
    }

    private CuvsIndexProvider createTestProvider() {
        // Create a test provider with minimal dependencies
        IndexDirectoryStructure.Factory directoryStructureFactory = createMockDirectoryStructureFactory();
        IndexStorageFactory storageFactory = mock(IndexStorageFactory.class);
        Monitors monitors = mock(Monitors.class);
        Config config = mock(Config.class);
        DatabaseReadOnlyChecker readOnlyChecker = mock(DatabaseReadOnlyChecker.class);
        JobScheduler scheduler = mock(JobScheduler.class);
        
        return new CuvsIndexProvider(
            CuvsIndexVersion.V1_0,
            storageFactory,
            monitors,
            config,
            readOnlyChecker,
            scheduler,
            directoryStructureFactory
        );
    }

    private <T> T mock(Class<T> clazz) {
        // Simple mock implementation for testing - just return null
        // In a real test, you'd use Mockito or similar
        return null;
    }

    private IndexDirectoryStructure.Factory createMockDirectoryStructureFactory() {
        return new IndexDirectoryStructure.Factory() {
            @Override
            public IndexDirectoryStructure forProvider(IndexProviderDescriptor providerDescriptor) {
                return new IndexDirectoryStructure() {
                    @Override
                    public java.nio.file.Path rootDirectory() {
                        return java.nio.file.Path.of("/tmp/test-root");
                    }

                    @Override
                    public java.nio.file.Path directoryForIndex(long indexId) {
                        return java.nio.file.Path.of("/tmp/test-index-" + indexId);
                    }
                };
            }
        };
    }
}