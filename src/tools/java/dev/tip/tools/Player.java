package dev.tip.tools;

import java.io.PrintStream;
import java.time.Duration;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.function.Consumer;

/**
 * Plays reactions one after another, like the LED on the panda: a reaction that arrives while another
 * plays waits in a queue of 4, and the oldest waiting one is dropped when the queue is full. The ack is
 * sent when a reaction starts playing.
 */
final class Player implements Runnable {

    static final int QUEUE_SIZE = 4;

    record Reaction(String tipId, LedPattern pattern) {}

    private final ArrayDeque<Reaction> queue = new ArrayDeque<>();
    private final Consumer<String> ack;
    private final PrintStream out;

    Player(Consumer<String> ack, PrintStream out) {
        this.ack = ack;
        this.out = out;
    }

    /** Queues the reaction and returns the one dropped to make room, or null. */
    synchronized Reaction enqueue(Reaction reaction) {
        Reaction dropped = queue.size() == QUEUE_SIZE ? queue.removeFirst() : null;
        queue.addLast(reaction);
        notifyAll();
        return dropped;
    }

    synchronized int waiting() {
        return queue.size();
    }

    @Override
    public void run() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                play(take());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private synchronized Reaction take() throws InterruptedException {
        while (queue.isEmpty()) {
            wait();
        }
        return queue.removeFirst();
    }

    private void play(Reaction reaction) throws InterruptedException {
        LedPattern pattern = reaction.pattern();
        Duration length = pattern.length();
        out.printf(
                "%s  %-8s  %-20s  %s  (%.2f s)  tip %s%n",
                LocalTime.now().truncatedTo(ChronoUnit.SECONDS),
                pattern.name(),
                pattern.label,
                pattern.picture,
                length.toMillis() / 1000.0,
                reaction.tipId());
        ack.accept(reaction.tipId());
        Thread.sleep(length);
    }
}
