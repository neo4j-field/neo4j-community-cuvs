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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.neo4j.internal.kernel.api.IndexQueryConstraints;
import org.neo4j.internal.kernel.api.PropertyIndexQuery;
import org.neo4j.internal.schema.IndexQuery.IndexQueryType;
import org.neo4j.internal.kernel.api.PropertyIndexQuery.NearestNeighborsPredicate;
import org.neo4j.internal.kernel.api.QueryContext;
import org.neo4j.internal.kernel.api.exceptions.schema.IndexNotApplicableKernelException;
import org.neo4j.internal.schema.IndexDescriptor;
import org.neo4j.io.pagecache.context.CursorContext;
import org.neo4j.kernel.api.index.IndexProgressor;
import org.neo4j.kernel.api.index.IndexSampler;
import org.neo4j.kernel.api.index.ValueIndexReader;
import org.neo4j.kernel.impl.index.schema.IndexUsageTracking;
import org.neo4j.kernel.impl.index.schema.PartitionedValueSeek;
import org.neo4j.values.storable.FloatingPointArray;
import org.neo4j.values.storable.Values;
import org.neo4j.values.storable.Value;

// CUVS imports
import com.nvidia.cuvs.TieredIndexQuery;
import com.nvidia.cuvs.CagraSearchParams;
import com.nvidia.cuvs.SearchResults;

/**
 * CUVS-based vector index reader that implements the ValueIndexReader interface.
 * This provides the standard Neo4j index reader interface for CUVS vector search operations.
 */
public class CuvsIndexReader implements ValueIndexReader {
    private final IndexDescriptor descriptor;
    private final IndexUsageTracking usageTracker;
    private final SimpleCuvsIndex cuvsIndex;
    private final OptionalInt dimensions;

    public CuvsIndexReader(
            IndexDescriptor descriptor,
            IndexUsageTracking usageTracker,
            SimpleCuvsIndex cuvsIndex,
            OptionalInt dimensions) {
        this.descriptor = descriptor;
        this.usageTracker = usageTracker;
        this.cuvsIndex = cuvsIndex;
        this.dimensions = dimensions;
    }

    @Override
    public long countIndexedEntities(
            long entityId, CursorContext cursorContext, int[] propertyKeyIds, Value... propertyValues) {
        // For CUVS, we need to check if the entity exists in our index
        // This is a simplified implementation - in practice, we'd need to maintain
        // a mapping of entity IDs to their positions in the CUVS index
        try {
            // For now, we'll do a simple check by looking at our internal vector list
            // In a real implementation, we'd need a more efficient way to check existence
            return cuvsIndex.getVectorCount() > 0 ? 1L : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    @Override
    public IndexSampler createSampler() {
        return IndexSampler.EMPTY;
    }

    @Override
    public void query(
            IndexProgressor.EntityValueClient client,
            QueryContext context,
            IndexQueryConstraints constraints,
            PropertyIndexQuery... predicates)
            throws IndexNotApplicableKernelException {
        
        if (predicates.length != 1) {
            throw new IndexNotApplicableKernelException(
                "CUVS index does not support composite queries. Got " + predicates.length + " predicates.");
        }

        final var predicate = predicates[0];
        
        // Validate query type
        if (predicate.type() != IndexQueryType.NEAREST_NEIGHBORS) {
            throw new IndexNotApplicableKernelException(
                "CUVS index only supports NEAREST_NEIGHBORS queries. Got: " + predicate.type());
        }

        final var nearestNeighborsPredicate = (NearestNeighborsPredicate) predicate;
        final var queryVector = nearestNeighborsPredicate.query();
        
        // Validate dimensions
        if (dimensions.isPresent() && queryVector.length != dimensions.getAsInt()) {
            throw new IndexNotApplicableKernelException(
                String.format("Query vector has %d dimensions, but index expects %d dimensions.",
                    queryVector.length, dimensions.getAsInt()));
        }

        // Calculate effective k (limit + skip)
        final var requestedK = nearestNeighborsPredicate.numberOfNeighbors();
        final var skip = constraints.skip().orElse(0);
        final var limit = constraints.limit().orElse(Integer.MAX_VALUE);
        final var effectiveK = (int) Math.min(requestedK + skip, limit);

        // Track usage
        if (context.monitor() != null) {
            context.monitor().queried(descriptor);
        }
        usageTracker.queried();

        // Create progressor for the query
        final var progressor = new CuvsIndexProgressor(
            cuvsIndex.getCuvsIndex(),
            cuvsIndex.getCuvsResources(),
            queryVector, 
            effectiveK, 
            (int) skip,
            client);

        // Initialize the client with our progressor
        client.initializeQuery(descriptor, progressor, false, false, constraints, predicate);
    }

    @Override
    public PartitionedValueSeek valueSeek(
            int desiredNumberOfPartitions, QueryContext queryContext, PropertyIndexQuery... query) {
        throw new UnsupportedOperationException(
            "CUVS indexes don't support partitioned seeks. " +
            "They are optimized for vector similarity search using GPU acceleration. " +
            "Use query() method for vector similarity search instead.");
    }

    @Override
    public void close() {
        // CUVS index reader doesn't need explicit cleanup
        // The underlying SimpleCuvsIndex manages its own resources
    }

    /**
     * Index progressor that handles CUVS search results and feeds them to the client.
     */
    private static class CuvsIndexProgressor implements IndexProgressor {
        private final com.nvidia.cuvs.TieredIndex cuvsIndex;
        private final com.nvidia.cuvs.CuVSResources cuvsResources;
        private final float[] queryVector;
        private final int effectiveK;
        private final int skip;
        private final EntityValueClient client;
        
        private List<SearchResult> searchResults;
        private int currentIndex = 0;
        private int skippedCount = 0;

        public CuvsIndexProgressor(
                com.nvidia.cuvs.TieredIndex cuvsIndex,
                com.nvidia.cuvs.CuVSResources cuvsResources,
                float[] queryVector,
                int effectiveK,
                int skip,
                EntityValueClient client) {
            this.cuvsIndex = cuvsIndex;
            this.cuvsResources = cuvsResources;
            this.queryVector = queryVector;
            this.effectiveK = effectiveK;
            this.skip = skip;
            this.client = client;
        }

        @Override
        public boolean next() {
            try {
                // Lazy initialization of search results
                if (searchResults == null) {
                    searchResults = performCuvsSearch();
                }

                // Skip the requested number of results
                while (skippedCount < skip && currentIndex < searchResults.size()) {
                    currentIndex++;
                    skippedCount++;
                }

                // Return results up to effectiveK
                if (currentIndex < searchResults.size() && (currentIndex - skippedCount) < effectiveK) {
                    SearchResult result = searchResults.get(currentIndex);
                    currentIndex++;
                    
                    // Convert CUVS result to Neo4j format
                    Value queryValue = Values.floatArray(queryVector); // Query vector as value
                    return client.acceptEntity(result.getNodeId(), (float) result.getScore(), queryValue);
                }

                return false; // No more results
            } catch (IOException e) {
                throw new RuntimeException("Failed to search CUVS index", e);
            }
        }

        private List<SearchResult> performCuvsSearch() throws IOException {
            if (cuvsIndex == null) {
                throw new IllegalStateException("CUVS index not initialized");
            }
            
            // Create search parameters for TieredIndex
            CagraSearchParams searchParams = new CagraSearchParams.Builder(cuvsResources)
                .withMaxIterations(20)
                .build();
            
            // Create TieredIndexQuery
            TieredIndexQuery query = new TieredIndexQuery.Builder()
                .withQueryVectors(new float[][]{queryVector})
                .withTopK(effectiveK + skip)
                .withSearchParams(searchParams)
                .build();
            
            // Perform search
            SearchResults searchResults;
            try {
                searchResults = cuvsIndex.search(query);
            } catch (Throwable e) {
                throw new IOException("Failed to search TieredIndex: " + e.getMessage(), e);
            }
            
            // Convert results to our format
            List<SearchResult> results = new ArrayList<>();
            List<Map<Integer, Float>> resultMaps = searchResults.getResults();
            if (!resultMaps.isEmpty()) {
                Map<Integer, Float> firstQueryResults = resultMaps.get(0);
                for (Map.Entry<Integer, Float> entry : firstQueryResults.entrySet()) {
                    long nodeId = entry.getKey();
                    float score = entry.getValue();
                    results.add(new SearchResult(nodeId, score, Map.of()));
                }
            }
            
            return results;
        }

        @Override
        public void close() {
            // No cleanup needed
        }
    }

    /**
     * Data class representing a search result.
     */
    private static class SearchResult {
        private final long nodeId;
        private final float score;
        private final Map<String, Object> properties;

        public SearchResult(long nodeId, float score, Map<String, Object> properties) {
            this.nodeId = nodeId;
            this.score = score;
            this.properties = properties;
        }

        public long getNodeId() {
            return nodeId;
        }

        public float getScore() {
            return score;
        }

        public Map<String, Object> getProperties() {
            return properties;
        }
    }
}
