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
package org.neo4j.kernel.impl.transaction.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.neo4j.collection.Dependencies;
import org.neo4j.internal.schema.IndexProviderDescriptor;
import org.neo4j.internal.schema.IndexType;
import org.neo4j.kernel.api.impl.fulltext.FulltextIndexProvider;
import org.neo4j.kernel.api.impl.schema.TextIndexProvider;
import org.neo4j.kernel.api.impl.schema.trigram.TrigramIndexProvider;
import org.neo4j.kernel.api.impl.schema.vector.VectorIndexProvider;
import org.neo4j.kernel.api.impl.schema.vector.cuvs.CuvsIndexProvider;
import org.neo4j.kernel.api.index.IndexProvider;
import org.neo4j.kernel.impl.api.index.IndexProviderNotFoundException;
import org.neo4j.kernel.impl.index.schema.PointIndexProvider;
import org.neo4j.kernel.impl.index.schema.RangeIndexProvider;
import org.neo4j.kernel.impl.index.schema.TokenIndexProvider;

class StaticIndexProviderMapCuvsTest {

    @Test
    void shouldRegisterCuvsProvider() throws Exception {
        // Given
        var tokenIndexProvider = mockProvider(TokenIndexProvider.class, IndexType.LOOKUP);
        var rangeIndexProvider = mockProvider(RangeIndexProvider.class, IndexType.RANGE);
        var pointIndexProvider = mockProvider(PointIndexProvider.class, IndexType.POINT);
        var textIndexProvider = mockProvider(TextIndexProvider.class, IndexType.TEXT);
        var trigramIndexProvider = mockProvider(TrigramIndexProvider.class, IndexType.TEXT);
        var fulltextIndexProvider = mockProvider(FulltextIndexProvider.class, IndexType.FULLTEXT);
        var vectorV1IndexProvider = mockProvider(VectorIndexProvider.class, IndexType.VECTOR);
        var vectorV2IndexProvider = mockProvider(VectorIndexProvider.class, IndexType.VECTOR);
        var cuvsV1IndexProvider = mockProvider(CuvsIndexProvider.class, IndexType.VECTOR);
        
        var map = new StaticIndexProviderMap(
                tokenIndexProvider,
                rangeIndexProvider,
                pointIndexProvider,
                textIndexProvider,
                trigramIndexProvider,
                fulltextIndexProvider,
                vectorV1IndexProvider,
                vectorV2IndexProvider,
                cuvsV1IndexProvider,
                new Dependencies());
        map.init();

        // When/Then - CUVS provider should be registered
        assertThat(map.lookup("cuvs")).isEqualTo(cuvsV1IndexProvider);
        assertThat(map.lookup(new IndexProviderDescriptor("cuvs", "1.0"))).isEqualTo(cuvsV1IndexProvider);
        
        // Should be available for VECTOR index type
        var vectorProviders = map.lookup(IndexType.VECTOR);
        assertThat(vectorProviders).contains(cuvsV1IndexProvider);
    }

    @Test
    void shouldThrowExceptionForUnknownCuvsProvider() throws Exception {
        // Given
        var tokenIndexProvider = mockProvider(TokenIndexProvider.class, IndexType.LOOKUP);
        var rangeIndexProvider = mockProvider(RangeIndexProvider.class, IndexType.RANGE);
        var pointIndexProvider = mockProvider(PointIndexProvider.class, IndexType.POINT);
        var textIndexProvider = mockProvider(TextIndexProvider.class, IndexType.TEXT);
        var trigramIndexProvider = mockProvider(TrigramIndexProvider.class, IndexType.TEXT);
        var fulltextIndexProvider = mockProvider(FulltextIndexProvider.class, IndexType.FULLTEXT);
        var vectorV1IndexProvider = mockProvider(VectorIndexProvider.class, IndexType.VECTOR);
        var vectorV2IndexProvider = mockProvider(VectorIndexProvider.class, IndexType.VECTOR);
        var cuvsV1IndexProvider = mockProvider(CuvsIndexProvider.class, IndexType.VECTOR);
        
        var map = new StaticIndexProviderMap(
                tokenIndexProvider,
                rangeIndexProvider,
                pointIndexProvider,
                textIndexProvider,
                trigramIndexProvider,
                fulltextIndexProvider,
                vectorV1IndexProvider,
                vectorV2IndexProvider,
                cuvsV1IndexProvider,
                new Dependencies());
        map.init();

        // When/Then - Should throw exception for unknown provider
        try {
            map.lookup("unknown-provider");
            assertThat(false).as("Should have thrown exception").isTrue();
        } catch (IndexProviderNotFoundException e) {
            assertThat(e.getMessage()).contains("unknown-provider");
        }
    }

    private static <T extends IndexProvider> T mockProvider(Class<T> providerClass, IndexType indexType) {
        var provider = mock(providerClass);
        var descriptor = mock(IndexProviderDescriptor.class);
        when(descriptor.name()).thenReturn(providerClass.getSimpleName().toLowerCase());
        when(provider.getProviderDescriptor()).thenReturn(descriptor);
        when(provider.getIndexType()).thenReturn(indexType);
        return provider;
    }
}
