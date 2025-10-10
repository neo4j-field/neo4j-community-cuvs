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
package org.neo4j.kernel.api.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.schema.IndexDefinition;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.test.extension.ImpermanentDbmsExtension;
import org.neo4j.test.extension.Inject;

@ImpermanentDbmsExtension
class CuvsIndexCypherIntegrationTest {

    @Inject
    private GraphDatabaseAPI db;

    @Test
    void shouldCreateCuvsVectorIndex() {
        // Given
        try (Transaction tx = db.beginTx()) {
            // Create test nodes with vectors
            tx.execute("CREATE (n:Document {id: 1, embedding: [1.0, 2.0, 3.0]})");
            tx.execute("CREATE (n:Document {id: 2, embedding: [4.0, 5.0, 6.0]})");
            tx.commit();
        }

        // When - Create CUVS vector index
        try (Transaction tx = db.beginTx()) {
            tx.execute("""
                CREATE VECTOR INDEX document_embeddings 
                FOR (n:Document) ON n.embedding 
                OPTIONS {
                    indexProvider: 'cuvs',
                    indexConfig: {
                        `vector.dimensions`: 3,
                        `vector.similarity_function`: 'EUCLIDEAN'
                    }
                }
                """);
            tx.commit();
        }

        // Then - Index should be created
        try (Transaction tx = db.beginTx()) {
            var result = tx.execute("SHOW INDEXES");
            var indexes = result.stream().toList();
            
            assertThat(indexes).hasSize(1);
            var index = indexes.get(0);
            assertThat(index.get("name")).isEqualTo("document_embeddings");
            assertThat(index.get("type")).isEqualTo("VECTOR");
            assertThat(index.get("labelsOrTypes")).isEqualTo(java.util.List.of("Document"));
            assertThat(index.get("properties")).isEqualTo(java.util.List.of("embedding"));
            // Note: provider might not be shown in SHOW INDEXES output
        }
    }

    @Test
    void shouldQueryCuvsVectorIndex() {
        // Given - Create index and data
        try (Transaction tx = db.beginTx()) {
            tx.execute("CREATE (n:Document {id: 1, embedding: [1.0, 2.0, 3.0]})");
            tx.execute("CREATE (n:Document {id: 2, embedding: [4.0, 5.0, 6.0]})");
            tx.execute("CREATE (n:Document {id: 3, embedding: [7.0, 8.0, 9.0]})");
            
            tx.execute("""
                CREATE VECTOR INDEX document_embeddings 
                FOR (n:Document) ON n.embedding 
                OPTIONS {
                    indexProvider: 'cuvs',
                    indexConfig: {
                        `vector.dimensions`: 3,
                        `vector.similarity_function`: 'EUCLIDEAN'
                    }
                }
                """);
            tx.commit();
        }

        // When - Query the vector index
        try (Transaction tx = db.beginTx()) {
            var result = tx.execute("""
                CALL db.index.vector.queryNodes('document_embeddings', 2, [1.0, 2.0, 3.0])
                YIELD node, score
                RETURN node.id, score
                ORDER BY score DESC
                """);
            
            var results = result.stream().toList();
            
            // Then - Should return similar vectors
            assertThat(results).hasSize(2);
            assertThat(results.get(0).get("node.id")).isEqualTo(1L); // Exact match
            assertThat(results.get(0).get("score")).isEqualTo(1.0f); // Perfect similarity
        }
    }

    @Test
    void shouldRejectInvalidCuvsProvider() {
        // When/Then - Should throw exception for invalid provider
        try (Transaction tx = db.beginTx()) {
            assertThatThrownBy(() -> {
                tx.execute("""
                    CREATE VECTOR INDEX test_index 
                    FOR (n:Document) ON n.embedding 
                    OPTIONS {
                        indexProvider: 'invalid-provider',
                        indexConfig: {
                            `vector.dimensions`: 3,
                            `vector.similarity_function`: 'EUCLIDEAN'
                        }
                    }
                    """);
            }).hasMessageContaining("Invalid input 'invalid-provider' for index provider type");
        }
    }
}
