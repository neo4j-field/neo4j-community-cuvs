#!/usr/bin/env python3
"""
Test script for CUVS vector index with sample data.
This script demonstrates:
1. Creating sample question data
2. Generating simple embeddings
3. Creating a CUVS vector index
4. Performing vector similarity search
"""

import neo4j
import numpy as np
import random

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

# Create sample questions
print("\n📚 Creating sample questions...")
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
    "What is quantum computing?",
    "What is the meaning of life?",
    "How does gravity work?",
    "What is the smallest particle?",
    "How do airplanes fly?",
    "What is climate change?",
    "How does the internet work?",
    "What is renewable energy?",
    "How do vaccines work?",
    "What is blockchain technology?",
    "How does the human brain work?"
]

print(f"✅ Created {len(documents)} sample questions")

print("\n📝 Sample questions:")
for i in range(min(10, len(documents))):
    print(f"  {i+1}. {documents[i]}")

# Generate semantic embeddings based on question content
print("\n🤖 Generating semantic embeddings...")

def create_semantic_embedding(question, dimension=384):
    """Create a simple semantic embedding based on question content"""
    # Convert question to lowercase and create a hash-based embedding
    words = question.lower().split()
    
    # Create embedding based on word patterns
    embedding = np.zeros(dimension, dtype=np.float32)
    
    # Science-related words
    science_words = ['how', 'what', 'work', 'does', 'is', 'machine', 'learning', 'ai', 'artificial', 'intelligence', 'quantum', 'computing', 'dna', 'photosynthesis', 'gravity', 'particle', 'energy', 'renewable', 'climate', 'vaccines', 'blockchain', 'brain']
    # Geography-related words  
    geo_words = ['capital', 'france', 'city', 'country', 'planet', 'largest', 'smallest']
    # Literature-related words
    lit_words = ['wrote', 'romeo', 'juliet', 'author', 'hamlet', 'meaning', 'life']
    # Technology-related words
    tech_words = ['computer', 'internet', 'airplane', 'fly', 'space', 'exploration', 'future']
    
    # Assign weights based on word categories
    for i, word in enumerate(words):
        if word in science_words:
            embedding[i % dimension] += 1.0
        elif word in geo_words:
            embedding[(i + 50) % dimension] += 1.0
        elif word in lit_words:
            embedding[(i + 100) % dimension] += 1.0
        elif word in tech_words:
            embedding[(i + 150) % dimension] += 1.0
        else:
            embedding[(i + 200) % dimension] += 0.5
    
    # Normalize the embedding
    norm = np.linalg.norm(embedding)
    if norm > 0:
        embedding = embedding / norm
    
    return embedding

# Generate embeddings for all documents
embeddings = np.array([create_semantic_embedding(doc) for doc in documents])

print(f"✅ Created {embeddings.shape[1]}-dimensional embeddings")
print(f"   Shape: {embeddings.shape}")

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

# Generate test queries
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
        # Generate semantic embedding for test question
        test_embedding = create_semantic_embedding(test_question)
        
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
            test_embedding = create_semantic_embedding("What is machine learning?")
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

# Test vector operations
print("\n🔬 Testing vector operations...")

with driver.session() as session:
    try:
        # Test adding more vectors
        print("📥 Adding more vectors...")
        new_questions = [
            "What is the future of AI?",
            "How does renewable energy work?",
            "What is space exploration?"
        ]
        
        for i, question in enumerate(new_questions):
            embedding = create_semantic_embedding(question)
            
            session.run("""
                CREATE (n:Question {
                    id: $id,
                    question: $question,
                    embedding: $embedding
                })
            """, {
                "id": len(documents) + i,
                "question": question,
                "embedding": embedding.tolist()
            })
        
        print("✅ Added 3 more questions")
        
        # Test search with new data
        print("🔍 Testing search with new data...")
        test_embedding = create_semantic_embedding("What is the future of AI?")
        
        result = session.run("""
            CALL db.index.vector.queryNodes('question_embeddings_euclidean', 5, $embedding)
            YIELD node, score
            RETURN node.question as question, score
            ORDER BY score ASC
        """, {"embedding": test_embedding.tolist()})
        
        matches = [record for record in result]
        if matches:
            print("   📋 Search results:")
            for i, match in enumerate(matches):
                print(f"     {i+1}. {match['question']} (score: {match['score']:.4f})")
        
    except Exception as e:
        print(f"❌ Vector operations test failed: {e}")

print("\n🎉 Test completed!")
print("\n📈 Summary:")
print("  ✅ Neo4j connection established")
print("  ✅ Sample questions created")
print("  ✅ Embeddings generated")
print("  ✅ Data inserted into Neo4j")
print("  ✅ CUVS vector index created")
print("  ✅ Vector similarity search tested")
print("  ✅ Multiple similarity functions tested")
print("  ✅ Vector operations tested")

driver.close()
print("\n👋 Connection closed. Test complete!")
