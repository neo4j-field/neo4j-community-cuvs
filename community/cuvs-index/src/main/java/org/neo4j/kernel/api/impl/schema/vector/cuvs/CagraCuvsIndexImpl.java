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
import com.nvidia.cuvs.CagraIndex;
import com.nvidia.cuvs.CagraIndexParams;
import com.nvidia.cuvs.CagraIndexParams.CuvsDistanceType;
import com.nvidia.cuvs.CagraQuery;
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
public class CagraCuvsIndexImpl extends AbstractCuvsIndex<CuvsIndexReader> implements CagraCuvsIndex {
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
    private CagraIndex cuvsIndex;
    
    // Instance tracking for debugging
    private static int instanceCounter = 0;
    private final int instanceId;
    private final String instanceName;
    
    // Debug method to track cuvsIndex changes
    private void debugCuvsIndexChange(String context, CagraIndex oldValue, CagraIndex newValue) {
        System.out.println("🔍 CUVS INDEX CHANGE [" + instanceName + "][" + context + "]: " + oldValue + " → " + newValue);
        if (oldValue != null && newValue == null) {
            System.out.println("⚠️ WARNING: cuvsIndex set to NULL in " + instanceName + " during " + context);
            Thread.dumpStack();
        }
    }
    
    // Override cuvsIndex setter to add debugging
    private void setCuvsIndex(CagraIndex newValue) {
        CagraIndex oldValue = this.cuvsIndex;
        this.cuvsIndex = newValue;
        debugCuvsIndexChange("setCuvsIndex", oldValue, newValue);
    }
    
    // CAGRA configuration
    private CagraIndexParams cagraParams;
    
    // Persistence support
    private CuvsIndexStorage storage;
    private CuvsIndexSerializer serializer;

    public CagraCuvsIndexImpl(
            IndexDescriptor descriptor,
            Path indexDirectory,
            VectorSimilarityFunction similarityFunction,
            FileSystemAbstraction fileSystem) {
        super(descriptor);
        this.indexDirectory = indexDirectory;
        this.similarityFunction = similarityFunction;
        
        // Initialize instance tracking
        this.instanceId = ++instanceCounter;
        this.instanceName = "CagraCuvsIndexImpl-" + instanceId + "-" + descriptor.getName();
        
        System.out.println("🏗️ " + instanceName + " constructor called");
        System.out.println("   - Index directory: " + indexDirectory);
        System.out.println("   - Descriptor: " + descriptor);
        
        // Initialize persistence components
        this.storage = new CuvsIndexStorage(indexDirectory, fileSystem, descriptor);
        this.serializer = new CuvsIndexSerializer(fileSystem);
        
        // Create storage directory structure
        try {
            this.storage.create();
            System.out.println("Storage directory created successfully: " + indexDirectory);
        } catch (IOException e) {
            System.err.println("Failed to create storage directory: " + e.getMessage());
            e.printStackTrace();
        }
        
        // Initialize the index immediately in constructor
        System.out.println("Initializing index in constructor...");
        try {
            initialize();
            System.out.println("Index initialized successfully in constructor, isInitialized: " + isInitialized);
        } catch (Exception e) {
            System.out.println("Failed to initialize index in constructor: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // CuvsDatabaseIndex implementation
    
    @Override
    protected void doCreate() throws IOException {
        System.out.println("CagraCuvsIndexImpl.doCreate() called");
        lock.writeLock().lock();
        try {
            if (isInitialized) {
                System.out.println("Already initialized in doCreate, returning");
                return;
            }
            
            System.out.println("Creating index directory...");
            // Create index directory if it doesn't exist
            if (!java.nio.file.Files.exists(indexDirectory)) {
                java.nio.file.Files.createDirectories(indexDirectory);
            }
            
            System.out.println("Calling initialize() from doCreate...");
            // Initialize the index
            initialize();
            System.out.println("doCreate() completed successfully");
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    protected void doOpen() throws IOException {
        System.out.println("CagraCuvsIndexImpl.doOpen() called");
        lock.writeLock().lock();
        try {
            if (isInitialized) {
                System.out.println("Already initialized in doOpen, returning");
                return;
            }
            
            System.out.println("Checking if hasPersistedData(): " + hasPersistedData());
            // Try to load existing index
            if (hasPersistedData()) {
                System.out.println("Loading existing index...");
                load();
            } else {
                System.out.println("Initializing new index from doOpen...");
                // Initialize new index
                initialize();
            }
            System.out.println("doOpen() completed successfully");
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
            if (config.containsKey("vector.dimensions")) {
                org.neo4j.values.storable.Value dimValue = config.get("vector.dimensions");
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
     * Initialize the CUVS index with the given descriptor.
     * @param descriptor The index descriptor containing configuration
     * @throws IOException if initialization fails
     */
    @Override
    public void initialize(IndexDescriptor descriptor) throws IOException {
        // Descriptor is already set in parent constructor
        initialize();
    }
    
    /**
     * Initialize the CUVS index with GPU resources.
     * @throws GpuUnavailableException if GPU resources are not available
     */
    public void initialize() {
        System.out.println("CagraCuvsIndexImpl.initialize() called");
        lock.writeLock().lock();
        try {
            if (isInitialized) {
                System.out.println("Already initialized, returning");
                return;
            }
            
            System.out.println("isDevelopmentMode(): " + isDevelopmentMode());
            // Check development mode first
            if (isDevelopmentMode()) {
                System.out.println("Development mode: CUVS using mock implementation");
                initializeMockMode();
                System.out.println("Development mode initialization completed, isInitialized: " + isInitialized);
                return;
            }
            
            System.out.println("Checking CUVS native library availability...");
            // Check if CUVS native library is available
            if (!CuvsNativeLibrary.isAvailable()) {
                System.out.println("CUVS native library not available, falling back to development mode");
                initializeMockMode();
                System.out.println("Fallback to development mode completed, isInitialized: " + isInitialized);
                return;
            }
            
            System.out.println("Checking GPU availability...");
            // Check GPU availability
            if (!GpuDetector.isGpuAvailable()) {
                System.out.println("No GPU detected, falling back to development mode");
                initializeMockMode();
                System.out.println("Fallback to development mode completed, isInitialized: " + isInitialized);
                return;
            }
            
            System.out.println("GPU detected, initializing GPU index");
            // Initialize GPU-based CUVS index
            initializeGpuIndex();
            System.out.println("GPU initialization completed, isInitialized: " + isInitialized);
        } catch (Exception e) {
            System.out.println("Initialization failed with exception: " + e.getMessage());
            System.out.println("Falling back to development mode");
            try {
                initializeMockMode();
                System.out.println("Fallback to development mode completed, isInitialized: " + isInitialized);
            } catch (Exception fallbackException) {
                System.out.println("Fallback to development mode also failed: " + fallbackException.getMessage());
                throw new RuntimeException("Failed to initialize CUVS index", fallbackException);
            }
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
            setCuvsIndex(null);
            
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

    /**
     * Convert VectorData list to CuVSMatrix for TieredIndex.
     */
    private CuVSMatrix convertToMatrix(List<VectorData> vectorDataList) {
        if (vectorDataList.isEmpty()) {
            throw new IllegalArgumentException("Cannot create matrix from empty vector list");
        }
        
        int vectorCount = vectorDataList.size();
        int dimensions = vectorDataList.get(0).vector.length;
        
        System.out.println("Converting " + vectorCount + " vectors to matrix, each with " + dimensions + " dimensions");
        
        // Create matrix array
        float[][] matrix = new float[vectorCount][dimensions];
        for (int i = 0; i < vectorCount; i++) {
            VectorData vectorData = vectorDataList.get(i);
            if (vectorData.vector.length != dimensions) {
                System.err.println("❌ Dimension mismatch at vector " + i + ": expected " + dimensions + ", got " + vectorData.vector.length);
                throw new IllegalArgumentException("Inconsistent vector dimensions: expected " + dimensions + ", got " + vectorData.vector.length);
            }
            System.arraycopy(vectorData.vector, 0, matrix[i], 0, dimensions);
        }
        
        System.out.println("✅ Matrix conversion completed: " + vectorCount + "x" + dimensions);
        return CuVSMatrix.ofArray(matrix);
    }

    /**
     * Extract vector dimensions from the index configuration.
     * @return the vector dimensions, or default to 384 if not specified
     */
    private int getVectorDimensionsFromConfig() {
        try {
            Map<String, org.neo4j.values.storable.Value> config = descriptor.getIndexConfig().asMap();
            if (config.containsKey("vector.dimensions")) {
                org.neo4j.values.storable.Value dimValue = config.get("vector.dimensions");
                if (dimValue instanceof org.neo4j.values.storable.IntValue) {
                    int dims = ((org.neo4j.values.storable.IntValue) dimValue).value();
                    System.out.println("Using vector dimensions from config: " + dims);
                    return dims;
                }
            }
        } catch (Exception e) {
            System.err.println("Could not extract vector dimensions from config: " + e.getMessage());
        }
        
        // Default fallback
        System.out.println("Using default vector dimensions: 384");
        return 384;
    }

    private void initializeGpuIndex() {
        try {
            // Initialize real CUVS Java API resources using factory method
            cuvsResources = CuVSResources.create();
            
            // Get dimensions from index configuration
            int vectorDimensions = getVectorDimensionsFromConfig();
            this.dimensions = vectorDimensions;
            
            // Create CAGRA configuration parameters
            CagraIndexParams.CuvsDistanceType distanceType = toCuvsDistanceType(similarityFunction);
            System.out.println("Using similarity function: " + similarityFunction + " -> CUVS distance type: " + distanceType);
            
                cagraParams = new CagraIndexParams.Builder()
                        .withGraphDegree(8)   // Even more conservative - minimum viable
                        .withIntermediateGraphDegree(16)  // Even more conservative
                        .withMetric(distanceType)
                        .build();
            
            // Initialize CAGRA index (will be built when first vectors are added)
            setCuvsIndex(null); // Will be created in addVectorsToIndex
            
            isInitialized = true;
            isGpuAvailable = true;
            System.out.println("Initialized CUVS CAGRA index with GPU acceleration (dimensions: " + vectorDimensions + ")");
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
                // CAGRA index has destroyIndex() method for cleanup
                try {
                    cuvsIndex.destroyIndex();
                } catch (Throwable e) {
                    System.err.println("Error destroying CAGRA index: " + e.getMessage());
                }
                // Don't set cuvsIndex to null here - let the caller decide when to do that
                // This prevents breaking atomic replacement logic
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
     * Note: CAGRA doesn't support incremental updates, so this always returns false.
     * @param vectorData The vector to add
     * @return false (CAGRA requires full rebuild for any changes)
     */
    private boolean tryExtendIndex(VectorData vectorData) {
        // CAGRA doesn't support incremental updates
        // Always return false to trigger a full rebuild
        System.out.println("CAGRA doesn't support incremental updates, will rebuild entire index");
        return false;
    }

    /**
     * Add vectors to the index during population (bulk loading).
     * This method is used by the populator for initial population.
     * @param vectorsToAdd List of vectors to add
     * @throws IOException if the operation fails
     */
    public void addVectors(List<VectorData> vectorsToAdd) throws IOException {
        System.out.println("📥 " + instanceName + ".addVectors() called with " + vectorsToAdd.size() + " vectors");
        System.out.println("   - isInitialized: " + isInitialized);
        System.out.println("   - cuvsIndex: " + cuvsIndex);
        System.out.println("   - cuvsResources: " + cuvsResources);
        lock.writeLock().lock();
        try {
            if (!isInitialized) {
                System.out.println("ERROR: Index not initialized!");
                throw new IllegalStateException("Index not initialized");
            }

            // Validate vectors before adding them
            validateVectors(vectorsToAdd);

            // CRITICAL FIX: Add vectors to the list BEFORE calling addVectorsToIndex
            // This ensures that addVectorsToIndex can see the existing vectors
            vectors.addAll(vectorsToAdd);
            vectorCount += vectorsToAdd.size();
            
            if (vectorsToAdd.size() > 0) {
                dimensions = vectorsToAdd.get(0).getVector().length;
            }

            // Check if we have CUVS resources (GPU mode) vs development mode
            if (cuvsResources != null) {
                System.out.println("GPU mode: cuvsResources available, calling addVectorsToIndex");
                addVectorsToIndex(vectorsToAdd);
            } else {
                // Development mode - just track vectors in memory
                System.out.println("Development mode: Tracking vectors in memory only");
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Validate vectors according to CUVS requirements.
     * Based on CUVS documentation: supports 1-2048 dimensions.
     */
    private void validateVectors(List<VectorData> vectorsToAdd) {
        if (vectorsToAdd == null || vectorsToAdd.isEmpty()) {
            throw new IllegalArgumentException("Cannot add null or empty vector list");
        }

        for (VectorData vector : vectorsToAdd) {
            if (vector == null) {
                throw new IllegalArgumentException("Cannot add null vector");
            }
            
            if (vector.getVector() == null) {
                throw new IllegalArgumentException("Vector data cannot be null for node: " + vector.getNodeId());
            }
            
            int vectorDimensions = vector.getVector().length;
            
            // CUVS dimension validation: 1-2048 dimensions
            if (vectorDimensions < 1) {
                throw new IllegalArgumentException(
                    "CUVS requires at least 1 dimension, got " + vectorDimensions + 
                    " for node: " + vector.getNodeId()
                );
            }
            
            if (vectorDimensions > 2048) {
                throw new IllegalArgumentException(
                    "CUVS supports maximum 2048 dimensions, got " + vectorDimensions + 
                    " for node: " + vector.getNodeId()
                );
            }
            
            // Check for NaN or infinite values
            for (int i = 0; i < vectorDimensions; i++) {
                float value = vector.getVector()[i];
                if (Float.isNaN(value)) {
                    throw new IllegalArgumentException(
                        "Vector contains NaN value at dimension " + i + 
                        " for node: " + vector.getNodeId()
                    );
                }
                if (Float.isInfinite(value)) {
                    throw new IllegalArgumentException(
                        "Vector contains infinite value at dimension " + i + 
                        " for node: " + vector.getNodeId()
                    );
                }
            }
        }
        
        // Validate dimension consistency
        if (vectorsToAdd.size() > 1) {
            int firstDimensions = vectorsToAdd.get(0).getVector().length;
            for (int i = 1; i < vectorsToAdd.size(); i++) {
                int currentDimensions = vectorsToAdd.get(i).getVector().length;
                if (currentDimensions != firstDimensions) {
                    throw new IllegalArgumentException(
                        "All vectors must have the same dimensions. Expected " + firstDimensions + 
                        ", got " + currentDimensions + " for node: " + vectorsToAdd.get(i).getNodeId()
                    );
                }
            }
        }
        
        System.out.println("Vector validation passed for " + vectorsToAdd.size() + " vectors with " + 
                          vectorsToAdd.get(0).getVector().length + " dimensions each");
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
                // CRITICAL FIX: Add vector to list BEFORE calling addVectorsToIndex
                vectors.add(vectorData);
                vectorCount++;
                
                List<VectorData> allVectors = new ArrayList<>(vectors);
                addVectorsToIndex(allVectors);
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
            System.out.println("🔧 addVectorsToIndex called with " + vectorsToAdd.size() + " new vectors");
            System.out.println("📊 Current stored vectors: " + vectors.size());
            
            // CAGRA doesn't support incremental updates, so we need to rebuild the entire index
            // Combine all existing vectors with the new ones
            List<VectorData> allVectors = new ArrayList<>(vectors);
            allVectors.addAll(vectorsToAdd);

            int vectorCount = allVectors.size();
            int vectorDimensions = allVectors.get(0).getVector().length;
            
            System.out.println("📈 Total vectors for CAGRA build: " + vectorCount);
            System.out.println("📏 Vector dimensions: " + vectorDimensions);

            // Create a matrix to hold all vectors
            float[][] matrixData = new float[vectorCount][vectorDimensions];
            for (int i = 0; i < vectorCount; i++) {
                matrixData[i] = allVectors.get(i).getVector();
            }
            
            System.out.println("✅ Matrix created: " + matrixData.length + " x " + matrixData[0].length);
            
            // ATOMIC INDEX REPLACEMENT: Build new index before destroying old one
            CagraIndex oldIndex = cuvsIndex;
            CagraIndex newIndex = null;
            
            try {
                // Build new CAGRA index with all vectors
                System.out.println("🚀 Starting CAGRA index build...");
                System.out.println("📊 Dataset: " + vectorCount + " vectors × " + vectorDimensions + " dimensions");
                System.out.println("⚙️ CAGRA params: graphDegree=" + cagraParams.getGraphDegree() + 
                                 ", intermediateGraphDegree=" + cagraParams.getIntermediateGraphDegree());
                
                System.out.println("Creating CuVSMatrix with " + vectorCount + " vectors of " + vectorDimensions + " dimensions");
                
                // Validate matrix data before creating CuVSMatrix
                for (int i = 0; i < Math.min(3, vectorCount); i++) {
                    System.out.println("Vector " + i + " first 3 values: " + 
                        matrixData[i][0] + ", " + matrixData[i][1] + ", " + matrixData[i][2]);
                }
                
                // CAGRA works better with more vectors
                System.out.println("📊 Building CAGRA index with " + vectorCount + " vectors (CAGRA will auto-adjust parameters)");
                
                // For testing purposes, let's use larger datasets
                if (vectorCount < 100) {
                    System.out.println("⚠️ Small dataset (" + vectorCount + " vectors) - CAGRA prefers larger datasets");
                    System.out.println("💡 Consider using 100+ vectors for optimal CAGRA performance");
                } else if (vectorCount >= 1000) {
                    System.out.println("🎉 Large dataset (" + vectorCount + " vectors) - Perfect for CAGRA!");
                } else {
                    System.out.println("✅ Good dataset size (" + vectorCount + " vectors) for CAGRA");
                }
                
                // Don't limit dataset size - let CAGRA handle it
                System.out.println("🚀 Proceeding with full dataset of " + vectorCount + " vectors");
                
                // Try creating CuVSMatrix with explicit row-major layout
                System.out.println("Creating CuVSMatrix with explicit row-major layout...");
                CuVSMatrix matrix;
                try {
                    // Try the standard approach first
                    matrix = CuVSMatrix.ofArray(matrixData);
                    System.out.println("✅ CuVSMatrix created successfully with ofArray()");
                } catch (Throwable e1) {
                    System.out.println("⚠️ ofArray() failed: " + e1.getMessage());
                    try {
                        // Try creating from flattened array
                        float[] flattened = new float[vectorCount * vectorDimensions];
                        for (int i = 0; i < vectorCount; i++) {
                            System.arraycopy(matrixData[i], 0, flattened, i * vectorDimensions, vectorDimensions);
                        }
                        // Create a 2D array from flattened data
                        float[][] matrixFromFlattened = new float[vectorCount][vectorDimensions];
                        for (int i = 0; i < vectorCount; i++) {
                            System.arraycopy(flattened, i * vectorDimensions, matrixFromFlattened[i], 0, vectorDimensions);
                        }
                        matrix = CuVSMatrix.ofArray(matrixFromFlattened);
                        System.out.println("✅ CuVSMatrix created successfully with flattened array");
                    } catch (Throwable e2) {
                        System.out.println("❌ Both matrix creation methods failed:");
                        System.out.println("  ofArray(matrixData): " + e1.getMessage());
                        System.out.println("  ofArray(flattened): " + e2.getMessage());
                        throw new IOException("Failed to create CuVSMatrix: " + e1.getMessage(), e1);
                    }
                }
                
                System.out.println("🔨 Building CAGRA index with CuVS...");
                System.out.println("📊 Matrix dimensions: " + vectorCount + " × " + vectorDimensions);
                System.out.println("⚙️ Using CAGRA parameters: " + cagraParams);
                
                long startTime = System.currentTimeMillis();
                setCuvsIndex(CagraIndex.newBuilder(cuvsResources)
                        .withDataset(matrix)
                        .withIndexParams(cagraParams)
                        .build());
                long buildTime = System.currentTimeMillis() - startTime;
                
                System.out.println("🎉 CAGRA index built successfully!");
                System.out.println("⏱️ Build time: " + buildTime + "ms");
                System.out.println("📊 Final index: " + vectorCount + " vectors × " + vectorDimensions + " dimensions");

                // ATOMIC REPLACEMENT: Set new index
                newIndex = cuvsIndex;
                System.out.println("✅ New CAGRA index ready for atomic replacement");

                // Serialize the CAGRA index to disk for future fast loading
                try {
                    storage.serializeCagraIndex(newIndex);
                    System.out.println("✅ CAGRA index serialized to disk");
                } catch (Exception e) {
                    System.err.println("⚠️ Failed to serialize CAGRA index (non-critical): " + e.getMessage());
                    // Don't fail the entire operation if serialization fails
                }
            } catch (Throwable e) {
                throw new IOException("Failed to build CAGRA index: " + e.getMessage(), e);
            }
            
            // ATOMIC REPLACEMENT: Only destroy old index after new one is successfully built
            if (oldIndex != null && newIndex != null) {
                try {
                    System.out.println("🗑️ Destroying old CAGRA index after successful replacement...");
                    oldIndex.destroyIndex();
                    System.out.println("✅ Old CAGRA index destroyed safely");
                } catch (Throwable e) {
                    System.err.println("⚠️ Error destroying old CAGRA index (non-critical): " + e.getMessage());
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
    public CagraIndex getCuvsIndex() {
        lock.readLock().lock();
        try {
            System.out.println("🔍 getCuvsIndex() called - cuvsIndex: " + cuvsIndex + ", isInitialized: " + isInitialized);
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
     * Get all stored vectors for mock search operations.
     * @return List of all stored vectors
     */
    public List<VectorData> getVectors() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(vectors);
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
        System.out.println("🔄 load() method called - cuvsIndex before: " + cuvsIndex);
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
            
            // Initialize CUVS resources without calling initialize() to avoid resetting cuvsIndex
            System.out.println("Loading existing index with " + vectors.size() + " vectors");
            
            // Check development mode first
            if (isDevelopmentMode()) {
                System.out.println("Development mode: Loading mock index");
                initializeMockMode();
                return;
            }
            
            // Check if CUVS native library is available
            if (!CuvsNativeLibrary.isAvailable()) {
                System.out.println("CUVS native library not available, falling back to development mode");
                initializeMockMode();
                return;
            }
            
            // Check GPU availability
            if (!GpuDetector.isGpuAvailable()) {
                System.out.println("No GPU detected, falling back to development mode");
                initializeMockMode();
                return;
            }
            
            // Initialize GPU resources
            System.out.println("Initializing GPU resources for loaded index");
            try {
                cuvsResources = CuVSResources.create();
                isGpuAvailable = true;
            } catch (Throwable e) {
                System.err.println("Failed to create CUVS resources: " + e.getMessage());
                e.printStackTrace();
                throw new RuntimeException("Failed to create CUVS resources", e);
            }
            
            // Try to load serialized CAGRA index first
            if (storage.hasCagraIndex()) {
                try {
                    System.out.println("🔄 Loading serialized CAGRA index from disk");
                    System.out.println("   - Storage path: " + storage.getIndexDirectory());
                    System.out.println("   - CAGRA file exists: " + storage.hasCagraIndex());
                    System.out.println("   - cuvsResources: " + cuvsResources);
                    
                    CagraIndex deserializedIndex = storage.deserializeCagraIndex(cuvsResources);
                    System.out.println("   - Deserialized index: " + deserializedIndex);
                    
                    setCuvsIndex(deserializedIndex);
                    System.out.println("✅ CAGRA index loaded successfully from disk!");
                    System.out.println("   - Final cuvsIndex: " + cuvsIndex);
                    
                    isInitialized = true;
                    storage.updateState("ONLINE", vectorCount, isGpuAvailable);
                    return;
                } catch (Exception e) {
                    System.err.println("❌ Failed to load serialized CAGRA index: " + e.getMessage());
                    e.printStackTrace();
                    System.out.println("Falling back to rebuilding index from vector data");
                }
            } else {
                System.out.println("📁 No serialized CAGRA index found, will rebuild from vector data");
            }
            
            // Rebuild index with loaded data
            if (!vectors.isEmpty()) {
                System.out.println("Rebuilding CAGRA index with " + vectors.size() + " loaded vectors");
                List<VectorData> vectorDataList = new ArrayList<>(vectors);
                
                // Create CAGRA parameters
                CagraIndexParams.CuvsDistanceType distanceType = toCuvsDistanceType(similarityFunction);
                System.out.println("Loading index with similarity function: " + similarityFunction + " -> CUVS distance type: " + distanceType);
                
                cagraParams = new CagraIndexParams.Builder()
                        .withGraphDegree(64)
                        .withIntermediateGraphDegree(128)
                        .withMetric(distanceType)
                        .build();
                
                // Build the CAGRA index with all loaded vectors
                try {
                    System.out.println("Building CAGRA index with " + vectorDataList.size() + " vectors, dimensions: " + this.dimensions);
                    setCuvsIndex(CagraIndex.newBuilder(cuvsResources)
                            .withDataset(convertToMatrix(vectorDataList))
                            .withIndexParams(cagraParams)
                            .build());
                    
                    System.out.println("✅ CAGRA index rebuild completed successfully!");
                    System.out.println("   - cuvsIndex: " + (cuvsIndex != null ? "created" : "null"));
                    System.out.println("   - Vector count: " + vectorDataList.size());
                    System.out.println("   - Dimensions: " + this.dimensions);
                    System.out.println("   - Distance type: " + distanceType);
                    
                    // Serialize the CAGRA index to disk for future fast loading
                    try {
                        storage.serializeCagraIndex(cuvsIndex);
                        System.out.println("✅ CAGRA index serialized to disk for future fast loading");
                    } catch (Exception e) {
                        System.err.println("⚠️ Failed to serialize CAGRA index (non-critical): " + e.getMessage());
                        // Don't fail the entire operation if serialization fails
                    }
                } catch (Throwable e) {
                    System.err.println("❌ Failed to rebuild CAGRA index: " + e.getMessage());
                    e.printStackTrace();
                    throw new RuntimeException("Failed to rebuild CAGRA index from loaded data", e);
                }
            }
            
            // Mark as initialized
            isInitialized = true;
            
            // Update state
            storage.updateState("ONLINE", vectorCount, isGpuAvailable);
            
            System.out.println("🔄 load() method completed - cuvsIndex after: " + cuvsIndex);
            
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
                setCuvsIndex(null); // Explicitly set to null after cleanup
                
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
                setCuvsIndex(null); // Explicitly set to null after cleanup
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
     * 
     * CUVS Distance Types:
     * - L2Expanded: Squared Euclidean (faster, but different from standard Euclidean)
     * - L2SqrtExpanded: Euclidean with square root (standard Euclidean distance)
     * - CosineExpanded: Cosine distance
     * - InnerProduct: Dot product
     */
    private CagraIndexParams.CuvsDistanceType toCuvsDistanceType(VectorSimilarityFunction similarityFunction) {
        if (similarityFunction == VectorSimilarityFunctions.EUCLIDEAN) {
            // CUVS only supports L2Expanded (squared Euclidean), not L2SqrtExpanded
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
    
    // CagraCuvsIndex interface implementation
    
    @Override
    public CagraIndex getCagraIndex() {
        return cuvsIndex;
    }
    
    @Override
    public CagraIndexParams getCagraParams() {
        return cagraParams;
    }
    
    @Override
    public void serializeIndex() throws IOException {
        if (cuvsIndex == null) {
            throw new IllegalStateException("CAGRA index not initialized");
        }
        storage.serializeCagraIndex(cuvsIndex);
    }
    
    @Override
    public void deserializeIndex() throws IOException {
        if (cuvsResources == null) {
            throw new IllegalStateException("CUVS resources not initialized");
        }
        cuvsIndex = storage.deserializeCagraIndex(cuvsResources);
    }
    
    @Override
    public boolean hasSerializedIndex() {
        return storage.hasCagraIndex();
    }
    
    @Override
    public CuvsIndexReader getReader() {
        System.out.println("📖 " + instanceName + ".getReader() called");
        System.out.println("   - cuvsIndex: " + cuvsIndex);
        System.out.println("   - isInitialized: " + isInitialized);
        
        // CRITICAL FIX: Load the CAGRA index if it's not already loaded
        if (cuvsIndex == null && cuvsResources != null) {
            System.out.println("🔧 CAGRA index is null, attempting to load from disk...");
            try {
                load();
                System.out.println("✅ Successfully loaded CAGRA index from disk!");
                System.out.println("   - cuvsIndex after load: " + cuvsIndex);
            } catch (Exception e) {
                System.err.println("❌ Failed to load CAGRA index: " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        return new CuvsIndexReader(descriptor, null, this, java.util.OptionalInt.empty());
    }
    
    /**
     * Get the similarity function used by this index.
     * @return the similarity function
     */
    public VectorSimilarityFunction getSimilarityFunction() {
        return similarityFunction;
    }

}
