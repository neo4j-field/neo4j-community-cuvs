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

import java.nio.file.Path;
import org.neo4j.configuration.Config;
import org.neo4j.dbms.database.readonly.DatabaseReadOnlyChecker;
import org.neo4j.internal.schema.IndexDescriptor;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.kernel.api.impl.schema.vector.VectorIndexConfig;
import org.neo4j.kernel.api.vector.VectorSimilarityFunction;

/**
 * Builder for creating CUVS vector indexes.
 * Similar to VectorIndexBuilder but for CUVS-based indexes.
 */
public class CuvsIndexBuilder {
    private final IndexDescriptor descriptor;
    private final VectorIndexConfig vectorIndexConfig;
    private final VectorSimilarityFunction similarityFunction;
    private final DatabaseReadOnlyChecker readOnlyChecker;
    private final Config config;
    private final FileSystemAbstraction fileSystem;
    
    private Path indexDirectory;
    private boolean permanentlyReadOnly = false;

    private CuvsIndexBuilder(
            IndexDescriptor descriptor,
            VectorIndexConfig vectorIndexConfig,
            VectorSimilarityFunction similarityFunction,
            DatabaseReadOnlyChecker readOnlyChecker,
            Config config,
            FileSystemAbstraction fileSystem) {
        this.descriptor = descriptor;
        this.vectorIndexConfig = vectorIndexConfig;
        this.similarityFunction = similarityFunction;
        this.readOnlyChecker = readOnlyChecker;
        this.config = config;
        this.fileSystem = fileSystem;
    }

    /**
     * Create a new CUVS index builder.
     *
     * @param descriptor The descriptor for this index
     * @param vectorIndexConfig The vector index configuration
     * @param similarityFunction The similarity function to use
     * @param readOnlyChecker Read-only checker for the database
     * @param config Neo4j configuration
     * @param fileSystem File system abstraction
     * @return CuvsIndexBuilder instance
     */
    public static CuvsIndexBuilder create(
            IndexDescriptor descriptor,
            VectorIndexConfig vectorIndexConfig,
            VectorSimilarityFunction similarityFunction,
            DatabaseReadOnlyChecker readOnlyChecker,
            Config config,
            FileSystemAbstraction fileSystem) {
        return new CuvsIndexBuilder(descriptor, vectorIndexConfig, similarityFunction, readOnlyChecker, config, fileSystem);
    }

    /**
     * Specify the index directory.
     *
     * @param indexDirectory The directory where the index will be stored
     * @return this builder
     */
    public CuvsIndexBuilder withIndexDirectory(Path indexDirectory) {
        this.indexDirectory = indexDirectory;
        return this;
    }

    /**
     * Mark the index as permanently read-only.
     *
     * @return this builder
     */
    public CuvsIndexBuilder permanentlyReadOnly() {
        this.permanentlyReadOnly = true;
        return this;
    }

    /**
     * Build the CUVS index.
     *
     * @return SimpleCuvsIndex instance
     */
    public SimpleCuvsIndex build() {
        if (indexDirectory == null) {
            throw new IllegalStateException("Index directory must be specified");
        }
        
        return new SimpleCuvsIndex(descriptor, indexDirectory, similarityFunction, fileSystem);
    }
}
