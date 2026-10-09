package dev.tip.ai;

import dev.tip.tipping.api.TipCaptured;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Classifies a tip once its capture is committed, on the AI executor so no request waits on Claude. */
@Component
class TipCapturedListener {

    private final TipReactionWorker worker;

    TipCapturedListener(TipReactionWorker worker) {
        this.worker = worker;
    }

    @Async(AiConfig.EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(TipCaptured event) {
        worker.process(event.tipId());
    }
}
