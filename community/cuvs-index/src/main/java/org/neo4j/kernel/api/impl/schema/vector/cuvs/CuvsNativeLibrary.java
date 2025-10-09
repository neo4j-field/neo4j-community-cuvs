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
 * Native library loader and version detection for CUVS (CUDA Vector Search).
 * This class handles loading the CUVS native library and provides version information.
 */
public class CuvsNativeLibrary {
    private static final boolean CUVS_AVAILABLE;

    static {
        boolean available = false;
        try {
            System.loadLibrary("cuvs");
            available = true;
            // CUVS native library loaded successfully
        } catch (UnsatisfiedLinkError e) {
            // CUVS native library not available: e.getMessage()
        }
        CUVS_AVAILABLE = available;
    }

    /**
     * Check if the CUVS native library is available.
     * @return true if CUVS is available, false otherwise
     */
    public static boolean isAvailable() {
        return CUVS_AVAILABLE;
    }

    /**
     * Get the version of the CUVS native library.
     * @return version string, or "unavailable" if CUVS is not available
     */
    public static String getVersion() {
        if (!CUVS_AVAILABLE) {
            return "unavailable";
        }
        
        try {
            // According to the API reference, version is not exposed through public API
            // The current version is 25.10.0 but we can't access it directly
            // Return a placeholder version
            return "25.10.0"; // Current CUVS version
        } catch (Exception e) {
            return "unknown";
        }
    }
}
