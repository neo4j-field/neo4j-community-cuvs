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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.neo4j.io.fs.FileSystemAbstraction;

// Real NVIDIA CUVS Java API imports
import com.nvidia.cuvs.CuVSResources;
import com.nvidia.cuvs.CuVSMatrix;
import com.nvidia.cuvs.TieredIndex;
import com.nvidia.cuvs.TieredIndexParams;
import com.nvidia.cuvs.TieredIndexQuery;
import com.nvidia.cuvs.CagraIndexParams;
import com.nvidia.cuvs.CagraIndexParams.CuvsDistanceType;
import com.nvidia.cuvs.CagraSearchParams;
import com.nvidia.cuvs.SearchResults;

import org.neo4j.internal.kernel.api.InternalIndexState;
import org.neo4j.internal.schema.IndexDescriptor;
import org.neo4j.kernel.api.index.ValueIndexReader;
import org.neo4j.kernel.api.impl.schema.vector.VectorSimilarityFunctions;
import org.neo4j.kernel.api.vector.VectorSimilarityFunction;
import org.neo4j.values.storable.FloatingPointArray;
import org.neo4j.values.storable.Value;

/**
 * Simplified CUVS (CUDA Vector Search) index implementation.
 * This class provides core functionality for GPU-accelerated vector search operations
 * and implements the CuvsDatabaseIndex interface for Neo4j integration.
 */
public class SimpleCuvsIndex extends AbstractCuvsIndex<CuvsIndexReader> {
    private final Path indexDirectory;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final VectorSimilarityFunction similarityFunction;
    
    // CUVS-specific state
    private boolean isInitialized = false;
    private boolean isGpuAvailable = false;
    private int vectorCount = 0;
    private int dimensions = 0;
    private final List<VectorData> vectors = new ArrayList<>();
    
    // Real NVIDIA CUVS Java API resources
    private CuVSResources cuvsResources;
    private TieredIndex cuvsIndex;
    
    // TieredIndex configuration
    private TieredIndexParams tieredParams;
    
    // Persistence support
    private CuvsIndexStorage storage;
    private CuvsIndexSerializer serializer;

    public SimpleCuvsIndex(
            IndexDescriptor descriptor,
            Path indexDirectory,
            VectorSimilarityFunction similarityFunction,
            FileSystemAbstraction fileSystem) {
        super(descriptor);
        this.indexDirectory = indexDirectory;
        this.similarityFunction = similarityFunction;
        
        // Initialize persistence components
        this.storage = new CuvsIndexStorage(indexDirectory, fileSystem, descriptor);
        this.serializer = new CuvsIndexSerializer(fileSystem);
    }

    // CuvsDatabaseIndex implementation
    
    @Override
    protected void doCreate() throws IOException {
        lock.writeLock().lock();
        try {
            if (isInitialized) {
                return;
            }
            
            // Create index directory if it doesn't exist
            if (!java.nio.file.Files.exists(indexDirectory)) {
                java.nio.file.Files.createDirectories(indexDirectory);
            }
            
            // Initialize the index
            initialize();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    protected void doOpen() throws IOException {
        lock.writeLock().lock();
        try {
            if (isInitialized) {
                return;
            }
            
            // Try to load existing index
            if (hasPersistedData()) {
                load();
            } else {
                // Initialize new index
                initialize();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean isValid() {
        lock.readLock().lock();
        try {
            return super.isValid() && isInitialized && (isGpuAvailable || isDevelopmentMode());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public ValueIndexReader getIndexReader(org.neo4j.kernel.impl.index.schema.IndexUsageTracking usageTracker) throws IOException {
        if (!isInitialized) {
            throw new IllegalStateException("Index not initialized");
        }
        
        // Get dimensions from the index descriptor or use default
        java.util.OptionalInt dimensions = java.util.OptionalInt.empty();
        
        // Extract dimensions from index config
        try {
            Map<String, org.neo4j.values.storable.Value> config = descriptor.getIndexConfig().asMap();
            if (config.containsKey("vector_dimensions")) {
                org.neo4j.values.storable.Value dimValue = config.get("vector_dimensions");
                if (dimValue instanceof org.neo4j.values.storable.IntValue) {
                    int dims = ((org.neo4j.values.storable.IntValue) dimValue).value();
                    dimensions = java.util.OptionalInt.of(dims);
                }
            }
        } catch (Exception e) {
            // If we can't extract dimensions, use empty OptionalInt
            dimensions = java.util.OptionalInt.empty();
        }
        
        return new CuvsIndexReader(descriptor, usageTracker, this, dimensions);
    }


    @Override
    public org.neo4j.graphdb.ResourceIterator<Path> snapshotFiles() throws IOException {
        return getSnapshotFiles();
    }

    /**
     * Initialize the CUVS index with GPU resources.
     * @throws GpuUnavailableException if GPU resources are not available
     */
    public void initialize() {
        lock.writeLock().lock();
        try {
            if (isInitialized) {
                return;
            }
            
            // Check development mode first
            if (isDevelopmentMode()) {
                System.out.println("Development mode: CUVS using mock implementation");
                initializeMockMode();
                return;
            }
            
            // Check if CUVS native library is available
            if (!CuvsNativeLibrary.isAvailable()) {
                throw GpuUnavailableException.createDetailed("CUVS native library not available");
            }
            
            // Check GPU availability
            if (!GpuDetector.isGpuAvailable()) {
                throw GpuUnavailableException.createDetailed("No compatible GPU detected");
            }
            
            // Initialize GPU-based CUVS index
            initializeGpuIndex();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private boolean isDevelopmentMode() {
        return Boolean.getBoolean("cuvs.development.mode");
    }
    
    private void initializeMockMode() {
        try {
            // In development mode, we skip CUVS initialization
            // The index will work in a limited mode without GPU acceleration
            cuvsResources = null;
            cuvsIndex = null;
            
            // Determine dimensions from descriptor or use default
            int vectorDimensions = 128; // Default dimensions for now
            this.dimensions = vectorDimensions;
            
            isInitialized = true;
            isGpuAvailable = false; // Mock mode doesn't use GPU
            System.out.println("Development mode: CUVS using mock implementation");
        } catch (Throwable e) {
            System.err.println("Development mode initialization failed: " + e.getMessage());
            throw new RuntimeException("Failed to initialize CUVS in development mode", e);
        }
    }

    private void initializeGpuIndex() {
        try {
            // Initialize real CUVS Java API resources using factory method
            cuvsResources = CuVSResources.create();
            
            // Determine dimensions from descriptor or use default
            int vectorDimensions = 128; // Default dimensions for now
            this.dimensions = vectorDimensions;
            
            // Create TieredIndex configuration parameters
            CagraIndexParams cagraParams = new CagraIndexParams.Builder()
                    .withGraphDegree(64)
                    .withIntermediateGraphDegree(128)
                    .withMetric(CuvsDistanceType.L2Expanded)
                    .build();
            
            tieredParams = new TieredIndexParams.Builder()
                    .minAnnRows(100000)  // Use brute force for < 100K vectors
                    .createAnnIndexOnExtend(false)  // Don't promote to ANN automatically
                    .withCagraParams(cagraParams)
                    .build();
            
            // Initialize TieredIndex (will be built when first vectors are added)
            cuvsIndex = null; // Will be created in addVectorsToIndex
            
            isInitialized = true;
            isGpuAvailable = true;
            System.out.println("Initialized CUVS TieredIndex with GPU acceleration (dimensions: " + vectorDimensions + ")");
        } catch (Throwable e) {
            System.err.println("GPU initialization failed: " + e.getMessage());
            e.printStackTrace();
            // Clean up any partially initialized resources
            cleanupGpuResources();
            throw new GpuUnavailableException("Failed to initialize GPU resources", e);
        }
    }
    
    private void cleanupGpuResources() {
        try {
            if (cuvsIndex != null) {
                // TieredIndex has destroyIndex() method for cleanup
                try {
                    cuvsIndex.destroyIndex();
                } catch (Throwable e) {
                    System.err.println("Error destroying TieredIndex: " + e.getMessage());
                }
                cuvsIndex = null;
            }
            if (cuvsResources != null) {
                cuvsResources.close();
                cuvsResources = null;
            }
        } catch (Exception e) {
            System.err.println("Error cleaning up GPU resources: " + e.getMessage());
        }
    }
    
    /**
     * Try to extend the CUVS index with a new vector using incremental update.
     * @param vectorData The vector to add
     * @return true if extension was successful, false if we need to fall back to rebuild
     */
    private boolean tryExtendIndex(VectorData vectorData) {
        try {
            if (cuvsIndex == null) {
                return false;
            }
            
            // TieredIndex supports incremental updates via extend()
            float[][] newVectors = {vectorData.getVector()};
            
            // Extend the existing TieredIndex with new vectors
            try {
                cuvsIndex.extend()
                    .withDataset(newVectors)
                    .execute();
            } catch (Throwable e) {
                throw new Exception("Failed to execute TieredIndex extend: " + e.getMessage(), e);
            }
            
            System.out.println("Successfully extended TieredIndex with 1 vector");
            return true;
        } catch (Exception e) {
            System.err.println("Failed to extend TieredIndex: " + e.getMessage());
            // If extend fails, return false to trigger rebuild
            return false;
        }
    }

    /**
     * Add vectors to the index during population (bulk loading).
     * This method is used by the populator for initial population.
     * @param vectorsToAdd List of vectors to add
     * @throws IOException if the operation fails
     */
    public void addVectors(List<VectorData> vectorsToAdd) throws IOException {
        lock.writeLock().lock();
        try {
            if (!isInitialized) {
                throw new IllegalStateException("Index not initialized");
            }

            // In development mode, we can still track vectors even if CUVS index is null
            if (cuvsIndex != null) {
                addVectorsToIndex(vectorsToAdd);
            } else {
                // Development mode - just track vectors in memory
                System.out.println("Development mode: Tracking vectors in memory only");
            }

            vectors.addAll(vectorsToAdd);
            vectorCount += vectorsToAdd.size();
            
            if (vectorsToAdd.size() > 0) {
                dimensions = vectorsToAdd.get(0).getVector().length;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Add a single vector to the index (for incremental updates).
     * This method is used by the accessor for online updates.
     * @param vectorData Vector to add
     * @throws IOException if the operation fails
     */
    public void addVector(VectorData vectorData) throws IOException {
        lock.writeLock().lock();
        try {
            if (!isInitialized) {
                throw new IllegalStateException("Index not initialized");
            }

            // Try to use CUVS extend() method for incremental updates
            if (tryExtendIndex(vectorData)) {
                // Successfully extended the index
                vectors.add(vectorData);
                vectorCount++;
            } else {
                // Fallback: rebuild the index with the new vector
                List<VectorData> allVectors = new ArrayList<>(vectors);
                allVectors.add(vectorData);
                
                addVectorsToIndex(allVectors);
                vectors.add(vectorData);
                vectorCount++;
            }
            
            if (dimensions == 0) {
                dimensions = vectorData.getVector().length;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void addVectorsToIndex(List<VectorData> vectorsToAdd) throws IOException {
        try {
            // In development mode, we don't have a real CUVS index, so we just track the vectors
            if (cuvsIndex == null) {
                System.out.println("Development mode: Skipping CUVS index operations, vectors tracked in memory");
                return;
            }
            
            // Convert vectors to matrix format
            int vectorCount = vectorsToAdd.size();
            int vectorDimensions = vectorsToAdd.get(0).getVector().length;
            
            // Create a matrix to hold all vectors
            float[][] matrixData = new float[vectorCount][vectorDimensions];
            for (int i = 0; i < vectorCount; i++) {
                matrixData[i] = vectorsToAdd.get(i).getVector();
            }
            
            if (cuvsIndex == null) {
                // First time - build initial TieredIndex
                try {
                    cuvsIndex = TieredIndex.newBuilder(cuvsResources)
                            .withDataset(matrixData)
                            .withIndexParams(tieredParams)
                            .build();
                    System.out.println("Built initial TieredIndex with " + vectorCount + " vectors (dimensions: " + vectorDimensions + ")");
                } catch (Throwable e) {
                    throw new IOException("Failed to build initial TieredIndex: " + e.getMessage(), e);
                }
            } else {
                // Subsequent times - extend existing TieredIndex
                try {
                    cuvsIndex.extend()
                            .withDataset(matrixData)
                            .execute();
                    System.out.println("Extended TieredIndex with " + vectorCount + " vectors (dimensions: " + vectorDimensions + ")");
                } catch (Throwable e) {
                    throw new IOException("Failed to extend TieredIndex: " + e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            throw new IOException("Failed to add vectors to CUVS index: " + e.getMessage(), e);
        }
    }




    /**
     * Get the underlying CUVS index for reader access.
     * @return the CUVS index, or null if not initialized
     */
    public TieredIndex getCuvsIndex() {
        lock.readLock().lock();
        try {
            return cuvsIndex;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get the CUVS resources for reader access.
     * @return the CUVS resources, or null if not initialized
     */
    public CuVSResources getCuvsResources() {
        lock.readLock().lock();
        try {
            return cuvsResources;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get the current state of the index.
     * @return The index state
     */
    public InternalIndexState getState() {
        lock.readLock().lock();
        try {
            if (!isInitialized) {
                return InternalIndexState.POPULATING;
            }
            return InternalIndexState.ONLINE;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get the current vector count.
     * @return Number of vectors in the index
     */
    public int getVectorCount() {
        lock.readLock().lock();
        try {
            return vectorCount;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Get the vector dimensions.
     * @return Number of dimensions per vector
     */
    public int getDimensions() {
        lock.readLock().lock();
        try {
            return dimensions;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Check if the index is initialized.
     * @return true if the index is initialized, false otherwise
     */
    public boolean isInitialized() {
        lock.readLock().lock();
        try {
            return isInitialized;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get index statistics.
     * @return Map of index statistics
     */
    public Map<String, Object> getStatistics() {
        lock.readLock().lock();
        try {
            return Map.of(
                "initialized", isInitialized,
                "gpuAvailable", isGpuAvailable,
                "vectorCount", vectorCount,
                "dimensions", dimensions,
                "similarityFunction", similarityFunction.name()
            );
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Save the current index state to disk.
     */
    public void save() throws IOException {
        lock.writeLock().lock();
        try {
            if (!isInitialized) {
                throw new IllegalStateException("Index not initialized");
            }
            
            // Create index data for serialization
            CuvsIndexSerializer.CuvsIndexData indexData = new CuvsIndexSerializer.CuvsIndexData(
                new CuvsIndexSerializer.IndexMetadata(
                    dimensions,
                    similarityFunction.name(),
                    100, // efConstruction - could be configurable
                    16,  // M - could be configurable
                    2,   // numThreads - could be configurable
                    "1.0",
                    System.currentTimeMillis(),
                    System.currentTimeMillis()
                ),
                new ArrayList<>(vectors.stream().map(v -> new CuvsIndexSerializer.VectorData(v.getNodeId(), v.getVector(), v.getProperties().entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> e.getValue().toString())))).toList())
            );
            
            // Serialize to disk
            serializer.serializeIndex(indexData, storage.getIndexDataFile(), storage.getVectorDataFile());
            
            // Update state
            storage.updateState("ONLINE", vectorCount, isGpuAvailable);
            
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Load the index state from disk.
     */
    public void load() throws IOException {
        lock.writeLock().lock();
        try {
            if (isInitialized) {
                throw new IllegalStateException("Index already initialized");
            }
            
            if (!storage.exists()) {
                throw new IOException("Index storage does not exist");
            }
            
            // Load index data
            CuvsIndexSerializer.CuvsIndexData indexData = serializer.deserializeIndex(
                storage.getIndexDataFile(), 
                storage.getVectorDataFile()
            );
            
            // Restore state
            this.dimensions = indexData.metadata.dimensions;
            this.vectorCount = indexData.vectorData.size();
            this.vectors.clear();
            this.vectors.addAll(indexData.vectorData.stream().map(v -> new VectorData(v.entityId, v.vector, v.metadata.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)))).toList());
            
            // Initialize CUVS resources
            initialize();
            
            // Rebuild index with loaded data
            if (!vectors.isEmpty()) {
                List<VectorData> vectorDataList = new ArrayList<>(vectors);
                addVectorsToIndex(vectorDataList);
            }
            
            // Update state
            storage.updateState("ONLINE", vectorCount, isGpuAvailable);
            
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Check if the index has persisted data.
     */
    public boolean hasPersistedData() {
        return storage.exists() && serializer.isValidIndex(
            storage.getIndexDataFile(), 
            storage.getVectorDataFile()
        );
    }
    
    /**
     * Get the current index state from storage.
     */
    public CuvsIndexStorage.IndexState getIndexState() throws IOException {
        return storage.readState();
    }
    
    /**
     * Get all files that should be included in snapshots.
     */
    public org.neo4j.graphdb.ResourceIterator<Path> getSnapshotFiles() throws IOException {
        List<Path> files = storage.getSnapshotFiles();
        return org.neo4j.internal.helpers.collection.Iterators.asResourceIterator(files.iterator());
    }
    
    /**
     * Close the index and release resources.
     * @throws IOException if the operation fails
     */
    @Override
    protected void doClose() throws IOException {
        lock.writeLock().lock();
        try {
            if (isInitialized) {
                // Clean up CUVS resources
                cleanupGpuResources();
                
                // Clear local state
                vectors.clear();
                vectorCount = 0;
                dimensions = 0;
                isInitialized = false;
                isGpuAvailable = false;
                
                System.out.println("CUVS index closed and resources cleaned up");
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Drop the index and delete all associated data.
     * @throws IOException if the operation fails
     */
    @Override
    protected void doDrop() throws IOException {
        lock.writeLock().lock();
        try {
            // Clean up GPU resources if index is initialized
            if (isInitialized) {
                cleanupGpuResources();
            }
            
            // Delete all persisted data
            storage.deleteAll();
            
            // Clear local state
            vectors.clear();
            vectorCount = 0;
            dimensions = 0;
            isInitialized = false;
            isGpuAvailable = false;
            
            System.out.println("CUVS index dropped and all data deleted");
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Data class representing a vector with its associated node ID.
     */
    public static class VectorData {
        private final long nodeId;
        private final float[] vector;
        private final Map<String, Object> properties;

        public VectorData(long nodeId, float[] vector, Map<String, Object> properties) {
            this.nodeId = nodeId;
            this.vector = vector;
            this.properties = properties;
        }

        public long getNodeId() {
            return nodeId;
        }

        public float[] getVector() {
            return vector;
        }

        public Map<String, Object> getProperties() {
            return properties;
        }
    }

    
    /**
     * Convert Neo4j VectorSimilarityFunction to real CUVS DistanceType.
     */
    private CagraIndexParams.CuvsDistanceType toCuvsDistanceType(VectorSimilarityFunction similarityFunction) {
        if (similarityFunction == VectorSimilarityFunctions.EUCLIDEAN) {
            return CagraIndexParams.CuvsDistanceType.L2Expanded;
        } else if (similarityFunction.name().equals("COSINE")) {
            return CagraIndexParams.CuvsDistanceType.CosineExpanded;
        } else if (similarityFunction.name().equals("DOT_PRODUCT")) {
            return CagraIndexParams.CuvsDistanceType.InnerProduct;
        } else {
            System.out.println("Unknown similarity function: " + similarityFunction + ", defaulting to L2Expanded");
            return CagraIndexParams.CuvsDistanceType.L2Expanded;
        }
    }

}
