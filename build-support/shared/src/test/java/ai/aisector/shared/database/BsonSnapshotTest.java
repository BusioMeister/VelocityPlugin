package ai.aisector.shared.database;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class BsonSnapshotTest {
    @Test
    void recursivelyCopiesMutableNestedValues() {
        Date date = new Date(10);
        byte[] bytes = {1, 2};
        List<Object> nested = new ArrayList<>(List.of(new Document("name", "before"), date, bytes));
        Document source = new Document("nested", new Document("items", nested));

        Document copy = BsonSnapshot.document(source);
        ((Document) ((List<?>) copy.get("nested", Document.class).get("items")).get(0)).put("name", "copy");
        date.setTime(99);
        bytes[0] = 9;
        nested.add("later");

        Document sourceNested = source.get("nested", Document.class);
        List<?> sourceItems = (List<?>) sourceNested.get("items");
        assertEquals("before", ((Document) sourceItems.get(0)).getString("name"));
        assertEquals(99L, ((Date) sourceItems.get(1)).getTime());
        assertEquals(9, ((byte[]) sourceItems.get(2))[0]);
        List<?> copyItems = (List<?>) copy.get("nested", Document.class).get("items");
        assertEquals(10L, ((Date) copyItems.get(1)).getTime());
        assertEquals(1, ((byte[]) copyItems.get(2))[0]);
        assertNotSame(sourceNested, copy.get("nested"));
        assertEquals(4, sourceItems.size());
        assertEquals(3, copyItems.size());
    }
}
