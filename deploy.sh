#!/bin/bash

# Exit on any error
set -e

echo "=== Deploying externalOccurrences-api ==="

# Source centralized environment variables
CENTRAL_ENV_FILE="/home/prakhar/workspace/gocd/.env"
if [ -f "$CENTRAL_ENV_FILE" ]; then
    echo "Loading environment variables from $CENTRAL_ENV_FILE"
    source "$CENTRAL_ENV_FILE"
else
    echo "Error: Central environment file not found at $CENTRAL_ENV_FILE"
    exit 1
fi

# Navigate to project directory
cd /home/prakhar/workspace/biodiv-externalOccurrences

# Run build script
echo "Building project..."
sh @ci/build-and-deploy.sh

# Check if build was successful
if [ $? -eq 0 ]; then
    echo "Build successful!"

    # Deploy to Tomcat
    echo "Deploying to Tomcat..."
    curl --fail --upload-file target/externalOccurrences-api.war \
      "http://${TOMCAT_USERNAME}:${TOMCAT_PASSWORD}@${TOMCAT_HOST}/manager/text/deploy?path=/externalOccurrences-api&update=true"

    if [ $? -eq 0 ]; then
        echo "=== Deployment completed successfully! ==="
    else
        echo "=== Deployment failed! ==="
        exit 1
    fi
else
    echo "=== Build failed! ==="
    exit 1
fi
