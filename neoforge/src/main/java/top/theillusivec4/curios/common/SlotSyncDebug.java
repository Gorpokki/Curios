/*
 * Copyright (c) 2018-2024 C4
 *
 * This file is part of Curios, a mod made for Minecraft.
 *
 * Curios is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Curios is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Curios.  If not, see <https://www.gnu.org/licenses/>.
 */

package top.theillusivec4.curios.common;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import top.theillusivec4.curios.CuriosConstants;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;

/**
 * Debug-build logging for slot count sync. Every line starts with "[curios-slot-sync]". It reads
 * sync tags only, so it never resizes a handler.
 */
public final class SlotSyncDebug {

  public static void log(String format, Object... args) {
    CuriosConstants.LOG.info("[curios-slot-sync] " + format, args);
  }

  public static String side(Entity entity) {
    return entity == null ? "none" : entity.level().isClientSide() ? "client" : "server";
  }

  public static String handlers(Map<String, ICurioStacksHandler> handlers) {
    Map<String, CompoundTag> tags = new LinkedHashMap<>();
    handlers.forEach((id, handler) -> tags.put(id, handler.getSyncTag()));
    return tags(tags);
  }

  /** "charm=10/4 c10 [curios:belt/0 +6.0 ADD_VALUE]": slots/base, cosmetic slots, modifiers. */
  public static String tags(Map<String, CompoundTag> tags) {
    StringJoiner out = new StringJoiner(" ", "{", "}");
    tags.forEach((id, tag) -> {
      int size = tag.getCompound("Stacks").getInt("Size");
      int cosmetics = tag.getCompound("Cosmetics").getInt("Size");
      int base = tag.getInt("BaseSize");
      ListTag list = tag.getList("Modifiers", Tag.TAG_COMPOUND);

      if (size == base && cosmetics == size && list.isEmpty()) {
        return;
      }
      StringJoiner modifiers = new StringJoiner(", ", "[", "]");

      for (int i = 0; i < list.size(); i++) {
        AttributeModifier modifier = AttributeModifier.load(list.getCompound(i));

        if (modifier != null) {
          modifiers.add(modifier.id() + " " + modifier.amount() + " " + modifier.operation());
        }
      }
      out.add(id + "=" + size + "/" + base + " c" + cosmetics + " " + modifiers);
    });
    return tags.size() + " handlers " + out;
  }
}
