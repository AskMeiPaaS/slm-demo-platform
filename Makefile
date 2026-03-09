# --- Variables ---
PROJECT_NAME := slm-demo-platform
MODEL_NAME := llama3.1:8b

# --- Make Directives ---
.PHONY: help build up down restart logs pull-model clean nuke

help:
	@echo "======================================================="
	@echo "🤖 $(PROJECT_NAME) - Developer Commands"
	@echo "======================================================="
	@echo "make build       - Build all Docker images"
	@echo "make up          - Start the platform in the background"
	@echo "make down        - Stop and remove containers"
	@echo "make pull-model  - Manually pull $(MODEL_NAME) into Ollama Engine"
	@echo "make logs        - Tail logs for all services"
	@echo "make clean       - Remove containers and unused volumes"
	@echo "make nuke        - WARNING: Wipe EVERYTHING including DB!"
	@echo "======================================================="

build:
	docker-compose build

up:
	docker-compose up -d
	@echo "Platform is booting up. Frontend will be at http://localhost:3000"

down:
	docker-compose down

restart: down up

logs:
	docker-compose logs -f

pull-model:
	@echo "Waiting for Ollama container to be ready..."
	@sleep 5
	@echo "Pulling the $(MODEL_NAME) model. This might take a minute..."
	docker exec -it ollama ollama run $(MODEL_NAME)

clean:
	docker-compose down -v --remove-orphans

nuke: clean
	docker system prune -a --volumes -f
	@echo "Everything wiped."
