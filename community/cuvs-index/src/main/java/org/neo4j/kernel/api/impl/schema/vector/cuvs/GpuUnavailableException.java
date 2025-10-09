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

/**
 * Exception thrown when GPU resources are required but unavailable.
 * CUVS (CUDA Vector Search) requires GPU acceleration and will not
 * fall back to CPU implementation.
 */
public class GpuUnavailableException extends RuntimeException {
    
    public GpuUnavailableException(String message) {
        super(message);
    }
    
    public GpuUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
    
    /**
     * Creates a detailed error message with troubleshooting steps.
     */
    public static GpuUnavailableException createDetailed(String reason) {
        String message = String.format(
            "CUVS requires GPU acceleration but GPU is unavailable: %s%n" +
            "Troubleshooting steps:%n" +
            "1. Ensure NVIDIA GPU is installed and recognized by the system%n" +
            "2. Install NVIDIA drivers (check with 'nvidia-smi')%n" +
            "3. Install CUDA runtime (version 12.0 or later)%n" +
            "4. Verify GPU has sufficient VRAM for vector operations%n" +
            "5. Check that no other processes are exclusively using the GPU%n" +
            "6. For development/testing, use -Dcuvs.development.mode=true",
            reason
        );
        return new GpuUnavailableException(message);
    }
}
