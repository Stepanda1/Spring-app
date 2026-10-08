package org.springframework.amqp.tutorials.rabbitmq_amqp_tutorials.notifications;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MessagingConfig {
    public static final String EXCHANGE = "notifications";
    public static final String QUEUE = "notifications.send";
    public static final String DEAD_QUEUE = "notifications.dead";

    @Bean
    Declarables topology() {
        DirectExchange exchange = new DirectExchange(EXCHANGE);
        DirectExchange dead = new DirectExchange("notifications.dlx");
        Queue queue = QueueBuilder.durable(QUEUE).deadLetterExchange(dead.getName())
                .deadLetterRoutingKey("failed").build();
        Queue deadQueue = QueueBuilder.durable(DEAD_QUEUE).build();
        return new Declarables(exchange, dead, queue, deadQueue,
                BindingBuilder.bind(queue).to(exchange).with("send"),
                BindingBuilder.bind(deadQueue).to(dead).with("failed"));
    }
}
