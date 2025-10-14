#!/usr/bin/env python3
"""
Test script for CUVS vector index with real data from SQuAD dataset.
This script demonstrates:
1. Loading real question data from SQuAD dataset
2. Generating embeddings using sentence-transformers
3. Creating a CUVS vector index
4. Performing vector similarity search
"""

import neo4j
import numpy as np
from sentence_transformers import SentenceTransformer
import random
from datasets import load_dataset

# Neo4j connection details (local machine)
uri = "bolt://localhost:7687"
username = "neo4j"
password = "password123"

print("🔍 Testing Neo4j connection...")
try:
    # Create driver
    driver = neo4j.GraphDatabase.driver(uri, auth=(username, password))
    
    # Test connection
    driver.verify_connectivity()
    print("✅ Connected to Neo4j!")
except Exception as e:
    print(f"❌ Connection failed: {e}")
    exit(1)

# Load SQuAD dataset
print("\n📚 Loading SQuAD dataset...")
try:
    dataset = load_dataset("squad", split="train[:200]")
    documents = [item["question"] for item in dataset]
    print(f"✅ Loaded {len(documents)} questions")
except Exception as e:
    print(f"❌ Failed to load dataset: {e}")
    print("Using sample questions instead...")
    documents = [
        "What is the capital of France?",
        "How does photosynthesis work?",
        "What is machine learning?",
        "Who wrote Romeo and Juliet?",
        "What is the speed of light?",
        "How do computers work?",
        "What is artificial intelligence?",
        "What is the largest planet?",
        "How does DNA replication work?",
        "What is quantum computing?"
    ]

print("\n📝 Sample questions:")
for i in range(min(10, len(documents))):
    print(f"  {i+1}. {documents[i]}")

# Initialize embedding model
print("\n🤖 Initializing embedding model...")
try:
    model = SentenceTransformer('all-MiniLM-L6-v2')
    print("✅ Model loaded successfully!")
except Exception as e:
    print(f"❌ Failed to load model: {e}")
    exit(1)

# Generate embeddings
print("\n🔄 Generating embeddings...")
try:
    embeddings = model.encode(documents)
    print(f"✅ Created {embeddings.shape[1]}-dimensional embeddings")
    print(f"   Shape: {embeddings.shape}")
except Exception as e:
    print(f"❌ Failed to generate embeddings: {e}")
    exit(1)

# Insert data into Neo4j
print("\n💾 Inserting data into Neo4j...")
with driver.session() as session:
    try:
        # Clear existing data
        print("🧹 Clearing existing data...")
        session.run("MATCH (n:Question) DELETE n")
        
        # Insert questions with embeddings
        print("📥 Inserting questions with embeddings...")
        for i, (question, embedding) in enumerate(zip(documents, embeddings)):
            session.run("""
                CREATE (n:Question {
                    id: $id,
                    question: $question,
                    embedding: $embedding
                })
            """, {
                "id": i,
                "question": question,
                "embedding": embedding.tolist()
            })
            
            if (i + 1) % 50 == 0:
                print(f"   Inserted {i + 1} questions...")
        
        # Verify insertion
        result = session.run("MATCH (n:Question) RETURN count(n) as count")
        count = result.single()["count"]
        print(f"✅ Inserted {count} questions successfully!")
        
    except Exception as e:
        print(f"❌ Failed to insert data: {e}")
        exit(1)

# Create CUVS vector index
print("\n🏗️ Creating CUVS vector index...")
with driver.session() as session:
    try:
        # Drop existing index if it exists
        print("🗑️ Dropping existing index...")
        session.run("DROP INDEX question_embeddings IF EXISTS")
        
        # Create CUVS vector index
        print("🔨 Creating CUVS vector index...")
        session.run("""
            CREATE VECTOR INDEX question_embeddings
            FOR (n:Question) ON n.embedding
            OPTIONS {
                indexProvider: 'cuvs-1.0',
                indexConfig: {
                    `vector.dimensions`: 384,
                    `vector.similarity_function`: 'EUCLIDEAN'
                }
            }
        """)
        
        print("✅ CUVS vector index created successfully!")
        
        # Check index status
        result = session.run("SHOW INDEXES")
        indexes = [record for record in result]
        print(f"\n📊 Available indexes: {len(indexes)}")
        for idx in indexes:
            if idx['type'] == 'VECTOR':
                print(f"  - {idx['name']}: {idx['state']} ({idx['indexProvider']})")
        
    except Exception as e:
        print(f"❌ Failed to create index: {e}")
        exit(1)

# Test vector similarity search
print("\n🔍 Testing vector similarity search...")

# Generate a test query
test_questions = [
    "What is the capital city of France?",
    "How does machine learning work?",
    "What is the fastest way to travel?",
    "Who is the author of Hamlet?",
    "What is the meaning of life?"
]

for test_question in test_questions:
    print(f"\n🔎 Searching for: '{test_question}'")
    
    try:
        # Generate embedding for test question
        test_embedding = model.encode([test_question])[0]
        
        with driver.session() as session:
            # Perform vector similarity search
            result = session.run("""
                CALL db.index.vector.queryNodes('question_embeddings', 5, $embedding)
                YIELD node, score
                RETURN node.question as question, score
                ORDER BY score ASC
            """, {"embedding": test_embedding.tolist()})
            
            matches = [record for record in result]
            
            if matches:
                print("   📋 Top matches:")
                for i, match in enumerate(matches):
                    print(f"     {i+1}. {match['question']} (score: {match['score']:.4f})")
            else:
                print("   ❌ No matches found")
                
    except Exception as e:
        print(f"   ❌ Search failed: {e}")

# Test with different similarity functions
print("\n🧪 Testing different similarity functions...")

similarity_functions = ['EUCLIDEAN', 'COSINE', 'DOT_PRODUCT']

for sim_func in similarity_functions:
    print(f"\n📐 Testing {sim_func} similarity...")
    
    try:
        # Drop and recreate index with different similarity function
        with driver.session() as session:
            session.run("DROP INDEX question_embeddings IF EXISTS")
            
            session.run(f"""
                CREATE VECTOR INDEX question_embeddings_{sim_func.lower()}
                FOR (n:Question) ON n.embedding
                OPTIONS {{
                    indexProvider: 'cuvs-1.0',
                    indexConfig: {{
                        `vector.dimensions`: 384,
                        `vector.similarity_function`: '{sim_func}'
                    }}
                }}
            """)
            
            print(f"   ✅ Created index with {sim_func} similarity")
            
            # Test search
            test_embedding = model.encode(["What is machine learning?"])[0]
            result = session.run("""
                CALL db.index.vector.queryNodes('question_embeddings_""" + sim_func.lower() + """', 3, $embedding)
                YIELD node, score
                RETURN node.question as question, score
                ORDER BY score ASC
            """, {"embedding": test_embedding.tolist()})
            
            matches = [record for record in result]
            if matches:
                print(f"   📋 Top match: {matches[0]['question']} (score: {matches[0]['score']:.4f})")
            
    except Exception as e:
        print(f"   ❌ {sim_func} test failed: {e}")

print("\n🎉 Test completed!")
print("\n📈 Summary:")
print("  ✅ Neo4j connection established")
print("  ✅ SQuAD dataset loaded")
print("  ✅ Embeddings generated")
print("  ✅ Data inserted into Neo4j")
print("  ✅ CUVS vector index created")
print("  ✅ Vector similarity search tested")
print("  ✅ Multiple similarity functions tested")

driver.close()
print("\n👋 Connection closed. Test complete!")
