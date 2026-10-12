package dev.tip.tools;

import java.io.PrintStream;
import java.time.Duration;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;

/**
 * Plays reactions one after another, like the LED on the panda: a reaction that arrives while another
 * plays waits in a queue of 8, and the oldest waiting one is dropped when the queue is full; the one playing
 * has already left the queue, so it is never dropped. Acks are not sent from here: the device acks on
 * receipt, before playback.
 */
final class Player implements Runnable {

    static final int QUEUE_SIZE = 8;

    record Reaction(String tipId, LedPattern pattern, int repeats) {}

    private final ArrayDeque<Reaction> queue = new ArrayDeque<>();
    private final PrintStream out;

    Player(PrintStream out) {
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
        int repeats = reaction.repeats();
        Duration length = pattern.length(repeats);
        out.printf(
                "%s  %-8s  %-13s  repeats %d  %s  (%.2f s)  tip %s%n",
                LocalTime.now().truncatedTo(ChronoUnit.SECONDS),
                pattern.name(),
                pattern.label + " x" + pattern.units(repeats),
                repeats,
                pattern.picture(repeats),
                length.toMillis() / 1000.0,
                reaction.tipId());
        Thread.sleep(length);
    }
}
