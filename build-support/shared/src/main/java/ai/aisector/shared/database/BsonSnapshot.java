package ai.aisector.shared.database;

import org.bson.Document;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Defensive copies of mutable values captured by queued Mongo operations. */
final class BsonSnapshot {
    private BsonSnapshot() { }

    static Document document(Document source) {
        if (source == null) throw new NullPointerException("document");
        return (Document) value(source);
    }

    static Object value(Object source) {
        if (source instanceof Document document) {
            Document copy = new Document();
            document.forEach((key, item) -> copy.put(key, value(item)));
            return copy;
        }
        if (source instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(key, value(item)));
            return copy;
        }
        if (source instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(value(item)));
            return copy;
        }
        if (source instanceof byte[] bytes) return bytes.clone();
        if (source instanceof Date date) return new Date(date.getTime());
        return source;
    }
}
