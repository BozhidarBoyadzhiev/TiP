package dev.tip.dashboard;

import java.util.List;

/** One page of the owner's tips. {@code totalItems} counts every tip matching the filters. */
record TipPage(List<TipView> items, int page, int size, long totalItems) {

    TipPage {
        items = List.copyOf(items);
    }
}
