//package com.platform.order;
//
//import com.fasterxml.jackson.databind.ObjectMapper;
//import com.platform.order.domain.Order;
//import com.platform.order.domain.OrderCommandService;
//import com.platform.order.domain.OrderEventStore;
//import com.platform.order.domain.OrderQueryService;
//import com.platform.shared.events.Events.*;
//import com.platform.shared.saga.OrderSagaOrchestrator;
//import com.platform.shared.saga.SagaState;
//import com.platform.shared.saga.SagaStateStore;
//import org.apache.kafka.clients.consumer.Consumer;
//import org.apache.kafka.clients.consumer.ConsumerRecord;
//import org.apache.kafka.clients.consumer.KafkaConsumer;
//import org.junit.jupiter.api.*;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
//import org.springframework.boot.test.context.SpringBootTest;
//import org.springframework.http.MediaType;
//import org.springframework.kafka.test.context.EmbeddedKafka;
//import org.springframework.test.context.DynamicPropertyRegistry;
//import org.springframework.test.context.DynamicPropertySource;
//import org.springframework.test.web.servlet.MockMvc;
//import org.testcontainers.containers.KafkaContainer;
//import org.testcontainers.containers.PostgreSQLContainer;
//import org.testcontainers.containers.GenericContainer;
//import org.testcontainers.junit.jupiter.Container;
//import org.testcontainers.junit.jupiter.Testcontainers;
//import org.testcontainers.utility.DockerImageName;
//
//import java.math.BigDecimal;
//import java.time.Duration;
//import java.util.List;
//import java.util.Map;
//import java.util.concurrent.TimeUnit;
//
//import static org.assertj.core.api.Assertions.*;
//import static org.awaitility.Awaitility.await;
//import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
//import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
//
///**
// * Integration Tests — Full Order Saga Flow
// *
// * Uses Testcontainers for real Kafka, PostgreSQL, and Redis instances.
// * Tests the complete end-to-end saga lifecycle.
// *
// * Run: mvn test -pl order-service -Dtest=OrderSagaIntegrationTest
// *
// * Note: These tests require Docker to be running.
// */
//@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
//@AutoConfigureMockMvc
//@Testcontainers
//@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
//class OrderSagaIntegrationTest {
//
//    // ─── Testcontainers ───────────────────────────────────────────────────────
//
//    @Container
//    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
//            .withDatabaseName("platform")
//            .withUsername("platform")
//            .withPassword("platform_secret");
//
//    @Container
//    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));
//
//    @Container
//    @SuppressWarnings("resource")
//    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
//            .withExposedPorts(6379);
//
//    @DynamicPropertySource
//    static void configureProperties(DynamicPropertyRegistry registry) {
//        registry.add("spring.datasource.url", postgres::getJdbcUrl);
//        registry.add("spring.datasource.username", postgres::getUsername);
//        registry.add("spring.datasource.password", postgres::getPassword);
//        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
//        registry.add("spring.data.redis.host", redis::getHost);
//        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
//    }
//
//    // ─── Autowired Beans ──────────────────────────────────────────────────────
//
//    @Autowired MockMvc mockMvc;
//    @Autowired ObjectMapper objectMapper;
//    @Autowired OrderCommandService commandService;
//    @Autowired OrderQueryService queryService;
//    @Autowired OrderEventStore eventStore;
//    @Autowired OrderSagaOrchestrator sagaOrchestrator;
//    @Autowired SagaStateStore sagaStateStore;
//
//    // ─── Happy Path Tests ─────────────────────────────────────────────────────
//
//    @Test
//    @org.junit.jupiter.api.Order(1)
//    @DisplayName("POST /api/orders — creates order and returns 201 with saga link")
//    void createOrder_shouldReturn201() throws Exception {
//        String requestBody = """
//            {
//                "customerId": "cust_test_001",
//                "items": [
//                    {"productId": "prod_laptop_001", "quantity": 1, "unitPrice": 999.99}
//                ],
//                "paymentMethod": "CREDIT_CARD",
//                "shippingAddress": {
//                    "street": "123 Test St",
//                    "city": "Test City",
//                    "country": "US",
//                    "postalCode": "10001"
//                }
//            }
//            """;
//
//        String response = mockMvc.perform(post("/api/orders")
//                        .contentType(MediaType.APPLICATION_JSON)
//                        .content(requestBody))
//                .andExpect(status().isCreated())
//                .andExpect(jsonPath("$.orderId").exists())
//                .andExpect(jsonPath("$.status").value("PENDING"))
//                .andExpect(jsonPath("$.correlationId").exists())
//                .andExpect(jsonPath("$._links.saga").exists())
//                .andReturn()
//                .getResponse()
//                .getContentAsString();
//
//        Map<?, ?> body = objectMapper.readValue(response, Map.class);
//        String orderId = (String) body.get("orderId");
//
//        assertThat(orderId).startsWith("ord_");
//
//        // Verify order is in DB
//        assertThat(queryService.getOrder(orderId)).isPresent();
//    }
//
//    @Test
//    @org.junit.jupiter.api.Order(2)
//    @DisplayName("CQRS — GET /api/orders/{id} served from Redis read model")
//    void getOrder_shouldServeFromReadModel() throws Exception {
//        // Create an order first
//        CreateOrderCommand cmd = buildCreateOrderCommand();
//        Order order = commandService.createOrder(cmd);
//
//        // Brief pause for async write to propagate to Redis
//        Thread.sleep(100);
//
//        mockMvc.perform(get("/api/orders/" + order.getId()))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.orderId").value(order.getId()))
//                .andExpect(jsonPath("$._source").value("read-model"));
//    }
//
//    @Test
//    @org.junit.jupiter.api.Order(3)
//    @DisplayName("Event Store — GET /api/orders/{id}/events returns audit trail")
//    void getOrderEvents_shouldReturnEventHistory() throws Exception {
//        CreateOrderCommand cmd = buildCreateOrderCommand();
//        Order order = commandService.createOrder(cmd);
//
//        // The order creation should have appended an ORDER_CREATED event
//        mockMvc.perform(get("/api/orders/" + order.getId() + "/events"))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.events").isArray())
//                .andExpect(jsonPath("$.events[0].type").value("orders.created"))
//                .andExpect(jsonPath("$.count").value(1));
//    }
//
//    // ─── Saga State Transition Tests ──────────────────────────────────────────
//
//    @Test
//    @org.junit.jupiter.api.Order(4)
//    @DisplayName("Saga — INVENTORY_RESERVED advances saga to payment step")
//    void saga_inventoryReserved_shouldAdvanceToPayment() {
//        OrderCreatedEvent orderEvent = buildOrderCreatedEvent();
//        sagaOrchestrator.startSaga(orderEvent);
//
//        // Verify saga is started
//        var stateOpt = sagaStateStore.load(orderEvent.getCorrelationId());
//        assertThat(stateOpt).isPresent();
//        assertThat(stateOpt.get().getStatus()).isEqualTo(SagaState.Status.RESERVING_INVENTORY);
//
//        // Simulate inventory service response
//        InventoryReservedEvent inventoryEvent = new InventoryReservedEvent(
//                orderEvent.getOrderId(),
//                orderEvent.getItems(),
//                orderEvent.getCorrelationId(),
//                orderEvent.getId()
//        );
//        sagaOrchestrator.onInventoryReserved(inventoryEvent);
//
//        // Verify saga advanced to payment step
//        stateOpt = sagaStateStore.load(orderEvent.getCorrelationId());
//        assertThat(stateOpt).isPresent();
//        assertThat(stateOpt.get().getStatus()).isEqualTo(SagaState.Status.PROCESSING_PAYMENT);
//        assertThat(stateOpt.get().getCompletedSteps()).contains("RESERVE_INVENTORY");
//    }
//
//    @Test
//    @org.junit.jupiter.api.Order(5)
//    @DisplayName("Saga Compensation — INVENTORY_RESERVATION_FAILED triggers full compensation")
//    void saga_inventoryFailed_shouldCompensate() {
//        OrderCreatedEvent orderEvent = buildOrderCreatedEvent();
//        sagaOrchestrator.startSaga(orderEvent);
//
//        InventoryReservationFailedEvent failedEvent = new InventoryReservationFailedEvent(
//                orderEvent.getOrderId(),
//                "Out of stock",
//                List.of("prod_laptop_001"),
//                orderEvent.getCorrelationId(),
//                orderEvent.getId()
//        );
//        sagaOrchestrator.onInventoryReservationFailed(failedEvent);
//
//        // Verify saga is compensated
//        var stateOpt = sagaStateStore.load(orderEvent.getCorrelationId());
//        assertThat(stateOpt).isPresent();
//        assertThat(stateOpt.get().getStatus()).isEqualTo(SagaState.Status.COMPENSATED);
//        assertThat(stateOpt.get().getFailureReason()).contains("Out of stock");
//    }
//
//    @Test
//    @org.junit.jupiter.api.Order(6)
//    @DisplayName("Saga Compensation — PAYMENT_FAILED after INVENTORY_RESERVED includes refund")
//    void saga_paymentFailed_shouldReleaseInventoryAndRefund() {
//        OrderCreatedEvent orderEvent = buildOrderCreatedEvent();
//        sagaOrchestrator.startSaga(orderEvent);
//
//        // Inventory succeeds
//        sagaOrchestrator.onInventoryReserved(new InventoryReservedEvent(
//                orderEvent.getOrderId(), orderEvent.getItems(),
//                orderEvent.getCorrelationId(), orderEvent.getId()));
//
//        // Payment fails
//        sagaOrchestrator.onPaymentFailed(new PaymentFailedEvent(
//                orderEvent.getOrderId(), "Insufficient funds",
//                orderEvent.getCorrelationId(), "evt_pay_fail"));
//
//        var state = sagaStateStore.load(orderEvent.getCorrelationId()).orElseThrow();
//        assertThat(state.getStatus()).isEqualTo(SagaState.Status.COMPENSATED);
//        // Completed steps before failure = [RESERVE_INVENTORY] → compensation releases inventory
//        assertThat(state.getCompletedSteps()).contains("RESERVE_INVENTORY");
//    }
//
//    @Test
//    @org.junit.jupiter.api.Order(7)
//    @DisplayName("Idempotency — duplicate events are skipped gracefully")
//    void saga_duplicateEvent_shouldBeSkipped() {
//        OrderCreatedEvent orderEvent = buildOrderCreatedEvent();
//        sagaOrchestrator.startSaga(orderEvent);
//
//        InventoryReservedEvent inventoryEvent = new InventoryReservedEvent(
//                orderEvent.getOrderId(), orderEvent.getItems(),
//                orderEvent.getCorrelationId(), orderEvent.getId());
//
//        // Process twice
//        sagaOrchestrator.onInventoryReserved(inventoryEvent);
//        sagaOrchestrator.onInventoryReserved(inventoryEvent); // Duplicate — should be no-op
//
//        // State should be at PROCESSING_PAYMENT (advanced once only)
//        var state = sagaStateStore.load(orderEvent.getCorrelationId()).orElseThrow();
//        assertThat(state.getStatus()).isEqualTo(SagaState.Status.PROCESSING_PAYMENT);
//    }
//
//    @Test
//    @org.junit.jupiter.api.Order(8)
//    @DisplayName("Validation — POST /api/orders with missing fields returns 400")
//    void createOrder_withMissingFields_shouldReturn400() throws Exception {
//        mockMvc.perform(post("/api/orders")
//                        .contentType(MediaType.APPLICATION_JSON)
//                        .content("{\"customerId\": \"cust_test\"}")) // missing items, paymentMethod
//                .andExpect(status().isBadRequest());
//    }
//
//    @Test
//    @org.junit.jupiter.api.Order(9)
//    @DisplayName("Event Sourcing — replay events reconstructs order state")
//    void eventSourcing_replayShouldReconstructState() {
//        CreateOrderCommand cmd = buildCreateOrderCommand();
//        Order order = commandService.createOrder(cmd);
//
//        Map<String, Object> reconstructed = eventStore.replayToCurrentState(order.getId());
//        assertThat(reconstructed).isNotEmpty();
//        assertThat(reconstructed.get("orderId")).isEqualTo(order.getId());
//        assertThat(reconstructed.get("status")).isEqualTo("PENDING");
//    }
//
//    // ─── Helpers ──────────────────────────────────────────────────────────────
//
//    private CreateOrderCommand buildCreateOrderCommand() {
//        return CreateOrderCommand.builder()
//                .customerId("cust_test_" + System.currentTimeMillis())
//                .items(List.of(new OrderItem("prod_mouse_001", 1, new BigDecimal("29.99"))))
//                .paymentMethod("CREDIT_CARD")
//                .shippingAddress(new ShippingAddress("123 Test St", "NY", "US", "10001"))
//                .build();
//    }
//
//    private OrderCreatedEvent buildOrderCreatedEvent() {
//        return new OrderCreatedEvent(
//                "ord_test_" + System.currentTimeMillis(),
//                "cust_test_001",
//                List.of(new OrderItem("prod_laptop_001", 1, new BigDecimal("999.99"))),
//                new BigDecimal("999.99"), "USD", "CREDIT_CARD",
//                new ShippingAddress("123 Test St", "NY", "US", "10001"),
//                java.util.UUID.randomUUID().toString()
//        );
//    }
//}
