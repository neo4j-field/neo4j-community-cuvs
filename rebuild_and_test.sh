#!/bin/bash

# Script to rebuild CUVS index provider and test it
set -e

echo "🔄 Rebuilding CUVS index provider and testing..."

# Stop Neo4j if it's running
echo "🛑 Stopping Neo4j..."
if [ -d "neo4j-community-5.26.0" ]; then
    cd neo4j-community-5.26.0
    ./bin/neo4j stop || true
    cd ..
fi

# Clean and rebuild the CUVS module and packaging
echo "🔨 Building CUVS module and packaging..."
export MAVEN_OPTS="-Xmx2048m"
mvn clean package -pl community/cuvs-index,packaging/standalone/standalone-community -am -DskipTests -T1C -q

# Remove old distribution and extract new one
echo "📦 Extracting new distribution..."
rm -rf neo4j-community-5.26.0
tar -xzf packaging/standalone/target/neo4j-community-5.26.0-unix.tar.gz

# Start Neo4j
echo "🚀 Starting Neo4j..."
cd neo4j-community-5.26.0
export JAVA_HOME=/Users/alexfournier/.jenv/versions/22
./bin/neo4j start

# Wait for Neo4j to start
echo "⏳ Waiting for Neo4j to start..."
sleep 10

# Test the connection and create CUVS index
echo "🧪 Testing CUVS index creation..."
cd ..
python3 test_cuvs_index.py

echo "✅ Rebuild and test complete!"
