#!/usr/bin/env python3
"""
Test script to create CUVS vector index and insert test data
"""

import numpy as np
from neo4j import GraphDatabase

# Neo4j connection details
URI = "bolt://localhost:7687"
USERNAME = "neo4j"
PASSWORD = "password123"

def test_cuvs_vector_index():
    """Test creating CUVS vector index and inserting test data"""
    
    driver = GraphDatabase.driver(URI, auth=(USERNAME, PASSWORD))
    
    try:
        with driver.session() as session:
            print("🔍 Testing Neo4j connection...")
            
            # Test connection
            result = session.run("RETURN 'Hello Neo4j!' as message")
            message = result.single()["message"]
            print(f"✅ Connected: {message}")
            
            # Clear existing data
            print("\n🧹 Clearing existing test data...")
            session.run("MATCH (n:TestDocument) DELETE n")
            
            # Create test documents with fake embeddings
            print("\n📝 Creating test documents with fake embeddings...")
            test_docs = [
                "This is a test document about machine learning",
                "Another document about artificial intelligence", 
                "A third document about neural networks",
                "Document about deep learning algorithms",
                "Final test document about computer vision"
            ]
            
            # Generate fake 128-dimensional embeddings
            embeddings = np.random.rand(len(test_docs), 128).astype(np.float32)
            
            for i, (doc, embedding) in enumerate(zip(test_docs, embeddings)):
                session.run("""
                    CREATE (n:TestDocument {
                        id: $id,
                        text: $text,
                        embedding: $embedding
                    })
                """, {
                    "id": i,
                    "text": doc,
                    "embedding": embedding.tolist()
                })
            
            # Verify insertion
            result = session.run("MATCH (n:TestDocument) RETURN count(n) as count")
            count = result.single()["count"]
            print(f"✅ Inserted {count} test documents")
            
            # Drop existing index if it exists
            print("\n🗑️ Dropping existing index if it exists...")
            try:
                session.run("DROP INDEX test_vector_index IF EXISTS")
                print("✅ Dropped existing index")
            except Exception as e:
                print(f"ℹ️ No existing index to drop: {e}")
            
            # First, let's see what indexes exist to understand the system
            print("\n🔍 Checking existing indexes...")
            result = session.run("SHOW INDEXES")
            indexes = [record for record in result]
            print("Existing indexes:")
            for idx in indexes:
                print(f"  - {idx}")
            
            # Try creating a vector index without specifying provider
            print("\n🔄 Testing default vector index provider...")
            try:
                session.run("DROP INDEX test_vector_index_default IF EXISTS")
                session.run("""
                    CREATE VECTOR INDEX test_vector_index_default
                    FOR (n:TestDocument) ON n.embedding
                    OPTIONS {
                        indexConfig: {
                            `vector.dimensions`: 128,
                            `vector.similarity_function`: 'EUCLIDEAN'
                        }
                    }
                """)
                print("✅ Default vector index created successfully!")
            except Exception as e:
                print(f"❌ Failed to create default vector index: {e}")
            
            # Drop the existing index first so we can test CUVS creation
            print("\n🗑️ Dropping existing vector index to test CUVS creation...")
            try:
                session.run("DROP INDEX test_vector_index_default IF EXISTS")
                print("✅ Dropped existing vector index")
            except Exception as e:
                print(f"⚠️ Could not drop index: {e}")
            
            # Now try creating another vector index (should use CUVS automatically)
            print("\n🚀 Creating another vector index (should use CUVS automatically)...")
            try:
                session.run("""
                    CREATE VECTOR INDEX test_vector_index
                    FOR (n:TestDocument) ON n.embedding
                    OPTIONS {
                        indexProvider: 'cuvs-1.0',
                        indexConfig: {
                            `vector.dimensions`: 128,
                            `vector.similarity_function`: 'EUCLIDEAN'
                        }
                    }
                """)
                print("✅ CUVS vector index created successfully!")
            except Exception as e:
                if "IndexAlreadyExists" in str(e) or "EquivalentSchemaRuleAlreadyExists" in str(e):
                    print("✅ CUVS vector index creation attempted - index already exists (this is expected)")
                else:
                    print(f"❌ Failed to create CUVS index: {e}")
                    print("This might mean CUVS provider is not available")
                
                # Check index status
                result = session.run("SHOW INDEXES")
                indexes = [record for record in result]
                print(f"\n📊 Available indexes: {len(indexes)}")
                for idx in indexes:
                    if 'test_vector_index' in str(idx):
                        print(f"  - {idx}")
                
                # Try creating with default provider as fallback
                print("\n🔄 Trying with default vector index provider...")
                try:
                    session.run("""
                        CREATE VECTOR INDEX test_vector_index_default
                        FOR (n:TestDocument) ON n.embedding
                        OPTIONS {
                            indexConfig: {
                                `vector.dimensions`: 128,
                                `vector.similarity_function`: 'EUCLIDEAN'
                            }
                        }
                    """)
                    print("✅ Default vector index created successfully!")
                except Exception as e2:
                    print(f"❌ Failed to create default vector index: {e2}")
            
    except Exception as e:
        print(f"❌ Error: {e}")
    finally:
        driver.close()

if __name__ == "__main__":
    test_cuvs_vector_index()
