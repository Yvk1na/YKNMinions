package com.xigua.yknminions.service;

import com.xigua.yknminions.TestItemStack;
import com.xigua.yknminions.model.PreparedClaim;
import com.xigua.yknminions.model.PreparedClaimLine;
import com.xigua.yknminions.model.MinionStorage;
import dev.chengzhi.skyblockcore.api.delivery.DetailedDeliveryResult;
import dev.chengzhi.skyblockcore.api.delivery.DeliveryLineResult;
import dev.chengzhi.skyblockcore.api.delivery.DeliveryResult;
import dev.chengzhi.skyblockcore.api.delivery.DeliveryStatus;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MinionManagerDeliveryTest {
    @Test
    void acceptsOrderedPartialResultForExactPreparedLines() {
        PreparedClaim claim = claim("claim:valid", 64, 10);
        DetailedDeliveryResult result = detailed("claim:valid", DeliveryStatus.PARTIAL,
                new DeliveryLineResult(0, 64, 20, 16, 28),
                new DeliveryLineResult(1, 10, 0, 0, 10));

        assertTrue(MinionManager.validDetailedResult(claim, result));
    }

    @Test
    void rejectsWrongOperationOrRequestedLineAmount() {
        PreparedClaim claim = claim("claim:expected", 64, 10);
        DetailedDeliveryResult wrongOperation = detailed("claim:other", DeliveryStatus.PARTIAL,
                new DeliveryLineResult(0, 64, 20, 16, 28),
                new DeliveryLineResult(1, 10, 0, 0, 10));
        DetailedDeliveryResult wrongLine = detailed("claim:expected", DeliveryStatus.PARTIAL,
                new DeliveryLineResult(0, 63, 20, 16, 27),
                new DeliveryLineResult(1, 10, 0, 0, 10));

        assertFalse(MinionManager.validDetailedResult(claim, wrongOperation));
        assertFalse(MinionManager.validDetailedResult(claim, wrongLine));
    }

    @Test
    void rejectedStatusCannotHideDeliveredItems() {
        PreparedClaim claim = claim("claim:rejected", 64, 10);
        DetailedDeliveryResult result = detailed("claim:rejected", DeliveryStatus.REJECTED,
                new DeliveryLineResult(0, 64, 1, 0, 63),
                new DeliveryLineResult(1, 10, 0, 0, 10));

        assertFalse(MinionManager.validDetailedResult(claim, result));
    }

    @Test
    void validatesStatusSpecificTerminalPartitions() {
        PreparedClaim claim = claim("claim:status", 64, 10);
        assertTrue(MinionManager.validDetailedResult(claim,
                detailed("claim:status", DeliveryStatus.COMPLETED,
                        new DeliveryLineResult(0, 64, 64, 0, 0),
                        new DeliveryLineResult(1, 10, 0, 10, 0))));
        assertFalse(MinionManager.validDetailedResult(claim,
                detailed("claim:status", DeliveryStatus.COMPLETED,
                        new DeliveryLineResult(0, 64, 63, 0, 1),
                        new DeliveryLineResult(1, 10, 0, 10, 0))));
        assertTrue(MinionManager.validDetailedResult(claim,
                detailed("claim:status", DeliveryStatus.FAILED,
                        new DeliveryLineResult(0, 64, 0, 0, 64),
                        new DeliveryLineResult(1, 10, 0, 0, 10))));
    }

    @Test
    void deliveryMatrixUsesInventoryPlusStashForStorageAndExperience() {
        assertSettlement(64, 0, 0, 0, 64.0);
        assertSettlement(32, 32, 0, 0, 64.0);
        assertSettlement(32, 16, 16, 16, 48.0);
        assertSettlement(0, 64, 0, 0, 64.0);
        assertSettlement(0, 0, 64, 64, 0.0);
    }

    @Test
    void mixedEquivalentLinesStayBoundToTheirOwnDeliveredAmounts() {
        UUID recipient = UUID.randomUUID();
        PreparedClaim claim = new PreparedClaim(UUID.randomUUID(), recipient,
                "claim:mixed", List.of(
                new PreparedClaimLine(new TestItemStack(Material.COAL, 20),
                        "auraskills", "mining", 0.5, 1),
                new PreparedClaimLine(new TestItemStack(Material.COAL_BLOCK, 3),
                        "auraskills", "mining", 0.5, 9)));
        List<DeliveryLineResult> lines = List.of(
                new DeliveryLineResult(0, 20, 8, 4, 8),
                new DeliveryLineResult(1, 3, 0, 2, 1));

        assertEquals(Map.of("mining", 15.0),
                MinionManager.experienceBySkill(claim, lines));
        assertEquals(recipient, claim.recipientId());
    }

    private static void assertSettlement(int inventory, int stash, int undelivered,
                                         int expectedStorage, double expectedXp) {
        String operation = "claim:matrix:" + inventory + ":" + stash;
        PreparedClaim claim = new PreparedClaim(UUID.randomUUID(), UUID.randomUUID(),
                operation, List.of(new PreparedClaimLine(
                new TestItemStack(Material.COAL, 64),
                "auraskills", "mining", 1.0, 1.0)));
        DeliveryStatus status = undelivered == 0 ? DeliveryStatus.COMPLETED
                : inventory + stash == 0 ? DeliveryStatus.REJECTED : DeliveryStatus.PARTIAL;
        DeliveryLineResult line = new DeliveryLineResult(
                0, 64, inventory, stash, undelivered);
        DetailedDeliveryResult result = detailed(operation, status, line);
        MinionStorage storage = new MinionStorage(15, 15, claim.items());
        storage.reserveAll();

        assertTrue(MinionManager.validDetailedResult(claim, result));
        assertTrue(storage.settleReservation(claim.items(),
                List.of(line.deliveredAmount())));
        assertEquals(expectedStorage,
                storage.snapshot().stream().mapToInt(ItemStack::getAmount).sum());
        assertEquals(expectedXp,
                MinionManager.experienceBySkill(claim, result.lines())
                        .getOrDefault("mining", 0.0), 0.0001);
    }

    private static PreparedClaim claim(String operationId, int first, int second) {
        return new PreparedClaim(UUID.randomUUID(), UUID.randomUUID(), operationId, List.of(
                PreparedClaimLine.withoutSkill(new TestItemStack(Material.COBBLESTONE, first)),
                PreparedClaimLine.withoutSkill(new TestItemStack(Material.DIRT, second))));
    }

    private static DetailedDeliveryResult detailed(String operationId, DeliveryStatus status,
                                                    DeliveryLineResult... lines) {
        long requested = 0;
        long inventory = 0;
        long stash = 0;
        long undelivered = 0;
        for (DeliveryLineResult line : lines) {
            requested += line.requestedAmount();
            inventory += line.inventoryAmount();
            stash += line.stashedAmount();
            undelivered += line.undeliveredAmount();
        }
        return new DetailedDeliveryResult(new DeliveryResult(operationId, status,
                requested, inventory, stash, undelivered, false), List.of(lines));
    }
}
