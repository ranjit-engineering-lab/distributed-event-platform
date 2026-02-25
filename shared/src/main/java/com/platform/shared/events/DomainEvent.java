package com.platform.shared.events;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.InstantDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.InstantSerializer;
import lombok.Getter;
import lombok.ToString;

import java.time.Instant;
import java.util.UUID;

/**
 * Base CloudEvent following the CloudEvents specification v1.0.
 * https://cloudevents.io/
 *
 * All domain events extend this class. Every event carries:
 *  - id:            Globally unique event identifier (UUID v4)
 *  - type:          Hierarchical dot-notation name, e.g. "orders.created"
 *  - source:        Originating service URI, e.g. "/services/order-service"
 *  - time:          ISO 8601 timestamp of when the event occurred
 *  - correlationId: Ties all events in a saga together
 *  - causationId:   The event that caused this event (parent event ID)
 *  - version:       Schema version for forward compatibility
 */
@Getter
@ToString
public abstract class DomainEvent {

    private final String id;
    private final String type;
    private final String source;

    @JsonSerialize(using = InstantSerializer.class)
    @JsonDeserialize(using = InstantDeserializer.class)
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private final Instant time;

    private final String correlationId;
    private final String causationId;
    private final int version;
    private final String specversion = "1.0";
    private final String datacontenttype = "application/json";

    protected DomainEvent(String type, String source, String correlationId, String causationId, int version) {
        this.id = UUID.randomUUID().toString();
        this.type = type;
        this.source = source;
        this.time = Instant.now();
        this.correlationId = correlationId != null ? correlationId : UUID.randomUUID().toString();
        this.causationId = causationId;
        this.version = version;
    }

    protected DomainEvent(String type, String source, String correlationId) {
        this(type, source, correlationId, null, 1);
    }
}
