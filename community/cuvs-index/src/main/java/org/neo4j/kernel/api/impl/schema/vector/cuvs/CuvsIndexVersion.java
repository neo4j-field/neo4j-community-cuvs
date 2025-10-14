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

import java.util.Comparator;
import java.util.Locale;
import java.util.OptionalInt;
import org.eclipse.collections.api.RichIterable;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.Sets;
import org.eclipse.collections.api.factory.SortedMaps;
import org.eclipse.collections.api.factory.primitive.BooleanSets;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.map.ImmutableMap;
import org.eclipse.collections.api.map.sorted.ImmutableSortedMap;
import org.eclipse.collections.api.set.SetIterable;
import org.eclipse.collections.api.set.primitive.BooleanSet;
import org.eclipse.collections.api.set.primitive.ImmutableBooleanSet;
import org.eclipse.collections.api.tuple.Pair;
import org.eclipse.collections.impl.tuple.Tuples;
import org.neo4j.configuration.Config;
import org.neo4j.graphdb.schema.IndexSetting;
import org.neo4j.internal.schema.AllIndexProviderDescriptors;
import org.neo4j.internal.schema.IndexProviderDescriptor;
import org.neo4j.kernel.KernelVersion;
import org.neo4j.kernel.api.vector.VectorSimilarityFunction;
import org.neo4j.kernel.api.impl.schema.vector.VectorSimilarityFunctions;
import org.neo4j.util.VisibleForTesting;
import org.neo4j.values.storable.FloatingPointArray;
import org.neo4j.values.storable.NumberArray;
import org.eclipse.collections.api.set.sorted.ImmutableSortedSet;
import org.eclipse.collections.impl.factory.SortedSets;
import org.neo4j.kernel.api.impl.schema.vector.VectorIndexConfig;
import org.neo4j.internal.schema.IndexConfigValidationRecords;
import org.neo4j.internal.schema.SettingsAccessor;
import org.neo4j.internal.schema.IndexConfig;
import org.neo4j.values.storable.Value;

/**
 * Version management for CUVS (CUDA Vector Search) index provider.
 * This enum defines the supported versions of the CUVS index provider,
 * including their capabilities, constraints, and validation rules.
 */
public enum CuvsIndexVersion {
    UNKNOWN(
            AllIndexProviderDescriptors.UNDECIDED,
            KernelVersion.EARLIEST,
            0,
            0,
            0,
            Sets.immutable.empty(),
            BooleanSets.immutable.empty()) {
        @Override
        protected RichIterable<Pair<KernelVersion, CuvsIndexSettingsValidator>> configureValidators() {
            return Lists.mutable.<Pair<KernelVersion, CuvsIndexSettingsValidator>>of(Tuples.pair(
                    KernelVersion.EARLIEST,
                    new BasicCuvsIndexSettingsValidator(CuvsIndexVersion.V1_0)));
        }

        @Override
        public boolean acceptsValueInstanceType(Value candidate) {
            return false;
        }
    },

    V1_0(
            AllIndexProviderDescriptors.CUVS_V1_DESCRIPTOR,
            KernelVersion.VERSION_NODE_VECTOR_INDEX_INTRODUCED,
            4096,  // Higher max dimensions for GPU processing
            512,   // Max HNSW M parameter
            3200,  // Max HNSW ef construction
            Sets.mutable.of(VectorSimilarityFunctions.EUCLIDEAN, VectorSimilarityFunctions.L2_NORM_COSINE),
            BooleanSets.immutable.empty()) {
        @Override
        protected RichIterable<Pair<KernelVersion, CuvsIndexSettingsValidator>> configureValidators() {
            System.out.println("CuvsIndexVersion.V1_0.configureValidators() called");
            var validator = new BasicCuvsIndexSettingsValidator(CuvsIndexVersion.V1_0);
            System.out.println("CuvsIndexVersion.V1_0.configureValidators() created validator: " + validator);
            return Lists.mutable.<Pair<KernelVersion, CuvsIndexSettingsValidator>>of(
                    Tuples.pair(KernelVersion.EARLIEST, validator),
                    Tuples.pair(KernelVersion.VERSION_NODE_VECTOR_INDEX_INTRODUCED, validator));
        }

        @Override
        public boolean acceptsValueInstanceType(Value candidate) {
            return candidate instanceof FloatingPointArray;
        }
    };

    public static final ImmutableList<CuvsIndexVersion> KNOWN_VERSIONS =
            Lists.mutable.with(values()).without(UNKNOWN).toImmutableList();

    public static CuvsIndexVersion latestSupportedVersion(KernelVersion kernelVersion) {
        for (final var version : KNOWN_VERSIONS.asReversed()) {
            if (kernelVersion.isAtLeast(version.minimumRequiredKernelVersion)) {
                return version;
            }
        }
        return UNKNOWN;
    }

    public static CuvsIndexVersion fromDescriptor(IndexProviderDescriptor descriptor) {
        for (final var version : KNOWN_VERSIONS.asReversed()) {
            if (version.descriptor.equals(descriptor)) {
                return version;
            }
        }
        return UNKNOWN;
    }

    private final KernelVersion minimumRequiredKernelVersion;
    private final IndexProviderDescriptor descriptor;
    private final int maxDimensions;
    private final ImmutableMap<String, VectorSimilarityFunction> similarityFunctions;
    private final ImmutableBooleanSet quantizationBooleans;
    private final int maxHnswM;
    private final int maxHnswEfConstruction;
    private final ImmutableSortedMap<KernelVersion, CuvsIndexSettingsValidator> validators;
    private final CuvsIndexSettingsValidator latestIndexSettingValidator;

    CuvsIndexVersion(
            IndexProviderDescriptor providerDescriptor,
            KernelVersion minimumRequiredKernelVersion,
            int maxDimensions,
            int maxHnswM,
            int maxHnswEfConstruction,
            SetIterable<VectorSimilarityFunction> supportedSimilarityFunctions,
            BooleanSet supportedQuantizationEnableds) {
        this.minimumRequiredKernelVersion = minimumRequiredKernelVersion;
        this.descriptor = providerDescriptor;

        this.maxDimensions = maxDimensions;
        this.similarityFunctions = supportedSimilarityFunctions.toImmutableMap(
                similarityFunction -> similarityFunction.name().toUpperCase(Locale.ROOT),
                similarityFunction -> similarityFunction);
        this.quantizationBooleans = supportedQuantizationEnableds.toImmutable();
        this.maxHnswM = maxHnswM;
        this.maxHnswEfConstruction = maxHnswEfConstruction;

        this.validators = SortedMaps.mutable
                .<KernelVersion, CuvsIndexSettingsValidator>of(Comparator.reverseOrder())
                .withAllKeyValues(configureValidators())
                .toImmutable();
        this.latestIndexSettingValidator = indexSettingValidator(KernelVersion.getLatestVersion(Config.defaults()));
    }

    public KernelVersion minimumRequiredKernelVersion() {
        return minimumRequiredKernelVersion;
    }

    public IndexProviderDescriptor descriptor() {
        return descriptor;
    }

    @VisibleForTesting
    public int maxDimensions() {
        return maxDimensions;
    }

    @VisibleForTesting
    public int maxHnswM() {
        return maxHnswM;
    }

    @VisibleForTesting
    public int maxHnswEfConstruction() {
        return maxHnswEfConstruction;
    }

    protected abstract RichIterable<Pair<KernelVersion, CuvsIndexSettingsValidator>> configureValidators();

    public abstract boolean acceptsValueInstanceType(Value candidate);

    public VectorSimilarityFunction maybeSimilarityFunction(String name) {
        return similarityFunctions.get(name.toUpperCase(Locale.ROOT));
    }

    public VectorSimilarityFunction similarityFunction(String name) {
        final var similarityFunction = maybeSimilarityFunction(name);
        if (similarityFunction == null) {
            throw new IllegalArgumentException(
                    "'%s' is an unsupported vector similarity function for CUVS index with provider %s. "
                                    .formatted(name, descriptor.name())
                            + "Supported: "
                            + similarityFunctions.keysView());
        }

        return similarityFunction;
    }

    @VisibleForTesting
    public RichIterable<VectorSimilarityFunction> supportedSimilarityFunctions() {
        return similarityFunctions.valuesView();
    }

    ImmutableMap<String, VectorSimilarityFunction> nameToSimilarityFunction() {
        return similarityFunctions;
    }

    @VisibleForTesting
    public ImmutableBooleanSet supportedQuantizationBooleans() {
        return quantizationBooleans;
    }

    public CuvsIndexSettingsValidator indexSettingValidator() {
        System.out.println("CuvsIndexVersion.indexSettingValidator() called for version: " + this);
        System.out.println("CuvsIndexVersion.indexSettingValidator() latestIndexSettingValidator: " + latestIndexSettingValidator);
        return latestIndexSettingValidator;
    }

    public CuvsIndexSettingsValidator indexSettingValidator(KernelVersion kernelVersion) {
        System.out.println("CuvsIndexVersion.indexSettingValidator(kernelVersion) called for version: " + this + ", kernelVersion: " + kernelVersion);
        System.out.println("CuvsIndexVersion.indexSettingValidator(kernelVersion) validators: " + validators);
        
        final var validator = validators
                .keyValuesView()
                .detect(kernelVersionAndValidator -> kernelVersion.isAtLeast(kernelVersionAndValidator.getOne()));
        
        System.out.println("CuvsIndexVersion.indexSettingValidator(kernelVersion) found validator: " + validator);
        
        if (validator == null) {
            System.out.println("CuvsIndexVersion.indexSettingValidator(kernelVersion) returning CuvsValidatorNotFoundForKernelVersion");
            return new CuvsValidatorNotFoundForKernelVersion(this, kernelVersion);
        }

        System.out.println("CuvsIndexVersion.indexSettingValidator(kernelVersion) returning validator: " + validator.getTwo());
        return validator.getTwo();
    }

}
