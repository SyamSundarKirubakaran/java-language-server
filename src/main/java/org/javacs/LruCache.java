package org.javacs;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Function;

class LruCache<K, V> {
    private final ArrayBlockingQueue<K> fifo;
    private final Map<K, V> map;
    private final Function<K, V> loader;

    // TODO this reference is not resolving
    LruCache(int capacity, Function<K, V> loader) {
        this.fifo = new ArrayBlockingQueue<>(capacity);
        this.map = new HashMap<K, V>(capacity);
        this.loader = loader;
    }

    public V get(K key) {
        // If we already have key in cache, return it
        if (map.containsKey(key)) return map.get(key);
        // If we need to make room for another entry, remove the oldest entry
        if (fifo.remainingCapacity() == 0) {
            var evict = fifo.remove();
            map.remove(evict);
        }
        // Add key to map
        map.put(key, loader.apply(key));
        fifo.add(key);

        return map.get(key);
    }
}
