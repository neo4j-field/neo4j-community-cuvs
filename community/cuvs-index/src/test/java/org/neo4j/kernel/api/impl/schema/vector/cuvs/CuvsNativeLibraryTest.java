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

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class CuvsNativeLibraryTest {

    @Test
    void shouldHandleLibraryUnavailable() {
        // This test will always pass since we're not loading the actual library
        // It tests the fallback behavior
        boolean available = CuvsNativeLibrary.isAvailable();
        // We expect false since we don't have the actual CUVS library
        assertFalse(available);
    }

    @Test
    void shouldReturnVersionWhenUnavailable() {
        String version = CuvsNativeLibrary.getVersion();
        assertEquals("unavailable", version);
    }
}
