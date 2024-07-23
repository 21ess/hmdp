package com.hmdp.config;

import com.hmdp.utils.SystemConstants;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class MqConfig {
    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public Queue OrderSaveQueue() {
        return new Queue(SystemConstants.SECKILL_VOUCHER_SAVE_QUEUE);
    }
}
