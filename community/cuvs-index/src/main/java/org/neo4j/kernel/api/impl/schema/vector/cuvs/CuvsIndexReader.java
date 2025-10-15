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
import com.nvidia.cuvs.CagraQuery;
import com.nvidia.cuvs.CagraSearchParams;
import com.nvidia.cuvs.SearchResults;

/**
 * CUVS-based vector index reader that implements the ValueIndexReader interface.
 * This provides the standard Neo4j index reader interface for CUVS vector search operations.
 */
public class CuvsIndexReader implements ValueIndexReader {
    private final IndexDescriptor descriptor;
    private final IndexUsageTracking usageTracker;
    private final CagraCuvsIndexImpl cuvsIndex;
    private final OptionalInt dimensions;

    public CuvsIndexReader(
            IndexDescriptor descriptor,
            IndexUsageTracking usageTracker,
            CagraCuvsIndexImpl cuvsIndex,
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
        
        System.out.println("CuvsIndexReader.query() called with " + predicates.length + " predicates");
        
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
        
        System.out.println("CuvsIndexReader: Query vector dimensions: " + queryVector.length);
        System.out.println("CuvsIndexReader: Expected dimensions: " + (dimensions.isPresent() ? dimensions.getAsInt() : "not set"));
        
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
        
        System.out.println("CuvsIndexReader: Search params - requestedK: " + requestedK + ", skip: " + skip + ", limit: " + limit + ", effectiveK: " + effectiveK);

        // Track usage
        if (context.monitor() != null) {
            context.monitor().queried(descriptor);
        }
        usageTracker.queried();

        // Create progressor for the query
        System.out.println("CuvsIndexReader: Creating progressor - cuvsIndex: " + cuvsIndex.getCuvsIndex() + ", cuvsResources: " + cuvsIndex.getCuvsResources());
        final var progressor = new CuvsIndexProgressor(
            cuvsIndex.getCuvsIndex(),
            cuvsIndex.getCuvsResources(),
            queryVector, 
            effectiveK, 
            (int) skip,
            client);

        // Initialize the client with our progressor
        System.out.println("CuvsIndexReader: Initializing client with progressor");
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
        // The underlying CagraCuvsIndexImpl manages its own resources
    }

    /**
     * Index progressor that handles CUVS search results and feeds them to the client.
     */
    private static class CuvsIndexProgressor implements IndexProgressor {
        private final com.nvidia.cuvs.CagraIndex cuvsIndex;
        private final com.nvidia.cuvs.CuVSResources cuvsResources;
        private final float[] queryVector;
        private final int effectiveK;
        private final int skip;
        private final EntityValueClient client;
        
        private List<SearchResult> searchResults;
        private int currentIndex = 0;
        private int skippedCount = 0;

        public CuvsIndexProgressor(
                com.nvidia.cuvs.CagraIndex cuvsIndex,
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
                    System.out.println("CuvsIndexProgressor: Performing CUVS search...");
                    searchResults = performCuvsSearch();
                    System.out.println("CuvsIndexProgressor: Search returned " + searchResults.size() + " results");
                }

                // Find the next result to return
                while (currentIndex < searchResults.size()) {
                    SearchResult result = searchResults.get(currentIndex);
                    currentIndex++;
                    
                    // Check if we should skip this result
                    if (skippedCount < skip) {
                        skippedCount++;
                        System.out.println("CuvsIndexProgressor: Skipping result " + skippedCount + "/" + skip);
                        continue; // Skip this result
                    }
                    
                    // Check if we've reached the limit
                    if ((skippedCount - skip) >= effectiveK) {
                        System.out.println("CuvsIndexProgressor: Reached limit of " + effectiveK + " results");
                        return false; // No more results
                    }
                    
                    // Return this result
                    System.out.println("CuvsIndexProgressor: Returning result - nodeId: " + result.getNodeId() + ", score: " + result.getScore());
                    boolean accepted = client.acceptEntity(result.getNodeId(), (float) result.getScore(), (Value[]) null);
                    System.out.println("CuvsIndexProgressor: Client accepted result: " + accepted);
                    skippedCount++;
                    return accepted;
                }
                
                System.out.println("CuvsIndexProgressor: No more results to return");
                return false; // No more results
            } catch (IOException e) {
                System.out.println("CuvsIndexProgressor: Error during search: " + e.getMessage());
                e.printStackTrace();
                throw new RuntimeException("Failed to search CUVS index", e);
            }
        }

        private List<SearchResult> performCuvsSearch() throws IOException {
            System.out.println("CuvsIndexProgressor.performCuvsSearch() called");
            System.out.println("CuvsIndexProgressor: cuvsIndex is " + (cuvsIndex == null ? "null" : "not null"));
            System.out.println("CuvsIndexProgressor: cuvsResources is " + (cuvsResources == null ? "null" : "not null"));
            
            if (cuvsIndex == null) {
                // Development mode - return empty results for now
                System.out.println("Development mode: Performing mock CUVS search");
                return performMockSearch(queryVector, effectiveK, skip);
            }
            
            System.out.println("GPU mode: Performing actual CUVS search");
            
            // Create search parameters for CAGRA
            CagraSearchParams searchParams = new CagraSearchParams.Builder(cuvsResources)
                .withMaxIterations(20)
                .build();
            
            // Create CagraQuery
            CagraQuery query = new CagraQuery.Builder()
                .withQueryVectors(new float[][]{queryVector})
                .withTopK(effectiveK + skip)
                .withSearchParams(searchParams)
                .build();
            
            System.out.println("CuvsIndexProgressor: Created CagraQuery with topK: " + (effectiveK + skip));
            
            // Perform search
            SearchResults searchResults;
            try {
                System.out.println("CuvsIndexProgressor: Calling cuvsIndex.search()...");
                searchResults = cuvsIndex.search(query);
                System.out.println("CuvsIndexProgressor: Search completed successfully");
            } catch (Throwable e) {
                System.out.println("CuvsIndexProgressor: Search failed: " + e.getMessage());
                e.printStackTrace();
                throw new IOException("Failed to search CAGRA index: " + e.getMessage(), e);
            }
            
            // Convert results to our format
            // CUVS returns Map<Integer, Float> where key is index position, not node ID
            // For now, we'll assume the key is the node ID (this might need fixing later)
            List<SearchResult> results = new ArrayList<>();
            List<Map<Integer, Float>> resultMaps = searchResults.getResults();
            System.out.println("CuvsIndexProgressor: CUVS returned " + resultMaps.size() + " result maps");
            
            if (!resultMaps.isEmpty()) {
                Map<Integer, Float> firstQueryResults = resultMaps.get(0);
                System.out.println("CuvsIndexProgressor: First query has " + firstQueryResults.size() + " results");
                
                for (Map.Entry<Integer, Float> entry : firstQueryResults.entrySet()) {
                    long nodeId = entry.getKey(); // This might be wrong - could be index position
                    float score = entry.getValue();
                    System.out.println("CuvsIndexProgressor: Result - nodeId: " + nodeId + ", score: " + score);
                    results.add(new SearchResult(nodeId, score, Map.of()));
                }
            }
            
            System.out.println("CuvsIndexProgressor: Returning " + results.size() + " results");
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
    
    /**
     * Perform mock search in development mode.
     * Returns empty results for now.
     */
    private static List<SearchResult> performMockSearch(float[] queryVector, int effectiveK, int skip) {
        System.out.println("Development mode: Mock search - returning empty results for now");
        return new ArrayList<>();
    }
}
