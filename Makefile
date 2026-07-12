# SentinelPay developer task runner. Uses the Maven wrapper (./mvnw).
.PHONY: build test verify up down clean run-gateway run-payment run-risk run-provider run-notification

build:      ## Compile all modules (skip tests)
	./mvnw -q -DskipTests verify

test:       ## Run all tests (requires Docker for Testcontainers)
	./mvnw -q test

verify:     ## Full build + tests
	./mvnw -q verify

up:         ## Start local infrastructure (Postgres, Redis, Kafka, MailHog)
	docker compose up -d

down:       ## Stop local infrastructure
	docker compose down

clean:      ## Remove build output
	./mvnw -q clean

run-gateway:        ; ./mvnw -q -pl api-gateway spring-boot:run
run-payment:        ; ./mvnw -q -pl payment-service spring-boot:run
run-risk:           ; ./mvnw -q -pl risk-service spring-boot:run
run-provider:       ; ./mvnw -q -pl provider-service spring-boot:run
run-notification:   ; ./mvnw -q -pl notification-service spring-boot:run
