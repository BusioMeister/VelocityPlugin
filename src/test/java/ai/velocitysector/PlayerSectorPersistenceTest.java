package ai.velocitysector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerSectorPersistenceTest {
    @Test
    void sectorNamesAreBoundedIdentifiers() {
        assertTrue(PlayerSectorPersistence.isValidSectorName("Sector_2"));
        assertFalse(PlayerSectorPersistence.isValidSectorName(""));
        assertFalse(PlayerSectorPersistence.isValidSectorName("../Sector1"));
        assertFalse(PlayerSectorPersistence.isValidSectorName("Sector:1"));
        assertFalse(PlayerSectorPersistence.isValidSectorName("x".repeat(65)));
    }
}
