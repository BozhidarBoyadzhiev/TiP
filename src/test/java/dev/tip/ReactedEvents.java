package dev.tip;

import dev.tip.ai.api.TipReacted;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Records every committed {@link TipReacted}, the way the device feature will see them. */
@TestComponent
public class ReactedEvents {

    private final ConcurrentLinkedQueue<TipReacted> events = new ConcurrentLinkedQueue<>();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(TipReacted event) {
        events.add(event);
    }

    public List<TipReacted> forTip(UUID tipId) {
        return events.stream().filter(e -> e.tipId().equals(tipId)).toList();
    }
}
