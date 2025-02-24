package com.nukkitx.proxypass.network.bedrock.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nukkitx.nbt.NBTOutputStream;
import com.nukkitx.nbt.NbtMap;
import com.nukkitx.nbt.NbtUtils;
import com.nukkitx.protocol.bedrock.data.inventory.*;
import com.nukkitx.protocol.bedrock.data.inventory.descriptor.*;
import com.nukkitx.protocol.bedrock.packet.CraftingDataPacket;
import com.nukkitx.proxypass.ProxyPass;
import lombok.*;
import lombok.experimental.UtilityClass;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;

@UtilityClass
public class RecipeUtils {
    private static final char[] SHAPE_CHARS = {'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J'};

    public static void writeRecipes(CraftingDataPacket packet, ProxyPass proxy) {
        List<CraftingDataEntry> entries = new ArrayList<>();

        for (CraftingData craftingData : packet.getCraftingData()) {
            CraftingDataEntry entry = new CraftingDataEntry();

            CraftingDataType type = craftingData.getType();
            entry.type = type.ordinal();

            entry.uuid = craftingData.getUuid();

            if (type == CraftingDataType.SHAPED || type == CraftingDataType.SHAPELESS || type == CraftingDataType.SHULKER_BOX) {
                entry.output = writeItemArray(craftingData.getOutputs().toArray(new ItemData[0]));
            }

            if (type == CraftingDataType.SHAPED) {

                int charCounter = 0;
                // ItemData[] inputs = craftingData.getInputs().toArray(new ItemData[0]);
                List<ItemDescriptorWithCount> inputs = craftingData.getInputDescriptors();
                Map<Descriptor, Character> charItemMap = new HashMap<>();
                char[][] shape = new char[craftingData.getHeight()][craftingData.getWidth()];

                for (int height = 0; height < craftingData.getHeight(); height++) {
                    Arrays.fill(shape[height], ' ');
                    int index = height * craftingData.getWidth();
                    for (int width = 0; width < craftingData.getWidth(); width++) {
                        int slot = index + width;
                        Descriptor descriptor = fromNetwork(inputs.get(slot));

                        if (ItemDescriptorType.INVALID.name().toLowerCase().equals(descriptor.getType())) {
                            continue;
                        }

                        Character shapeChar = charItemMap.get(descriptor);
                        if (shapeChar == null) {
                            shapeChar = SHAPE_CHARS[charCounter++];
                            charItemMap.put(descriptor, shapeChar);
                        }

                        shape[height][width] = shapeChar;
                    }
                }

                String[] shapeString = new String[shape.length];
                for (int i = 0; i < shape.length; i++) {
                    shapeString[i] = new String(shape[i]);
                }
                entry.shape = shapeString;

                Map<Character, Descriptor> itemMap = new HashMap<>();
                for (Map.Entry<Descriptor, Character> mapEntry : charItemMap.entrySet()) {
                    itemMap.put(mapEntry.getValue(), mapEntry.getKey());
                }
                entry.input = itemMap;
            }
            if (type == CraftingDataType.SHAPELESS || type == CraftingDataType.SHULKER_BOX) {
                entry.input = writeDescriptorArray(craftingData.getInputDescriptors());
            }

            if (type == CraftingDataType.FURNACE || type == CraftingDataType.FURNACE_DATA) {
                Integer damage = craftingData.getInputDamage();
                if (damage == 0x7fff) damage = -1;
                if (damage == 0) damage = null;
                entry.input = new Item(craftingData.getInputId(), ProxyPass.legacyIdMap.get(craftingData.getInputId()), damage, null, null);
                entry.output = itemFromNetwork(craftingData.getOutputs().get(0));
            }
            entries.add(entry);
        }

        Recipes recipes = new Recipes(ProxyPass.CODEC.getProtocolVersion(), entries);

        proxy.saveJson("recipes.json", recipes);
    }

    private static List<Item> writeItemArray(ItemData[] inputs) {
        List<Item> outputs = new ArrayList<>();
        for (ItemData input : inputs) {
            Item item = itemFromNetwork(input);
            if (item != Item.EMPTY) {
                outputs.add(item);
            }
        }
        return outputs;
    }

    private static List<Descriptor> writeDescriptorArray(List<ItemDescriptorWithCount> inputs) {
        List<Descriptor> outputs = new ArrayList<>();
        for (ItemDescriptorWithCount input : inputs) {
            Descriptor descriptor = fromNetwork(input);
            if (!ItemDescriptorType.INVALID.name().toLowerCase().equals(descriptor.getType())) {
                outputs.add(descriptor);
            }
        }
        return outputs;
    }

    private static String nbtToBase64(NbtMap tag) {
        if (tag != null) {
            ByteArrayOutputStream tagStream = new ByteArrayOutputStream();
            try (NBTOutputStream writer = NbtUtils.createWriterLE(tagStream)) {
                writer.writeTag(tag);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return Base64.getEncoder().encodeToString(tagStream.toByteArray());
        } else {
            return null;
        }
    }

    private static Item itemFromNetwork(ItemData data) {
        int id = data.getId();
        String identifier = ProxyPass.legacyIdMap.get(id);
        Integer damage = data.getDamage();
        Integer count = data.getCount();
        String tag = nbtToBase64(data.getTag());

        if (id == 0) {
            return Item.EMPTY;
        }
        if (damage == 0 || damage == -1) damage = null;
        if (count == 1) count = null;

        return new Item(id, identifier, damage, count, tag);
    }

    private static Descriptor fromNetwork(ItemDescriptorWithCount descriptorWithCount) {
        Descriptor descriptor = new Descriptor();
        descriptor.setType(descriptorWithCount.getDescriptor().getType().name().toLowerCase());
        descriptor.setCount(descriptorWithCount.getCount());
        ItemDescriptor itemDescriptor = descriptorWithCount.getDescriptor();

        if (itemDescriptor instanceof DefaultDescriptor) {
            descriptor.setItemId(((DefaultDescriptor) itemDescriptor).getItemId());
            descriptor.setAuxValue(((DefaultDescriptor) itemDescriptor).getAuxValue());
        }

        return descriptor;
    }

    @NoArgsConstructor
    @AllArgsConstructor
    @Getter
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private static class CraftingDataEntry {
        private int type;
        private Object input;
        private Object output;
        private String[] shape;
        private UUID uuid;
    }

    @Value
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private static class Item {
        public static final Item EMPTY = new Item(0, "minecraft:air", null, null, null);

        int legacyId;
        String id;
        Integer damage;
        Integer count;
        String nbt_b64;
    }

    @Value
    private static class Recipes {
        int version;

        List<CraftingDataEntry> recipes;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private static class Descriptor {
        String type;
        int count;
        // Default descriptor
        Integer itemId;
        Integer auxValue;
    }
}
