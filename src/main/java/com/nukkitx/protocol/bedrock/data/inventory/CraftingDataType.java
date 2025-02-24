package com.nukkitx.protocol.bedrock.data.inventory;

public enum CraftingDataType {
    SHAPELESS,
    SHAPED,
    FURNACE,
    FURNACE_DATA,
    MULTI,
    SHULKER_BOX;

    private static final CraftingDataType[] VALUES = values();

    public static CraftingDataType byId(int id) {
        if (id >= 0 && id < VALUES.length) {
            return VALUES[id];
        }
        throw new UnsupportedOperationException("Unknown CraftingDataType ID: " + id);
    }
}
