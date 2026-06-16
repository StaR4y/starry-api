package tech.starryapi.payment;

import java.time.Instant;
import org.reactivestreams.Publisher;
import org.springframework.data.r2dbc.mapping.event.BeforeConvertCallback;
import org.springframework.data.relational.core.sql.SqlIdentifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class PaymentOrderTimestampCallback implements BeforeConvertCallback<PaymentOrderEntity> {

    @Override
    public Publisher<PaymentOrderEntity> onBeforeConvert(PaymentOrderEntity order, SqlIdentifier table) {
        Instant now = Instant.now();
        if (order.getCreatedAt() == null) {
            order.setCreatedAt(now);
        }
        order.setUpdatedAt(now);
        return Mono.just(order);
    }
}
