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

package top.theillusivec4.curiostest.backpack;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.handling.ServerPayloadContext;
import net.neoforged.neoforge.network.payload.AdvancedOpenScreenPayload;
import net.neoforged.neoforge.network.registration.ChannelAttributes;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackItem;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContainer;
import net.p3pp3rf1y.sophisticatedbackpacks.network.BackpackOpenPayload;
import net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler;
import org.slf4j.Logger;
import top.theillusivec4.curios.CuriosConstants;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;
import top.theillusivec4.curios.common.inventory.CurioStacksHandler;
import top.theillusivec4.curios.common.inventory.CurioSlot;
import top.theillusivec4.curios.common.network.client.CPacketOpenCurios;
import top.theillusivec4.curios.common.network.server.CuriosServerPayloadHandler;
import top.theillusivec4.curios.common.network.server.sync.SPacketSyncCurios;
import top.theillusivec4.curios.common.network.server.sync.SPacketSyncModifiers;
import top.theillusivec4.curios.common.network.server.sync.SPacketSyncStack;
import top.theillusivec4.curiostest.common.CuriosTestRegistry;

/**
 * Curios slot sync tests A and B, with an equipped Sophisticated Backpacks backpack, and test C.
 *
 * <p>Each test has a server player and a second player that stands in for that player's client:
 * the client player gets only what the server would send it, decoded through the real codecs.
 * A test is red when the client's back[0] no longer holds the server's backpack.
 */
@GameTestHolder(CuriosConstants.MOD_ID)
@PrefixGameTestTemplate(false)
public class BackpackSlotSyncTests {

  private static final Logger LOG = LogUtils.getLogger();
  private static final ResourceLocation CHARM =
      ResourceLocation.fromNamespaceAndPath("curiostest", "backpack_sync_charm");
  private static final int MARKERS = 36;
  private static final int WARMUP_TICKS = 20;
  private static final int HOLD_TICKS = 40;
  private static final int CLIENT_TICKS = 5;

  // A mock connection negotiates no channels, so this accepts every payload id.
  private static final Set<ResourceLocation> EVERY_CHANNEL = new AbstractSet<>() {
    @Override
    public boolean contains(Object o) {
      return true;
    }

    @Override
    public Iterator<ResourceLocation> iterator() {
      return Collections.emptyIterator();
    }

    @Override
    public int size() {
      return 0;
    }
  };

  /** Test A: the server has one more charm slot than the client, then Curios opens its menu. */
  @GameTest(template = "empty", timeoutTicks = 200)
  public static void charmMismatchMenuWritesClientBack(GameTestHelper helper) {
    ServerPlayer server = player(helper, "sync-a-server");
    ServerPlayer client = player(helper, "sync-a-client");
    fixture(server, client);
    // The client never gets this modifier: it stays at the base three charm slots.
    curios(server).addTransientSlotModifier("charm", CHARM, 1,
        AttributeModifier.Operation.ADD_VALUE);
    step(helper, "A", WARMUP_TICKS, () -> {
      Map<String, Object> record = new LinkedHashMap<>();
      int serverCharm = slots(server, "charm");
      int clientCharm = slots(client, "charm");
      record.put("charm_slots", serverCharm + " server, " + clientCharm + " client");
      check(serverCharm == clientCharm + 1, "fixture charm slots: " + record.get("charm_slots"));
      check(sameBackpack(back(server), back(client)),
          "fixture back[0] differs before Curios opens");
      sent(server);
      CPacketOpenCurios request =
          roundTrip(helper, CPacketOpenCurios.STREAM_CODEC, new CPacketOpenCurios(ItemStack.EMPTY));
      CuriosServerPayloadHandler.getInstance().handleOpenCurios(request,
          new ServerPayloadContext(server.connection, CPacketOpenCurios.TYPE.id()));
      List<Object> packets = sent(server);
      ClientboundOpenScreenPacket open = packets.stream()
          .filter(ClientboundOpenScreenPacket.class::isInstance)
          .map(ClientboundOpenScreenPacket.class::cast).findFirst()
          .orElseThrow(() -> new GameTestAssertException("server opened no Curios menu"));
      ClientboundContainerSetContentPacket contents =
          contents(helper, packets, open.getContainerId())
          .orElseThrow(() -> new GameTestAssertException("server sent no Curios menu contents"));
      AbstractContainerMenu menu =
          open.getType().create(open.getContainerId(), client.getInventory());
      client.containerMenu = menu;
      List<String> serverSlots = curioSlots(server.containerMenu);
      List<String> clientSlots = curioSlots(menu);
      record.put("server_menu", serverSlots);
      record.put("client_menu", clientSlots);
      record.put("contents_error", "none");

      try {
        menu.initializeContents(contents.getStateId(), contents.getItems(),
            contents.getCarriedItem());
      } catch (RuntimeException e) {
        record.put("contents_error", e.toString());
      }
      int position = clientSlots.stream().filter(s -> s.endsWith(":back[0]")).findFirst()
          .map(s -> Integer.parseInt(s.substring(0, s.indexOf(':')))).orElse(-1);
      record.put("client_back_position", position < 0 ? "none" : position + " carries server "
          + serverSlots.stream().filter(s -> s.startsWith(position + ":")).findFirst().orElse("?")
          + " = " + describe(contents.getItems().get(position)));
      record.put("server_back", describe(back(server)));
      record.put("client_back", describe(back(client)));
      boolean red = !sameBackpack(back(server), back(client));
      server.closeContainer();
      client.doCloseContainer();
      record.put("backpack_open", openBackpack(helper, server, client));
      finish(helper, "A", record, red, "Curios menu contents left client back[0] = "
          + record.get("client_back") + " via menu position " + record.get("client_back_position"));
    });
  }

  /** Test B: the server's back[0] packet is held unencoded while its item object is emptied. */
  @GameTest(template = "empty", timeoutTicks = 200)
  public static void heldBackPacketEncodesEmpty(GameTestHelper helper) {
    ServerPlayer server = player(helper, "sync-b-server");
    ServerPlayer client = player(helper, "sync-b-client");
    fixture(server, client);
    step(helper, "B", WARMUP_TICKS, () -> {
      check(sameBackpack(back(server), back(client)), "fixture back[0] differs before hold");
      CompoundTag marker = new CompoundTag();
      marker.putString("curiostest", "sync-b");
      ItemStack original = back(server).copy();
      original.set(DataComponents.CUSTOM_DATA, CustomData.of(marker));
      sent(server);
      handler(server).getStacks().setStackInSlot(0, original);
      awaitBackPacket(helper, server, client, original, HOLD_TICKS);
    });
  }

  /**
   * Test C: knuckles in hands[0] add two necklace slots while the game runs. The client stand-in
   * gets every Curios packet, decoded, and runs the client side of the Curios tick. It must keep
   * the server's necklace slots and items after the equip, a relog and a datapack reload.
   */
  @GameTest(template = "empty", timeoutTicks = 200)
  public static void runtimeSlotsReachClient(GameTestHelper helper) {
    ServerPlayer server = player(helper, "sync-c-server");
    ServerPlayer client = standIn(helper, "sync-c-client");
    Map<String, Object> record = new LinkedHashMap<>();
    List<String> errors = new ArrayList<>();
    pump(helper, server, client, errors, CLIENT_TICKS, () -> {
      curios(server).getStacksHandler("hands").orElseThrow().getStacks()
          .setStackInSlot(0, new ItemStack(CuriosTestRegistry.KNUCKLES.get()));
      step(helper, "C", 1, () -> {
        IDynamicStackHandler necklace =
            curios(server).getStacksHandler("necklace").orElseThrow().getStacks();
        check(necklace.getSlots() == 3, "fixture necklace slots on server: " + necklace.getSlots());

        for (int i = 0; i < necklace.getSlots(); i++) {
          necklace.setStackInSlot(i, new ItemStack(Items.DIAMOND, i + 1));
        }
        pump(helper, server, client, errors, CLIENT_TICKS, () -> {
          record.put("equip", compare(server, client, errors));
          // Relog: the server saves the player, then loads it again on a new connection.
          GameProfile profile = server.getGameProfile();
          helper.getLevel().getServer().getPlayerList().remove(server);
          ServerPlayer relogged = player(helper, profile);
          ServerPlayer relogClient = standIn(helper, "sync-c-client");
          pump(helper, relogged, relogClient, errors, CLIENT_TICKS, () -> {
            record.put("relog", compare(relogged, relogClient, errors));
            NeoForge.EVENT_BUS.post(
                new OnDatapackSyncEvent(helper.getLevel().getServer().getPlayerList(), null));
            pump(helper, relogged, relogClient, errors, CLIENT_TICKS, () -> {
              record.put("reload", compare(relogged, relogClient, errors));
              record.put("client_errors", errors.isEmpty() ? "none" : errors);
              boolean red = !errors.isEmpty() || record.values().stream()
                  .anyMatch(v -> v instanceof String s && s.startsWith("differs"));
              finish(helper, "C", record, red, "client stand-in lost runtime necklace slots: "
                  + record);
            });
          });
        });
      });
    });
  }

  /** Feeds the server's Curios packets to the stand-in and runs its client tick, once a tick. */
  private static void pump(GameTestHelper helper, ServerPlayer server, ServerPlayer client,
                           List<String> errors, int ticks, Runnable then) {
    step(helper, "C", 1, () -> {
      replay(helper, server, client, sent(server), errors);
      // The client side of CuriosEventHandler.tick: the server-only equip work does not run.
      client.tickCount++;
      curios(client).clearCachedSlotModifiers();
      curios(client).getCurios().values().forEach(ICurioStacksHandler::getSlots);

      if (ticks > 1) {
        pump(helper, server, client, errors, ticks - 1, then);
      } else {
        then.run();
      }
    });
  }

  /** What CuriosClientPackets does with each Curios packet for this player, after decoding. */
  private static void replay(GameTestHelper helper, ServerPlayer server, ServerPlayer client,
                             List<Object> packets, List<String> errors) {

    for (Object payload : payloads(packets, CustomPacketPayload.class)) {

      if (payload instanceof SPacketSyncCurios p && p.entityId == server.getId()) {
        SPacketSyncCurios data = roundTrip(helper, SPacketSyncCurios.STREAM_CODEC, p);
        ICuriosItemHandler handler = curios(client);
        Map<String, ICurioStacksHandler> stacks = new LinkedHashMap<>();
        data.map.forEach((id, tag) -> {
          CurioStacksHandler stacksHandler = new CurioStacksHandler(handler, id);
          stacksHandler.applySyncTag(tag);
          stacks.put(id, stacksHandler);
        });
        handler.setCurios(stacks);
      } else if (payload instanceof SPacketSyncModifiers p && p.entityId == server.getId()) {
        SPacketSyncModifiers data = roundTrip(helper, SPacketSyncModifiers.STREAM_CODEC, p);
        Map<String, ICurioStacksHandler> curios = curios(client).getCurios();
        data.updates.forEach((id, tag) -> {

          if (curios.get(id) != null) {
            curios.get(id).applySyncTag(tag);
          }
        });
      } else if (payload instanceof SPacketSyncStack p && p.entityId() == server.getId()) {
        SPacketSyncStack data = roundTrip(helper, SPacketSyncStack.STREAM_CODEC, p);
        curios(client).getStacksHandler(data.curioId()).ifPresent(stacksHandler -> {
          IDynamicStackHandler target = SPacketSyncStack.HandlerType.fromValue(data.handlerType())
              == SPacketSyncStack.HandlerType.COSMETIC ? stacksHandler.getCosmeticStacks()
              : stacksHandler.getStacks();

          try {
            target.setStackInSlot(data.slotId(), data.stack().copy());
          } catch (RuntimeException e) {
            errors.add(data.curioId() + ": " + e.getMessage());
          }
        });
      }
    }
  }

  /** "same ..." when the stand-in has the server's necklace slots and items, else "differs ...". */
  private static String compare(ServerPlayer server, ServerPlayer client, List<String> errors) {
    IDynamicStackHandler serverNecklace =
        curios(server).getStacksHandler("necklace").orElseThrow().getStacks();
    IDynamicStackHandler clientNecklace =
        curios(client).getStacksHandler("necklace").orElseThrow().getStacks();
    List<String> serverItems = new ArrayList<>();
    List<String> clientItems = new ArrayList<>();

    for (int i = 0; i < serverNecklace.getSlots(); i++) {
      serverItems.add(describe(serverNecklace.getStackInSlot(i)));
    }

    for (int i = 0; i < clientNecklace.getSlots(); i++) {
      clientItems.add(describe(clientNecklace.getStackInSlot(i)));
    }
    String state = "necklace server " + serverItems + ", client " + clientItems + ", errors "
        + errors.size();
    return (serverItems.equals(clientItems) ? "same " : "differs ") + state;
  }

  /** A player for a relog: same profile, new connection; PlayerList loads its saved data. */
  private static ServerPlayer player(GameTestHelper helper, GameProfile profile) {
    MinecraftServer server = helper.getLevel().getServer();
    CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
    ServerPlayer player =
        new ServerPlayer(server, helper.getLevel(), profile, cookie.clientInformation());
    Connection connection = new Connection(PacketFlow.SERVERBOUND);
    new EmbeddedChannel(connection);
    connection.channel().attr(ChannelAttributes.ADHOC_CHANNELS).set(EVERY_CHANNEL);
    ChannelAttributes.setConnectionType(connection, ConnectionType.NEOFORGE);
    server.getPlayerList().placeNewPlayer(connection, player, cookie);
    return player;
  }

  /** A client stand-in: never added to the level, so no server tick runs on it. */
  private static ServerPlayer standIn(GameTestHelper helper, String name) {
    return new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
        new GameProfile(UUID.randomUUID(), name), ClientInformation.createDefault());
  }

  private static void awaitBackPacket(GameTestHelper helper, ServerPlayer server,
                                      ServerPlayer client, ItemStack original, int ticksLeft) {
    // Test callbacks run after the level tick that builds the packet and before it is flushed.
    Optional<SPacketSyncStack> found = backPackets(server, sent(server)).stream().findFirst();

    if (found.isEmpty()) {
      check(ticksLeft > 0, "Curios sent no back[0] packet within " + HOLD_TICKS + " ticks");
      step(helper, "B", 1,
          () -> awaitBackPacket(helper, server, client, original, ticksLeft - 1));
      return;
    }
    SPacketSyncStack held = found.get();
    Map<String, Object> record = new LinkedHashMap<>();
    record.put("held_item_is_slot_object", held.stack() == original);
    ItemStack copy = original.copy();
    handler(server).getStacks().setStackInSlot(0, copy);
    check(ItemStack.matches(handler(server).getStacks().getPreviousStackInSlot(0), copy),
        "fixture remembered previous back[0] differs from R");
    original.setCount(0);
    SPacketSyncStack decoded;

    try {
      decoded = roundTrip(helper, SPacketSyncStack.STREAM_CODEC, held);
    } catch (RuntimeException e) {
      record.put("decoder_error", e.toString());
      finish(helper, "B", record, true, "decoder error " + e);
      return;
    }
    record.put("decoder_error", "none");
    record.put("decoded", describe(decoded.stack()));
    // What CuriosClientPackets.handle(SPacketSyncStack) does on the client.
    handler(client).getStacks().setStackInSlot(decoded.slotId(), decoded.stack().copy());
    step(helper, "B", 2, () -> {
      List<SPacketSyncStack> corrective = backPackets(server, sent(server));
      record.put("corrective_back_packets_in_2_ticks", corrective.size());

      for (SPacketSyncStack packet : corrective) {
        SPacketSyncStack fixed = roundTrip(helper, SPacketSyncStack.STREAM_CODEC, packet);
        handler(client).getStacks().setStackInSlot(fixed.slotId(), fixed.stack().copy());
      }
      record.put("server_back", describe(back(server)));
      record.put("client_back", describe(back(client)));
      boolean red = !sameBackpack(back(server), back(client));
      record.put("backpack_open", openBackpack(helper, server, client));
      finish(helper, "B", record, red, "held back[0] packet decoded as " + record.get("decoded")
          + " while server back[0] = " + record.get("server_back") + ", corrective packets: "
          + record.get("corrective_back_packets_in_2_ticks"));
    });
  }

  /** Sends Backpacks' normal open request and builds the client's menu from the server reply. */
  private static String openBackpack(GameTestHelper helper, ServerPlayer server,
                                     ServerPlayer client) {
    try {
      sent(server);
      BackpackOpenPayload request =
          roundTrip(helper, BackpackOpenPayload.STREAM_CODEC, new BackpackOpenPayload());
      BackpackOpenPayload.handlePayload(request,
          new ServerPayloadContext(server.connection, BackpackOpenPayload.TYPE.id()));
      List<Object> packets = sent(server);
      Optional<AdvancedOpenScreenPayload> open = payloads(packets, AdvancedOpenScreenPayload.class)
          .stream().findFirst();

      if (open.isEmpty()) {
        return "server opened no backpack menu";
      }
      AdvancedOpenScreenPayload screen = open.get();
      AbstractContainerMenu menu = screen.menuType().create(screen.windowId(),
          client.getInventory(),
          buffer(helper, Unpooled.wrappedBuffer(screen.additionalData())));
      client.containerMenu = menu;
      contents(helper, packets, screen.windowId()).ifPresent(
          c -> menu.initializeContents(c.getStateId(), c.getItems(), c.getCarriedItem()));
      int lost = 0;

      for (int i = 0; i < MARKERS; i++) {
        ItemStack stack = client.getInventory().getItem(i);
        lost += stack.is(Items.STICK) && stack.getCount() == i + 1 ? 0 : 1;
      }
      boolean noop = menu instanceof BackpackContainer backpack
          && backpack.getStorageWrapper() == IBackpackWrapper.Noop.INSTANCE;
      client.doCloseContainer();
      server.closeContainer();
      return (noop ? "client found no backpack (Noop wrapper)" : "client found the backpack")
          + ", client player markers overwritten " + lost + "/" + MARKERS;
    } catch (RuntimeException e) {
      return "backpack open threw " + e;
    }
  }

  /** Runs a test step after the delay; a step that throws first removes the test's sync players. */
  private static void step(GameTestHelper helper, String test, int delay, Runnable body) {
    helper.runAfterDelay(delay, () -> {

      try {
        body.run();
      } catch (RuntimeException e) {
        removeSyncPlayers(helper, test);
        throw e;
      }
    });
  }

  private static void removeSyncPlayers(GameTestHelper helper, String test) {

    for (ServerPlayer player : List.copyOf(helper.getLevel().getServer().getPlayerList()
        .getPlayers())) {

      if (player.getGameProfile().getName().startsWith("sync-" + test.toLowerCase())) {
        helper.getLevel().getServer().getPlayerList().remove(player);
      }
    }
  }

  private static void finish(GameTestHelper helper, String test, Map<String, Object> record,
                             boolean red, String failure) {
    record.put("result", red ? "red" : "green");
    LOG.info("[backpack-slot-sync] test {} {}", test, record);
    removeSyncPlayers(helper, test);

    if (red) {
      helper.fail("Test " + test + " red: " + failure + "; " + record.get("backpack_open"));
    } else {
      helper.succeed();
    }
  }

  /** A player whose connection is an EmbeddedChannel: sent packets stay unencoded objects. */
  private static ServerPlayer player(GameTestHelper helper, String name) {
    MinecraftServer server = helper.getLevel().getServer();
    CommonListenerCookie cookie =
        CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), name), false);
    ServerPlayer player =
        new ServerPlayer(server, helper.getLevel(), cookie.gameProfile(),
            cookie.clientInformation());
    Connection connection = new Connection(PacketFlow.SERVERBOUND);
    new EmbeddedChannel(connection);
    connection.channel().attr(ChannelAttributes.ADHOC_CHANNELS).set(EVERY_CHANNEL);
    ChannelAttributes.setConnectionType(connection, ConnectionType.NEOFORGE);
    server.getPlayerList().placeNewPlayer(connection, player, cookie);
    return player;
  }

  /** Iron backpack in back[0] with paper 1..36 in slots 0..35; player slots hold sticks 1..36. */
  private static void fixture(ServerPlayer server, ServerPlayer client) {
    ItemStack backpack = new ItemStack(BuiltInRegistries.ITEM.get(
        ResourceLocation.fromNamespaceAndPath("sophisticatedbackpacks", "iron_backpack")));
    InventoryHandler storage = BackpackWrapper.fromStack(backpack).getInventoryHandler();

    for (int i = 0; i < MARKERS; i++) {
      storage.setStackInSlot(i, new ItemStack(Items.PAPER, i + 1));
    }

    for (ServerPlayer player : List.of(server, client)) {

      for (int i = 0; i < MARKERS; i++) {
        player.getInventory().setItem(i, new ItemStack(Items.STICK, i + 1));
      }
      handler(player).getStacks().setStackInSlot(0, backpack.copy());
      handler(player).getCosmeticStacks().setStackInSlot(0, ItemStack.EMPTY);
    }
  }

  /** Flushes the connection and takes what it wrote since the last call; nothing is encoded. */
  private static List<Object> sent(ServerPlayer player) {
    EmbeddedChannel channel = (EmbeddedChannel) player.connection.getConnection().channel();
    channel.flush();
    List<Object> packets = new ArrayList<>();

    for (Object msg; (msg = channel.readOutbound()) != null; ) {

      if (msg instanceof ClientboundBundlePacket bundle) {
        bundle.subPackets().forEach(packets::add);
      } else {
        packets.add(msg);
      }
    }
    return packets;
  }

  private static <T> List<T> payloads(List<Object> packets, Class<T> type) {
    return packets.stream().filter(p -> p instanceof ClientboundCustomPayloadPacket custom
            && type.isInstance(custom.payload()))
        .map(p -> type.cast(((ClientboundCustomPayloadPacket) p).payload())).toList();
  }

  private static List<SPacketSyncStack> backPackets(ServerPlayer player, List<Object> packets) {
    return payloads(packets, SPacketSyncStack.class).stream()
        .filter(p -> p.entityId() == player.getId() && "back".equals(p.curioId())
            && p.slotId() == 0
            && p.handlerType() == SPacketSyncStack.HandlerType.EQUIPMENT.ordinal())
        .toList();
  }

  /** The menu contents packet for a window, decoded the way the client receives it. */
  private static Optional<ClientboundContainerSetContentPacket> contents(GameTestHelper helper,
                                                                         List<Object> packets,
                                                                         int window) {
    return packets.stream().filter(ClientboundContainerSetContentPacket.class::isInstance)
        .map(ClientboundContainerSetContentPacket.class::cast)
        .filter(p -> p.getContainerId() == window).findFirst()
        .map(p -> roundTrip(helper, ClientboundContainerSetContentPacket.STREAM_CODEC, p));
  }

  private static <T> T roundTrip(GameTestHelper helper,
                                 StreamCodec<? super RegistryFriendlyByteBuf, T> codec, T value) {
    RegistryFriendlyByteBuf buffer = buffer(helper, Unpooled.buffer());

    try {
      codec.encode(buffer, value);
      return codec.decode(buffer);
    } finally {
      buffer.release();
    }
  }

  private static RegistryFriendlyByteBuf buffer(GameTestHelper helper, ByteBuf bytes) {
    return new RegistryFriendlyByteBuf(bytes, helper.getLevel().registryAccess(),
        ConnectionType.NEOFORGE);
  }

  /** Each Curios menu position as "index:identifier[local]", with a "c" suffix for cosmetic. */
  private static List<String> curioSlots(AbstractContainerMenu menu) {
    List<String> slots = new ArrayList<>();

    for (int i = 0; i < menu.slots.size(); i++) {

      if (menu.slots.get(i) instanceof CurioSlot slot) {
        slots.add(i + ":" + slot.getIdentifier() + "[" + slot.getSlotIndex() + "]"
            + (slot.isCosmetic() ? "c" : ""));
      }
    }
    return slots;
  }

  private static ICuriosItemHandler curios(ServerPlayer player) {
    return CuriosApi.getCuriosInventory(player).orElseThrow();
  }

  private static ICurioStacksHandler handler(ServerPlayer player) {
    return curios(player).getStacksHandler("back").orElseThrow();
  }

  private static ItemStack back(ServerPlayer player) {
    return handler(player).getStacks().getStackInSlot(0);
  }

  private static int slots(ServerPlayer player, String identifier) {
    return curios(player).getStacksHandler(identifier).map(ICurioStacksHandler::getSlots).orElse(0);
  }

  private static boolean sameBackpack(ItemStack server, ItemStack client) {
    return server.getItem() instanceof BackpackItem && ItemStack.matches(server, client);
  }

  private static String describe(ItemStack stack) {
    String item = BuiltInRegistries.ITEM.getKey(stack.getItem()) + " x" + stack.getCount();
    return stack.getItem() instanceof BackpackItem ? item + BackpackWrapper.fromStack(stack)
        .getContentsUuid().map(uuid -> " storage " + uuid).orElse("") : item;
  }

  private static void check(boolean condition, String message) {

    if (!condition) {
      throw new GameTestAssertException(message);
    }
  }
}
