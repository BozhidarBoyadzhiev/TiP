package dev.tip;

import dev.tip.tipping.api.TipCaptured;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Records every committed {@link TipCaptured}, the way a real listener in another feature sees them. */
@TestComponent
public class CapturedEvents {

    private final ConcurrentLinkedQueue<TipCaptured> events = new ConcurrentLinkedQueue<>();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(TipCaptured event) {
        events.add(event);
    }

    public List<TipCaptured> forTip(UUID tipId) {
        return events.stream().filter(e -> e.tipId().equals(tipId)).toList();
    }
}
