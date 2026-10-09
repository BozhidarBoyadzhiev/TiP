package dev.tip.tools;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/** The last tip ids the device has seen, oldest forgotten first, so a resent reaction plays only once. */
final class RecentIds {

    static final int CAPACITY = 16;

    private final ArrayDeque<String> order = new ArrayDeque<>();
    private final Set<String> ids = new HashSet<>();

    /** Remembers the id and returns true when it was not among the recent ones. */
    synchronized boolean add(String id) {
        if (ids.contains(id)) {
            return false;
        }
        if (order.size() == CAPACITY) {
            ids.remove(order.removeFirst());
        }
        order.addLast(id);
        ids.add(id);
        return true;
    }
}
