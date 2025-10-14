#!/usr/bin/env python3
"""
Test script to verify CUVS vector index search functionality
"""

import numpy as np
from neo4j import GraphDatabase

# Neo4j connection details
URI = "bolt://localhost:7687"
USERNAME = "neo4j"
PASSWORD = "password123"

def test_cuvs_vector_search():
    """Test CUVS vector index search functionality"""
    
    driver = GraphDatabase.driver(URI, auth=(USERNAME, PASSWORD))
    
    try:
        with driver.session() as session:
            print("🔍 Testing CUVS vector index search...")
            
            # First, check if our index exists and is online
            print("\n📊 Checking index status...")
            result = session.run("SHOW INDEXES")
            indexes = [record for record in result]
            
            cuvs_index = None
            for idx in indexes:
                if 'cuvs-1.0' in str(idx) and 'VECTOR' in str(idx):
                    cuvs_index = idx
                    break
            
            if cuvs_index:
                print(f"✅ Found CUVS index: {cuvs_index}")
                print(f"   State: {cuvs_index['state']}")
                print(f"   Population: {cuvs_index['populationPercent']}%")
            else:
                print("❌ No CUVS index found!")
                return
            
            # Test vector similarity search
            print("\n🔍 Testing vector similarity search...")
            
            # Create a query vector (similar to one of our test documents)
            query_vector = np.random.rand(128).astype(np.float32).tolist()
            
            # Try to use the index for similarity search
            try:
                result = session.run("""
                    CALL db.index.vector.queryNodes('test_vector_index_default', 3, $query_vector)
                    YIELD node, score
                    RETURN node.text as text, score
                    ORDER BY score DESC
                """, {"query_vector": query_vector})
                
                results = [record for record in result]
                print(f"✅ Vector search successful! Found {len(results)} results:")
                for i, result in enumerate(results):
                    print(f"   {i+1}. Score: {result['score']:.4f}, Text: {result['text'][:50]}...")
                    
            except Exception as e:
                print(f"❌ Vector search failed: {e}")
                
                # Try alternative search method
                print("\n🔄 Trying alternative search method...")
                try:
                    result = session.run("""
                        MATCH (n:TestDocument)
                        WHERE n.embedding IS NOT NULL
                        RETURN n.text as text, n.id as id
                        LIMIT 3
                    """)
                    
                    results = [record for record in result]
                    print(f"✅ Basic query successful! Found {len(results)} documents:")
                    for i, result in enumerate(results):
                        print(f"   {i+1}. ID: {result['id']}, Text: {result['text'][:50]}...")
                        
                except Exception as e2:
                    print(f"❌ Alternative search also failed: {e2}")
            
            # Test if we can insert more data
            print("\n📝 Testing data insertion...")
            try:
                session.run("""
                    CREATE (n:TestDocument {
                        id: 999,
                        text: 'New test document for CUVS',
                        embedding: $embedding
                    })
                """, {"embedding": np.random.rand(128).astype(np.float32).tolist()})
                
                print("✅ Data insertion successful!")
                
                # Check total count
                result = session.run("MATCH (n:TestDocument) RETURN count(n) as count")
                count = result.single()["count"]
                print(f"   Total documents: {count}")
                
            except Exception as e:
                print(f"❌ Data insertion failed: {e}")
                
    except Exception as e:
        print(f"❌ Error: {e}")
    finally:
        driver.close()

if __name__ == "__main__":
    test_cuvs_vector_search()
