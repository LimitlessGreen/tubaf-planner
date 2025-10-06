#!/bin/bash

# TUBAF Planner - Development Server Start Script
# Starts the application with:
# - Dev profile (port 8085)
# - Auto-reload on code changes
# - Debug logging
# - Visible console output

set -e

# Change to backend directory
cd "$(dirname "$0")"

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  TUBAF Planner - Development Server${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

# Check if port 8085 is already in use
if lsof -Pi :8085 -sTCP:LISTEN -t >/dev/null 2>&1 ; then
    echo -e "${YELLOW}⚠️  Port 8085 is already in use!${NC}"
    echo -e "${YELLOW}Existing process:${NC}"
    lsof -Pi :8085 -sTCP:LISTEN
    echo ""
    read -p "Kill existing process? (y/N) " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        echo -e "${YELLOW}Killing process on port 8085...${NC}"
        kill -9 $(lsof -t -i:8085) 2>/dev/null || true
        sleep 2
    else
        echo -e "${YELLOW}Exiting. Please stop the other process first.${NC}"
        exit 1
    fi
fi

echo -e "${GREEN}✓ Port 8085 is available${NC}"
echo ""

# Check for Gradle wrapper
if [ ! -f "./gradlew" ]; then
    echo -e "${YELLOW}⚠️  Gradle wrapper not found. Installing...${NC}"
    gradle wrapper
fi

echo -e "${BLUE}Starting application...${NC}"
echo -e "${GREEN}Profile:${NC} dev"
echo -e "${GREEN}Port:${NC} 8085"
echo -e "${GREEN}Auto-reload:${NC} enabled"
echo -e "${GREEN}Logging:${NC} DEBUG"
echo ""
echo -e "${BLUE}----------------------------------------${NC}"
echo -e "${YELLOW}Press Ctrl+C to stop the server${NC}"
echo -e "${BLUE}----------------------------------------${NC}"
echo ""

# Start the application with bootRun
# - spring-boot-devtools enables auto-reload
# - --continuous flag watches for changes (optional, works with devtools)
# - spring.profiles.active=dev uses dev profile
# - server.port=8085 sets the port
./gradlew bootRun \
    --args='--spring.profiles.active=dev --server.port=8085' \
    --console=plain \
    2>&1 | tee dev-server.log

# Note: The log is also saved to dev-server.log for later inspection
