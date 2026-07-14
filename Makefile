# SentinelPay developer task runner. Uses the Maven wrapper (./mvnw).
.PHONY: build test verify up up-infra down demo clean run-gateway run-payment run-risk run-provider run-notification

INFRA_SERVICES := postgres-payments postgres-risk postgres-provider postgres-notifications redis zookeeper kafka mailhog prometheus tempo grafana

build:      ## Compile all modules (skip tests)
	./mvnw -q -DskipTests verify

test:       ## Run all tests (requires Docker for Testcontainers)
	./mvnw -q test

verify:     ## Full build + tests
	./mvnw -q verify

up:         ## Start full system (infra + all five services)
	docker compose up -d --build

up-infra:   ## Start infra/observability only (services run from IDE/Maven)
	docker compose up -d $(INFRA_SERVICES)

down:       ## Stop all containers
	docker compose down

demo:       ## Run the narrated three-act demo (full stack must be up)
	bash scripts/demo.sh

clean:      ## Remove build output
	./mvnw -q clean

run-gateway:        ; ./mvnw -q -pl api-gateway -am spring-boot:run -Dspring-boot.run.profiles=dev
run-payment:        ; ./mvnw -q -pl payment-service -am spring-boot:run -Dspring-boot.run.profiles=dev
run-risk:           ; ./mvnw -q -pl risk-service -am spring-boot:run
run-provider:       ; ./mvnw -q -pl provider-service -am spring-boot:run
run-notification:   ; ./mvnw -q -pl notification-service -am spring-boot:run
